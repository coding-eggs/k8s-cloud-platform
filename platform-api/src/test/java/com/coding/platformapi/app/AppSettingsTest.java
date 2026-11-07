package com.coding.platformapi.app;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppSettingsTest {

    private static Map<String, Object> svc(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("imageRepo", "nginx");
        m.put("port", 80);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void normalizeKeepsOnlyEffectiveValues() {
        assertThat(AppSettings.normalize(null)).isEmpty();
        assertThat(AppSettings.normalize(Map.of("tracing", false, "sampling", 20, "mesh", Map.of()))).isEmpty();
        assertThat(AppSettings.normalize(Map.of("tracing", true, "sampling", "30",
                "mesh", Map.of("dataplaneMode", "AMBIENT", "useWaypoint", " wp "))))
                .isEqualTo(Map.of("tracing", true, "sampling", 30,
                        "mesh", Map.of("dataplaneMode", "ambient", "useWaypoint", "wp")));
    }

    @Test
    void normalizeRejectsBadInput() {
        assertThatThrownBy(() -> AppSettings.normalize(Map.of("foo", 1))).hasMessageContaining("foo");
        assertThatThrownBy(() -> AppSettings.normalize(Map.of("tracing", true, "sampling", 0)))
                .hasMessageContaining("采样率");
        assertThatThrownBy(() -> AppSettings.normalize(Map.of("mesh", Map.of("dataplaneMode", "sidecar"))))
                .hasMessageContaining("sidecar");
        assertThatThrownBy(() -> AppSettings.normalize(Map.of("mesh", Map.of("useWaypoint", "Bad_Name"))))
                .hasMessageContaining("waypoint");
    }

    @Test
    @SuppressWarnings("unchecked")
    void effectiveTracesOnlyServicesWithLanguage() {
        Map<String, Object> settings = Map.of("tracing", true, "sampling", 10);
        Map<String, Object> java = AppSettings.effective(svc("otel", Map.of("language", "java", "logs", false)), settings);
        assertThat((Map<String, Object>) java.get("otel")).containsEntry("tracing", true).containsEntry("sampling", 10);
        assertThat(AppSettings.effective(svc(), settings)).doesNotContainKey("otel");
        // 应用没开：只有语言的服务什么都不注入
        assertThat(AppServiceFeatures.required(AppSettings.effective(
                svc("otel", Map.of("language", "java", "logs", false)), Map.of()))).isEmpty();
    }

    @Test
    void effectiveMeshDropsWaypointForWorkers() {
        Map<String, Object> settings = Map.of("mesh", Map.of("dataplaneMode", "ambient", "useWaypoint", "wp"));
        assertThat(AppSettings.effective(svc(), settings).get("mesh"))
                .isEqualTo(Map.of("dataplaneMode", "ambient", "useWaypoint", "wp"));
        Map<String, Object> worker = svc();
        worker.remove("port");
        assertThat(AppSettings.effective(worker, settings).get("mesh")).isEqualTo(Map.of("dataplaneMode", "ambient"));
        worker.put("x", 1);
        assertThat(AppSettings.effective(worker, Map.of("mesh", Map.of("useWaypoint", "wp")))).doesNotContainKey("mesh");
    }

    @Test
    void requiredAndNewlyEnabled() {
        assertThat(AppSettings.required(Map.of("tracing", true))).containsExactly("otel-collection");
        assertThat(AppSettings.required(Map.of("mesh", Map.of("dataplaneMode", "none", "useWaypoint", "none")))).isEmpty();
        assertThat(AppSettings.required(Map.of("mesh", Map.of("useWaypoint", "wp")))).containsExactly("ambient-mesh");
        assertThat(AppSettings.newlyEnabled(Map.of("mesh", Map.of("useWaypoint", "a")),
                Map.of("mesh", Map.of("useWaypoint", "b")))).containsExactly("ambient-mesh");
        assertThat(AppSettings.newlyEnabled(Map.of("tracing", true), Map.of("tracing", true, "sampling", 5))).isEmpty();
    }

    @Test
    void snapshotRoundTripsAppAndOmitsItWhenEmpty() {
        AppSnapshot empty = new AppSnapshot(new java.util.TreeMap<>(Map.of("a", svc())), List.of());
        assertThat(empty.toJson()).doesNotContain("\"app\"");
        AppSnapshot withApp = new AppSnapshot(new java.util.TreeMap<>(Map.of("a", svc())), List.of(),
                new LinkedHashMap<>(Map.of("tracing", true)));
        assertThat(AppSnapshot.parse(withApp.toJson()).app()).isEqualTo(Map.of("tracing", true));
        assertThat(AppSnapshot.parse(empty.toJson()).app()).isEmpty();
    }
}
