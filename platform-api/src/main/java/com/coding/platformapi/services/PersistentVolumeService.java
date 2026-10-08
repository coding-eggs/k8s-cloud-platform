package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PersistentVolumeDTO;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.security.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PersistentVolume 业务层（集群级只读）+ <b>租户收窄</b>。
 *
 * <p><b>为什么需要收窄</b>：PV 是集群级对象（无 namespace），k8s-server 侧走
 * {@code AbstractClusterResourceController}，其边界只校验"集群已登记"，完全没有租户维度；而
 * {@code tenant:persistentvolume:{list,get,yaml}} 又确实绑在 tenant-admin / tenant-member 上。
 * 两者相加 = 任意租户成员可列出<b>全集群 PV</b>，含他租户 PVC 的 {@code claimRef}（namespace + name），
 * 部分 CSI 驱动的 PV annotation 还可能带凭据。故在本层按归属过滤（V2026_10_07 批次的安全修复）。
 *
 * <p><b>归属判据</b>：{@code spec.claimRef.namespace ∈ 本租户在该集群的已分配命名空间集合}。
 * 未绑定的 PV（{@code claimNamespace == null}）属集群存储池，是平台资源，<b>对租户不可见</b>。
 *
 * <p><b>平台管理员不过滤</b>：无租户帽的 base token（{@link AuthContext#hatTenantId()} 为 null）看到全量。
 *
 * <p><b>信任边界</b>：本过滤在 platform-api（业务层）。k8s-server 的 {@code /persistentvolumes}
 * 对任何已认证 token 可达——它按内网可信服务对待。若 k8s-server 日后对外暴露，须在该侧加同款收窄。
 * 见 docs/development/backend-layering.md。
 */
@Service
@RequiredArgsConstructor
public class PersistentVolumeService {

    private final K8sClient k8s;
    private final AuthContext auth;
    private final PlatformTenantNamespaceMapper allocationMapper;

    /**列出 PV：租户帽下只返回绑定到本租户已分配命名空间的 PV；平台管理员返回全量 */
    public List<PersistentVolumeDTO> list(PersistentVolumeDTO query) {
        List<PersistentVolumeDTO> all = k8s.list(query);
        String tenantId = auth.hatTenantId();
        if (tenantId == null) {
            return all; // base token（平台管理员）：不过滤
        }
        Set<String> owned = ownedNamespaces(tenantId, query.getClusterId());
        return all.stream()
                .filter(pv -> pv.getClaimNamespace() != null && owned.contains(pv.getClaimNamespace()))
                .toList();
    }

    public PersistentVolumeDTO get(String name, String tenantId, String clusterId) {
        PersistentVolumeDTO pv = k8s.get(dto(name, tenantId, clusterId));
        assertClaimVisible(pv, clusterId);
        return pv;
    }

    /**
     * 查询 PV YAML（只读展示）。
     * <p>先取对象做归属校验、再取 YAML —— 两次调用换来"按名字探测"不可绕过列表过滤，
     * 这是读取路径上的必要代价（yaml 端点本身不回带 claimRef，无法就地判断）。
     */
    public String yaml(String name, String tenantId, String clusterId) {
        assertClaimVisible(k8s.get(dto(name, tenantId, clusterId)), clusterId);
        return k8s.yaml(dto(name, tenantId, clusterId));
    }

    /**
     * 归属校验：不可见时抛 {@link EnumResponseType#RESOURCE_NOT_EXIST} 而非 403——
     * 与"PV 不存在"同一响应，不泄露对象存在性（否则可逐名枚举出他租户的 PV 名单）。
     */
    private void assertClaimVisible(PersistentVolumeDTO pv, String clusterId) {
        if (pv == null) {
            return; // 不存在：保持 k8s-server 的 null 语义，由前端按空处理
        }
        String tenantId = auth.hatTenantId();
        if (tenantId == null) {
            return; // 平台管理员
        }
        String claimNs = pv.getClaimNamespace();
        if (claimNs == null || !ownedNamespaces(tenantId, clusterId).contains(claimNs)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "持久卷不存在: " + pv.getName());
        }
    }

    /**本租户在指定集群的已分配命名空间集合；clusterId 缺失时返回空集（fail-closed，避免跨集群放行） */
    private Set<String> ownedNamespaces(String tenantId, String clusterId) {
        if (!StringUtils.hasText(clusterId)) {
            return Set.of();
        }
        Set<String> owned = new HashSet<>();
        for (PlatformTenantNamespace a : allocationMapper.listByTenant(tenantId)) {
            if (clusterId.equals(a.getClusterId())) {
                owned.add(a.getNamespace());
            }
        }
        return owned;
    }

    /**查询 DTO：apiPath 内置于 DTO（集群级，无 namespace） */
    private PersistentVolumeDTO dto(String name, String tenantId, String clusterId) {
        PersistentVolumeDTO d = new PersistentVolumeDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        return d;
    }
}
