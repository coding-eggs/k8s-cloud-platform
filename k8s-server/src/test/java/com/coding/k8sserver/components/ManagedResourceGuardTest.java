package com.coding.k8sserver.components;

import com.coding.common.exception.CloudPlatformException;
import com.coding.k8sserver.middleware.MiddlewareCrLookup;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagedResourceGuardTest {

    private final MiddlewareCrLookup lookup = mock(MiddlewareCrLookup.class);
    private final ManagedResourceGuard guard = new ManagedResourceGuard(lookup);

    @Test
    void platformLabelMarksManaged() {
        assertThat(guard.managedBy("c1", "demo",
                Map.of("platform.coding/managed-by", "middleware"), Map.of()))
                .isEqualTo(ManagedResourceGuard.MIDDLEWARE);
        assertThat(guard.managedBy("c1", "demo",
                Map.of("platform.coding/managed-by", "component"), Map.of()))
                .isEqualTo(ManagedResourceGuard.COMPONENT);
    }

    @Test
    void helmReleasePrefixMarksManaged() {
        assertThat(guard.managedBy("c1", "demo", Map.of(),
                Map.of("meta.helm.sh/release-name", "platform-thanos")))
                .isEqualTo(ManagedResourceGuard.COMPONENT);
        assertThat(guard.managedBy("c1", "demo", Map.of(),
                Map.of("meta.helm.sh/release-name", "mw-nacos-abc12345")))
                .isEqualTo(ManagedResourceGuard.MIDDLEWARE);
    }

    @Test
    void operatorChildResourcesAreFoundViaCrLookup() {
        when(lookup.ownedByMiddleware(anyString(), anyString(), any())).thenReturn(true);

        assertThat(guard.managedBy("c1", "demo",
                Map.of("app.kubernetes.io/instance", "mw-mysql-abc"), Map.of()))
                .isEqualTo(ManagedResourceGuard.MIDDLEWARE);
    }

    @Test
    void userResourcesAreNotManaged() {
        when(lookup.ownedByMiddleware(anyString(), anyString(), any())).thenReturn(false);

        assertThat(guard.managedBy("c1", "demo",
                Map.of("app", "web"), Map.of("meta.helm.sh/release-name", "my-app"))).isNull();
    }

    @Test
    void assertMutableRejectsManagedResources() {
        assertThatThrownBy(() -> guard.assertMutable("c1", "demo",
                Map.of("platform.coding/managed-by", "component"), Map.of()))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("平台组件或中间件实例");
    }

    @Test
    void assertMutableAllowsUserResources() {
        when(lookup.ownedByMiddleware(anyString(), anyString(), any())).thenReturn(false);
        guard.assertMutable("c1", "demo", Map.of("app", "web"), Map.of());
    }
}
