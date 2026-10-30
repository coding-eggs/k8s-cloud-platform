package com.coding.platformapi.components;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OtelObjectsTest {

    @Test
    void itBuildsThreeInstrumentationsAndOneGateway() {
        var objects = OtelObjects.build(true, 1, "plaintext");
        assertThat(objects).hasSize(5);
        assertThat(objects.stream()
                .map(o -> String.valueOf(((Map<?, ?>) o.get("metadata")).get("name")))
                .toList())
                .containsExactly("platform-instrumentation", "platform-instrumentation-logs",
                        "platform-instrumentation-all", "platform-otel-gateway",
                        "platform-otel-sidecar");
    }

    @Test
    void theSidecarCollectorReadsTheSharedLogVolume() {
        String sidecar = OtelObjects.build(true, 1, "plaintext").get(4).toString();
        assertThat(sidecar).contains("mode=sidecar")
                // 采哪个文件由每个 Pod 的注解决定（downward API → env → filelog include）
                .contains("filelog").contains("${env:OTEL_FILELOG_INCLUDE}")
                .contains("metadata.annotations['platform.coding/otel-log-file']")
                .contains("platform-otel-logs")
                .contains("K8S_POD_UID")
                .contains("platform-otel-gateway-collector.monitor.svc:4317");
    }

    @Test
    void theTracingInstrumentationUsesTheHttpEndpointAndRatioPlaceholder() {
        Map<?, ?> tracing = OtelObjects.build(false, 1, "plaintext").get(0);
        Map<?, ?> spec = (Map<?, ?>) tracing.get("spec");
        assertThat(((Map<?, ?>) spec.get("exporter")).get("endpoint"))
                .isEqualTo("http://platform-otel-gateway-collector.monitor.svc:4318");
        assertThat(spec.get("env").toString()).contains("http/protobuf");
        assertThat(((Map<?, ?>) spec.get("sampler")).get("argument")).isEqualTo("${otel.sampling}");
    }

    @Test
    void theLogsInstrumentationTurnsTracingOffAndAddsTheLogEnv() {
        Map<?, ?> logs = OtelObjects.build(true, 1, "plaintext").get(1);
        Map<?, ?> spec = (Map<?, ?>) logs.get("spec");
        assertThat(((Map<?, ?>) spec.get("sampler")).get("type")).isEqualTo("always_off");
        assertThat(spec.get("env").toString()).contains("OTEL_LOGS_EXPORTER").contains("otlp");
        assertThat(spec.get("java").toString()).contains("LOGBACK_APPENDER")
                .contains("LOG4J_APPENDER");
    }

    @Test
    void logsOffOmitsTheElasticsearchExporterAndTheLogsPipeline() {
        String off = OtelObjects.build(false, 1, "plaintext").get(3).toString();
        assertThat(off).doesNotContain("elasticsearch").doesNotContain("logs=");
        String on = OtelObjects.build(true, 1, "plaintext").get(3).toString();
        assertThat(on).contains("elasticsearch").contains("logs=")
                // 按天分割：logs_index 是前缀，日期由 logstash_format 追加（不是 Fluentd 的 %{+...}）
                .contains("logstash_format").contains("%Y.%m.%d")
                .doesNotContain("%{+yyyy.MM.dd}")
                // mapping.mode 在 0.161.0 被忽略、默认 otel（data stream）；必须用 allowed_modes 固定 none
                .contains("allowed_modes=[none]")
                .doesNotContain("mode=none");
    }

    @Test
    void theGatewayOnlyExposesTheOtlpPorts() {
        Map<?, ?> gateway = OtelObjects.build(true, 2, "plaintext").get(3);
        Map<?, ?> spec = (Map<?, ?>) gateway.get("spec");
        assertThat(spec.get("image").toString()).endsWith("opentelemetry-collector-contrib:0.161.0");
        // 指标不走 OTel（平台指标走 Prometheus/ServiceMonitor）：没有 8889 / prometheus exporter / metrics 管道
        assertThat(spec.get("ports").toString()).contains("4317").contains("4318").doesNotContain("8889");
        assertThat(spec.toString()).doesNotContain("prometheus").doesNotContain("metrics=");
        // replicas 必须是整数：ValueTemplate 只能产出字符串，CRD 会拒
        assertThat(spec.get("replicas")).isEqualTo(2);
    }

    @Test
    void theInstrumentationTurnsOffTheSdkMetricExporter() {
        // gateway 没有 metrics 管道了，agent 再推指标只会白耗带宽、刷 "no pipeline" 错误
        String instrumentation = OtelObjects.build(false, 1, "plaintext").get(0).toString();
        assertThat(instrumentation).contains("OTEL_METRICS_EXPORTER").contains("none");
    }

    @Test
    void theGatewayCarriesTheTempoAndEsPlaceholders() {
        String gateway = OtelObjects.build(true, 1, "plaintext").get(3).toString();
        assertThat(gateway).contains("${tempo.endpoint}")
                .contains("${otel.es.endpoint}")
                .contains("otel-logs")
                // 用户名/密码都从 platform-otel-es 读（来源 Secret 固定 username/password 两个 key）
                .contains("key=username").contains("key=password")
                // collector 的 env 展开用 ${env:...}（含冒号，不会命中平台占位符正则）
                .contains("${env:TEMPO_ENDPOINT}")
                .contains("${env:ES_PASSWORD}");
    }

    @Test
    void theGatewayStampsClusterNameOnAllSignals() {
        String gateway = OtelObjects.build(true, 1, "plaintext").get(3).toString();
        assertThat(gateway).contains("resource=").contains("cluster.name").contains("${cluster.name}");
        assertThat(gateway).contains("processors=[memory_limiter, resource, k8sattributes, batch]");
    }

    @Test
    void tempoTlsModeControlsTheExporterTlsBlock() {
        assertThat(OtelObjects.build(true, 1, "plaintext").get(3).toString())
                .contains("insecure=true");
        assertThat(OtelObjects.build(true, 1, "tls").get(3).toString())
                .doesNotContain("insecure=true");
        assertThat(OtelObjects.build(true, 1, "tls-skip-verify").get(3).toString())
                .contains("insecure_skip_verify=true");
    }
}
