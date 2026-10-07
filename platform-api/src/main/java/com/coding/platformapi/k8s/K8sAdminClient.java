package com.coding.platformapi.k8s;

import com.coding.common.models.k8s.dto.BgpConfigurationDTO;
import com.coding.common.models.k8s.dto.BgpFilterDTO;
import com.coding.common.models.k8s.dto.BgpPeerDTO;
import com.coding.common.models.k8s.dto.CalicoFormOptionDTO;
import com.coding.common.models.k8s.dto.IpamBlockStatDTO;
import com.coding.common.models.k8s.dto.IpamIpDetailDTO;
import com.coding.common.models.k8s.dto.IpoolDTO;
import com.coding.common.models.k8s.dto.IpReservationDTO;
import com.coding.common.models.k8s.dto.PoolIpamSummaryDTO;
import com.coding.common.models.k8s.dto.SecretRefOptionDTO;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**刷新集群 API 能力（运行时 discovery 快照）→ group→versions */
    public Map<String, List<String>> refreshCapability(String clusterId) {
        AdminClusterKeyRequest req = new AdminClusterKeyRequest();
        req.setClusterId(clusterId);
        return gateway.exchange(HttpMethod.POST, "/admin/cluster/capability/refresh", null, req,
                gateway.capabilityResponseType());
    }

    /**失效集群 client 缓存（kubeconfig 变更 / 禁用 / 删除后）：admin + 派生 tenant client */
    public void evictClusterClient(String clusterId) {
        AdminClusterKeyRequest req = new AdminClusterKeyRequest();
        req.setClusterId(clusterId);
        gateway.exchange(HttpMethod.POST, "/admin/cluster/client/evict", null, req, gateway.responseType(Void.class));
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

    /**租户 K8s 侧批量清理（k8s-server 内部 best-effort + 清租户 client 缓存） */
    public void cleanupTenant(AdminCleanupRequest req) {
        gateway.exchange(HttpMethod.POST, "/admin/tenant/cleanup", null, req, gateway.responseType(Void.class));
    }

    // ==================== Calico IPPool（/admin/calico/ippool，集群级 CRUD） ====================

    /**列出 IPPool：POST /admin/calico/ippool/list（clusterId/labelSelector 在 body） */
    public List<IpoolDTO> listIppools(IpoolDTO query) {
        return gateway.exchange(HttpMethod.POST, "/admin/calico/ippool/list", null, query,
                gateway.listResponseType(IpoolDTO.class));
    }

    /**查询单个 IPPool：GET /admin/calico/ippool/{name}?clusterId */
    public IpoolDTO getIppool(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/admin/calico/ippool/" + name, queryOf(clusterId), null,
                gateway.responseType(IpoolDTO.class));
    }

    /**查询 IPPool YAML（只读）：GET /admin/calico/ippool/{name}/yaml?clusterId */
    public String ippoolYaml(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/admin/calico/ippool/" + name + "/yaml", queryOf(clusterId), null,
                gateway.responseType(String.class));
    }

    /**创建 IPPool：POST /admin/calico/ippool?clusterId */
    public IpoolDTO createIppool(IpoolDTO dto) {
        return gateway.exchange(HttpMethod.POST, "/admin/calico/ippool", queryOf(dto.getClusterId()), dto,
                gateway.responseType(IpoolDTO.class));
    }

    /**更新 IPPool：PUT /admin/calico/ippool/{name}?clusterId */
    public IpoolDTO updateIppool(IpoolDTO dto) {
        return gateway.exchange(HttpMethod.PUT, "/admin/calico/ippool/" + dto.getName(), queryOf(dto.getClusterId()), dto,
                gateway.responseType(IpoolDTO.class));
    }

    /**删除 IPPool：DELETE /admin/calico/ippool/{name}?clusterId */
    public void deleteIppool(String clusterId, String name) {
        gateway.exchange(HttpMethod.DELETE, "/admin/calico/ippool/" + name, queryOf(clusterId), null,
                gateway.responseType(Void.class));
    }

    // ==================== Calico IPReservation（/admin/calico/ipreservation，集群级 CRUD）★保留 IP ====================

    /**列出 IPReservation：POST /admin/calico/ipreservation/list（clusterId/labelSelector 在 body） */
    public List<IpReservationDTO> listIpreervations(IpReservationDTO query) {
        return gateway.exchange(HttpMethod.POST, "/admin/calico/ipreservation/list", null, query,
                gateway.listResponseType(IpReservationDTO.class));
    }

    /**查询单个 IPReservation：GET /admin/calico/ipreservation/{name}?clusterId */
    public IpReservationDTO getIpreervation(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/admin/calico/ipreservation/" + name, queryOf(clusterId), null,
                gateway.responseType(IpReservationDTO.class));
    }

    /**查询 IPReservation YAML（只读）：GET /admin/calico/ipreservation/{name}/yaml?clusterId */
    public String ipreservationYaml(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/admin/calico/ipreservation/" + name + "/yaml", queryOf(clusterId), null,
                gateway.responseType(String.class));
    }

    /**创建 IPReservation：POST /admin/calico/ipreservation?clusterId */
    public IpReservationDTO createIpreervation(IpReservationDTO dto) {
        return gateway.exchange(HttpMethod.POST, "/admin/calico/ipreservation", queryOf(dto.getClusterId()), dto,
                gateway.responseType(IpReservationDTO.class));
    }

    /**更新 IPReservation：PUT /admin/calico/ipreservation/{name}?clusterId */
    public IpReservationDTO updateIpreervation(IpReservationDTO dto) {
        return gateway.exchange(HttpMethod.PUT, "/admin/calico/ipreservation/" + dto.getName(), queryOf(dto.getClusterId()), dto,
                gateway.responseType(IpReservationDTO.class));
    }

    /**删除 IPReservation（释放保留段）：DELETE /admin/calico/ipreservation/{name}?clusterId */
    public void deleteIpreervation(String clusterId, String name) {
        gateway.exchange(HttpMethod.DELETE, "/admin/calico/ipreservation/" + name, queryOf(clusterId), null,
                gateway.responseType(Void.class));
    }

    // ==================== Calico BGP*（/admin/calico/bgp*，集群级 CRUD；list 走 body；get/yaml 的 clusterId 走 query） ====================

    /**列出 BGPConfiguration：POST /admin/calico/bgpconfiguration/list（clusterId/labelSelector 在 body） */
    public List<BgpConfigurationDTO> listBgpConfigurations(BgpConfigurationDTO query) {
        return gateway.exchange(HttpMethod.POST, "/admin/calico/bgpconfiguration/list", null, query,
                gateway.listResponseType(BgpConfigurationDTO.class));
    }

    /**查询单个 BGPConfiguration：GET /admin/calico/bgpconfiguration/{name}?clusterId */
    public BgpConfigurationDTO getBgpConfiguration(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/admin/calico/bgpconfiguration/" + name, queryOf(clusterId), null,
                gateway.responseType(BgpConfigurationDTO.class));
    }

    /**查询 BGPConfiguration YAML（只读）：GET /admin/calico/bgpconfiguration/{name}/yaml?clusterId */
    public String bgpConfigurationYaml(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/admin/calico/bgpconfiguration/" + name + "/yaml", queryOf(clusterId), null,
                gateway.responseType(String.class));
    }

    /**列出 BGPPeer：POST /admin/calico/bgppeer/list（clusterId/labelSelector 在 body） */
    public List<BgpPeerDTO> listBgpPeers(BgpPeerDTO query) {
        return gateway.exchange(HttpMethod.POST, "/admin/calico/bgppeer/list", null, query,
                gateway.listResponseType(BgpPeerDTO.class));
    }

    /**查询单个 BGPPeer：GET /admin/calico/bgppeer/{name}?clusterId */
    public BgpPeerDTO getBgpPeer(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/admin/calico/bgppeer/" + name, queryOf(clusterId), null,
                gateway.responseType(BgpPeerDTO.class));
    }

    /**查询 BGPPeer YAML（只读）：GET /admin/calico/bgppeer/{name}/yaml?clusterId */
    public String bgpPeerYaml(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/admin/calico/bgppeer/" + name + "/yaml", queryOf(clusterId), null,
                gateway.responseType(String.class));
    }

    /**列出 BGPFilter：POST /admin/calico/bgpfilter/list（clusterId/labelSelector 在 body） */
    public List<BgpFilterDTO> listBgpFilters(BgpFilterDTO query) {
        return gateway.exchange(HttpMethod.POST, "/admin/calico/bgpfilter/list", null, query,
                gateway.listResponseType(BgpFilterDTO.class));
    }

    /**查询单个 BGPFilter：GET /admin/calico/bgpfilter/{name}?clusterId */
    public BgpFilterDTO getBgpFilter(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/admin/calico/bgpfilter/" + name, queryOf(clusterId), null,
                gateway.responseType(BgpFilterDTO.class));
    }

    /**查询 BGPFilter YAML（只读）：GET /admin/calico/bgpfilter/{name}/yaml?clusterId */
    public String bgpFilterYaml(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/admin/calico/bgpfilter/" + name + "/yaml", queryOf(clusterId), null,
                gateway.responseType(String.class));
    }

    /**创建 BGPConfiguration（仅平台管理员）：POST /admin/calico/bgpconfiguration?clusterId */
    public BgpConfigurationDTO createBgpConfiguration(BgpConfigurationDTO dto) {
        return gateway.exchange(HttpMethod.POST, "/admin/calico/bgpconfiguration", queryOf(dto.getClusterId()), dto,
                gateway.responseType(BgpConfigurationDTO.class));
    }

    /**更新 BGPConfiguration（仅平台管理员）：PUT /admin/calico/bgpconfiguration/{name}?clusterId */
    public BgpConfigurationDTO updateBgpConfiguration(BgpConfigurationDTO dto) {
        return gateway.exchange(HttpMethod.PUT, "/admin/calico/bgpconfiguration/" + dto.getName(), queryOf(dto.getClusterId()), dto,
                gateway.responseType(BgpConfigurationDTO.class));
    }

    /**删除 BGPConfiguration（仅平台管理员）：DELETE /admin/calico/bgpconfiguration/{name}?clusterId */
    public void deleteBgpConfiguration(String clusterId, String name) {
        gateway.exchange(HttpMethod.DELETE, "/admin/calico/bgpconfiguration/" + name, queryOf(clusterId), null,
                gateway.responseType(Void.class));
    }

    /**创建 BGPPeer：POST /admin/calico/bgppeer?clusterId */
    public BgpPeerDTO createBgpPeer(BgpPeerDTO dto) {
        return gateway.exchange(HttpMethod.POST, "/admin/calico/bgppeer", queryOf(dto.getClusterId()), dto,
                gateway.responseType(BgpPeerDTO.class));
    }

    /**更新 BGPPeer：PUT /admin/calico/bgppeer/{name}?clusterId */
    public BgpPeerDTO updateBgpPeer(BgpPeerDTO dto) {
        return gateway.exchange(HttpMethod.PUT, "/admin/calico/bgppeer/" + dto.getName(), queryOf(dto.getClusterId()), dto,
                gateway.responseType(BgpPeerDTO.class));
    }

    /**删除 BGPPeer：DELETE /admin/calico/bgppeer/{name}?clusterId */
    public void deleteBgpPeer(String clusterId, String name) {
        gateway.exchange(HttpMethod.DELETE, "/admin/calico/bgppeer/" + name, queryOf(clusterId), null,
                gateway.responseType(Void.class));
    }

    /**创建 BGPFilter：POST /admin/calico/bgpfilter?clusterId */
    public BgpFilterDTO createBgpFilter(BgpFilterDTO dto) {
        return gateway.exchange(HttpMethod.POST, "/admin/calico/bgpfilter", queryOf(dto.getClusterId()), dto,
                gateway.responseType(BgpFilterDTO.class));
    }

    /**更新 BGPFilter：PUT /admin/calico/bgpfilter/{name}?clusterId */
    public BgpFilterDTO updateBgpFilter(BgpFilterDTO dto) {
        return gateway.exchange(HttpMethod.PUT, "/admin/calico/bgpfilter/" + dto.getName(), queryOf(dto.getClusterId()), dto,
                gateway.responseType(BgpFilterDTO.class));
    }

    /**删除 BGPFilter：DELETE /admin/calico/bgpfilter/{name}?clusterId */
    public void deleteBgpFilter(String clusterId, String name) {
        gateway.exchange(HttpMethod.DELETE, "/admin/calico/bgpfilter/" + name, queryOf(clusterId), null,
                gateway.responseType(Void.class));
    }

    // ==================== Calico IPAM 派生查询（/admin/calico/ipam，全只读） ====================

    /**池 IPAM 汇总：GET /admin/calico/ipam/summary?clusterId&poolName */
    public PoolIpamSummaryDTO ipamSummary(String clusterId, String poolName) {
        Map<String, String> q = queryOf(clusterId);
        q.put("poolName", poolName);
        return gateway.exchange(HttpMethod.GET, "/admin/calico/ipam/summary", q, null,
                gateway.responseType(PoolIpamSummaryDTO.class));
    }

    /**已物化块表：GET /admin/calico/ipam/blocks?clusterId[&poolName][&search] */
    public List<IpamBlockStatDTO> ipamBlocks(String clusterId, String poolName, String search) {
        Map<String, String> q = queryOf(clusterId);
        if (poolName != null && !poolName.isBlank()) {
            q.put("poolName", poolName);
        }
        if (search != null && !search.isBlank()) {
            q.put("search", search);
        }
        return gateway.exchange(HttpMethod.GET, "/admin/calico/ipam/blocks", q, null,
                gateway.listResponseType(IpamBlockStatDTO.class));
    }

    /**点查空闲：GET /admin/calico/ipam/is-free?clusterId&cidrOrIp */
    public boolean ipamIsFree(String clusterId, String cidrOrIp) {
        Map<String, String> q = queryOf(clusterId);
        q.put("cidrOrIp", cidrOrIp);
        return Boolean.TRUE.equals(gateway.exchange(HttpMethod.GET, "/admin/calico/ipam/is-free", q, null,
                gateway.responseType(Boolean.class)));
    }

    /**下一批空闲块：GET /admin/calico/ipam/next-free-blocks?clusterId&poolName&offset&limit */
    public List<String> ipamNextFreeBlocks(String clusterId, String poolName, int offset, int limit) {
        Map<String, String> q = queryOf(clusterId);
        q.put("poolName", poolName);
        q.put("offset", String.valueOf(offset));
        q.put("limit", String.valueOf(limit));
        return gateway.exchange(HttpMethod.GET, "/admin/calico/ipam/next-free-blocks", q, null,
                gateway.listResponseType(String.class));
    }

    /**单块 per-IP：GET /admin/calico/ipam/block-ips?clusterId&cidr */
    public List<IpamIpDetailDTO> ipamBlockIps(String clusterId, String cidr) {
        Map<String, String> q = queryOf(clusterId);
        q.put("cidr", cidr);
        return gateway.exchange(HttpMethod.GET, "/admin/calico/ipam/block-ips", q, null,
                gateway.listResponseType(IpamIpDetailDTO.class));
    }

    // ==================== Calico BGP 编辑器下拉候选（/admin/calico/form-options，只读） ====================

    /**BGP 编辑器下拉候选（namespaces / workloads）：POST /admin/calico/form-options?clusterId */
    public CalicoFormOptionDTO calicoFormOptions(String clusterId) {
        return gateway.exchange(HttpMethod.POST, "/admin/calico/form-options", queryOf(clusterId), null,
                gateway.responseType(CalicoFormOptionDTO.class));
    }

    /**某命名空间下的 Secret 引用候选（name + data keys；级联第二级）：GET /admin/calico/form-options/secrets?clusterId&namespace */
    public List<SecretRefOptionDTO> calicoSecretOptions(String clusterId, String namespace) {
        Map<String, String> q = queryOf(clusterId);
        q.put("namespace", namespace);
        return gateway.exchange(HttpMethod.GET, "/admin/calico/form-options/secrets", q, null,
                gateway.listResponseType(SecretRefOptionDTO.class));
    }

    private Map<String, String> queryOf(String clusterId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("clusterId", clusterId);
        return params;
    }
}
