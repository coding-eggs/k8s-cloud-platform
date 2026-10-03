package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.LimitRangeDTO;
import com.coding.common.models.k8s.dto.LimitRangeItemDTO;
import com.coding.common.models.k8s.dto.NamespaceDTO;
import com.coding.common.models.k8s.dto.ResourcePairDTO;
import com.coding.common.models.k8s.dto.ResourceQuotaDTO;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.configs.NamespaceProtectionProperties;
import com.coding.platformapi.k8s.K8sResourceClient;
import com.coding.platformapi.models.NamespaceLimitRangeUpsertRequest;
import com.coding.platformapi.models.NamespaceQuotaUpsertRequest;
import com.coding.platformapi.models.NamespaceUpsertRequest;
import com.coding.platformapi.models.NamespaceView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NamespaceServiceTest {

    private final K8sResourceClient k8s = mock(K8sResourceClient.class);
    private final K8sClusterMapper clusterMapper = mock(K8sClusterMapper.class);
    private final PlatformTenantNamespaceMapper allocationMapper = mock(PlatformTenantNamespaceMapper.class);
    private final PlatformTenantMapper tenantMapper = mock(PlatformTenantMapper.class);
    private final NamespaceProtectionProperties protection = new NamespaceProtectionProperties();
    private final NamespaceService svc = new NamespaceService(
            k8s, clusterMapper, allocationMapper, tenantMapper, protection);

    private void clusterExists() {
        K8sCluster c = new K8sCluster();
        c.setClusterId("c1");
        when(clusterMapper.selectByPrimaryKey("c1")).thenReturn(c);
    }

    private NamespaceDTO ns(String name, boolean managed) {
        NamespaceDTO d = new NamespaceDTO();
        d.setName(name);
        d.setPhase("Active");
        d.setLabels(managed ? Map.of("app.kubernetes.io/managed-by", "k8s-cloud-platform") : Map.of("team", "sre"));
        return d;
    }

    @Test
    void create_validates_name_and_rejects_existing() {
        clusterExists();
        when(k8s.get(any(NamespaceDTO.class))).thenReturn(ns("app", true));
        NamespaceUpsertRequest req = new NamespaceUpsertRequest();
        req.setClusterId("c1");
        req.setName("app");
        assertThatThrownBy(() -> svc.create(req)).isInstanceOf(CloudPlatformException.class);
        verify(k8s, never()).create(any(NamespaceDTO.class));
    }

    @Test
    void create_rejects_illegal_name_before_touching_k8s() {
        clusterExists();
        NamespaceUpsertRequest req = new NamespaceUpsertRequest();
        req.setClusterId("c1");
        req.setName("Bad_Name");
        assertThatThrownBy(() -> svc.create(req)).isInstanceOf(CloudPlatformException.class);
        verify(k8s, never()).get(any(NamespaceDTO.class));
    }

    @Test
    void update_allows_foreign_namespace_not_in_protected_list() {
        clusterExists();
        when(k8s.get(any(NamespaceDTO.class))).thenReturn(ns("foreign", false));
        when(k8s.update(any(NamespaceDTO.class))).thenAnswer(i -> i.getArgument(0));
        NamespaceUpsertRequest req = new NamespaceUpsertRequest();
        req.setClusterId("c1");
        req.setName("foreign");
        req.setDescription("想改别人的");
        svc.update(req);   // 默认全纳管：非受保护 foreign ns 可编辑，不再拒绝
        verify(k8s).update(any(NamespaceDTO.class));
    }

    @Test
    void delete_rejects_allocated_namespace_existing_rule_regression_lock() {
        clusterExists();
        when(k8s.get(any(NamespaceDTO.class))).thenReturn(ns("app", true));
        PlatformTenantNamespace a = new PlatformTenantNamespace();
        a.setNamespace("app");
        when(allocationMapper.listByCluster("c1")).thenReturn(List.of(a));
        assertThatThrownBy(() -> svc.delete("c1", "app"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("取消分配");
        verify(k8s, never()).delete(any(NamespaceDTO.class));
    }

    @Test
    void delete_allows_foreign_namespace_not_in_protected_list() {
        clusterExists();
        when(k8s.get(any(NamespaceDTO.class))).thenReturn(ns("foreign", false));
        when(allocationMapper.listByCluster("c1")).thenReturn(List.of());   // 未分配
        svc.delete("c1", "foreign");   // 默认全纳管 + 未分配 → 可删，不再拒绝
        verify(k8s).delete(any(NamespaceDTO.class));
    }

    @Test
    void protected_namespace_blocks_all_writes_but_reads_stay_open() {
        protection.setProtectedNamespaces(List.of("kube-system"));
        clusterExists();
        when(k8s.get(any(NamespaceDTO.class))).thenReturn(ns("kube-system", false));

        // 读仍开放（详情可见）
        assertThat(svc.get("c1", "kube-system")).isNotNull();

        // 编辑拒
        NamespaceUpsertRequest up = new NamespaceUpsertRequest();
        up.setClusterId("c1");
        up.setName("kube-system");
        up.setDescription("x");
        assertThatThrownBy(() -> svc.update(up))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("受保护的系统命名空间");
        verify(k8s, never()).update(any(NamespaceDTO.class));

        // 删除拒
        assertThatThrownBy(() -> svc.delete("c1", "kube-system"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("受保护的系统命名空间");
        verify(k8s, never()).delete(any(NamespaceDTO.class));

        // 配额 / 限制范围拒
        NamespaceQuotaUpsertRequest qr = new NamespaceQuotaUpsertRequest();
        qr.setClusterId("c1");
        qr.setNamespace("kube-system");
        qr.setQuota(new ResourceQuotaDTO());
        assertThatThrownBy(() -> svc.quotaUpsert(qr))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("受保护的系统命名空间");

        NamespaceLimitRangeUpsertRequest lrr = new NamespaceLimitRangeUpsertRequest();
        lrr.setClusterId("c1");
        lrr.setNamespace("kube-system");
        lrr.setLimitRange(new LimitRangeDTO());
        assertThatThrownBy(() -> svc.limitRangeUpsert(lrr))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("受保护的系统命名空间");
    }

    @Test
    void list_merges_allocation_and_maps_description_labels_managed() {
        clusterExists();
        NamespaceDTO a = ns("a", true);
        a.setDescription("订单域");
        NamespaceDTO b = ns("b", false);
        // 乱序返回，验证 service 层排序（旧端点返回已排序，迁移须保平价）
        when(k8s.list(any(NamespaceDTO.class))).thenReturn(List.of(b, a));
        PlatformTenantNamespace alloc = new PlatformTenantNamespace();
        alloc.setNamespace("a");
        alloc.setTenantId("t1");
        when(allocationMapper.listByCluster("c1")).thenReturn(List.of(alloc));
        PlatformTenant t = new PlatformTenant();
        t.setId("t1");
        t.setName("租户一");
        when(tenantMapper.selectByPrimaryKey("t1")).thenReturn(t);

        List<NamespaceView> views = svc.list("c1");
        assertThat(views).extracting(NamespaceView::getName).containsExactly("a", "b");
        NamespaceView va = views.get(0);
        assertThat(va.isManagedBy()).isTrue();
        assertThat(va.getAllocatedTenantName()).isEqualTo("租户一");
        assertThat(va.getDescription()).isEqualTo("订单域");
        assertThat(views.get(1).isManagedBy()).isFalse();
        assertThat(views.get(1).getAllocatedTenantName()).isNull();
    }

    // ---------- Task 8：quota / limitrange ----------

    private void managedNamespaceExists() {
        when(k8s.get(any(NamespaceDTO.class))).thenReturn(ns("ns1", true));
    }

    @Test
    void quota_upsert_creates_with_forced_default_name_when_absent() {
        clusterExists();
        managedNamespaceExists();
        when(k8s.get(any(ResourceQuotaDTO.class))).thenReturn(null);
        when(k8s.create(any(ResourceQuotaDTO.class))).thenAnswer(i -> i.getArgument(0));
        NamespaceQuotaUpsertRequest req = new NamespaceQuotaUpsertRequest();
        req.setClusterId("c1");
        req.setNamespace("ns1");
        ResourceQuotaDTO q = new ResourceQuotaDTO();
        // 空白名 → 静默改写为 default（非空且非 default 的入参名由 quota_upsert_rejects_non_default_incoming_name 锁定为拒绝）
        q.setName("");
        q.setRequestsCpu(new BigDecimal("2"));
        req.setQuota(q);
        svc.quotaUpsert(req);
        ArgumentCaptor<ResourceQuotaDTO> cap = ArgumentCaptor.forClass(ResourceQuotaDTO.class);
        verify(k8s).create(cap.capture());
        assertThat(cap.getValue().getName()).isEqualTo("default");
        assertThat(cap.getValue().getNamespace()).isEqualTo("ns1");
        assertThat(cap.getValue().getClusterId()).isEqualTo("c1");
        assertThat(cap.getValue().getRequestsCpu()).isEqualByComparingTo("2");
    }

    @Test
    void quota_upsert_updates_when_present() {
        clusterExists();
        managedNamespaceExists();
        when(k8s.get(any(ResourceQuotaDTO.class))).thenReturn(new ResourceQuotaDTO());
        when(k8s.update(any(ResourceQuotaDTO.class))).thenAnswer(i -> i.getArgument(0));
        NamespaceQuotaUpsertRequest req = new NamespaceQuotaUpsertRequest();
        req.setClusterId("c1");
        req.setNamespace("ns1");
        req.setQuota(new ResourceQuotaDTO());
        svc.quotaUpsert(req);
        verify(k8s).update(any(ResourceQuotaDTO.class));
        verify(k8s, never()).create(any(ResourceQuotaDTO.class));
    }

    @Test
    void quota_upsert_rejects_non_default_incoming_name() {
        clusterExists();
        managedNamespaceExists();
        NamespaceQuotaUpsertRequest req = new NamespaceQuotaUpsertRequest();
        req.setClusterId("c1");
        req.setNamespace("ns1");
        ResourceQuotaDTO q = new ResourceQuotaDTO();
        q.setName("other");
        req.setQuota(q);
        assertThatThrownBy(() -> svc.quotaUpsert(req))
                .isInstanceOf(CloudPlatformException.class)
                .hasFieldOrPropertyWithValue("code", EnumResponseType.QUOTA_NAME_NOT_DEFAULT.getCode())
                .hasMessageContaining("default");
        verify(k8s, never()).create(any(ResourceQuotaDTO.class));
        verify(k8s, never()).update(any(ResourceQuotaDTO.class));
    }

    @Test
    void quota_get_null_when_absent_and_multiple_flag_when_extra_objects() {
        clusterExists();
        managedNamespaceExists();
        when(k8s.get(any(ResourceQuotaDTO.class))).thenReturn(null);
        assertThat(svc.quotaGet("c1", "ns1")).isNull();

        ResourceQuotaDTO one = new ResourceQuotaDTO();
        one.setName("default");
        when(k8s.get(any(ResourceQuotaDTO.class))).thenReturn(one);
        when(k8s.list(any(ResourceQuotaDTO.class))).thenReturn(List.of(one, new ResourceQuotaDTO(), new ResourceQuotaDTO()));
        ResourceQuotaDTO got = svc.quotaGet("c1", "ns1");
        assertThat(got).isNotNull();
        assertThat(got.getMultiple()).isTrue();
    }

    @Test
    void quota_get_single_object_not_flagged_multiple() {
        clusterExists();
        managedNamespaceExists();
        ResourceQuotaDTO one = new ResourceQuotaDTO();
        one.setName("default");
        when(k8s.get(any(ResourceQuotaDTO.class))).thenReturn(one);
        when(k8s.list(any(ResourceQuotaDTO.class))).thenReturn(List.of(one));
        ResourceQuotaDTO got = svc.quotaGet("c1", "ns1");
        assertThat(got).isNotNull();
        assertThat(got.getMultiple()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void quota_delete_is_noop_when_absent() {
        clusterExists();
        managedNamespaceExists();
        when(k8s.get(any(ResourceQuotaDTO.class))).thenReturn(null);
        svc.quotaDelete("c1", "ns1");
        verify(k8s, never()).delete(any(ResourceQuotaDTO.class));
    }

    @Test
    void quota_delete_removes_when_present() {
        clusterExists();
        managedNamespaceExists();
        when(k8s.get(any(ResourceQuotaDTO.class))).thenReturn(new ResourceQuotaDTO());
        svc.quotaDelete("c1", "ns1");
        verify(k8s).delete(any(ResourceQuotaDTO.class));
    }

    @Test
    void limitrange_upsert_rejects_unsupported_type() {
        clusterExists();
        managedNamespaceExists();
        when(k8s.get(any(LimitRangeDTO.class))).thenReturn(null);
        when(k8s.create(any(LimitRangeDTO.class))).thenAnswer(i -> i.getArgument(0));
        NamespaceLimitRangeUpsertRequest req = new NamespaceLimitRangeUpsertRequest();
        req.setClusterId("c1");
        req.setNamespace("ns1");
        LimitRangeDTO lr = new LimitRangeDTO();
        LimitRangeItemDTO it = new LimitRangeItemDTO();
        it.setType("ContainerFixed");     // 未建模类型不得经 API 写入
        lr.setLimits(List.of(it));
        req.setLimitRange(lr);
        assertThatThrownBy(() -> svc.limitRangeUpsert(req))
                .isInstanceOf(CloudPlatformException.class)
                .hasFieldOrPropertyWithValue("code", EnumResponseType.LIMIT_RANGE_TYPE_UNSUPPORTED.getCode())
                .hasMessageContaining("Container / PersistentVolumeClaim")
                .hasMessageContaining("ContainerFixed");
        verify(k8s, never()).create(any(LimitRangeDTO.class));
    }

    @Test
    void limitrange_upsert_rejects_max_less_than_min() {
        clusterExists();
        managedNamespaceExists();
        when(k8s.get(any(LimitRangeDTO.class))).thenReturn(null);
        when(k8s.create(any(LimitRangeDTO.class))).thenAnswer(i -> i.getArgument(0));
        NamespaceLimitRangeUpsertRequest req = new NamespaceLimitRangeUpsertRequest();
        req.setClusterId("c1");
        req.setNamespace("ns1");
        LimitRangeDTO lr = new LimitRangeDTO();
        LimitRangeItemDTO it = new LimitRangeItemDTO();
        it.setType("Container");
        ResourcePairDTO max = new ResourcePairDTO();
        max.setCpu(new BigDecimal("1"));
        ResourcePairDTO min = new ResourcePairDTO();
        min.setCpu(new BigDecimal("2"));      // min > max → 非法
        it.setMax(max);
        it.setMin(min);
        lr.setLimits(List.of(it));
        req.setLimitRange(lr);
        assertThatThrownBy(() -> svc.limitRangeUpsert(req))
                .isInstanceOf(CloudPlatformException.class)
                .hasFieldOrPropertyWithValue("code", EnumResponseType.LIMIT_RANGE_VALUE_INVALID.getCode())
                .hasMessageContaining("Container cpu max 不得小于 min");
        verify(k8s, never()).create(any(LimitRangeDTO.class));
    }

    @Test
    void limitrange_upsert_rejects_defaultRequest_greater_than_default() {
        clusterExists();
        managedNamespaceExists();
        when(k8s.get(any(LimitRangeDTO.class))).thenReturn(null);
        NamespaceLimitRangeUpsertRequest req = new NamespaceLimitRangeUpsertRequest();
        req.setClusterId("c1");
        req.setNamespace("ns1");
        LimitRangeDTO lr = new LimitRangeDTO();
        LimitRangeItemDTO it = new LimitRangeItemDTO();
        it.setType("Container");
        ResourcePairDTO def = new ResourcePairDTO();
        def.setMemory(new BigDecimal("100"));
        ResourcePairDTO defReq = new ResourcePairDTO();
        defReq.setMemory(new BigDecimal("200"));   // defaultRequest > default → 非法
        it.setDefaultValue(def);
        it.setDefaultRequest(defReq);
        lr.setLimits(List.of(it));
        req.setLimitRange(lr);
        assertThatThrownBy(() -> svc.limitRangeUpsert(req))
                .isInstanceOf(CloudPlatformException.class)
                .hasFieldOrPropertyWithValue("code", EnumResponseType.LIMIT_RANGE_VALUE_INVALID.getCode())
                .hasMessageContaining("Container memory defaultRequest 不得大于 default");
        verify(k8s, never()).create(any(LimitRangeDTO.class));
    }

    @Test
    void limitrange_upsert_forces_default_name_and_creates_when_absent() {
        clusterExists();
        managedNamespaceExists();
        when(k8s.get(any(LimitRangeDTO.class))).thenReturn(null);
        when(k8s.create(any(LimitRangeDTO.class))).thenAnswer(i -> i.getArgument(0));
        NamespaceLimitRangeUpsertRequest req = new NamespaceLimitRangeUpsertRequest();
        req.setClusterId("c1");
        req.setNamespace("ns1");
        LimitRangeDTO lr = new LimitRangeDTO();
        lr.setName("");   // 空白名 → 静默改写为 default
        LimitRangeItemDTO it = new LimitRangeItemDTO();
        it.setType("Container");
        lr.setLimits(List.of(it));
        req.setLimitRange(lr);
        svc.limitRangeUpsert(req);
        ArgumentCaptor<LimitRangeDTO> cap = ArgumentCaptor.forClass(LimitRangeDTO.class);
        verify(k8s).create(cap.capture());
        assertThat(cap.getValue().getName()).isEqualTo("default");
        assertThat(cap.getValue().getNamespace()).isEqualTo("ns1");
    }

    @Test
    void limitrange_get_null_when_absent_and_multiple_flag() {
        clusterExists();
        managedNamespaceExists();
        when(k8s.get(any(LimitRangeDTO.class))).thenReturn(null);
        assertThat(svc.limitRangeGet("c1", "ns1")).isNull();

        LimitRangeDTO one = new LimitRangeDTO();
        one.setName("default");
        when(k8s.get(any(LimitRangeDTO.class))).thenReturn(one);
        when(k8s.list(any(LimitRangeDTO.class))).thenReturn(List.of(one, new LimitRangeDTO()));
        LimitRangeDTO got = svc.limitRangeGet("c1", "ns1");
        assertThat(got).isNotNull();
        assertThat(got.getMultiple()).isTrue();
    }

    @Test
    void limitrange_delete_is_noop_when_absent() {
        clusterExists();
        managedNamespaceExists();
        when(k8s.get(any(LimitRangeDTO.class))).thenReturn(null);
        svc.limitRangeDelete("c1", "ns1");
        verify(k8s, never()).delete(any(LimitRangeDTO.class));
    }

    @Test
    void constraint_ops_allow_foreign_namespace_not_in_protected_list() {
        clusterExists();
        when(k8s.get(any(NamespaceDTO.class))).thenReturn(ns("foreign", false));
        // 非受保护 foreign ns：配额/限制范围不再因"非平台管理"被拒，走到取对象（未配置 → null）
        when(k8s.get(any(ResourceQuotaDTO.class))).thenReturn(null);
        assertThat(svc.quotaGet("c1", "foreign")).isNull();
        when(k8s.get(any(LimitRangeDTO.class))).thenReturn(null);
        assertThat(svc.limitRangeGet("c1", "foreign")).isNull();
    }

    @Test
    void constraint_ops_reject_missing_namespace() {
        clusterExists();
        when(k8s.get(any(NamespaceDTO.class))).thenReturn(null);   // ns 不存在
        NamespaceQuotaUpsertRequest req = new NamespaceQuotaUpsertRequest();
        req.setClusterId("c1");
        req.setNamespace("ghost");
        req.setQuota(new ResourceQuotaDTO());
        assertThatThrownBy(() -> svc.quotaUpsert(req))
                .isInstanceOf(CloudPlatformException.class)
                .hasFieldOrPropertyWithValue("code", EnumResponseType.RESOURCE_NOT_EXIST.getCode())
                .hasMessageContaining("命名空间不存在");
        verify(k8s, never()).create(any(ResourceQuotaDTO.class));
    }
}
