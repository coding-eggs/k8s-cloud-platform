package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PersistentVolumeDTO;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.security.AuthContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PersistentVolume 的租户收窄（V2026_10_07 批次的安全修复）。
 *
 * <p>背景：PV 是集群级对象，k8s-server 侧走 {@code AbstractClusterResourceController}（只校验集群已登记、
 * 无租户维度），而 {@code tenant:persistentvolume:*} 又确实绑在租户角色上 —— 两者相加曾是
 * 「任意租户成员可列出全集群 PV（含他租户 claimRef）」的越权面。收窄逻辑在服务层，故用单测钉住。
 */
class PersistentVolumeServiceTest {

    private final K8sClient k8s = mock(K8sClient.class);
    private final AuthContext auth = mock(AuthContext.class);
    private final PlatformTenantNamespaceMapper allocationMapper = mock(PlatformTenantNamespaceMapper.class);
    private final PersistentVolumeService svc = new PersistentVolumeService(k8s, auth, allocationMapper);

    private PersistentVolumeDTO pv(String name, String claimNamespace, String claimName) {
        PersistentVolumeDTO d = new PersistentVolumeDTO();
        d.setName(name);
        d.setClusterId("c1");
        d.setClaimNamespace(claimNamespace);
        d.setClaimName(claimName);
        return d;
    }

    private PersistentVolumeDTO query() {
        PersistentVolumeDTO q = new PersistentVolumeDTO();
        q.setClusterId("c1");
        return q;
    }

    private void allocationsOf(String tenantId, String... clusterNamespacePairs) {
        List<PlatformTenantNamespace> rows = new java.util.ArrayList<>();
        for (int i = 0; i < clusterNamespacePairs.length; i += 2) {
            PlatformTenantNamespace a = new PlatformTenantNamespace();
            a.setTenantId(tenantId);
            a.setClusterId(clusterNamespacePairs[i]);
            a.setNamespace(clusterNamespacePairs[i + 1]);
            rows.add(a);
        }
        when(allocationMapper.listByTenant(tenantId)).thenReturn(rows);
    }

    // ---------- list ----------

    @Test
    void list_platform_admin_sees_everything() {
        when(auth.hatTenantId()).thenReturn(null); // base token：平台管理员
        when(k8s.list(any(PersistentVolumeDTO.class))).thenReturn(List.of(
                pv("pv-a", "ns-a", "pvc-a"),
                pv("pv-b", "ns-b", "pvc-b"),
                pv("pv-free", null, null)));

        assertThat(svc.list(query())).extracting(PersistentVolumeDTO::getName)
                .containsExactly("pv-a", "pv-b", "pv-free");
        // 管理员路径不做分配表查询
        verify(allocationMapper, never()).listByTenant(anyString());
    }

    @Test
    void list_tenant_sees_only_pvs_bound_to_its_allocated_namespaces() {
        when(auth.hatTenantId()).thenReturn("t1");
        allocationsOf("t1", "c1", "ns-a", "c1", "ns-c", "c2", "ns-other-cluster");
        when(k8s.list(any(PersistentVolumeDTO.class))).thenReturn(List.of(
                pv("pv-a", "ns-a", "pvc-a"),
                pv("pv-c", "ns-c", "pvc-c"),
                pv("pv-b", "ns-b", "pvc-b"))); // ns-b 属他租户

        assertThat(svc.list(query())).extracting(PersistentVolumeDTO::getName)
                .containsExactly("pv-a", "pv-c");
    }

    @Test
    void list_tenant_excludes_unbound_pv() {
        // 未绑定的 PV 属集群存储池（平台资源），不应出现在租户视图里
        when(auth.hatTenantId()).thenReturn("t1");
        allocationsOf("t1", "c1", "ns-a");
        when(k8s.list(any(PersistentVolumeDTO.class))).thenReturn(List.of(pv("pv-free", null, null)));

        assertThat(svc.list(query())).isEmpty();
    }

    @Test
    void list_tenant_fails_closed_when_cluster_id_missing() {
        // clusterId 缺失时若不过滤，会把「所有集群的已分配 ns」都当成可见集合 → 跨集群放行
        when(auth.hatTenantId()).thenReturn("t1");
        allocationsOf("t1", "c1", "ns-a");
        when(k8s.list(any(PersistentVolumeDTO.class))).thenReturn(List.of(pv("pv-a", "ns-a", "pvc-a")));

        PersistentVolumeDTO q = new PersistentVolumeDTO(); // clusterId 为 null

        assertThat(svc.list(q)).isEmpty();
    }

    // ---------- get / yaml（按名寻址不能绕过列表过滤） ----------

    @Test
    void get_own_pv_succeeds() {
        when(auth.hatTenantId()).thenReturn("t1");
        allocationsOf("t1", "c1", "ns-a");
        when(k8s.get(any(PersistentVolumeDTO.class))).thenReturn(pv("pv-a", "ns-a", "pvc-a"));

        assertThat(svc.get("pv-a", "t1", "c1").getName()).isEqualTo("pv-a");
    }

    @Test
    void get_other_tenant_pv_reports_not_exist() {
        // 与「PV 不存在」同一错误码：否则可逐名探测出他租户的 PV 名单
        when(auth.hatTenantId()).thenReturn("t1");
        allocationsOf("t1", "c1", "ns-a");
        when(k8s.get(any(PersistentVolumeDTO.class))).thenReturn(pv("pv-b", "ns-b", "pvc-b"));

        assertThatThrownBy(() -> svc.get("pv-b", "t1", "c1"))
                .isInstanceOfSatisfying(CloudPlatformException.class, e ->
                        assertThat(e.getCode()).isEqualTo(EnumResponseType.RESOURCE_NOT_EXIST.getCode()));
    }

    @Test
    void get_missing_pv_stays_null() {
        // 不存在保持 k8s-server 的 null 语义（前端按空处理），不要改成抛错
        when(auth.hatTenantId()).thenReturn("t1");
        allocationsOf("t1", "c1", "ns-a");
        when(k8s.get(any(PersistentVolumeDTO.class))).thenReturn(null);

        assertThat(svc.get("nope", "t1", "c1")).isNull();
    }

    @Test
    void yaml_other_tenant_pv_throws_and_never_fetches_yaml() {
        when(auth.hatTenantId()).thenReturn("t1");
        allocationsOf("t1", "c1", "ns-a");
        when(k8s.get(any(PersistentVolumeDTO.class))).thenReturn(pv("pv-b", "ns-b", "pvc-b"));

        assertThatThrownBy(() -> svc.yaml("pv-b", "t1", "c1"))
                .isInstanceOf(CloudPlatformException.class);
        // 归属校验不通过就不该再去取 YAML —— 否则 YAML 变成绕过过滤的旁路
        verify(k8s, never()).yaml(any(PersistentVolumeDTO.class));
    }

    @Test
    void yaml_platform_admin_skips_ownership_check() {
        when(auth.hatTenantId()).thenReturn(null);
        when(k8s.get(any(PersistentVolumeDTO.class))).thenReturn(pv("pv-b", "ns-b", "pvc-b"));
        when(k8s.yaml(any(PersistentVolumeDTO.class))).thenReturn("kind: PersistentVolume");

        assertThat(svc.yaml("pv-b", null, "c1")).isEqualTo("kind: PersistentVolume");
    }
}
