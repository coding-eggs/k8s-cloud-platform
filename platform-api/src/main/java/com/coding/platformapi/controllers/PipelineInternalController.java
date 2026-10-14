package com.coding.platformapi.controllers;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.services.PipelineArchiveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * k8s-server → platform-api 的**内部回调**：某个运行终结了，去归档（spec §4.5②）。
 *
 * <h2>它为什么不走用户 JWT 体系</h2>
 * 调用方是 k8s-server，**没有用户身份**（它只是盯着集群里的事件）。所以：
 * <ul>
 *   <li>它**不进权限表**（不是用户端点），但必须进 {@code ExemptPaths}，
 *       否则 {@code PermissionCrossCheckRunner} 会让应用拒启；</li>
 *   <li>它**自己校验一个共享密钥**，而那就是**唯一防线** ——
 *       platform-api 是单体，没法只给这一个端点绑 {@code 127.0.0.1}
 *       （它跟着 8081 一起暴露在内网）。所以密钥必须是随机长串（32+ 字节），
 *       不能是 {@code platform} 这种。</li>
 *   <li><b>密钥留空 = 拒绝一切</b>（fail-closed，与凭据主密钥同一条判断）。
 *       ⚠️ 注意是「留空就拒绝」，不是「留空就放行」—— 后者会让没配好的部署把归档端点敞在内网上。</li>
 * </ul>
 *
 * <p>它是**回环调用**（同机 8080 ↔ 8081），不跨网络边界。
 */
@Slf4j
@Tag(name = "内部回调", description = "集群 → 平台的内部通知（共享密钥鉴权，不在用户权限体系里）")
@RestController
@RequestMapping("/internal")
public class PipelineInternalController {

    private final PipelineArchiveService archive;
    private final String token;

    public PipelineInternalController(PipelineArchiveService archive,
                                      @Value("${platform.pipeline.archive.internal-token:}") String token) {
        this.archive = archive;
        this.token = token;
    }

    @PostMapping("/pipeline-run/finished")
    @Operation(summary = "某个流水线运行终结了（k8s-server 调用；X-Internal-Token 鉴权）")
    public ResponseData<Void> finished(
            @RequestHeader(value = "X-Internal-Token", required = false) String given,
            @RequestBody FinishedRequest req) {
        // ⚠️ 两边都空**不算匹配**：`token.equals(given)` 在都为 null 时会是 true 吗？不会（NPE），
        // 但 `"" .equals("")` 会是 true —— 所以先判 token 非空，这是 fail-closed 的关键一步。
        if (!StringUtils.hasText(token) || !token.equals(given)) {
            log.warn("内部归档回调被拒（密钥未配置或不匹配）run={}", req == null ? null : req.runName());
            throw new CloudPlatformException(EnumResponseType.NON_AUTH_ENTRY_POINT,
                    "内部回调鉴权失败");
        }
        archive.archive(req.clusterId(), req.namespace(), req.runName(), req.timedOut());
        return new ResponseData<>();
    }

    /**
     * @param timedOut 平台**判定超时**而终结的（节拍器发的）。
     *                 ⚠️ 必须跟着请求走：Tekton 侧看到的是平台 patch 的 {@code spec.status: Cancelled}，
     *                 光看对象的 condition 分不出「用户取消」与「平台判超时」，
     *                 而 spec §4.2 要求运行历史里两者不一样。
     */
    public record FinishedRequest(String clusterId, String namespace, String runName, boolean timedOut) {
    }
}
