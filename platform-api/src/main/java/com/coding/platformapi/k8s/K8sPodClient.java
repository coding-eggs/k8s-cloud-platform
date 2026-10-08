package com.coding.platformapi.k8s;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * k8s-server Pod 专用端点 client：目前只有流式日志（{@code /pods/{name}/logs}）。
 * <p>
 * 为什么不并入 {@link K8sClient}：日志不是六端点标准形态（流式、不整体缓冲、响应非 ResponseData），
 * 按"公共行为一个 client，其余专用端点各自独立"的约定单列。Pod exec/attach 的转发端点
 * （platform-api 侧的 {@code PodExecRelayHandler}）日后若改走本 client，同归此类。
 */
@Component
@RequiredArgsConstructor
public class K8sPodClient {

    private final K8sServerGateway gateway;

    /**
     * Pod 日志（流式透传，不整体缓冲）：
     * GET /pods/{name}/logs?clusterId&namespace[&tenantId][&container][&sinceTime|tailLines|sinceSeconds]
     */
    public void streamLogs(String name, String tenantId, String clusterId, String namespace,
                           String container, Integer sinceSeconds, String sinceTime, Integer tailLines,
                           OutputStream out) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        params.put("clusterId", clusterId);
        params.put("namespace", namespace);
        StreamQueryParams.putLogOptions(params, container, sinceSeconds, sinceTime, tailLines);
        gateway.streamGet("/pods/" + name + "/logs", params, out);
    }
}
