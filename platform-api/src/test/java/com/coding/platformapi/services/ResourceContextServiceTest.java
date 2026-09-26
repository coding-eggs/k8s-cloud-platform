package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.admin.ResourceContextDTO;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.K8sCluster;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceContextServiceTest {

    private final PlatformTenantMapper tenantMapper = mock(PlatformTenantMapper.class);
    private final K8sClusterMapper clusterMapper = mock(K8sClusterMapper.class);
    private final PlatformTenantNamespaceMapper allocationMapper = mock(PlatformTenantNamespaceMapper.class);
    private final ResourceContextService svc =
            new ResourceContextService(tenantMapper, clusterMapper, allocationMapper);

    private PlatformTenant tenant(String id, String name) {
        PlatformTenant t = new PlatformTenant();
        t.setId(id);
        t.setName(name);
        t.setStatus((byte) 1);
        return t;
    }

    private K8sCluster cluster(String id) {
        K8sCluster c = new K8sCluster();
        c.setClusterId(id);
        c.setClusterName(id + "-name");
        c.setEnabled(1);
        return c;
    }

    private PlatformTenantNamespace alloc(String tenantId, String clusterId, String ns) {
        PlatformTenantNamespace a = new PlatformTenantNamespace();
        a.setTenantId(tenantId);
        a.setClusterId(clusterId);
        a.setNamespace(ns);
        return a;
    }

    private void givenTwoTenants() {
        when(tenantMapper.listAll()).thenReturn(List.of(tenant("t1", "alpha"), tenant("t2", "beta")));
        when(clusterMapper.listAll()).thenReturn(List.of(cluster("c1")));
        when(allocationMapper.listAll()).thenReturn(List.of(
                alloc("t1", "c1", "ns-a"), alloc("t2", "c1", "ns-b")));
    }

    @Test
    void base_token_returns_all_tenants() {
        givenTwoTenants();
        ResourceContextDTO dto = svc.build(null);
        assertThat(dto.getTenants()).extracting(ResourceContextDTO.TenantNode::getTenantId)
                .containsExactly("t1", "t2");
    }

    @Test
    void tenant_hat_only_returns_own_tenant() {
        givenTwoTenants();
        ResourceContextDTO dto = svc.build("t2");
        assertThat(dto.getTenants()).extracting(ResourceContextDTO.TenantNode::getTenantId)
                .containsExactly("t2");
        assertThat(dto.getTenants().get(0).getClusters().get(0).getNamespaces())
                .containsExactly("ns-b");
    }

    @Test
    void hat_for_tenant_without_allocations_yields_empty_tree() {
        givenTwoTenants();
        assertThat(svc.build("t-none").getTenants()).isEmpty();
    }
}
