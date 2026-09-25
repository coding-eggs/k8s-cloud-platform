package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.NamespaceDTO;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.k8s.K8sResourceClient;
import com.coding.platformapi.models.NamespaceUpsertRequest;
import com.coding.platformapi.models.NamespaceView;
import org.junit.jupiter.api.Test;

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
    private final NamespaceService svc = new NamespaceService(
            k8s, clusterMapper, allocationMapper, tenantMapper);

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
    void update_rejects_foreign_namespace_not_managed_by_platform() {
        clusterExists();
        when(k8s.get(any(NamespaceDTO.class))).thenReturn(ns("foreign", false));
        NamespaceUpsertRequest req = new NamespaceUpsertRequest();
        req.setClusterId("c1");
        req.setName("foreign");
        req.setDescription("想改别人的");
        assertThatThrownBy(() -> svc.update(req))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("平台");
        verify(k8s, never()).update(any(NamespaceDTO.class));
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
}
