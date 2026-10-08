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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * k8s-server Calico client（{@code /calico/**}）：IPPool / IPReservation / BGP* 的 CRUD、
 * IPAM 派生查询与编辑器下拉候选，一个方法一个端点，纯传输零业务（降级/TTL 缓存/删除守卫在 {@code CalicoService}）。
 * <p>
 * Calico 资源是 CRD、且不走通用 DTO 六操作（list 走 body、get/yaml 的 clusterId 走 query、路径前缀在 /admin 下），
 * 故独立成 client 而非并入 {@link K8sClient}。见 docs/development/backend-layering.md。
 */
@Component
@RequiredArgsConstructor
public class K8sCalicoClient {

    private final K8sServerGateway gateway;

    // ==================== IPPool（/calico/ippool，集群级 CRUD） ====================

    /**列出 IPPool：POST /calico/ippool/list（clusterId/labelSelector 在 body） */
    public List<IpoolDTO> listIppools(IpoolDTO query) {
        return gateway.exchange(HttpMethod.POST, "/calico/ippool/list", null, query,
                gateway.listResponseType(IpoolDTO.class));
    }

    /**查询单个 IPPool：GET /calico/ippool/{name}?clusterId */
    public IpoolDTO getIppool(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/calico/ippool/" + name, queryOf(clusterId), null,
                gateway.responseType(IpoolDTO.class));
    }

    /**查询 IPPool YAML（只读）：GET /calico/ippool/{name}/yaml?clusterId */
    public String ippoolYaml(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/calico/ippool/" + name + "/yaml", queryOf(clusterId), null,
                gateway.responseType(String.class));
    }

    /**创建 IPPool：POST /calico/ippool?clusterId */
    public IpoolDTO createIppool(IpoolDTO dto) {
        return gateway.exchange(HttpMethod.POST, "/calico/ippool", queryOf(dto.getClusterId()), dto,
                gateway.responseType(IpoolDTO.class));
    }

    /**更新 IPPool：PUT /calico/ippool/{name}?clusterId */
    public IpoolDTO updateIppool(IpoolDTO dto) {
        return gateway.exchange(HttpMethod.PUT, "/calico/ippool/" + dto.getName(), queryOf(dto.getClusterId()), dto,
                gateway.responseType(IpoolDTO.class));
    }

    /**删除 IPPool：DELETE /calico/ippool/{name}?clusterId */
    public void deleteIppool(String clusterId, String name) {
        gateway.exchange(HttpMethod.DELETE, "/calico/ippool/" + name, queryOf(clusterId), null,
                gateway.responseType(Void.class));
    }

    // ==================== IPReservation（/calico/ipreservation，集群级 CRUD）★保留 IP ====================

    /**列出 IPReservation：POST /calico/ipreservation/list（clusterId/labelSelector 在 body） */
    public List<IpReservationDTO> listIpreervations(IpReservationDTO query) {
        return gateway.exchange(HttpMethod.POST, "/calico/ipreservation/list", null, query,
                gateway.listResponseType(IpReservationDTO.class));
    }

    /**查询单个 IPReservation：GET /calico/ipreservation/{name}?clusterId */
    public IpReservationDTO getIpreervation(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/calico/ipreservation/" + name, queryOf(clusterId), null,
                gateway.responseType(IpReservationDTO.class));
    }

    /**查询 IPReservation YAML（只读）：GET /calico/ipreservation/{name}/yaml?clusterId */
    public String ipreservationYaml(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/calico/ipreservation/" + name + "/yaml", queryOf(clusterId), null,
                gateway.responseType(String.class));
    }

    /**创建 IPReservation：POST /calico/ipreservation?clusterId */
    public IpReservationDTO createIpreervation(IpReservationDTO dto) {
        return gateway.exchange(HttpMethod.POST, "/calico/ipreservation", queryOf(dto.getClusterId()), dto,
                gateway.responseType(IpReservationDTO.class));
    }

    /**更新 IPReservation：PUT /calico/ipreservation/{name}?clusterId */
    public IpReservationDTO updateIpreervation(IpReservationDTO dto) {
        return gateway.exchange(HttpMethod.PUT, "/calico/ipreservation/" + dto.getName(), queryOf(dto.getClusterId()), dto,
                gateway.responseType(IpReservationDTO.class));
    }

    /**删除 IPReservation（释放保留段）：DELETE /calico/ipreservation/{name}?clusterId */
    public void deleteIpreervation(String clusterId, String name) {
        gateway.exchange(HttpMethod.DELETE, "/calico/ipreservation/" + name, queryOf(clusterId), null,
                gateway.responseType(Void.class));
    }

    // ==================== BGP*（/calico/bgp*，集群级 CRUD；list 走 body；get/yaml 的 clusterId 走 query） ====================

    /**列出 BGPConfiguration：POST /calico/bgpconfiguration/list（clusterId/labelSelector 在 body） */
    public List<BgpConfigurationDTO> listBgpConfigurations(BgpConfigurationDTO query) {
        return gateway.exchange(HttpMethod.POST, "/calico/bgpconfiguration/list", null, query,
                gateway.listResponseType(BgpConfigurationDTO.class));
    }

    /**查询单个 BGPConfiguration：GET /calico/bgpconfiguration/{name}?clusterId */
    public BgpConfigurationDTO getBgpConfiguration(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/calico/bgpconfiguration/" + name, queryOf(clusterId), null,
                gateway.responseType(BgpConfigurationDTO.class));
    }

    /**查询 BGPConfiguration YAML（只读）：GET /calico/bgpconfiguration/{name}/yaml?clusterId */
    public String bgpConfigurationYaml(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/calico/bgpconfiguration/" + name + "/yaml", queryOf(clusterId), null,
                gateway.responseType(String.class));
    }

    /**列出 BGPPeer：POST /calico/bgppeer/list（clusterId/labelSelector 在 body） */
    public List<BgpPeerDTO> listBgpPeers(BgpPeerDTO query) {
        return gateway.exchange(HttpMethod.POST, "/calico/bgppeer/list", null, query,
                gateway.listResponseType(BgpPeerDTO.class));
    }

    /**查询单个 BGPPeer：GET /calico/bgppeer/{name}?clusterId */
    public BgpPeerDTO getBgpPeer(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/calico/bgppeer/" + name, queryOf(clusterId), null,
                gateway.responseType(BgpPeerDTO.class));
    }

    /**查询 BGPPeer YAML（只读）：GET /calico/bgppeer/{name}/yaml?clusterId */
    public String bgpPeerYaml(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/calico/bgppeer/" + name + "/yaml", queryOf(clusterId), null,
                gateway.responseType(String.class));
    }

    /**列出 BGPFilter：POST /calico/bgpfilter/list（clusterId/labelSelector 在 body） */
    public List<BgpFilterDTO> listBgpFilters(BgpFilterDTO query) {
        return gateway.exchange(HttpMethod.POST, "/calico/bgpfilter/list", null, query,
                gateway.listResponseType(BgpFilterDTO.class));
    }

    /**查询单个 BGPFilter：GET /calico/bgpfilter/{name}?clusterId */
    public BgpFilterDTO getBgpFilter(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/calico/bgpfilter/" + name, queryOf(clusterId), null,
                gateway.responseType(BgpFilterDTO.class));
    }

    /**查询 BGPFilter YAML（只读）：GET /calico/bgpfilter/{name}/yaml?clusterId */
    public String bgpFilterYaml(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/calico/bgpfilter/" + name + "/yaml", queryOf(clusterId), null,
                gateway.responseType(String.class));
    }

    /**创建 BGPConfiguration（仅平台管理员）：POST /calico/bgpconfiguration?clusterId */
    public BgpConfigurationDTO createBgpConfiguration(BgpConfigurationDTO dto) {
        return gateway.exchange(HttpMethod.POST, "/calico/bgpconfiguration", queryOf(dto.getClusterId()), dto,
                gateway.responseType(BgpConfigurationDTO.class));
    }

    /**更新 BGPConfiguration（仅平台管理员）：PUT /calico/bgpconfiguration/{name}?clusterId */
    public BgpConfigurationDTO updateBgpConfiguration(BgpConfigurationDTO dto) {
        return gateway.exchange(HttpMethod.PUT, "/calico/bgpconfiguration/" + dto.getName(), queryOf(dto.getClusterId()), dto,
                gateway.responseType(BgpConfigurationDTO.class));
    }

    /**删除 BGPConfiguration（仅平台管理员）：DELETE /calico/bgpconfiguration/{name}?clusterId */
    public void deleteBgpConfiguration(String clusterId, String name) {
        gateway.exchange(HttpMethod.DELETE, "/calico/bgpconfiguration/" + name, queryOf(clusterId), null,
                gateway.responseType(Void.class));
    }

    /**创建 BGPPeer：POST /calico/bgppeer?clusterId */
    public BgpPeerDTO createBgpPeer(BgpPeerDTO dto) {
        return gateway.exchange(HttpMethod.POST, "/calico/bgppeer", queryOf(dto.getClusterId()), dto,
                gateway.responseType(BgpPeerDTO.class));
    }

    /**更新 BGPPeer：PUT /calico/bgppeer/{name}?clusterId */
    public BgpPeerDTO updateBgpPeer(BgpPeerDTO dto) {
        return gateway.exchange(HttpMethod.PUT, "/calico/bgppeer/" + dto.getName(), queryOf(dto.getClusterId()), dto,
                gateway.responseType(BgpPeerDTO.class));
    }

    /**删除 BGPPeer：DELETE /calico/bgppeer/{name}?clusterId */
    public void deleteBgpPeer(String clusterId, String name) {
        gateway.exchange(HttpMethod.DELETE, "/calico/bgppeer/" + name, queryOf(clusterId), null,
                gateway.responseType(Void.class));
    }

    /**创建 BGPFilter：POST /calico/bgpfilter?clusterId */
    public BgpFilterDTO createBgpFilter(BgpFilterDTO dto) {
        return gateway.exchange(HttpMethod.POST, "/calico/bgpfilter", queryOf(dto.getClusterId()), dto,
                gateway.responseType(BgpFilterDTO.class));
    }

    /**更新 BGPFilter：PUT /calico/bgpfilter/{name}?clusterId */
    public BgpFilterDTO updateBgpFilter(BgpFilterDTO dto) {
        return gateway.exchange(HttpMethod.PUT, "/calico/bgpfilter/" + dto.getName(), queryOf(dto.getClusterId()), dto,
                gateway.responseType(BgpFilterDTO.class));
    }

    /**删除 BGPFilter：DELETE /calico/bgpfilter/{name}?clusterId */
    public void deleteBgpFilter(String clusterId, String name) {
        gateway.exchange(HttpMethod.DELETE, "/calico/bgpfilter/" + name, queryOf(clusterId), null,
                gateway.responseType(Void.class));
    }

    // ==================== IPAM 派生查询（/calico/ipam，全只读） ====================

    /**池 IPAM 汇总：GET /calico/ipam/summary?clusterId&poolName */
    public PoolIpamSummaryDTO ipamSummary(String clusterId, String poolName) {
        Map<String, String> q = queryOf(clusterId);
        q.put("poolName", poolName);
        return gateway.exchange(HttpMethod.GET, "/calico/ipam/summary", q, null,
                gateway.responseType(PoolIpamSummaryDTO.class));
    }

    /**已物化块表：GET /calico/ipam/blocks?clusterId[&poolName][&search] */
    public List<IpamBlockStatDTO> ipamBlocks(String clusterId, String poolName, String search) {
        Map<String, String> q = queryOf(clusterId);
        if (poolName != null && !poolName.isBlank()) {
            q.put("poolName", poolName);
        }
        if (search != null && !search.isBlank()) {
            q.put("search", search);
        }
        return gateway.exchange(HttpMethod.GET, "/calico/ipam/blocks", q, null,
                gateway.listResponseType(IpamBlockStatDTO.class));
    }

    /**点查空闲：GET /calico/ipam/is-free?clusterId&cidrOrIp */
    public boolean ipamIsFree(String clusterId, String cidrOrIp) {
        Map<String, String> q = queryOf(clusterId);
        q.put("cidrOrIp", cidrOrIp);
        return Boolean.TRUE.equals(gateway.exchange(HttpMethod.GET, "/calico/ipam/is-free", q, null,
                gateway.responseType(Boolean.class)));
    }

    /**下一批空闲块：GET /calico/ipam/next-free-blocks?clusterId&poolName&offset&limit */
    public List<String> ipamNextFreeBlocks(String clusterId, String poolName, int offset, int limit) {
        Map<String, String> q = queryOf(clusterId);
        q.put("poolName", poolName);
        q.put("offset", String.valueOf(offset));
        q.put("limit", String.valueOf(limit));
        return gateway.exchange(HttpMethod.GET, "/calico/ipam/next-free-blocks", q, null,
                gateway.listResponseType(String.class));
    }

    /**单块 per-IP：GET /calico/ipam/block-ips?clusterId&cidr */
    public List<IpamIpDetailDTO> ipamBlockIps(String clusterId, String cidr) {
        Map<String, String> q = queryOf(clusterId);
        q.put("cidr", cidr);
        return gateway.exchange(HttpMethod.GET, "/calico/ipam/block-ips", q, null,
                gateway.listResponseType(IpamIpDetailDTO.class));
    }

    // ==================== BGP 编辑器下拉候选（/calico/form-options，只读） ====================

    /**BGP 编辑器下拉候选（namespaces / workloads）：POST /calico/form-options?clusterId */
    public CalicoFormOptionDTO calicoFormOptions(String clusterId) {
        return gateway.exchange(HttpMethod.POST, "/calico/form-options", queryOf(clusterId), null,
                gateway.responseType(CalicoFormOptionDTO.class));
    }

    /**某命名空间下的 Secret 引用候选（name + data keys；级联第二级）：GET /calico/form-options/secrets?clusterId&namespace */
    public List<SecretRefOptionDTO> calicoSecretOptions(String clusterId, String namespace) {
        Map<String, String> q = queryOf(clusterId);
        q.put("namespace", namespace);
        return gateway.exchange(HttpMethod.GET, "/calico/form-options/secrets", q, null,
                gateway.listResponseType(SecretRefOptionDTO.class));
    }

    private Map<String, String> queryOf(String clusterId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("clusterId", clusterId);
        return params;
    }
}
