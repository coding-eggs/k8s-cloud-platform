package com.coding.platformapi.services;

import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.metrics.MetricsService;
import com.coding.platformapi.metrics.ThanosQueryClient;
import com.coding.platformapi.metrics.dto.MeshTopologyResponse;
import com.coding.platformapi.metrics.labels.MeshEdgeLabels;
import com.coding.platformapi.metrics.labels.MeshErrorLabels;
import com.coding.platformapi.metrics.labels.MeshLatencyLabels;
import com.coding.platformapi.metrics.labels.MetricLabels;
import com.coding.platformapi.metrics.model.PromResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拓扑的**纯合并逻辑**（Thanos 客户端 mock 掉）：三条业务查询 + 一条哨兵 → 节点与边。
 * <p>每一条都对应 plan 的 Review Focus：命名空间进 id、错误率分母是总量、缺延迟是 null 不是 0、
 * 「没接采集」与「接了但没调用」必须分开。
 */
class MeshTopologyServiceTest {

    private final K8sClusterMapper clusterMapper = mock(K8sClusterMapper.class);
    private final ThanosQueryClient client = mock(ThanosQueryClient.class);
    private final K8sClient resourceClient = mock(K8sClient.class);
    private final MetricsService svc = new MetricsService(clusterMapper, client, resourceClient);

    private void cluster() {
        K8sCluster c = new K8sCluster();
        c.setClusterId("c1");
        c.setClusterName("prod");
        when(clusterMapper.selectByPrimaryKey("c1")).thenReturn(c);
    }

    /** 即时查询的行：value = [ts, "val"]，没有 values。 */
    private static <T> PromResult<T> row(T labels, String value) {
        PromResult<T> r = new PromResult<>();
        r.setMetric(labels);
        r.setValue(List.of("1792200000", value));
        return r;
    }

    private static MeshEdgeLabels edgeLabels(String sNs, String s, String dNs, String d) {
        MeshEdgeLabels l = new MeshEdgeLabels();
        l.setSourceNamespace(sNs);
        l.setSourceWorkload(s);
        l.setDestinationNamespace(dNs);
        l.setDestinationWorkload(d);
        return l;
    }

    private static MeshErrorLabels errorLabels(String sNs, String s, String dNs, String d) {
        MeshErrorLabels l = new MeshErrorLabels();
        l.setSourceNamespace(sNs);
        l.setSourceWorkload(s);
        l.setDestinationNamespace(dNs);
        l.setDestinationWorkload(d);
        l.setResponseCode("500");
        return l;
    }

    private static MeshLatencyLabels latencyLabels(String sNs, String s, String dNs, String d) {
        MeshLatencyLabels l = new MeshLatencyLabels();
        l.setSourceNamespace(sNs);
        l.setSourceWorkload(s);
        l.setDestinationNamespace(dNs);
        l.setDestinationWorkload(d);
        l.setLe("100");
        return l;
    }

    private static PromResult<MetricLabels> sentinel(String value) {
        PromResult<MetricLabels> r = new PromResult<>();
        r.setMetric(new MetricLabels());
        r.setValue(List.of("1792200000", value));
        return r;
    }

    /** 延迟查询是否模拟失败（只有「延迟失败不影响整张图」那条用）。 */
    private boolean latencyFails;

    private void stub(List<PromResult<MeshEdgeLabels>> edges,
                      List<PromResult<MeshErrorLabels>> errors,
                      List<PromResult<MeshLatencyLabels>> latency,
                      List<PromResult<MetricLabels>> sentinel) {
        stub(edges, errors, latency, sentinel, List.of());
    }

