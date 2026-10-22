package com.coding.k8sserver.components;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.OwnerReferenceDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedResourceGuardTest {

    private final ManagedResourceGuard guard = new ManagedResourceGuard(
            "platform-system,platform-middleware-system,monitor,istio-system,tekton-pipelines,cert-manager");

    private static OwnerReferenceDTO owner(String name) {
        OwnerReferenceDTO dto = new OwnerReferenceDTO();
        dto.setName(name);
        dto.setKind("StatefulSet");
        return dto;
    }

    @Test
    void platformManagedByLabelMarksManaged() {
        assertThat(guard.managedBy(Map.of("platform.coding/managed-by", "middleware"), Map.of(), null))
                .isEqualTo(ManagedResourceGuard.MIDDLEWARE);
        assertThat(guard.managedBy(Map.of("platform.coding/managed-by", "middleware-operator"), Map.of(), null))
                .isEqualTo(ManagedResourceGuard.MIDDLEWARE);
        assertThat(guard.managedBy(Map.of("platform.coding/managed-by", "component"), Map.of(), null))
                .isEqualTo(ManagedResourceGuard.COMPONENT);
    }

    @Test
    void platformManagedTrueLabelIsComponent() {
        // 组件的 Gateway/Secret 等对象用的是 managed=true + component=<code>（2026-10-22 复审 C1）
        assertThat(guard.managedBy(
                Map.of("platform.coding/managed", "true", "platform.coding/component", "istio"), Map.of(), null))
                .isEqualTo(ManagedResourceGuard.COMPONENT);
    }

    @Test
    void helmReleasePrefixesMarkManaged() {
        assertThat(guard.managedBy(Map.of(), Map.of("meta.helm.sh/release-name", "mw-nacos-abc12345"), null))
                .isEqualTo(ManagedResourceGuard.MIDDLEWARE);
        assertThat(guard.managedBy(Map.of(), Map.of("meta.helm.sh/release-name", "platform-thanos"), null))
                .isEqualTo(ManagedResourceGuard.COMPONENT);
    }

    @Test
    void helmReleaseInPlatformNamespaceIsComponent() {
        // Istio 的 release 名不带 platform- 前缀（istiod/ztunnel/...），靠 release-namespace 兜住
        assertThat(guard.managedBy(Map.of(),
                Map.of("meta.helm.sh/release-name", "istiod",
                        "meta.helm.sh/release-namespace", "istio-system"), null))
                .isEqualTo(ManagedResourceGuard.COMPONENT);
    }

    @Test
    void ownerReferencePrefixesMarkManaged() {
        // Nacos 的 PVC 没有任何标签/注解，只能靠属主 StatefulSet 的名字
        assertThat(guard.managedBy(Map.of(), Map.of(), List.of(owner("mw-nacos-abc12345"))))
                .isEqualTo(ManagedResourceGuard.MIDDLEWARE);
        assertThat(guard.managedBy(Map.of(), Map.of(), List.of(owner("platform-thanos-query-6d9f"))))
                .isEqualTo(ManagedResourceGuard.COMPONENT);
    }

    @Test
    void instanceLabelValuesWithPlatformPrefixMarkManaged() {
        assertThat(guard.managedBy(Map.of("app.kubernetes.io/instance", "mw-mysql-abc12345"), null, null))
                .isEqualTo(ManagedResourceGuard.MIDDLEWARE);
        assertThat(guard.managedBy(Map.of("app", "mw-redis-abc12345"), null, null))
                .isEqualTo(ManagedResourceGuard.MIDDLEWARE);
    }

    @Test
    void genericUserLabelsAreNotManaged() {
        // `app: redis` 是 Helm 模板的通用标签；不做 CR 回查就不会误判
        assertThat(guard.managedBy(Map.of("app", "redis"), Map.of(), null)).isNull();
        assertThat(guard.managedBy(Map.of("app.kubernetes.io/instance", "my-app"), Map.of(), null)).isNull();
        assertThat(guard.managedBy(Map.of(),
                Map.of("meta.helm.sh/release-name", "my-app",
                        "meta.helm.sh/release-namespace", "demo"), null)).isNull();
    }

    @Test
    void assertMutableRejectsManagedResources() {
        assertThatThrownBy(() -> guard.assertMutable(
                Map.of("platform.coding/managed", "true", "platform.coding/component", "istio"), Map.of(), null))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("平台组件或中间件实例");
    }

    @Test
    void assertMutableAllowsUserResources() {
        guard.assertMutable(Map.of("app", "web"), Map.of(), null);
    }
}
