package com.coding.k8sserver.middleware;

import com.coding.common.models.middleware.EsIndexDetailDTO;
import com.coding.common.models.middleware.EsIndexSummaryDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ES REST 的**纯解析**部分：地址拼装、`_cat` 行 → DTO、未分配分片 → 降级消息。
 *
 * <p>真正发请求的那层不在这里测（要起 HTTP 桩）；这里只钉住「拿到字节之后怎么解释」——
 * 它是降级消息与索引列表的全部内容来源，错了就是谎报。
 */
class ElasticsearchRestTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- 地址

    @Test
    void baseUrlFollowsCrTlsSwitch() {
        // ECK 的 es-http 服务两种情形都在；协议跟着 CR 的 http.tls 走（关掉自签证书 = 明文）
        assertThat(ElasticsearchRest.baseUrl(false, "platform-system", "mw-es-1"))
                .isEqualTo("http://mw-es-1-es-http.platform-system.svc:9200");
        assertThat(ElasticsearchRest.baseUrl(true, "platform-system", "mw-es-1"))
                .isEqualTo("https://mw-es-1-es-http.platform-system.svc:9200");
    }

    // ---------------------------------------------------------------- 列表

    @Test
    void mapsCatIndicesRows() {
        JsonNode cat = json("""
                [
                  {"index":"video","health":"green","status":"open","pri":"1","rep":"1",
                   "docs.count":"2347","store.size":"6.7mb","creation.date.string":"2026-10-27T10:00:00.000+08:00"},
                  {"index":".kibana_task_manager_9.5.0_001","health":"red","status":"open","pri":"1","rep":"1",
                   "docs.count":null,"store.size":null,"creation.date.string":"2026-10-23T09:00:00.000+08:00"}
                ]""");

        List<EsIndexSummaryDTO> rows = ElasticsearchRest.indicesFrom(cat);

        assertThat(rows).hasSize(2);
        // 输出按名字排序（系统索引 `.` 在前），与 ES 返回的顺序无关 —— 列表要稳定
        assertThat(rows).extracting(EsIndexSummaryDTO::getName)
                .containsExactly(".kibana_task_manager_9.5.0_001", "video");
        EsIndexSummaryDTO video = rows.get(1);
        assertThat(video.getHealth()).isEqualTo("green");
        assertThat(video.getPrimaries()).isEqualTo(1);
        assertThat(video.getReplicas()).isEqualTo(1);
        assertThat(video.getDocs()).isEqualTo(2347L);
        assertThat(video.getStoreSize()).isEqualTo("6.7mb");
        assertThat(video.getCreatedAt()).isEqualTo("2026-10-27T10:00:00.000+08:00");
        // 空分片（red 且没有活跃主分片）时 _cat 给的是 null，不能变成 0
        EsIndexSummaryDTO red = rows.get(0);
        assertThat(red.getDocs()).isNull();
        assertThat(red.getStoreSize()).isNull();
        assertThat(red.getHealth()).isEqualTo("red");
    }

    // ---------------------------------------------------------------- 详情

    @Test
    void extractsMappingSettingsAndAliasFilter() {
        JsonNode body = json("""
                {"video":{"aliases":{"video_read":{"filter":{"term":{"tenant":"t1"}}}},
                          "mappings":{"properties":{"title":{"type":"text"}}},
                          "settings":{"index":{"number_of_shards":"1","number_of_replicas":"1"}}}}""");

        EsIndexDetailDTO detail = ElasticsearchRest.detailFrom("video", body);

        assertThat(detail.getName()).isEqualTo("video");
        assertThat(detail.getMapping()).contains("\"title\"");
        assertThat(detail.getSettings()).contains("number_of_shards");
        // 别名的 filter 必须原样带出来 —— 「这个别名看得见哪些文档」就靠它
        assertThat(detail.getAliases()).contains("video_read").contains("\"tenant\"");
    }

    @Test
    void detailOfMissingIndexIsEmptyButNamed() {
        EsIndexDetailDTO detail = ElasticsearchRest.detailFrom("gone", json("{}"));

        assertThat(detail.getName()).isEqualTo("gone");
        assertThat(detail.getMapping()).isEqualTo("{}");
        assertThat(detail.getAliases()).isEqualTo("{}");
    }

    // ---------------------------------------------------------------- 降级归因

    @Test
    void redSummaryNamesUnassignedPrimariesAndReason() {
        JsonNode shards = json("""
                [
                  {"index":".kibana_task_manager_9.5.0_001","shard":"0","prirep":"p",
                   "state":"UNASSIGNED","unassigned.reason":"CLUSTER_RECOVERED"},
                  {"index":".kibana_task_manager_9.5.0_001","shard":"0","prirep":"r",
                   "state":"UNASSIGNED","unassigned.reason":"CLUSTER_RECOVERED"},
                  {"index":".kibana_usage_counters_9.5.0_001","shard":"0","prirep":"p",
                   "state":"UNASSIGNED","unassigned.reason":"CLUSTER_RECOVERED"},
                  {"index":"video","shard":"0","prirep":"p","state":"STARTED","unassigned.reason":null}
                ]""");

        String summary = ElasticsearchRest.redSummary(shards);

        // 只数主分片（副本坏了不该重复计数），已分配的不进消息
        assertThat(summary).isEqualTo("2 个主分片未分配："
                + ".kibana_task_manager_9.5.0_001（CLUSTER_RECOVERED）、"
                + ".kibana_usage_counters_9.5.0_001（CLUSTER_RECOVERED）");
    }

    @Test
    void redSummaryCapsLongIndexLists() {
        JsonNode shards = json("""
                [{"index":"a","shard":"0","prirep":"p","state":"UNASSIGNED","unassigned.reason":"NO_VALID_SHARD_COPY"},
                 {"index":"b","shard":"0","prirep":"p","state":"UNASSIGNED","unassigned.reason":"NO_VALID_SHARD_COPY"},
                 {"index":"c","shard":"0","prirep":"p","state":"UNASSIGNED","unassigned.reason":"NO_VALID_SHARD_COPY"},
                 {"index":"d","shard":"0","prirep":"p","state":"UNASSIGNED","unassigned.reason":"NO_VALID_SHARD_COPY"}]""");

        String summary = ElasticsearchRest.redSummary(shards);

        assertThat(summary).contains("4 个主分片未分配").contains("a（NO_VALID_SHARD_COPY）").contains("等 4 个索引");
        assertThat(summary).doesNotContain("d（");
    }

    @Test
    void redSummaryIsNullWhenNothingIsUnassigned() {
        assertThat(ElasticsearchRest.redSummary(json("[]"))).isNull();
        assertThat(ElasticsearchRest.redSummary(json(
                "[{\"index\":\"a\",\"shard\":\"0\",\"prirep\":\"p\",\"state\":\"STARTED\"}]"))).isNull();
    }

    @Test
    void redSummaryToleratesMissingReason() {
        JsonNode shards = json("""
                [{"index":"a","shard":"0","prirep":"p","state":"UNASSIGNED"}]""");

        assertThat(ElasticsearchRest.redSummary(shards)).isEqualTo("1 个主分片未分配：a（原因未知）");
    }
}
