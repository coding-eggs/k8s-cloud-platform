package com.coding.platformapi.k8s;

import com.coding.common.models.k8s.dto.NamespaceSnapshotDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * k8s-server 命名空间统计端点 client（{@code /namespacestats/**}）：一个方法一个端点，纯传输零业务。
 * <p>仿 {@link K8sNodeClient}：认证透传、错误转换都由 {@link K8sServerGateway} 兜住，这里只描述 URL 与参数。
 */
@Component
@RequiredArgsConstructor
public class K8sNamespaceStatClient {

    private final K8sServerGateway gateway;

    /** 单命名空间快照：GET /namespacestats/{namespace}?clusterId[&tenantId] */
    public NamespaceSnapshotDTO row(String tenantId, String clusterId, String namespace) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("clusterId", clusterId);
        if (tenantId != null) {
            params.put("tenantId", tenantId);
        }
        return gateway.exchange(HttpMethod.GET, "/namespacestats/" + namespace, params, null,
                gateway.responseType(NamespaceSnapshotDTO.class));
    }
}
