package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.mapper.k8s.PlatformRbacTemplateMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.data.models.k8s.PlatformRbacTemplate;
import com.coding.platformapi.k8s.K8sLifecycleClient;
import com.coding.platformapi.models.AllocationCreateRequest;
import com.coding.platformapi.security.AuthContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 一个命名空间只能归属一个租户（spec 2026-10-31 §3.2）。
 *
 * <p>库里那条唯一键（`V2026_10_31_3`）是兜底，但它要手工重放才生效 —— 代码里必须先判，
 * 否则用户看到的是 SQL 的 Duplicate entry，既看不懂也定位不到。文案要**指名道姓**说清归谁了。
 */
class NamespaceAllocationServiceOneTenantTest {

    private final PlatformTenantMapper tenantMapper = mock(PlatformTenantMapper.class);
    private final PlatformTenantNamespaceMapper allocationMapper = mock(PlatformTenantNamespaceMapper.class);
    private final K8sClusterMapper clusterMapper = mock(K8sClusterMapper.class);
    private final PlatformRbacTemplateMapper templateMapper = mock(PlatformRbacTemplateMapper.class);
    private final K8sProvisioningService provisioning = mock(K8sProvisioningService.class);
    private final K8sLifecycleClient adminClient = mock(K8sLifecycleClient.class);
    private final NamespaceService namespaceService = mock(NamespaceService.class);
    private final AuthContext authContext = mock(AuthContext.class);
    private final RegistryPullSecretService pullSecrets = mock(RegistryPullSecretService.class);

    private final NamespaceAllocationService svc = new NamespaceAllocationService(
            tenantMapper, allocationMapper, clusterMapper, templateMapper,
            provisioning, adminClient, namespaceService, authContext, pullSecrets);

    private PlatformTenant tenant(String id, String name) {
        PlatformTenant t = new PlatformTenant();
        t.setId(id);
        t.setName(name);
        t.setStatus((byte) 1);
        return t;
    }

    private K8sCluster cluster() {
        K8sCluster c = new K8sCluster();
        c.setClusterId("c1");
        c.setEnabled(1);
        return c;
    }

    private PlatformRbacTemplate builtIn() {
        PlatformRbacTemplate t = new PlatformRbacTemplate();
        t.setId("tpl");
        t.setName("ns-admin");
        t.setBuiltIn(1);
        return t;
    }

    private AllocationCreateRequest req(String tenantId, String namespace) {
        AllocationCreateRequest r = new AllocationCreateRequest();
        r.setTenantId(tenantId);
        r.setClusterId("c1");
        r.setNamespace(namespace);
        return r;
    }

    @Test
    void aNamespaceAlreadyBelongingToAnotherTenantIsRefusedByName() {
        when(tenantMapper.selectByPrimaryKey("t2")).thenReturn(tenant("t2", "ops"));
        when(clusterMapper.selectByPrimaryKey("c1")).thenReturn(cluster());
        when(templateMapper.listAll()).thenReturn(List.of(builtIn()));

        PlatformTenantNamespace holder = new PlatformTenantNamespace();
        holder.setTenantId("t1");
        holder.setClusterId("c1");
        holder.setNamespace("ns-a");
        when(allocationMapper.selectByClusterAndNs("c1", "ns-a")).thenReturn(List.of(holder));
        when(tenantMapper.selectByPrimaryKey("t1")).thenReturn(tenant("t1", "dev"));

        assertThatThrownBy(() -> svc.create(req("t2", "ns-a")))
                .as("文案要说清是哪个租户占着，管理员才知道去取消哪一条")
                .hasMessageContaining("dev");
        verify(allocationMapper, never()).insert(any());
    }
}
