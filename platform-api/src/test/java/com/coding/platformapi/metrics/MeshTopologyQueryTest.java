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
                MetricQuery.MESH_TOPOLOGY_EDGES, MetricQuery.MESH_TOPOLOGY_ERRORS,
                MetricQuery.MESH_TOPOLOGY_LATENCY, MetricQuery.MESH_TOPOLOGY_L4_EDGES}) {
            assertThat(q.sql()).as(q.name()).doesNotContain("reporter=");
        }
    }

    @Test
    void theL4EdgeQueryUsesBytesNotConnectionCount() {
        // ⚠️ 实测：连接数对长连接（mysql/redis）几乎恒为 0，画出来全是细线；
        // 字节/秒才有区分度（mysql 7.9KB/s、es 1.5KB/s）。
        String sql = MetricQuery.MESH_TOPOLOGY_L4_EDGES.sql();
        assertThat(sql).contains("istio_tcp_sent_bytes_total");
        assertThat(sql).doesNotContain("connections_opened");
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
        // 三条业务查询各三个 %s：① 集群名 ② 命名空间过滤片段（无过滤时为空串）③ 时间窗（如 300s）。
        // 多一个少一个都会让 String.format 在运行期炸，所以钉住。
        for (MetricQuery q : new MetricQuery[]{
                MetricQuery.MESH_TOPOLOGY_EDGES, MetricQuery.MESH_TOPOLOGY_ERRORS,
                MetricQuery.MESH_TOPOLOGY_LATENCY, MetricQuery.MESH_TOPOLOGY_L4_EDGES}) {
            long slots = q.sql().chars().filter(c -> c == '%').count();
            assertThat(slots).as(q.name()).isEqualTo(3);
        }
        // 哨兵只要一个：集群名。
        long sentinel = MetricQuery.MESH_TOPOLOGY_SCRAPED.sql().chars().filter(c -> c == '%').count();
        assertThat(sentinel).isEqualTo(1);
    }

    @Test
    void everyQueryIsScopedToOneCluster() {
        // ⚠️ 实测：Thanos 是全局单端点，istio_* 指标**都带 cluster_name**。
        // 不按集群过滤会把别的集群的边一起画进来；哨兵不滤则「B 集群没采集、A 集群在采集」
        // 会被误判成已采集。四条一律带上 `cluster_name="%s"`。
        for (MetricQuery q : new MetricQuery[]{
                MetricQuery.MESH_TOPOLOGY_EDGES, MetricQuery.MESH_TOPOLOGY_ERRORS,
                MetricQuery.MESH_TOPOLOGY_LATENCY, MetricQuery.MESH_TOPOLOGY_L4_EDGES,
                MetricQuery.MESH_TOPOLOGY_SCRAPED}) {
            assertThat(q.sql()).as(q.name()).contains("cluster_name=\"%s\"");
        }
    }

    @Test
    void theSentinelIsTrafficIndependent() {
        // ⭐ 判"有没有在采集"**不能**看主查询空不空 —— "没接采集"和"接了但没流量"的主查询**都是空的**。
        // 哨兵由每个代理产出（istiod / ztunnel / waypoint / 网关），代理活着就有，与流量无关。
        assertThat(MetricQuery.MESH_TOPOLOGY_SCRAPED.sql()).isEqualTo("count(istio_build{cluster_name=\"%s\"})");
    }
}
