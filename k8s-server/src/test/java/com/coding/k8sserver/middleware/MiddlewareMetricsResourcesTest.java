package com.coding.k8sserver.middleware;

import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务指标的采集资源（Service + ServiceMonitor）。
 *
 * <p>用 Service 而不是 PodMonitor 的原因：本集群 PodMonitor CRD 没有 targetPort，只能引用容器端口名，
 * 而各 Operator 的端口命名不归平台管。这些断言把「端口名固定 metrics、targetPort 为数字」钉住。
 */
class MiddlewareMetricsResourcesTest {

    private final RabbitmqMiddlewareAdapter adapter = new RabbitmqMiddlewareAdapter();

    private MiddlewareCreateRequestDTO req() {
        MiddlewareCreateRequestDTO r = new MiddlewareCreateRequestDTO();
        r.setInstanceId("mwi_1");
        r.setClusterId("c1");
        r.setCode("rabbitmq");
        r.setMode("SINGLE");
        r.setNamespace("demo");
        r.setName("mw-rabbitmq-abc12345");
        return r;
    }

    @Test
    void rabbitmqMetricsSpecPointsAtPrometheusPlugin() {
        var spec = adapter.metricsSpec("mw-rabbitmq-abc12345", "RabbitmqCluster", "mwi_1");

        assertThat(spec).isNotNull();
        assertThat(spec.port()).isEqualTo(15692);
        assertThat(spec.path()).isEqualTo("/metrics");
        // selector 要只命中 RabbitMQ Pod（状态判定同款），否则会抓到 Operator/其它 Pod
        assertThat(spec.podSelector()).isEqualTo(
                "app.kubernetes.io/name=mw-rabbitmq-abc12345,app.kubernetes.io/component=rabbitmq");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsServiceAndServiceMonitorSelectingIt() {
        var req = req();
        var resources = adapter.buildMetricsResources(req, adapter.metricsSpec(req.getName(), "RabbitmqCluster", "mwi_1"));

        assertThat(resources).hasSize(2);
        GenericKubernetesResource service = resources.get(0).resource();
        GenericKubernetesResource monitor = resources.get(1).resource();

        assertThat(service.getKind()).isEqualTo("Service");
        assertThat(service.getMetadata().getName()).isEqualTo("mw-rabbitmq-abc12345-platform-metrics");
        var serviceSpec = (Map<String, Object>) service.getAdditionalProperties().get("spec");
        assertThat((Map<String, Object>) serviceSpec.get("selector"))
                .containsEntry("app.kubernetes.io/name", "mw-rabbitmq-abc12345")
                .containsEntry("app.kubernetes.io/component", "rabbitmq");
        var port = ((List<Map<String, Object>>) serviceSpec.get("ports")).get(0);
        assertThat(port).containsEntry("name", "metrics").containsEntry("targetPort", 15692);

        assertThat(monitor.getKind()).isEqualTo("ServiceMonitor");
        assertThat(monitor.getApiVersion()).isEqualTo("monitoring.coreos.com/v1");
        var monitorSpec = (Map<String, Object>) monitor.getAdditionalProperties().get("spec");
        var selector = (Map<String, Object>) monitorSpec.get("selector");
        assertThat((Map<String, Object>) selector.get("matchLabels"))
                .containsEntry("platform.coding/middleware-monitor", "mw-rabbitmq-abc12345-platform-metrics");
        var endpoint = ((List<Map<String, Object>>) monitorSpec.get("endpoints")).get(0);
        assertThat(endpoint).containsEntry("port", "metrics")
                .containsEntry("path", "/metrics")
                .containsEntry("scheme", "http");
    }

    @Test
    void parsedSelectorFeedsTheServiceSelector() {
        assertThat(MiddlewareAdapter.parseSelector("a=1,b=2"))
                .containsEntry("a", "1").containsEntry("b", "2");
        assertThat(MiddlewareAdapter.parseSelector("")).isEmpty();
        assertThat(MiddlewareAdapter.parseSelector(null)).isEmpty();
        // 没有 '=' 的片段忽略（防脏 selector 建出"选全部 Pod"的 Service）
        assertThat(MiddlewareAdapter.parseSelector("broken")).isEmpty();
    }

    @Test
    void postgresqlUsesTheSameServiceMonitorPath() {
        PostgresqlMiddlewareAdapter pg = new PostgresqlMiddlewareAdapter();

        // CNPG 0.29 的 spec.monitoring.enablePodMonitor 已 deprecated，平台统一走 Service + ServiceMonitor
        var spec = pg.metricsSpec("mw-postgresql-abc12345", "Cluster", "mwi_1");
        assertThat(spec).isNotNull();
        assertThat(spec.port()).isEqualTo(9187);
        assertThat(spec.podSelector()).isEqualTo("cnpg.io/cluster=mw-postgresql-abc12345,cnpg.io/podRole=instance");

        var req = req();
        req.setCode("postgresql");
        req.setName("mw-postgresql-abc12345");
        Map<String, Object> crSpec = (Map<String, Object>) pg.buildCreate(req).get(0)
                .resource().getAdditionalProperties().get("spec");
        assertThat(crSpec).doesNotContainKey("monitoring");
    }
}
