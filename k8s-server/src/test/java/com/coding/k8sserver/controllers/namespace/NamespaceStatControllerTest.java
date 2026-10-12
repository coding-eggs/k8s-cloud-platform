package com.coding.k8sserver.controllers.namespace;

import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.core.NamespaceStatOperations;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.components.ResourceAccessResolver.AccessContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 边界与身份解析是本端点的全部内容：租户 token 用 token 租户、admin 代操作要显式 tenantId —— 都交给 resolveNamespacedAccess。 */
class NamespaceStatControllerTest {

    private final KubernetesOperationsFactory factory = mock(KubernetesOperationsFactory.class);
    private final ResourceAccessResolver resolver = mock(ResourceAccessResolver.class);
    private final NamespaceStatOperations ops = mock(NamespaceStatOperations.class);

    @Test
    void resolves_namespaced_access_then_samples_that_namespace() {
        when(resolver.resolveNamespacedAccess(null, "c1", "team-a")).thenReturn(new AccessContext("t1", false));
        when(factory.getNamespaceStatOperation("c1", "t1", false)).thenReturn(ops);
        when(ops.row("team-a")).thenReturn(NamespaceStatOperations.emptySnapshot("team-a", 1L));

        var controller = new NamespaceStatController(factory, resolver);
        var resp = controller.stat("team-a", null, "c1");

        assertThat(resp.getData().getStat().getNamespace()).isEqualTo("team-a");
        verify(resolver).resolveNamespacedAccess(null, "c1", "team-a");
        verify(factory).getNamespaceStatOperation("c1", "t1", false);
    }

    @Test
    void admin_delegation_uses_explicit_tenant_and_admin_client() {
        when(resolver.resolveNamespacedAccess("t9", "c1", "team-a")).thenReturn(new AccessContext("t9", true));
        when(factory.getNamespaceStatOperation("c1", "t9", true)).thenReturn(ops);
        when(ops.row("team-a")).thenReturn(NamespaceStatOperations.emptySnapshot("team-a", 1L));

        new NamespaceStatController(factory, resolver).stat("team-a", "t9", "c1");

        verify(factory).getNamespaceStatOperation("c1", "t9", true);
    }
}
