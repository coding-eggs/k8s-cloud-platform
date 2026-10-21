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

    @Test
    void unknownBuiltInKindIsFailClosed() {
        // 复审 I6：内置组里没列过的 kind 不能默认当成命名空间级（新版本会新增集群级内置 kind）
        ScopeResolver r = index(List.of()).forCluster("c1");

        assertThat(r.scopeOf("apps/v1", "Widget")).isEqualTo(Scope.UNKNOWN);
    }

    @Test
    void mutatingAdmissionPolicyIsClusterScoped() {
        ScopeResolver r = index(List.of()).forCluster("c1");

        assertThat(r.scopeOf("admissionregistration.k8s.io/v1", "MutatingAdmissionPolicy"))
                .isEqualTo(Scope.CLUSTER);
        assertThat(r.scopeOf("admissionregistration.k8s.io/v1", "MutatingAdmissionPolicyBinding"))
                .isEqualTo(Scope.CLUSTER);
    }

    @Test
    void commonNamespacedKindsStayNamespaced() {
        ScopeResolver r = index(List.of()).forCluster("c1");

        assertThat(r.scopeOf("v1", "Pod")).isEqualTo(Scope.NAMESPACED);
        assertThat(r.scopeOf("v1", "Service")).isEqualTo(Scope.NAMESPACED);
        assertThat(r.scopeOf("batch/v1", "CronJob")).isEqualTo(Scope.NAMESPACED);
        assertThat(r.scopeOf("networking.k8s.io/v1", "Ingress")).isEqualTo(Scope.NAMESPACED);
    }
}
