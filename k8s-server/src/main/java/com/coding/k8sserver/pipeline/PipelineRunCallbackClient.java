package com.coding.k8sserver.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
            http.post()
                    .uri(platformUrl + "/internal/pipeline-run/finished")
                    .header("X-Internal-Token", token == null ? "" : token)
                    .body(Map.of("clusterId", clusterId, "namespace", namespace,
                            "runName", runName, "timedOut", timedOut))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("归档回调失败（进待补集合，由节拍器重投）run={}/{}", namespace, runName, e);
            return false;
        }
    }
}
