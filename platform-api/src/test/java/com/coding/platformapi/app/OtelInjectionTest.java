package com.coding.platformapi.app;

import com.coding.common.exception.CloudPlatformException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtelInjectionTest {

    @Test
    void tracingOnlyJava() {
        OtelInjection.Plan p = OtelInjection.plan("java", true, false, null, null, null, "svc");
        assertThat(p.podAnnotations()).isEqualTo(Map.of(
                "instrumentation.opentelemetry.io/inject-java", "monitor/platform-instrumentation"));
        assertThat(p.env()).isEqualTo(Map.of("OTEL_SERVICE_NAME", "svc"));
        assertThat(p.logMountDir()).isNull();
    }

    @Test
    void tracingAndAppLogs() {
        OtelInjection.Plan p = OtelInjection.plan("python", true, true, "app", null, null, "svc");
        assertThat(p.podAnnotations()).containsEntry(
                "instrumentation.opentelemetry.io/inject-python", "monitor/platform-instrumentation-all");
    }

    @Test
    void appLogsOnly() {
        OtelInjection.Plan p = OtelInjection.plan("java", false, true, "app", null, null, "svc");
        assertThat(p.podAnnotations()).containsEntry(
                "instrumentation.opentelemetry.io/inject-java", "monitor/platform-instrumentation-logs");
    }

    @Test
    void fileLogsOnly() {
        OtelInjection.Plan p = OtelInjection.plan(null, false, true, "file", "/var/log/nginx/access.log", null, "svc");
        assertThat(p.podAnnotations()).isEqualTo(Map.of(
                "sidecar.opentelemetry.io/inject", "monitor/platform-otel-sidecar",
                "platform.coding/otel-log-file", "/var/log/app/access.log"));
        assertThat(p.env()).isEmpty();
        assertThat(p.logMountDir()).isEqualTo("/var/log/nginx");
    }

    @Test
    void sampling20() {
        OtelInjection.Plan p = OtelInjection.plan("java", true, false, null, null, 20, "svc");
        assertThat(p.env()).containsEntry("OTEL_TRACES_SAMPLER", "parentbased_traceidratio")
                .containsEntry("OTEL_TRACES_SAMPLER_ARG", "0.2");
        OtelInjection.Plan noTracing = OtelInjection.plan("java", false, true, "app", null, 20, "svc");
        assertThat(noTracing.env()).doesNotContainKeys("OTEL_TRACES_SAMPLER", "OTEL_TRACES_SAMPLER_ARG");
    }

    @Test
    void samplingOutOfRange() {
        assertThatThrownBy(() -> OtelInjection.plan("java", true, false, null, null, 101, "svc"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("采样率");
    }

    @Test
    void unsupportedLanguage() {
        assertThatThrownBy(() -> OtelInjection.plan("node", true, false, null, null, null, "svc"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("node");
    }

    @Test
    void offMeansNothing() {
        OtelInjection.Plan p = OtelInjection.plan("java", false, false, null, null, null, "svc");
        assertThat(p.podAnnotations()).isEmpty();
        assertThat(p.env()).isEmpty();
        assertThat(p.logMountDir()).isNull();
    }
}
