package com.coding.platformapi.metrics;

import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.metrics.dto.MetricPoint;
import com.coding.platformapi.metrics.dto.MetricSeriesResponse;
import com.coding.platformapi.metrics.labels.NamespaceLabels;
import com.coding.platformapi.metrics.model.PromResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MetricsService} 的**租户合计曲线**（R10：曲线不在 TenantOverviewService，落在这里）。
 * <p>口径：按集群取 {@code CLUSTER_*_USED_BY_NS} 的原始 per-ns 序列 → 用租户的 ns 名集合过滤 legend →
 * 逐时间点求和成**一条** {@code legend="租户合计"}；跨集群再把同名 ns 的值相加。
 * <p>核心不变量：**只把租户自己的命名空间算进去**（别的 ns 即使出现在同一集群的返回里也必须被过滤掉）。
 */
class MetricsServiceTenantTest {

    private final K8sClusterMapper clusterMapper = mock(K8sClusterMapper.class);
    private final ThanosQueryClient client = mock(ThanosQueryClient.class);
    private final K8sClient resourceClient = mock(K8sClient.class);
    private final MetricsService svc = new MetricsService(clusterMapper, client, resourceClient);

    private void cluster(String id, String name) {
        K8sCluster c = new K8sCluster();
        c.setClusterId(id);
        c.setClusterName(name);
        when(clusterMapper.selectByPrimaryKey(id)).thenReturn(c);
    }

    @Test
    void tenant_metrics_sum_only_the_tenants_namespaces() {
        cluster("c1", "prod");
        // 同一集群返回 a 与 b 两条序列；租户只拥有 a → 只算 a（不是 a+b）
        when(client.queryRange(anyString(), anyLong(), anyLong(), anyInt(), eq(NamespaceLabels.class)))
                .thenReturn(List.of(row("a", 1, 2), row("b", 10, 20)));

        MetricSeriesResponse resp = svc.tenantCpu("t1", List.of("c1"), List.of("a"), 0, 60);

        assertThat(resp.getSeries()).singleElement()
                .satisfies(s -> {
                    assertThat(s.getLegend()).isEqualTo("租户合计");
                    assertThat(s.getPoints()).extracting(MetricPoint::getValue).containsExactly(1.0, 2.0);
                });
    }

    @Test
    void tenant_metrics_sum_same_named_ns_across_clusters() {
        cluster("c1", "prod");
        cluster("c2", "dr");
        when(client.queryRange(contains("prod"), anyLong(), anyLong(), anyInt(), eq(NamespaceLabels.class)))
                .thenReturn(List.of(row("a", 1, 2)));
        when(client.queryRange(contains("dr"), anyLong(), anyLong(), anyInt(), eq(NamespaceLabels.class)))
                .thenReturn(List.of(row("a", 10, 20)));

        MetricSeriesResponse resp = svc.tenantCpu("t1", List.of("c1", "c2"), List.of("a"), 0, 60);

        assertThat(resp.getSeries()).singleElement()
                .satisfies(s -> assertThat(s.getPoints()).extracting(MetricPoint::getValue)
                        .containsExactly(11.0, 22.0));
    }

    @Test
    void tenant_memory_curve_uses_memory_unit_and_same_namespace_filter() {
        cluster("c1", "prod");
        when(client.queryRange(anyString(), anyLong(), anyLong(), anyInt(), eq(NamespaceLabels.class)))
                .thenReturn(List.of(row("a", 100, 200), row("other", 999)));

        MetricSeriesResponse resp = svc.tenantMemory("t1", List.of("c1"), List.of("a"), 0, 60);

        assertThat(resp.getUnit()).isEqualTo("字节");
        assertThat(resp.getSeries()).singleElement()
                .satisfies(s -> assertThat(s.getPoints()).extracting(MetricPoint::getValue)
                        .containsExactly(100.0, 200.0));
    }

    /** 一条 PromResult（by namespace 行）：values = [[ts,"v"], ...]，ts 从 0 起、步长 60。 */
    private static PromResult<NamespaceLabels> row(String ns, double... vals) {
        NamespaceLabels labels = new NamespaceLabels();
        labels.setNamespace(ns);
        PromResult<NamespaceLabels> r = new PromResult<>();
        r.setMetric(labels);
        List<List<String>> values = new ArrayList<>();
        long ts = 0;
        for (double v : vals) {
            values.add(List.of(String.valueOf(ts), String.valueOf(v)));
            ts += 60;
        }
        r.setValues(values);
        return r;
    }
}
