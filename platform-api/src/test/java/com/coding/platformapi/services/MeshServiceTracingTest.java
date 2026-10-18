package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.OtelTelemetryStateDTO;
import com.coding.platformapi.k8s.K8sComponentClient;
import com.coding.platformapi.k8s.K8sMeshClient;
import com.coding.platformapi.models.WaypointTracingRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** waypoint 链路追踪（批次二）：命名、采样率来源、参数校验。 */
class MeshServiceTracingTest {

    private final K8sMeshClient k8s = mock(K8sMeshClient.class);
    private final ClusterComponentService componentService = mock(ClusterComponentService.class);
    private final ClusterCapabilityService capabilityService = mock(ClusterCapabilityService.class);
    private final ComponentInstallService installService = mock(ComponentInstallService.class);
    private final K8sComponentClient componentClient = mock(K8sComponentClient.class);
    private final MeshService svc = new MeshService(k8s, componentService, capabilityService,
            installService, componentClient);

    @Test
    void settingWaypointTracingUsesTheOtelSamplingSnapshot() {
        when(installService.otelSamplingPercentage("c1")).thenReturn(25);
        when(componentClient.applyOtelTelemetry("c1", "team-a", "platform-tracing-waypoint",
                "waypoint", 25, true)).thenReturn(true);

        assertThat(svc.setWaypointTracing(req("waypoint", true))).isTrue();

        verify(componentClient).applyOtelTelemetry("c1", "team-a", "platform-tracing-waypoint",
                "waypoint", 25, true);
    }

    @Test
    void queryingReadsTheTelemetryState() {
        when(componentClient.otelTelemetryState("c1", "team-a", "platform-tracing-waypoint"))
                .thenReturn(new OtelTelemetryStateDTO(true, 25));

        assertThat(svc.waypointTracing(req("waypoint", false))).isTrue();
    }

    @Test
    void aBlankGatewayNameIsRejected() {
        assertThatThrownBy(() -> svc.setWaypointTracing(req(" ", true)))
                .hasMessageContaining("gatewayName");
    }

    private static WaypointTracingRequest req(String gatewayName, boolean enabled) {
        WaypointTracingRequest r = new WaypointTracingRequest();
        r.setTenantId("t1");
        r.setClusterId("c1");
        r.setNamespace("team-a");
        r.setGatewayName(gatewayName);
        r.setEnabled(enabled);
        return r;
    }
}
