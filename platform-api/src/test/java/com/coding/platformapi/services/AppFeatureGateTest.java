package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.WaypointRefDTO;
import com.coding.data.models.system.PlatformApplication;
import com.coding.platformapi.app.AppSnapshot;
import com.coding.platformapi.models.ComponentFlagDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppFeatureGateTest {

    private GatewayService gateways;
    private AppFeatureGate gate;
    private final Map<String, ComponentFlagDTO> flags = new LinkedHashMap<>();
    private PlatformApplication app;

    @BeforeEach
    void setUp() {
        ClusterComponentService components = mock(ClusterComponentService.class);
        gateways = mock(GatewayService.class);
        when(components.flags("c1")).thenReturn(flags);
        gate = new AppFeatureGate(new ComponentFeatureGuard(components), components, gateways);
        app = new PlatformApplication();
        app.setId("app1");
        app.setTenantId("t1");
        app.setClusterId("c1");
        app.setNamespace("demo");
    }

    private static Map<String, Object> d(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("imageRepo", "nginx");
        m.put("imageTag", "1");
        m.put("port", 80);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static WaypointRefDTO wp(String name, String waypointFor) {
        WaypointRefDTO r = new WaypointRefDTO();
        r.setName(name);
        r.setWaypointFor(waypointFor);
        return r;
    }

    private static AppSnapshot snap(Map<String, Map<String, Object>> services) {
        return new AppSnapshot(new LinkedHashMap<>(services), List.of());
    }

    @Test
    void enableRejectedWhenFlagUnavailable() {
        flags.put("hpa-resource-metrics", new ComponentFlagDTO(false, "metrics-server 未安装", List.of("metrics-server")));
        assertThatThrownBy(() -> gate.assertCanEnable("c1", d(), d("autoscalingEnabled", true)))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("metrics-server 未安装");
    }

    @Test
    void enableRejectedWhenFlagMissing() {
        assertThatThrownBy(() -> gate.assertCanEnable("c1", null, d("mesh", Map.of("dataplaneMode", "ambient"))))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void enableAllowedWhenFlagAvailable() {
        flags.put("hpa-resource-metrics", new ComponentFlagDTO(true, null, List.of()));
        gate.assertCanEnable("c1", d(), d("autoscalingEnabled", true));
    }

    @Test
    void disableAlwaysAllowed() {
        gate.assertCanEnable("c1", d("autoscalingEnabled", true, "mesh", Map.of("dataplaneMode", "ambient")),
                d("mesh", Map.of("dataplaneMode", "none")));
    }

    @Test
    void waypointMustHandleService() {
        when(gateways.waypointRefs("t1", "c1", "demo")).thenReturn(List.of(wp("wl", "workload"), wp("svc", "service")));
        assertThatThrownBy(() -> gate.assertWaypoint("t1", "c1", "demo", d("mesh", Map.of("useWaypoint", "wl"))))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("wl");
        assertThatThrownBy(() -> gate.assertWaypoint("t1", "c1", "demo", d("mesh", Map.of("useWaypoint", "nope"))))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("nope");
        gate.assertWaypoint("t1", "c1", "demo", d("mesh", Map.of("useWaypoint", "svc")));
        gate.assertWaypoint("t1", "c1", "demo", d("mesh", Map.of("useWaypoint", "none")));
    }

    @Test
    void waypointLookupFailureLetsSaveThrough() {
        when(gateways.waypointRefs(any(), any(), any())).thenThrow(new IllegalStateException("cluster unreachable"));
        gate.assertWaypoint("t1", "c1", "demo", d("mesh", Map.of("useWaypoint", "svc")));
    }

    @Test
    void releaseWarnsForUnselectedLiveFeature() {
        Map<String, Object> api = d("autoscalingEnabled", true);
        AppFeatureGate.Report r = gate.release(app, snap(Map.of("api", api)),
                snap(Map.of("api", api, "web", d())), List.of("web"));
        assertThat(r.problems()).isEmpty();
        assertThat(r.warnings()).singleElement().asString().contains("api");
    }

    @Test
    void releaseBlocksSelectedNewlyEnabled() {
        AppFeatureGate.Report r = gate.release(app, snap(Map.of("api", d())),
                snap(Map.of("api", d("otel", Map.of("language", "java", "logs", true, "logMode", "app")))),
                List.of("api"));
        // 「日志框架」方式：探针 + 日志两个组件都缺，各一条
        assertThat(r.problems()).hasSize(2).allSatisfy(p -> assertThat(p).contains("api"));
    }

    @Test
    void releaseAppTracingBlocksAllServicesInOneLine() {
        Map<String, Object> java = d("otel", Map.of("language", "java", "logs", false));
        Map<String, Object> tracing = Map.of("tracing", true);
        AppFeatureGate.Report r = gate.release(app, snap(Map.of("a", java, "b", java, "c", d())),
                new AppSnapshot(new LinkedHashMap<>(Map.of("a", java, "b", java, "c", d())), List.of(),
                        new LinkedHashMap<>(tracing)),
                List.of("@app"));
        // c 没选语言：不追踪，不需要组件；a、b 合并成一条
        assertThat(r.problems()).singleElement().asString().contains("a、b").doesNotContain("c");
    }

    @Test
    void releaseAppTracingUnpickedOnlyWarns() {
        Map<String, Object> java = d("otel", Map.of("language", "java", "logs", false));
        AppSnapshot both = new AppSnapshot(new LinkedHashMap<>(Map.of("a", java)), List.of(),
                new LinkedHashMap<>(Map.of("tracing", true)));
        AppFeatureGate.Report r = gate.release(app, both, both, List.of("a"));
        assertThat(r.problems()).isEmpty();
        assertThat(r.warnings()).singleElement().asString().contains("a");
    }

    @Test
    void releaseWaypointMissingBlocksWhenAppPickedWarnsOtherwise() {
        when(gateways.waypointRefs("t1", "c1", "demo")).thenReturn(List.of());
        flags.put("ambient-mesh", new ComponentFlagDTO(true, null, List.of()));
        AppSnapshot withWp = new AppSnapshot(new LinkedHashMap<>(Map.of("a", d())), List.of(),
                new LinkedHashMap<>(Map.of("mesh", Map.of("useWaypoint", "gone"))));
        AppFeatureGate.Report picked = gate.release(app, snap(Map.of("a", d())), withWp, List.of("@app"));
        assertThat(picked.problems()).singleElement().asString().contains("应用设置").contains("gone");
        AppFeatureGate.Report unpicked = gate.release(app, withWp, withWp, List.of("a"));
        assertThat(unpicked.problems()).isEmpty();
        assertThat(unpicked.warnings()).singleElement().asString().contains("gone");
    }

    @Test
    void settingsSaveGatesOnlyOffToOn() {
        assertThatThrownBy(() -> gate.assertSettings(app, Map.of(), Map.of("tracing", true)))
                .isInstanceOf(CloudPlatformException.class);
        // 已开着的继续保存不拦
        gate.assertSettings(app, Map.of("tracing", true), Map.of("tracing", true, "sampling", 10));
        // none / 跟随 不需要组件
        gate.assertSettings(app, Map.of(), Map.of("mesh", Map.of("dataplaneMode", "none")));
    }
}
