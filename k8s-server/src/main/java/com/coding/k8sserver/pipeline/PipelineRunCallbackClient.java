package com.coding.k8sserver.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.system.ResponseData;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * 把「某个 run 终结了」告诉 platform-api。
 *
 * <p>⚠️ 这是**回环调用**（同机 `127.0.0.1`），不跨网络边界。
 * 鉴权靠一个共享密钥 —— platform-api 是单体，没法只给这一个端点绑 127.0.0.1（spec §4.5）。
 */
@Slf4j
@Component
public class PipelineRunCallbackClient {

    private final RestClient http;
    private final String platformUrl;
    private final String token;

    public PipelineRunCallbackClient(
            @Value("${platform.pipeline.callback.url:http://127.0.0.1:8081}") String platformUrl,
            @Value("${platform.pipeline.archive.internal-token:}") String token) {
        this.platformUrl = platformUrl;
        this.token = token;
        HttpClient hc = HttpClient.newBuilder()
                // 关闭重定向：回调是同机固定路径，跟随重定向只会让"没配好"看起来像"配好了"
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(hc);
        f.setReadTimeout(Duration.ofSeconds(30));     // 归档要抓日志，给宽一点
        this.http = RestClient.builder().requestFactory(f).build();
    }

    /** @return 是否被确认（2xx）。**false 会进「待补」集合**，由节拍器重投。 */
    public boolean finished(String clusterId, String namespace, String runName) {
        return finished(clusterId, namespace, runName, false);
    }

    /**
     * @param timedOut 平台**判定超时**而终结的（节拍器发的）。
     *                 ⚠️ 这个标志必须跟着请求走：Tekton 侧看到的是平台 patch 的
     *                 {@code spec.status: Cancelled}，光看对象的 condition 区分不出
     *                 「用户取消」与「平台判超时」—— 而 spec §4.2 要求运行历史里两者是不一样的。
     * @return 是否被确认（2xx）。**false 会进「待补」集合**，由节拍器重投。
     */
    public boolean finished(String clusterId, String namespace, String runName, boolean timedOut) {
        try {
            ResponseData<?> resp = http.post()
                    .uri(platformUrl + "/internal/pipeline-run/finished")
                    .header("X-Internal-Token", token == null ? "" : token)
                    .body(Map.of("clusterId", clusterId, "namespace", namespace,
                            "runName", runName, "timedOut", timedOut))
                    .retrieve()
                    .body(ResponseData.class);
            // ⚠️ **只看 HTTP 状态是不够的**：本仓的业务失败一律是 200 + body 里的错误码
            // （前端 http.ts 就是靠 body.code 判断失败的，见 GlobalExceptionHandler）。
            // 只看 HTTP 会把「密钥不对」「未终结不归档」「集群 401」全当成成功 ——
            // 于是 k8s-server 关掉 watch、把这条 run 从待补集合里删掉、**再也不重投**，
            // 而集群里的对象与 Pod 就一直留着。这正是本批要消掉的东西，且是静默的。
            boolean ok = resp != null
                    && Integer.valueOf(EnumResponseType.SUCCESS.getCode()).equals(resp.getCode());
            if (!ok) {
                log.warn("归档回调未被平台接受（进待补集合，由节拍器重投）run={}/{} code={}",
                        namespace, runName, resp == null ? null : resp.getCode());
            }
            return ok;
        } catch (Exception e) {
            log.warn("归档回调失败（进待补集合，由节拍器重投）run={}/{}", namespace, runName, e);
            return false;
        }
    }
}
