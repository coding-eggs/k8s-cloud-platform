package com.coding.platformapi.k8s;

import com.coding.common.models.k8s.dto.NamespaceSnapshotDTO;
import com.coding.common.models.k8s.dto.admin.AdminNamespaceKeyRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * k8s-server 命名空间统计端点 client（{@code /namespacestats/**}）：一个方法一个端点，纯传输零业务。
 * <p>仿 {@link K8sNodeClient}：认证透传、错误转换都由 {@link K8sServerGateway} 兜住，这里只描述 URL 与参数。
 * <p>两条端点对应两种"谁在问"：有租户上下文走租户边界（{@link #row}），没有则走平台模式（{@link #platformRow}）。
 */
@Component
@RequiredArgsConstructor
public class K8sNamespaceStatClient {

    private final K8sServerGateway gateway;

    /**
     * 单命名空间快照（<b>租户轨</b>）：GET /namespacestats/{namespace}?clusterId&tenantId，边界 TENANT。
     * <p>tenantId 由调用方（{@code NamespaceStatService}）保证非空 —— 自管轨传会话租户、代管轨传被查看租户。
     */
    public NamespaceSnapshotDTO row(String tenantId, String clusterId, String namespace) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("clusterId", clusterId);
        if (tenantId != null) {
            params.put("tenantId", tenantId);
        }
        return gateway.exchange(HttpMethod.GET, "/namespacestats/" + namespace, params, null,
                gateway.responseType(NamespaceSnapshotDTO.class));
    }

    /**
     * 单命名空间快照（<b>平台模式</b>）：POST /namespacestats/platform/get，边界 PLATFORM（admin client）。
     * <p><b>{@code tenantId == null} 的语义就是"平台模式"</b>——平台侧页面（集群概览明细、命名空间详情页）读的是
     * 某个命名空间的行，而该行不含租户维度（{@code kube-system}/{@code default} 等未分配命名空间根本没有租户），
     * <b>不是</b>"忘了传 tenantId"。走 TENANT 边界会被分配表直接拒，故这里另开一条平台边界入口。
     */
    public NamespaceSnapshotDTO platformRow(String clusterId, String namespace) {
        AdminNamespaceKeyRequest req = new AdminNamespaceKeyRequest();
        req.setClusterId(clusterId);
        req.setNamespace(namespace);
        return gateway.exchange(HttpMethod.POST, "/namespacestats/platform/get", null, req,
                gateway.responseType(NamespaceSnapshotDTO.class));
    }
}
