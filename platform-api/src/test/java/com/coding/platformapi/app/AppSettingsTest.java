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
                "mesh", Map.of("mode", "L7", "waypoint", " wp "))))
                .isEqualTo(Map.of("tracing", true, "sampling", 30,
                        "mesh", Map.of("mode", "l7", "waypoint", "wp")));
        // L4 / none 不带 waypoint（传了也丢掉）
        assertThat(AppSettings.normalize(Map.of("mesh", Map.of("mode", "l4", "waypoint", "wp"))))
                .isEqualTo(Map.of("mesh", Map.of("mode", "l4")));
    }

    @Test
    void normalizeRejectsBadInput() {
        assertThatThrownBy(() -> AppSettings.normalize(Map.of("foo", 1))).hasMessageContaining("foo");
        assertThatThrownBy(() -> AppSettings.normalize(Map.of("tracing", true, "sampling", 0)))
                .hasMessageContaining("采样率");
        assertThatThrownBy(() -> AppSettings.normalize(Map.of("mesh", Map.of("mode", "sidecar"))))
                .hasMessageContaining("sidecar");
        assertThatThrownBy(() -> AppSettings.normalize(Map.of("mesh", Map.of("mode", "l7"))))
                .hasMessageContaining("L7 需要选择 waypoint");
        assertThatThrownBy(() -> AppSettings.normalize(Map.of("mesh", Map.of("mode", "l7", "waypoint", "Bad_Name"))))
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
    void effectiveTranslatesMeshModes() {
        Map<String, Object> worker = svc();
        worker.remove("port");
        Map<String, Object> l7 = Map.of("mesh", Map.of("mode", "l7", "waypoint", "wp"));
        assertThat(AppSettings.effective(svc(), l7).get("mesh"))
                .isEqualTo(Map.of("dataplaneMode", "ambient", "useWaypoint", "wp"));
        // 后台进程没有 Service：L7 也只有 L4
        assertThat(AppSettings.effective(worker, l7).get("mesh")).isEqualTo(Map.of("dataplaneMode", "ambient"));
        // L4：显式不走 waypoint（盖过命名空间级的 use-waypoint）
        assertThat(AppSettings.effective(svc(), Map.of("mesh", Map.of("mode", "l4"))).get("mesh"))
                .isEqualTo(Map.of("dataplaneMode", "ambient", "useWaypoint", "none"));
        assertThat(AppSettings.effective(svc(), Map.of("mesh", Map.of("mode", "none"))).get("mesh"))
                .isEqualTo(Map.of("dataplaneMode", "none"));
        assertThat(AppSettings.effective(svc(), Map.of())).doesNotContainKey("mesh");
    }

    @Test
    void requiredAndNewlyEnabled() {
        assertThat(AppSettings.required(Map.of("tracing", true))).containsExactly("otel-collection");
        assertThat(AppSettings.required(Map.of("mesh", Map.of("mode", "none")))).isEmpty();
        assertThat(AppSettings.required(Map.of("mesh", Map.of("mode", "l4")))).containsExactly("ambient-mesh");
        assertThat(AppSettings.newlyEnabled(Map.of("mesh", Map.of("mode", "l7", "waypoint", "a")),
                Map.of("mesh", Map.of("mode", "l7", "waypoint", "b")))).containsExactly("ambient-mesh");
        assertThat(AppSettings.newlyEnabled(Map.of("mesh", Map.of("mode", "l4")),
                Map.of("mesh", Map.of("mode", "l7", "waypoint", "b")))).isEmpty();
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
