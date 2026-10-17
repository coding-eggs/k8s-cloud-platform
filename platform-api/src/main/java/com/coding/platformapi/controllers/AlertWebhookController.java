package com.coding.platformapi.controllers;

import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.configs.AlertWebhookTokenCheck;
import com.coding.platformapi.models.AlertWebhookRequest;
import com.coding.platformapi.services.AlertIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * Alertmanager 的告警推送入口。
 *
 * <h2>⚠️ 为什么这个控制器不走本仓的常规异常路线</h2>
 * 本仓的 {@code GlobalExceptionHandler} 对 {@code CloudPlatformException} 一律回 **HTTP 200**
 * 加 body 里的错误码 —— 其余端点都这么用，前端按 code 判断。**但 Alertmanager 只看状态码**：
 * 它收到 2xx 就认为投递成功，不会重试、不会有任何异常痕迹。密钥错了回 200 = **告警被静默丢掉**。
 *
 * <p>所以本类**直接返回 {@link ResponseEntity}**，并且把请求体收成 {@code String} 自己解析
 * （否则畸形 JSON 会被全局处理器的 {@code HttpMessageNotReadableException} 分支当成 200 吞掉）。
 * 三种失败模式的状态码全部由本类说了算：
 *
 * <ul>
 *   <li>密钥不对 → <b>401</b></li>
 *   <li>体解析失败 / clusterId 缺失 → <b>400</b></li>
 *   <li>落库内部异常 → <b>200</b>（重试不会变好，只会放大）</li>
 * </ul>
 *
 * <p>{@code clusterId} 走 <b>URL 查询参数</b>（Alertmanager 的 receiver url 里带
 * {@code ?clusterId=xxx}）：平台纳管多集群，写死在配置里就不对了；一个集群配一个 receiver
 * 本来就该分得清。缺失回 400 —— 配错了要能看出来，不能默默归到某个默认集群。
 */
@Slf4j
@Tag(name = "告警", description = "Alertmanager webhook 接收")
@RestController
@RequestMapping("/alert")
@RequiredArgsConstructor
public class AlertWebhookController {

    private final AlertIngestService ingest;
    private final AlertWebhookTokenCheck tokenCheck;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @PostMapping("/webhook")
    @Operation(summary = "接收 Alertmanager 的告警推送（共享密钥鉴权；真实状态码）")
    public ResponseEntity<ResponseData<Map<String, Integer>>> webhook(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String clusterId,
            @RequestBody(required = false) String rawBody) {

        if (!tokenCheck.matches(authorization)) {
            log.warn("告警接收端点鉴权失败（Authorization {}）",
                    authorization == null ? "缺失" : "不匹配");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ResponseData<>(EnumResponseType.ALERT_WEBHOOK_UNAUTHORIZED.getCode(),
                            "告警接收端点鉴权失败。", null));
        }
        if (!StringUtils.hasText(clusterId)) {
            log.warn("告警推送缺少 clusterId 查询参数 —— 集群侧 receiver 的 url 配错了。");
            return ResponseEntity.badRequest()
                    .body(new ResponseData<>(EnumResponseType.INVALID_FORMAT_FAIL.getCode(),
                            "缺少 clusterId 查询参数（写在 receiver 的 url 里，如 ?clusterId=xxx）。", null));
        }

        List<AlertWebhookRequest.Alert> alerts;
        try {
            JavaType type = jsonMapper.getTypeFactory()
                    .constructCollectionType(List.class, AlertWebhookRequest.Alert.class);
            alerts = jsonMapper.readValue(rawBody == null ? "" : rawBody, type);
        } catch (Exception e) {
            log.warn("告警推送的请求体解析失败：{}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(new ResponseData<>(EnumResponseType.INVALID_FORMAT_FAIL.getCode(),
                            "请求体不是 Alertmanager 的告警数组。", null));
        }

        int accepted;
        try {
            accepted = ingest.ingest(clusterId, alerts);
        } catch (Exception e) {
            // 仍然回 200：Alertmanager 只看状态码，重投一次也不会好，只会放大。
            log.error("告警落库整体失败（cluster={}）", clusterId, e);
            accepted = 0;
        }
        return ResponseEntity.ok(new ResponseData<>(Map.of("accepted", accepted)));
    }
}
