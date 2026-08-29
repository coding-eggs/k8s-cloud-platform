package com.coding.platformapi.k8s;

import com.coding.common.models.k8s.dto.NamespaceDTO;
import com.coding.common.models.k8s.dto.admin.AdminClusterKeyRequest;
import com.coding.common.models.k8s.dto.admin.AdminCleanupRequest;
import com.coding.common.models.k8s.dto.admin.AdminNamespaceKeyRequest;
import com.coding.common.models.k8s.dto.admin.AdminProbeRequest;
import com.coding.common.models.k8s.dto.admin.AdminProbeResult;
import com.coding.common.models.k8s.dto.admin.AdminProvisionRequest;
import com.coding.common.models.k8s.dto.admin.AdminSaEnsureRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * k8s-server /admin/** 特殊端点 client：非六端点形态的生命周期接口，一个方法一个端点，纯传输零业务。
 * 未来 pod log/exec 等流式端点也归此类（流式走 {@link K8sServerGateway#streamGet}）。
 */
@Component
@RequiredArgsConstructor
public class K8sAdminClient {

    private final K8sServerGateway gateway;

    /**连通性探测（纳管前一次性，明文 kubeconfig）→ K8s 版本（gitVersion） */
    public String probe(String kubeconfig) {
        AdminProbeRequest req = new AdminProbeRequest();
        req.setKubeconfig(kubeconfig);
        AdminProbeResult result = gateway.exchange(HttpMethod.POST, "/admin/cluster/probe", null, req,
                gateway.responseType(AdminProbeResult.class));
        return result != null ? result.getVersion() : null;
    }

    /**集群开通（幂等）：platform-system + 全部启用租户 SA + 全部模板 ClusterRole */
    public void provisionCluster(String clusterId) {
        AdminProvisionRequest req = new AdminProvisionRequest();
        req.setClusterId(clusterId);
        gateway.exchange(HttpMethod.POST, "/admin/cluster/provision", null, req, gateway.responseType(Void.class));
    }

    /**补建单租户 SA（幂等）；serviceAccount 为裸名（K8s 对象名 = tn- + 该值） */
    public void ensureTenantSa(String clusterId, String serviceAccount) {
        AdminSaEnsureRequest req = new AdminSaEnsureRequest();
        req.setClusterId(clusterId);
        req.setServiceAccount(serviceAccount);
        gateway.exchange(HttpMethod.POST, "/admin/tenant/sa/ensure", null, req, gateway.responseType(Void.class));
    }

    /**创建命名空间（幂等，打 managed-by 标签） */
    public void ensureNamespace(String clusterId, String namespace) {
        AdminNamespaceKeyRequest req = new AdminNamespaceKeyRequest();
        req.setClusterId(clusterId);
        req.setNamespace(namespace);
        gateway.exchange(HttpMethod.POST, "/admin/namespace/create", null, req, gateway.responseType(Void.class));
    }

    /**列出集群内全部命名空间（全字段对象；展示裁剪由调用方业务决定） */
    public List<NamespaceDTO> listNamespaces(String clusterId) {
        AdminClusterKeyRequest req = new AdminClusterKeyRequest();
        req.setClusterId(clusterId);
        return gateway.exchange(HttpMethod.POST, "/admin/namespace/list", null, req,
                gateway.listResponseType(NamespaceDTO.class));
    }

    /**删除命名空间（能否删除由调用方业务规则判定） */
    public void deleteNamespace(String clusterId, String namespace) {
        AdminNamespaceKeyRequest req = new AdminNamespaceKeyRequest();
        req.setClusterId(clusterId);
        req.setNamespace(namespace);
        gateway.exchange(HttpMethod.POST, "/admin/namespace/delete", null, req, gateway.responseType(Void.class));
    }

    /**租户 K8s 侧批量清理（k8s-server 内部 best-effort + 清租户 client 缓存） */
    public void cleanupTenant(AdminCleanupRequest req) {
        gateway.exchange(HttpMethod.POST, "/admin/tenant/cleanup", null, req, gateway.responseType(Void.class));
    }
}
