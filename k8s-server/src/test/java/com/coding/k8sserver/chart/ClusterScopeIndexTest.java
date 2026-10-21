package com.coding.k8sserver.chart;

import com.coding.k8sserver.chart.ClusterScopeIndex.CrdScope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterScopeIndexTest {

    private ClusterScopeIndex index(List<CrdScope> crds) {
        return new ClusterScopeIndex(clusterId -> crds, Duration.ofMinutes(5));
    }

    @Test
    void knowsBuiltInClusterScopedKinds() {
        ScopeResolver r = index(List.of()).forCluster("c1");

        assertThat(r.scopeOf("v1", "Namespace")).isEqualTo(Scope.CLUSTER);
        assertThat(r.scopeOf("v1", "Node")).isEqualTo(Scope.CLUSTER);
        assertThat(r.scopeOf("rbac.authorization.k8s.io/v1", "ClusterRole")).isEqualTo(Scope.CLUSTER);
        assertThat(r.scopeOf("storage.k8s.io/v1", "StorageClass")).isEqualTo(Scope.CLUSTER);
        assertThat(r.scopeOf("admissionregistration.k8s.io/v1", "MutatingWebhookConfiguration"))
                .isEqualTo(Scope.CLUSTER);
    }

    @Test
    void builtInNamespacedKindsAreNamespaced() {
        ScopeResolver r = index(List.of()).forCluster("c1");

        assertThat(r.scopeOf("apps/v1", "Deployment")).isEqualTo(Scope.NAMESPACED);
        assertThat(r.scopeOf("v1", "ConfigMap")).isEqualTo(Scope.NAMESPACED);
    }

    @Test
    void customResourcesUseCrdScope() {
        ScopeResolver r = index(List.of(
                new ClusterScopeIndex.CrdScope("monitoring.coreos.com", "Prometheus", true),
                new ClusterScopeIndex.CrdScope("cert-manager.io", "ClusterIssuer", false))).forCluster("c1");

        assertThat(r.scopeOf("monitoring.coreos.com/v1", "Prometheus")).isEqualTo(Scope.NAMESPACED);
        assertThat(r.scopeOf("cert-manager.io/v1", "ClusterIssuer")).isEqualTo(Scope.CLUSTER);
    }

    @Test
    void unknownCustomKindIsUnknown() {
        ScopeResolver r = index(List.of()).forCluster("c1");

        assertThat(r.scopeOf("demo.example.com/v1", "Widget")).isEqualTo(Scope.UNKNOWN);
    }
}
