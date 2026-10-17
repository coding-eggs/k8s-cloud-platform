package com.coding.platformapi.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 三条 PromQL 的形状 —— 钉住 2026-10-17 实测出来的那几个坑。 */
class MeshTopologyQueryTest {

    @Test
    void theEdgeQueryGroupsByBothEndsOfTheCall() {
        String sql = MetricQuery.MESH_TOPOLOGY_EDGES.sql();
        assertThat(sql).contains("istio_requests_total");
        assertThat(sql).contains("sum by (source_workload, destination_workload");
        assertThat(sql).contains("rate(");
    }

    @Test
    void noQueryFiltersOnTheDestinationReporter() {
        // ⚠️ 实测：本集群只有 reporter=source(网关) 与 reporter=waypoint，
        // 照抄网上的 reporter="destination" 会查出空图。
        for (MetricQuery q : new MetricQuery[]{
                MetricQuery.MESH_TOPOLOGY_EDGES, MetricQuery.MESH_TOPOLOGY_ERRORS, MetricQuery.MESH_TOPOLOGY_LATENCY}) {
            assertThat(q.sql()).as(q.name()).doesNotContain("reporter=");
        }
    }

    @Test
    void theLatencyQueryUsesTheMillisecondsBucket() {
        // ⚠️ 实测：本集群没有 _seconds 版，也没有不带后缀的裸名。
        String sql = MetricQuery.MESH_TOPOLOGY_LATENCY.sql();
        assertThat(sql).contains("istio_request_duration_milliseconds_bucket");
        assertThat(sql).doesNotContain("duration_seconds");
        assertThat(sql).contains("histogram_quantile(0.95");
    }

    @Test
    void theErrorQueryFiltersOn5xx() {
        assertThat(MetricQuery.MESH_TOPOLOGY_ERRORS.sql()).contains("response_code=~\"5..\"");
    }

    @Test
    void eachTemplateTakesTheSlotsItsCallersFill() {
        // 三条业务查询各两个 %s：① 命名空间过滤片段 ② 时间窗（如 300s）。
        // 多一个少一个都会让 String.format 在运行期炸，所以钉住。
        for (MetricQuery q : new MetricQuery[]{
                MetricQuery.MESH_TOPOLOGY_EDGES, MetricQuery.MESH_TOPOLOGY_ERRORS, MetricQuery.MESH_TOPOLOGY_LATENCY}) {
            long slots = q.sql().chars().filter(c -> c == '%').count();
            assertThat(slots).as(q.name()).isEqualTo(2);
        }
        // 哨兵没有参数
        long sentinel = MetricQuery.MESH_TOPOLOGY_SCRAPED.sql().chars().filter(c -> c == '%').count();
        assertThat(sentinel).isZero();
    }

    @Test
    void theSentinelIsTrafficIndependent() {
        // ⭐ 判"有没有在采集"**不能**看主查询空不空 —— "没接采集"和"接了但没流量"的主查询**都是空的**。
        // 哨兵由每个代理产出（istiod / ztunnel / waypoint / 网关），代理活着就有，与流量无关。
        assertThat(MetricQuery.MESH_TOPOLOGY_SCRAPED.sql()).isEqualTo("count(istio_build)");
    }
}
