package com.coding.k8sserver.chart;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestInspectorTest {

    /** 测试用 scope 表：Deployment 命名空间级，ClusterRole/CRD 集群级，未知 kind 一律 UNKNOWN */
    private static final ScopeResolver SCOPES = (apiVersion, kind) -> switch (kind) {
        case "Deployment", "Service", "ConfigMap" -> Scope.NAMESPACED;
        case "ClusterRole", "CustomResourceDefinition", "Namespace" -> Scope.CLUSTER;
        default -> Scope.UNKNOWN;
    };

    private static List<Violation> inspect(String manifest) {
        return ManifestInspector.inspect(manifest, "demo", true, SCOPES);
    }

    @Test
    void ignoresSourceCommentsAndEmptyDocuments() {
        String manifest = """
                ---
                # Source: demo/templates/deploy.yaml
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: web
                ---
                ---
                """;

        assertThat(inspect(manifest)).isEmpty();
    }

    @Test
    void rejectsExplicitNamespaceThatIsNotTheTarget() {
        String manifest = """
                apiVersion: apps/v1
                kind: Service
                metadata:
                  name: svc
                  namespace: other
                """;

        assertThat(inspect(manifest))
                .extracting(Violation::type)
                .containsExactly("CROSS_NAMESPACE");
    }

    @Test
    void crossNamespaceIsRejectedEvenWhenNotStrict() {
        // 管理员也不允许：跨 ns 会让"卸载是否删干净"不可预期
        String manifest = """
                apiVersion: apps/v1
                kind: Service
                metadata:
                  name: svc
                  namespace: other
                """;

        assertThat(ManifestInspector.inspect(manifest, "demo", false, SCOPES))
                .anyMatch(v -> v.type().equals("CROSS_NAMESPACE") && v.blocking());
    }

    @Test
    void clusterScopedBlocksTenantButOnlyWarnsAdmin() {
        String manifest = """
                apiVersion: rbac.authorization.k8s.io/v1
                kind: ClusterRole
                metadata:
                  name: reader
                """;

        assertThat(ManifestInspector.inspect(manifest, "demo", true, SCOPES))
                .anyMatch(v -> v.type().equals("CLUSTER_SCOPED") && v.blocking());
        assertThat(ManifestInspector.inspect(manifest, "demo", false, SCOPES))
                .anyMatch(v -> v.type().equals("CLUSTER_SCOPED") && !v.blocking());
    }

    @Test
    void unknownKindIsFailClosedForTenant() {
        String manifest = """
                apiVersion: demo.example.com/v1
                kind: Widget
                metadata:
                  name: w
                """;

        assertThat(ManifestInspector.inspect(manifest, "demo", true, SCOPES))
                .anyMatch(v -> v.type().equals("UNKNOWN_KIND") && v.blocking());
    }

    @Test
    void namespaceObjectIsAlwaysRejected() {
        String manifest = """
                apiVersion: v1
                kind: Namespace
                metadata:
                  name: other
                """;

        assertThat(inspect(manifest))
                .anyMatch(v -> v.type().equals("NAMESPACE_OBJECT") && v.blocking());
    }

    @Test
    void hookObjectsBlockTenantButOnlyWarnAdmin() {
        String manifest = """
                apiVersion: batch/v1
                kind: Job
                metadata:
                  name: migrate
                  annotations:
                    "helm.sh/hook": post-install
                """;

        assertThat(ManifestInspector.inspect(manifest, "demo", true, SCOPES))
                .anyMatch(v -> v.type().equals("HOOK") && v.blocking());
        assertThat(ManifestInspector.inspect(manifest, "demo", false, SCOPES))
                .anyMatch(v -> v.type().equals("HOOK") && !v.blocking());
    }

    @Test
    void duplicateObjectsAreRejected() {
        String doc = """
                apiVersion: apps/v1
                kind: Service
                metadata:
                  name: svc
                """;

        assertThat(inspect(doc + "\n---\n" + doc))
                .anyMatch(v -> v.type().equals("DUPLICATE_OBJECT") && v.blocking());
    }

    @Test
    void objectWithoutNameIsRejected() {
        String manifest = """
                apiVersion: apps/v1
                kind: Service
                metadata: {}
                """;

        assertThat(inspect(manifest))
                .anyMatch(v -> v.type().equals("INVALID_OBJECT") && v.blocking());
    }
}
