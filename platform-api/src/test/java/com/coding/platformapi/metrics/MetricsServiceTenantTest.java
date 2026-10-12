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
import java.util.Map;

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
 * <p>作用域（R12）是**成对**的 {@code clusterId → 该集群下本租户的命名空间}：逐集群各查各的、按**该集群自己的**
 * ns 集合过滤 legend，再把跨集群**同名** ns 的值相加（同名且都在本租户名下才是对的相加）。
 * <p>核心不变量：**只把本租户自己拥有的命名空间算进去** —— 别的 ns（含同一集群里别的租户占用的**同名** ns）
 * 即使出现在查询返回里，也必须被过滤掉。
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

        MetricSeriesResponse resp = svc.tenantCpu("t1", Map.of("c1", List.of("a")), 0, 60);

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
        // 租户在 c1、c2 **都**拥有 a → 两侧的 a 相加（同名且都在本租户名下 = 对的相加）
        when(client.queryRange(contains("prod"), anyLong(), anyLong(), anyInt(), eq(NamespaceLabels.class)))
                .thenReturn(List.of(row("a", 1, 2)));
        when(client.queryRange(contains("dr"), anyLong(), anyLong(), anyInt(), eq(NamespaceLabels.class)))
                .thenReturn(List.of(row("a", 10, 20)));

        MetricSeriesResponse resp = svc.tenantCpu("t1", Map.of("c1", List.of("a"), "c2", List.of("a")), 0, 60);

        assertThat(resp.getSeries()).singleElement()
                .satisfies(s -> assertThat(s.getPoints()).extracting(MetricPoint::getValue)
                        .containsExactly(11.0, 22.0));
    }

    /**
     * 区分"成对作用域"与"扁平作用域"的钉子（R12）：
     * 租户拥有 c1 的 team-a、c2 的 team-b；而 c2 里**也有**一个 team-a（属别的租户）。
     * 成对实现：c2 只按 {team-b} 过滤 → team-a（别人的）被滤掉 → 合计 = c1.team-a + c2.team-b。
     * 扁平实现：clusterIds=[c1,c2] + namespaces={team-a,team-b} → c2 的 team-a 混进来 → 合计偏大。
     */
    @Test
    void tenant_metrics_exclude_same_named_ns_owned_by_another_tenant() {
        cluster("c1", "prod");
        cluster("c2", "dr");
        when(client.queryRange(contains("prod"), anyLong(), anyLong(), anyInt(), eq(NamespaceLabels.class)))
                .thenReturn(List.of(row("team-a", 1, 2)));                       // c1 的 team-a 属本租户
        when(client.queryRange(contains("dr"), anyLong(), anyLong(), anyInt(), eq(NamespaceLabels.class)))
                .thenReturn(List.of(row("team-b", 5, 6), row("team-a", 10, 20))); // c2: team-b 属本租户，team-a 属别人

        MetricSeriesResponse resp = svc.tenantCpu("t1",
                Map.of("c1", List.of("team-a"), "c2", List.of("team-b")), 0, 60);

        // 正确 = 1+5, 2+6 = 6,8；扁平实现会得 1+5+10=16, 2+6+20=28 → 断言红
        assertThat(resp.getSeries()).singleElement()
                .satisfies(s -> assertThat(s.getPoints()).extracting(MetricPoint::getValue)
                        .containsExactly(6.0, 8.0));
    }

    @Test
    void tenant_memory_curve_uses_memory_unit_and_same_namespace_filter() {
        cluster("c1", "prod");
        when(client.queryRange(anyString(), anyLong(), anyLong(), anyInt(), eq(NamespaceLabels.class)))
                .thenReturn(List.of(row("a", 100, 200), row("other", 999)));

        MetricSeriesResponse resp = svc.tenantMemory("t1", Map.of("c1", List.of("a")), 0, 60);

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
