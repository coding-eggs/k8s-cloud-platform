package com.coding.k8sserver.pipeline;

import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.pipeline.PipelineRunSnapshotDTO;
import com.coding.common.models.system.ResponseData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * 把「某个运行终结了」**连同它的快照**告诉 platform-api。
 *
 * <p>⚠️ 这是**回环调用**（同机 `127.0.0.1`），不跨网络边界。鉴权靠一个共享密钥。
 *
 * <p>⚠️ 为什么快照也在这条请求里（而不是让 platform-api 回头来取）：watch 是 k8s-server 开的，
 * 收到终结事件时集群对象、TaskRun、Pod 都还在它手上 —— 快照本来就在它手里。
 * 让 platform-api 反过来取，就得为「没有用户 token 的后台调用」另造一套机器身份，
 * 那是个安全边界问题；能绕开就绕开。
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
        // 归档要落库、可能带上几 MB 日志，给宽一点
        f.setReadTimeout(Duration.ofSeconds(30));
        this.http = RestClient.builder().requestFactory(f).build();
    }

    /**
     * @return 是否被平台确认。**false 会进「待补」集合**，由节拍器重投。
     *
     * <p>⚠️ **只看 HTTP 状态是不够的**：本仓的业务失败一律是 200 + body 里的错误码
     * （前端 http.ts 就是靠 body.code 判断失败的）。只看 HTTP 会把「密钥不对」「未终结不归档」
     * 全当成成功 —— 于是关掉 watch、把这条 run 从待补集合里删掉、**再也不重投**，
     * 而集群里的对象与 Pod 就一直留着，失败还是静默的。
     */
    public boolean finished(PipelineRunSnapshotDTO snapshot) {
        try {
            ResponseData<?> resp = http.post()
                    .uri(platformUrl + "/internal/pipeline-run/finished")
                    .header("X-Internal-Token", token == null ? "" : token)
                    .body(Map.of("snapshot", snapshot))
                    .retrieve()
                    .body(ResponseData.class);
            boolean ok = resp != null
                    && Integer.valueOf(EnumResponseType.SUCCESS.getCode()).equals(resp.getCode());
            if (!ok) {
                log.warn("归档回调未被平台接受（进待补集合，由节拍器重投）run={}/{} code={}",
                        snapshot.namespace(), snapshot.runName(), resp == null ? null : resp.getCode());
            }
            return ok;
        } catch (Exception e) {
            log.warn("归档回调失败（进待补集合，由节拍器重投）run={}/{}",
                    snapshot.namespace(), snapshot.runName(), e);
            return false;
        }
    }
}
