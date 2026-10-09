package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
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
import com.coding.platformapi.cache.RedisJsonCache;
import com.coding.platformapi.k8s.K8sCalicoClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import tools.jackson.core.type.TypeReference;

/**
 * Calico 业务层：IPPool CRUD 透传 + IPAM 派生查询编排（降级 + TTL 缓存）+ IPPool 删除守卫。
 * <p>降级原则（spec §9）：某源不可用（集群断开 / capability 缺失 / admin RBAC 未覆盖 crd.projectcalico.org）
 * → 该段返回 null/空，前端显示「—」，不整页崩。IPAM 派生结果加短 TTL 缓存（45s），避免反复全量扫 claimed 集 M。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalicoService {

    private final K8sCalicoClient k8s;
    private final RedisJsonCache cache;

    /** IPAM 派生结果 TTL：同 B4，短缓存吸收高频刷新，过期重算。 */
    private static final Duration CACHE_TTL = Duration.ofSeconds(45);

    /** 各查询的反序列化类型（Redis 里存 JSON） */
    private static final TypeReference<PoolIpamSummaryDTO> SUMMARY_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<IpamBlockStatDTO>> BLOCKS_TYPE = new TypeReference<>() {};
    private static final TypeReference<Boolean> BOOL_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<IpamIpDetailDTO>> IP_DETAIL_TYPE = new TypeReference<>() {};

    // ==================== IPPool CRUD（透传；delete 带守卫） ====================

    public List<IpoolDTO> listIppools(IpoolDTO query) {
        return k8s.listIppools(query);
    }

    public IpoolDTO getIppool(String clusterId, String name) {
        return k8s.getIppool(clusterId, name);
    }

    public String ippoolYaml(String clusterId, String name) {
        return k8s.ippoolYaml(clusterId, name);
    }

    public IpoolDTO createIppool(IpoolDTO dto) {
        return k8s.createIppool(dto);
    }

    public IpoolDTO updateIppool(IpoolDTO dto) {
        return k8s.updateIppool(dto);
    }

    /**
     * 删除守卫（spec §5.3）：先取池 allocated——>0 拒绝（「仍有 N 个已分配 IP」）；=0 放行。
     * 汇总不可用（crd 缺失 / 集群断开）→ 无法确认占用，保守拒绝，避免误删在用池。默认池因恒有占用被天然保护。
     */
    public void deleteIppool(String clusterId, String name) {
        PoolIpamSummaryDTO s = freshSummary(clusterId, name);
        if (s == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "无法确认池「" + name + "」内是否有已分配 IP，请手动确认后再删除");
        }
        if (s.getAllocated() > 0) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "该池仍有 " + s.getAllocated() + " 个已分配 IP，无法删除；请先释放/迁移");
        }
        k8s.deleteIppool(clusterId, name);
    }

    // ==================== IPReservation CRUD（透传；删除=释放保留段，无守卫）★保留 IP ====================

    public List<IpReservationDTO> listIpreervations(IpReservationDTO query) {
        return k8s.listIpreervations(query);
    }

    public IpReservationDTO getIpreervation(String clusterId, String name) {
        return k8s.getIpreervation(clusterId, name);
    }

    public String ipreservationYaml(String clusterId, String name) {
        return k8s.ipreservationYaml(clusterId, name);
    }

    public IpReservationDTO createIpreervation(IpReservationDTO dto) {
        return k8s.createIpreervation(dto);
    }

    public IpReservationDTO updateIpreervation(IpReservationDTO dto) {
        return k8s.updateIpreervation(dto);
    }

    /** 删除=释放保留段：IP 回到自动分配池，无占用风险，直接透传。 */
    public void deleteIpreervation(String clusterId, String name) {
        k8s.deleteIpreervation(clusterId, name);
    }

    // ==================== BGP*（透传；Configuration 写仅平台管理员——权限在端点层 code 收敛） ====================

    public List<BgpConfigurationDTO> listBgpConfigurations(BgpConfigurationDTO query) {
        return k8s.listBgpConfigurations(query);
    }

    public BgpConfigurationDTO getBgpConfiguration(String clusterId, String name) {
        return k8s.getBgpConfiguration(clusterId, name);
    }

    public String bgpConfigurationYaml(String clusterId, String name) {
        return k8s.bgpConfigurationYaml(clusterId, name);
    }

    /** BGPConfiguration 是集群全局 BGP 默认配置，误改可能中断节点间/对外 BGP——仅平台管理员可达（端点层鉴权）。 */
    public BgpConfigurationDTO createBgpConfiguration(BgpConfigurationDTO dto) {
        return k8s.createBgpConfiguration(dto);
    }

    public BgpConfigurationDTO updateBgpConfiguration(BgpConfigurationDTO dto) {
        return k8s.updateBgpConfiguration(dto);
    }

    public void deleteBgpConfiguration(String clusterId, String name) {
        k8s.deleteBgpConfiguration(clusterId, name);
    }

    public List<BgpPeerDTO> listBgpPeers(BgpPeerDTO query) {
        return k8s.listBgpPeers(query);
    }

    public BgpPeerDTO getBgpPeer(String clusterId, String name) {
        return k8s.getBgpPeer(clusterId, name);
    }

    public String bgpPeerYaml(String clusterId, String name) {
        return k8s.bgpPeerYaml(clusterId, name);
    }

    public BgpPeerDTO createBgpPeer(BgpPeerDTO dto) {
        return k8s.createBgpPeer(dto);
    }

    public BgpPeerDTO updateBgpPeer(BgpPeerDTO dto) {
        return k8s.updateBgpPeer(dto);
    }

    public void deleteBgpPeer(String clusterId, String name) {
        k8s.deleteBgpPeer(clusterId, name);
    }

    public List<BgpFilterDTO> listBgpFilters(BgpFilterDTO query) {
        return k8s.listBgpFilters(query);
    }

    public BgpFilterDTO getBgpFilter(String clusterId, String name) {
        return k8s.getBgpFilter(clusterId, name);
    }

    public String bgpFilterYaml(String clusterId, String name) {
        return k8s.bgpFilterYaml(clusterId, name);
    }

    public BgpFilterDTO createBgpFilter(BgpFilterDTO dto) {
        return k8s.createBgpFilter(dto);
    }

    public BgpFilterDTO updateBgpFilter(BgpFilterDTO dto) {
        return k8s.updateBgpFilter(dto);
    }

    public void deleteBgpFilter(String clusterId, String name) {
        k8s.deleteBgpFilter(clusterId, name);
    }

    // ==================== BGP 编辑器下拉候选（降级：源不可用 → 空候选，不阻塞表单） ====================

    public CalicoFormOptionDTO formOptions(String clusterId) {
        CalicoFormOptionDTO options = safe(() -> k8s.calicoFormOptions(clusterId));
        return options != null ? options : new CalicoFormOptionDTO();
    }

    /** 某命名空间下的 Secret 引用候选（级联第二级）；失败降级空列表。 */
    public List<SecretRefOptionDTO> secretOptions(String clusterId, String namespace) {
        List<SecretRefOptionDTO> options = safe(() -> k8s.calicoSecretOptions(clusterId, namespace));
        return options != null ? options : List.of();
    }

    // ==================== IPAM 派生查询（降级 + TTL 缓存） ====================

    public PoolIpamSummaryDTO ipamSummary(String clusterId, String poolName) {
        return cache.get("calico:summary:" + clusterId + ":" + poolName, CACHE_TTL, SUMMARY_TYPE, () -> safe(
                () -> k8s.ipamSummary(clusterId, poolName)));
    }

    public List<IpamBlockStatDTO> ipamBlocks(String clusterId, String poolName, String search) {
        return cache.get("calico:blocks:" + clusterId + ":" + poolName + ":" + (search == null ? "" : search),
                CACHE_TTL, BLOCKS_TYPE, () -> safe(() -> k8s.ipamBlocks(clusterId, poolName, search)));
    }

    public Boolean ipamIsFree(String clusterId, String cidrOrIp) {
        return cache.get("calico:isfree:" + clusterId + ":" + cidrOrIp, CACHE_TTL, BOOL_TYPE, () -> safe(
                () -> k8s.ipamIsFree(clusterId, cidrOrIp)));
    }

    public List<String> ipamNextFreeBlocks(String clusterId, String poolName, int offset, int limit) {
        return cache.get("calico:nextfree:" + clusterId + ":" + poolName + ":" + offset + ":" + limit,
                CACHE_TTL, STRING_LIST_TYPE, () -> safe(
                        () -> k8s.ipamNextFreeBlocks(clusterId, poolName, offset, limit)));
    }

    public List<IpamIpDetailDTO> ipamBlockIps(String clusterId, String cidr) {
        return cache.get("calico:blockips:" + clusterId + ":" + cidr, CACHE_TTL, IP_DETAIL_TYPE, () -> safe(
                () -> k8s.ipamBlockIps(clusterId, cidr)));
    }

    /** 删除守卫用：绕过缓存取新鲜汇总；失败降级 null（由调用方保守处理）。 */
    private PoolIpamSummaryDTO freshSummary(String clusterId, String poolName) {
        return safe(() -> k8s.ipamSummary(clusterId, poolName));
    }

    /** 某源不可用 → null + warn，不抛错（前端「—」降级）。仅缓存成功结果，失败下次重试。 */
    private <T> T safe(Supplier<T> loader) {
        try {
            return loader.get();
        } catch (Exception e) {
            log.warn("Calico 派生查询降级（源不可用）: {}", e.getMessage());
            return null;
        }
    }
}
