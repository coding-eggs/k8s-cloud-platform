package com.coding.platformapi.k8s;

import com.coding.common.models.k8s.dto.ClusterAggregateDTO;
import com.coding.common.models.k8s.dto.admin.AdminCleanupRequest;
import com.coding.common.models.k8s.dto.admin.AdminClusterKeyRequest;
import com.coding.common.models.k8s.dto.admin.AdminNamespaceKeyRequest;
import com.coding.common.models.k8s.dto.admin.AdminProbeRequest;
import com.coding.common.models.k8s.dto.admin.AdminProbeResult;
import com.coding.common.models.k8s.dto.admin.AdminProvisionRequest;
import com.coding.common.models.k8s.dto.admin.AdminSaEnsureRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * k8s-server 生命周期 client（{@code /cluster/**}、{@code /tenant/**}、{@code /namespace/create}）：
 * 集群纳管/开通、租户 SA 补建、K8s 侧批量清理与 client 缓存失效，一个方法一个端点，纯传输零业务。
 * <p>
 * 与 {@link K8sClient} 的分工：这些是"动作"，不是标准六端点形态的资源 CRUD，故不复用 DTO 六操作。
 * 同族的专用端点 client 还有 {@link K8sCalicoClient}（Calico 全族）、{@link K8sNodeClient}（节点专属动作）、
 * {@link K8sPodClient}（Pod 流式日志）。
 */
@Component
@RequiredArgsConstructor
public class K8sLifecycleClient {

    private final K8sServerGateway gateway;

    /**连通性探测（纳管前一次性，明文 kubeconfig）→ K8s 版本（gitVersion） */
    public String probe(String kubeconfig) {
        AdminProbeRequest req = new AdminProbeRequest();
        req.setKubeconfig(kubeconfig);
        AdminProbeResult result = gateway.exchange(HttpMethod.POST, "/cluster/probe", null, req,
                gateway.responseType(AdminProbeResult.class));
        return result != null ? result.getVersion() : null;
    }

    /**集群开通（幂等）：platform-system + 全部启用租户 SA + 全部模板 ClusterRole */
    public void provisionCluster(String clusterId) {
        AdminProvisionRequest req = new AdminProvisionRequest();
        req.setClusterId(clusterId);
        gateway.exchange(HttpMethod.POST, "/cluster/provision", null, req, gateway.responseType(Void.class));
    }

    /**
     * 集群资源聚合快照（集群概览的数据面）：跨命名空间一次 pass 出总量 + per-ns 明细 + 异常 Pod +
     * 节点健康 + 存储统计。较慢（9 次全量 list）→ 调用方 {@code ClusterService} 加短 TTL 缓存。
     */
    public ClusterAggregateDTO resourceAggregate(String clusterId) {
        AdminClusterKeyRequest req = new AdminClusterKeyRequest();
        req.setClusterId(clusterId);
        return gateway.exchange(HttpMethod.POST, "/cluster/resource-aggregate", null, req,
                gateway.responseType(ClusterAggregateDTO.class));
    }

    /**刷新集群 API 能力（运行时 discovery 快照）→ group→versions */
    public Map<String, List<String>> refreshCapability(String clusterId) {
        AdminClusterKeyRequest req = new AdminClusterKeyRequest();
        req.setClusterId(clusterId);
        return gateway.exchange(HttpMethod.POST, "/cluster/capability/refresh", null, req,
                gateway.capabilityResponseType());
    }

    /**失效集群 client 缓存（kubeconfig 变更 / 禁用 / 删除后）：admin + 派生 tenant client */
    public void evictClusterClient(String clusterId) {
        AdminClusterKeyRequest req = new AdminClusterKeyRequest();
        req.setClusterId(clusterId);
        gateway.exchange(HttpMethod.POST, "/cluster/client/evict", null, req, gateway.responseType(Void.class));
    }

    /**补建单租户 SA（幂等）；serviceAccount 为裸名（K8s 对象名 = tn- + 该值） */
    public void ensureTenantSa(String clusterId, String serviceAccount) {
        AdminSaEnsureRequest req = new AdminSaEnsureRequest();
        req.setClusterId(clusterId);
        req.setServiceAccount(serviceAccount);
        gateway.exchange(HttpMethod.POST, "/tenant/sa/ensure", null, req, gateway.responseType(Void.class));
    }

    /**创建命名空间（幂等，打 managed-by 标签） */
    public void ensureNamespace(String clusterId, String namespace) {
        AdminNamespaceKeyRequest req = new AdminNamespaceKeyRequest();
        req.setClusterId(clusterId);
        req.setNamespace(namespace);
        gateway.exchange(HttpMethod.POST, "/namespace/create", null, req, gateway.responseType(Void.class));
    }

    /**租户 K8s 侧批量清理（k8s-server 内部 best-effort + 清租户 client 缓存） */
    public void cleanupTenant(AdminCleanupRequest req) {
        gateway.exchange(HttpMethod.POST, "/tenant/cleanup", null, req, gateway.responseType(Void.class));
    }
}
