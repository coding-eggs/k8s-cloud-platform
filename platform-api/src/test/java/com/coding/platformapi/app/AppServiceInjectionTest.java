package com.coding.platformapi.app;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppServiceInjectionTest {

    private static Map<String, Object> desired(Object... kv) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("imageRepo", "nginx");
        d.put("imageTag", "1");
        d.put("port", 80);
        for (int i = 0; i < kv.length; i += 2) {
            d.put((String) kv[i], kv[i + 1]);
        }
        return d;
    }

    @Test
    void emptyWhenNoBlocks() {
        assertThat(AppServiceInjection.params(desired(), "shop-api")).isEmpty();
    }

    @Test
    void otelTracingJava() {
        Map<String, Object> p = AppServiceInjection.params(desired("otel",
                Map.of("language", "java", "tracing", true, "logs", false, "logMode", "app", "sampling", 50)), "shop-api");
        assertThat(p.get("podAnnotations")).isEqualTo(Map.of(
                "instrumentation.opentelemetry.io/inject-java", "monitor/platform-instrumentation"));
        assertThat(p.get("extraEnv")).isEqualTo(List.of(
                Map.of("name", "OTEL_SERVICE_NAME", "value", "shop-api"),
                Map.of("name", "OTEL_TRACES_SAMPLER", "value", "parentbased_traceidratio"),
                Map.of("name", "OTEL_TRACES_SAMPLER_ARG", "value", "0.5")));
        assertThat(p).doesNotContainKey("logMountDir");
    }

    @Test
    void otelFileLogsMountDir() {
        Map<String, Object> p = AppServiceInjection.params(desired("otel",
                Map.of("tracing", false, "logs", true, "logMode", "file", "logFile", "/var/log/nginx/access.log")), "shop-api");
        assertThat(p).containsEntry("logMountDir", "/var/log/nginx").doesNotContainKey("extraEnv");
    }

    @Test
    void meshAmbientAndWaypoint() {
        Map<String, Object> p = AppServiceInjection.params(desired("mesh",
                Map.of("dataplaneMode", "ambient", "useWaypoint", "waypoint")), "shop-api");
        assertThat(p).containsEntry("podLabels", Map.of("istio.io/dataplane-mode", "ambient"))
                .containsEntry("serviceLabels", Map.of("istio.io/use-waypoint", "waypoint"));
    }

    @Test
    void meshNoneWritten() {
        Map<String, Object> p = AppServiceInjection.params(desired("mesh",
                Map.of("dataplaneMode", "none", "useWaypoint", "none")), "shop-api");
        assertThat(p).containsEntry("podLabels", Map.of("istio.io/dataplane-mode", "none"))
                .containsEntry("serviceLabels", Map.of("istio.io/use-waypoint", "none"));
    }
}
