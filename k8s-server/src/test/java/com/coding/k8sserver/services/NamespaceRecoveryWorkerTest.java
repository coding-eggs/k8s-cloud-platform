package com.coding.k8sserver.services;

import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.k8score.factory.KubernetesClientFactory;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class NamespaceRecoveryWorkerTest {
    final io.fabric8.kubernetes.client.dsl.Resource<Namespace> namespace = mock(io.fabric8.kubernetes.client.dsl.Resource.class);
    final KubernetesClient client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
    final KubernetesClientFactory clients = mock(KubernetesClientFactory.class);
    final PlatformTenantNamespaceMapper allocations = mock(PlatformTenantNamespaceMapper.class);
    final PlatformTenantMapper tenants = mock(PlatformTenantMapper.class);
    final NamespaceRecoveryWorker worker = new NamespaceRecoveryWorker(allocations, tenants, null, clients, null);

    PlatformTenantNamespace allocation(String state) {
        var row = new PlatformTenantNamespace();
        row.setId("a"); row.setTenantId("t"); row.setNamespace("demo"); row.setClusterId("c");
        row.setNamespaceUid("original"); row.setLifecycle(state); row.setExpiresAt(1L);
        var namespaceOperations = client.namespaces();
        doReturn(namespace).when(namespaceOperations).withName("demo");
        when(clients.getAdminClient("c")).thenReturn(client);
        var tenant = new PlatformTenant();
        tenant.setServiceAccount("tenant-a");
        when(tenants.selectByPrimaryKey("t")).thenReturn(tenant);
        return row;
    }
    Namespace live(String uid) {
        return new NamespaceBuilder().withNewMetadata().withUid(uid).withName("demo").withResourceVersion("18").endMetadata().build();
    }
    @Test void replacementIsNeverTouchedByAnOldTask() {
        for (String state : new String[]{"DELETING","STOPPING","RESTORING","ACTIVE"}) {
            var row = allocation(state);
            when(namespace.get()).thenReturn(live("replacement"));
            assertThatThrownBy(() -> worker.advance(row)).hasMessageContaining("UID");
        }
        verifyNoInteractions(allocations);
        verify(client, never()).pods();
        verify(client, never()).resourceQuotas();
    }
    @Test void expiredSpaceWaitsForAnAdministrator() {
        var row = allocation("ACTIVE");
        when(namespace.get()).thenReturn(live("original"));
        var roles = client.rbac().roleBindings();
        var bindings = mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        doReturn(bindings).when(roles).inNamespace("demo");
        doReturn(mock(io.fabric8.kubernetes.client.dsl.Resource.class)).when(bindings).withName(anyString());
        worker.advance(row);
        verify(allocations).transition("a","EXPIRED",null);
        verify(client, never()).pods();
        verify(client, never()).resourceQuotas();
    }
    @Test void expiredRenewalMustNotInitiatePodRecovery() {
        var row = allocation("RESTORING");
        when(namespace.get()).thenReturn(live("original"));
        worker.advance(row);
        verify(allocations).transition(eq("a"), eq("ACTIVE"), anyString());
        verify(client, never()).pods();
        verify(client, never()).resourceQuotas();
    }
    @Test void zeroingRequiresAnAdmissionDenialFromTheRecoveryQuota() {
        var pod = mock(io.fabric8.kubernetes.client.dsl.NamespaceableResource.class);
        doReturn(pod).when(client).resource(any(io.fabric8.kubernetes.api.model.Pod.class));
        doReturn(pod).when(pod).dryRun();
        assertThatThrownBy(() -> worker.verifyPodAdmission(client, "demo")).hasMessageContaining("仍允许");
        when(pod.create()).thenThrow(new io.fabric8.kubernetes.client.KubernetesClientException("unrelated rejection", 403, null));
        assertThatThrownBy(() -> worker.verifyPodAdmission(client, "demo")).hasMessageContaining("无法验证");
        doThrow(new io.fabric8.kubernetes.client.KubernetesClientException(
                "exceeded quota: platform-recovery, requested: count/pods=1",403,null)).when(pod).create();
        worker.verifyPodAdmission(client, "demo");
        verify(client, never()).pods();
    }

    @Test void namespaceMustDisappearBeforeReleasingTheAllocation() {
        var row = allocation("DELETING");
        var ns = live("original");
        ns.getMetadata().setDeletionTimestamp("2026-11-19T00:00:00Z");
        when(namespace.get()).thenReturn(ns,null);
        worker.advance(row);
        verify(allocations, never()).deleteByPrimaryKey(anyString());
        worker.advance(row);
        verify(allocations).retireApplications(row);
        verify(allocations).retireMiddleware(row);
        verify(allocations).deleteByPrimaryKey("a");
    }
}
