package com.coding.platformapi.metrics;

import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.metrics.labels.DestinationWorkloadLabels;
import com.coding.platformapi.metrics.model.PromResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 灰度对比：Istio 指标按 reporter 分来源、按 destination_workload 拆成 stable / canary 两列。 */
class MetricsServiceCanaryTest {

    private final K8sClusterMapper clusterMapper = mock(K8sClusterMapper.class);
    private final ThanosQueryClient client = mock(ThanosQueryClient.class);
    private final MetricsService svc = new MetricsService(clusterMapper, client, mock(K8sClient.class));

    @BeforeEach
    void setUp() {
        K8sCluster c = new K8sCluster();
        c.setClusterId("c1");
        c.setClusterName("c-name");
        when(clusterMapper.selectByPrimaryKey("c1")).thenReturn(c);
        when(client.query(anyString(), isNull(), eq(DestinationWorkloadLabels.class))).thenReturn(List.of());
    }

    private static PromResult<DestinationWorkloadLabels> row(String service, String value) {
        DestinationWorkloadLabels l = new DestinationWorkloadLabels();
        l.setWorkload(service);
        PromResult<DestinationWorkloadLabels> r = new PromResult<>();
        r.setMetric(l);
        r.setValue(List.of("1700000000", value));
        return r;
    }

    private void answer(String sqlPart, List<PromResult<DestinationWorkloadLabels>> rows) {
        when(client.query(org.mockito.ArgumentMatchers.contains(sqlPart), isNull(), eq(DestinationWorkloadLabels.class)))
                .thenReturn(rows);
    }

    @Test
    void httpCompareSplitsByServiceName() {
        answer("rate(istio_requests_total", List.of(row("shop-api", "10"), row("shop-api-canary", "0")));
        answer("response_code=~\"5..\"", List.of(row("shop-api", "0.5")));
        answer("histogram_quantile(0.5,", List.of(row("shop-api", "12"), row("shop-api-canary", "30")));
        answer("histogram_quantile(0.99,", List.of(row("shop-api", "80")));

        Map<String, Map<String, Double>> out =
                svc.canaryCompare("c1", "demo", "shop-api", "shop-api-canary", "15m", false, "waypoint");

        assertThat(out.get("stable")).containsEntry("rps", 10.0).containsEntry("errorRate", 0.05)
                .containsEntry("p50", 12.0).containsEntry("p99", 80.0);
        // canary 总量为 0：错误率不能算成 0/0
        assertThat(out.get("canary")).containsEntry("rps", 0.0).containsEntry("errorRate", null)
                .containsEntry("p50", 30.0).containsEntry("p99", null);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(client, atLeastOnce()).query(sql.capture(), any(), eq(DestinationWorkloadLabels.class));
        assertThat(sql.getAllValues()).allSatisfy(s -> assertThat(s)
                .contains("cluster_name=\"c-name\"")
                .contains("destination_workload_namespace=\"demo\"")
                .contains("destination_workload=~\"shop-api|shop-api-canary\"")
                .contains("reporter=\"waypoint\"")
                .contains("[15m]")
                .doesNotContain("destination_service_name")
                .doesNotContain("%"));
    }

    @Test
    void tcpCompareReturnsConnectionsAndBytes() {
        answer("istio_tcp_connections_opened_total", List.of(row("shop-api", "2"), row("shop-api-canary", "1")));
        answer("istio_tcp_sent_bytes_total", List.of(row("shop-api", "1024")));

        Map<String, Map<String, Double>> out =
                svc.canaryCompare("c1", "demo", "shop-api", "shop-api-canary", "5m", true, "source");

        assertThat(out.get("stable")).containsOnlyKeys("cps", "bytes")
                .containsEntry("cps", 2.0).containsEntry("bytes", 1024.0);
        assertThat(out.get("canary")).containsEntry("cps", 1.0).containsEntry("bytes", null);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(client, atLeastOnce()).query(sql.capture(), any(), eq(DestinationWorkloadLabels.class));
        String bytes = sql.getAllValues().stream().filter(s -> s.contains("sent_bytes")).findFirst().orElseThrow();
        assertThat(bytes).contains("istio_tcp_received_bytes_total").contains("[5m]").doesNotContain("%");
        assertThat(sql.getAllValues()).allSatisfy(q -> assertThat(q).contains("reporter=\"source\""));
    }

    @Test
    void missingSeriesIsNullNotZero() {
        answer("histogram_quantile(0.5,", List.of(row("shop-api", "NaN")));

        Map<String, Map<String, Double>> out =
                svc.canaryCompare("c1", "demo", "shop-api", "shop-api-canary", "1h", false, "waypoint");

        List<Double> all = new ArrayList<>(out.get("stable").values());
        all.addAll(out.get("canary").values());
        assertThat(all).containsOnlyNulls();
        assertThat(out.get("stable")).containsOnlyKeys("rps", "errorRate", "p50", "p99");
    }
}
