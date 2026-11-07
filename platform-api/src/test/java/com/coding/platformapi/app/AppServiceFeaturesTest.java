package com.coding.platformapi.app;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppServiceFeaturesTest {

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

    @Test
    void requiredMapsEachSwitch() {
        assertThat(AppServiceFeatures.required(d("autoscalingEnabled", true))).containsExactly("hpa-resource-metrics");
        assertThat(AppServiceFeatures.required(d("metricsEnabled", true))).containsExactly("servicemonitor");
        Map<String, Object> worker = d("metricsEnabled", true);
        worker.remove("port");
        assertThat(AppServiceFeatures.required(worker)).isEmpty();
        assertThat(AppServiceFeatures.required(d("otel",
                Map.of("language", "java", "tracing", true, "logs", false, "logMode", "app"))))
                .containsExactly("otel-collection");
        assertThat(AppServiceFeatures.required(d("otel",
                Map.of("language", "java", "tracing", false, "logs", true, "logMode", "app"))))
                .containsExactlyInAnyOrder("otel-collection", "otel-logs");
        assertThat(AppServiceFeatures.required(d("otel",
                Map.of("tracing", false, "logs", true, "logMode", "file", "logFile", "/a/b.log"))))
                .containsExactly("otel-logs");
        assertThat(AppServiceFeatures.required(d("mesh", Map.of("dataplaneMode", "ambient"))))
                .containsExactly("ambient-mesh");
        assertThat(AppServiceFeatures.required(d("mesh", Map.of("useWaypoint", "wp")))).containsExactly("ambient-mesh");
    }

    @Test
    void noneAndFollowRequireNothing() {
        assertThat(AppServiceFeatures.required(d())).isEmpty();
        assertThat(AppServiceFeatures.required(d("mesh", Map.of("dataplaneMode", "none", "useWaypoint", "none"))))
                .isEmpty();
    }

    @Test
    void waypointRenameIsNewlyEnabled() {
        assertThat(AppServiceFeatures.newlyEnabled(d("mesh", Map.of("useWaypoint", "a")),
                d("mesh", Map.of("useWaypoint", "b")))).containsExactly("ambient-mesh");
        assertThat(AppServiceFeatures.newlyEnabled(d("mesh", Map.of("useWaypoint", "a")),
                d("mesh", Map.of("useWaypoint", "a")))).isEmpty();
    }

    @Test
    void disablingIsNotNewlyEnabled() {
        assertThat(AppServiceFeatures.newlyEnabled(d("autoscalingEnabled", true), d())).isEmpty();
        assertThat(AppServiceFeatures.newlyEnabled(null, d("autoscalingEnabled", true)))
                .containsExactly("hpa-resource-metrics");
        assertThat(AppServiceFeatures.newlyEnabled(d("autoscalingEnabled", true),
                d("autoscalingEnabled", true, "replicas", 3))).isEmpty();
    }
}