    /**
     * 一个分发式的桩：Mockito 的 {@code argThat} 在连续注册多个同方法桩时会互相把对方的
     * 记录值（null 占位）当参数求值，所以五个查询统一按**入参类型 + SQL 形状**分派
     * （L7 与 L4 用的是同一个 label 类，只能靠 SQL 区分）。
     */
    private void stub(List<PromResult<MeshEdgeLabels>> edges,
                      List<PromResult<MeshErrorLabels>> errors,
                      List<PromResult<MeshLatencyLabels>> latency,
                      List<PromResult<MetricLabels>> sentinel,
                      List<PromResult<MeshEdgeLabels>> l4Edges) {
        when(client.query(anyString(), isNull(), any())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            Class<?> type = inv.getArgument(2);
            if (type == MeshErrorLabels.class) {
                return errors;
            }
            if (type == MeshLatencyLabels.class) {
                if (latencyFails) {
                    throw new RuntimeException("thanos 打嗝");
                }
                return latency;
            }
            if (type == MeshEdgeLabels.class) {
                return sql.contains("istio_tcp_sent_bytes_total") ? l4Edges : edges;
            }
            if (type == MetricLabels.class) {
                return sentinel;
            }
            return List.of();
        });
    }

    @Test
    void edgesBecomeNodesAndEdgesWithQps() {
        cluster();
        stub(List.of(row(edgeLabels("default", "web", "default", "api"), "2.5")),
                List.of(), List.of(), List.of(sentinel("8")));

        MeshTopologyResponse resp = svc.meshTopology("c1", null, 300);

        assertThat(resp.getWindowSeconds()).isEqualTo(300);
        assertThat(resp.getEdges()).singleElement().satisfies(e -> {
            assertThat(e.getSource()).isEqualTo("default/web");
            assertThat(e.getTarget()).isEqualTo("default/api");
            assertThat(e.getRps()).isEqualTo(2.5);
        });
        assertThat(resp.getNodes()).extracting(MeshTopologyResponse.MeshNode::getId)
                .containsExactlyInAnyOrder("default/web", "default/api");
    }

    @Test
    void theErrorRateIsErrorsOverTotal() {
        cluster();
        // 总量 10 rps、5xx 3 rps → 30%。⚠️ 分母是 rps（总量），不是 rps+errors。
        stub(List.of(row(edgeLabels("default", "web", "default", "api"), "10")),
                List.of(row(errorLabels("default", "web", "default", "api"), "3")),
                List.of(), List.of(sentinel("8")));

        MeshTopologyResponse resp = svc.meshTopology("c1", null, 300);

        assertThat(resp.getEdges()).singleElement()
                .satisfies(e -> assertThat(e.getErrorRate()).isEqualTo(0.3));
    }

    @Test
    void aMissingErrorSeriesMeansZeroErrorsNotNoEdge() {
        cluster();
        // 有边、窗口内没有 5xx → errorRate 0，边仍然在
        stub(List.of(row(edgeLabels("default", "web", "default", "api"), "1")),
                List.of(), List.of(), List.of(sentinel("8")));

        MeshTopologyResponse resp = svc.meshTopology("c1", null, 300);

        assertThat(resp.getEdges()).singleElement()
                .satisfies(e -> assertThat(e.getErrorRate()).isZero());
    }

    @Test
    void aMissingLatencySeriesMeansNullNotZero() {
        cluster();
        // ⚠️ p95 是 null 不是 0 —— 0 会被读成"延迟为 0ms"，那是假信息
        stub(List.of(row(edgeLabels("default", "web", "default", "api"), "1")),
                List.of(), List.of(), List.of(sentinel("8")));

        assertThat(svc.meshTopology("c1", null, 300).getEdges()).singleElement()
                .satisfies(e -> assertThat(e.getP95Ms()).isNull());
    }

    @Test
    void aNaNLatencyFromAnIdleBucketIsAlsoNull() {
        cluster();
        // ⭐ 实测：窗口内没请求时 histogram_quantile 返回字符串 "NaN"（不是空序列）。
        stub(List.of(row(edgeLabels("default", "web", "default", "api"), "0")),
                List.of(), List.of(row(latencyLabels("default", "web", "default", "api"), "NaN")),
                List.of(sentinel("8")));

        assertThat(svc.meshTopology("c1", null, 300).getEdges()).singleElement()
                .satisfies(e -> assertThat(e.getP95Ms()).isNull());
    }

    @Test
    void aLatencyFailureDoesNotTakeDownTheWholeGraph() {
        cluster();
        latencyFails = true;
        stub(List.of(row(edgeLabels("default", "web", "default", "api"), "1")),
                List.of(), List.of(), List.of(sentinel("8")));

        MeshTopologyResponse resp = svc.meshTopology("c1", null, 300);

        assertThat(resp.getEdges()).singleElement()
                .satisfies(e -> assertThat(e.getP95Ms()).isNull());
    }

    @Test
    void noSentinelMeansMetricsAreNotScraped() {
        cluster();
        stub(List.of(), List.of(), List.of(), List.of());

        assertThat(svc.meshTopology("c1", null, 300).isMeshMetricsAvailable()).isFalse();
    }

    @Test
    void anEmptyWindowWithASentinelIsStillScraped() {
        cluster();
        // ⭐ 这一条正是改名换判据的理由：「没接采集」与「接了但没调用」的主查询**都是空的**。
        stub(List.of(), List.of(), List.of(), List.of(sentinel("8")));

        MeshTopologyResponse resp = svc.meshTopology("c1", null, 300);

        assertThat(resp.isMeshMetricsAvailable()).isTrue();
        assertThat(resp.getEdges()).isEmpty();
        assertThat(resp.getNodes()).isEmpty();
    }

    @Test
    void theSameWorkloadNameInTwoNamespacesStaysTwoNodes() {
        cluster();
        // ⚠️ 只按 workload 名做 id 会把这两个粘成一个节点
        stub(List.of(
                        row(edgeLabels("a", "web", "a", "api"), "1"),
                        row(edgeLabels("b", "web", "b", "api"), "1")),
                List.of(), List.of(), List.of(sentinel("8")));

        assertThat(svc.meshTopology("c1", null, 300).getNodes())
                .extracting(MeshTopologyResponse.MeshNode::getId)
                .containsExactlyInAnyOrder("a/web", "a/api", "b/web", "b/api");
    }

    @Test
    void theGatewayIsFlaggedAsGateway() {
        cluster();
        stub(List.of(row(edgeLabels("istio-system", "external-gateway-istio", "default", "web"), "1")),
                List.of(), List.of(), List.of(sentinel("8")));

        MeshTopologyResponse resp = svc.meshTopology("c1", null, 300);

        assertThat(resp.getNodes()).filteredOn(n -> n.getId().equals("istio-system/external-gateway-istio"))
                .singleElement().satisfies(n -> assertThat(n.isGateway()).isTrue());
        assertThat(resp.getNodes()).filteredOn(n -> n.getId().equals("default/web"))
                .singleElement().satisfies(n -> assertThat(n.isGateway()).isFalse());
    }

    @Test
    void theNamespaceFilterIsPassedThrough() {
        cluster();
        stub(List.of(row(edgeLabels("default", "web", "default", "api"), "1")),
                List.of(), List.of(), List.of(sentinel("8")));

        svc.meshTopology("c1", "default", 600);

        // 两端都滤：只滤一端会漏掉"从别处进来"的边。同时钉住集群名与时间窗。
        // 只看 L7 那条（L4 用同一个 DTO、同样的过滤，会一起匹配到）
        verify(client).query(argThat(s -> s.contains("istio_requests_total")
                        && s.contains("cluster_name=\"prod\"")
                        && s.contains("source_workload_namespace=\"default\"")
                        && s.contains("destination_workload_namespace=\"default\"")
                        && s.contains("[600s]")),
                isNull(), eq(MeshEdgeLabels.class));
    }

    @Test
    void noNamespaceMeansNoNamespaceFilter() {
        cluster();
        stub(List.of(row(edgeLabels("default", "web", "default", "api"), "1")),
                List.of(), List.of(), List.of(sentinel("8")));

        svc.meshTopology("c1", null, 300);

        verify(client).query(argThat(s -> s.contains("istio_requests_total")
                        && !s.contains("source_workload_namespace=\"")
                        && !s.contains("destination_workload_namespace=\"")
                        && s.contains("[300s]")),
                isNull(), eq(MeshEdgeLabels.class));
    }

    @Test
    void anL4OnlyEdgeKeepsItsBytesAndDoesNotInventQps() {
        cluster();
        // L4 只有连接与字节：没有错误率、没有请求延迟，QPS 也不该硬编码成有意义的数
        stub(List.of(), List.of(), List.of(), List.of(sentinel("8")),
                List.of(row(edgeLabels("default", "movie", "default", "mysql"), "7933.4")));

        MeshTopologyResponse resp = svc.meshTopology("c1", null, 300);

        assertThat(resp.getEdges()).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo("L4");
            assertThat(e.getBytesPerSec()).isEqualTo(7933.4);
            assertThat(e.getRps()).isZero();
            assertThat(e.getP95Ms()).isNull();
        });
        assertThat(resp.getNodes()).hasSize(2);
    }

    @Test
    void anL7EdgeCarriesTheBytesOfTheSamePairAndIsNotDrawnTwice() {
        cluster();
        // 同一对端点两层都有 → 只出一条 L7 边，字节数附在它上面（不重复画两条）
        stub(List.of(row(edgeLabels("default", "movie", "default", "mysql"), "2")),
                List.of(), List.of(), List.of(sentinel("8")),
                List.of(row(edgeLabels("default", "movie", "default", "mysql"), "100")));

        MeshTopologyResponse resp = svc.meshTopology("c1", null, 300);

        assertThat(resp.getEdges()).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo("L7");
            assertThat(e.getRps()).isEqualTo(2);
            assertThat(e.getBytesPerSec()).isEqualTo(100);
        });
    }

    @Test
    void theUnknownWorkloadIsNotDrawnAsANode() {
        cluster();
        // Istio 解析不到目标时给 destination_workload="unknown"，把它当节点画出来只会让人困惑
        stub(List.of(row(edgeLabels("istio-system", "external-gateway-istio", "default", "unknown"), "1")),
                List.of(), List.of(), List.of(sentinel("8")),
                List.of(row(edgeLabels("istio-system", "external-gateway-istio", "default", "unknown"), "10")));

        MeshTopologyResponse resp = svc.meshTopology("c1", null, 300);

        assertThat(resp.getEdges()).isEmpty();
        assertThat(resp.getNodes()).isEmpty();
    }
}
