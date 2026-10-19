package com.coding.platformapi.services;

import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.PlatformAlertEventMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.PlatformAlertEvent;
import com.coding.platformapi.models.AlertWebhookRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AlertIngestServiceTest {

    private PlatformAlertEventMapper mapper;
    private PlatformTenantNamespaceMapper allocs;
    private AlertIngestService svc;

    @BeforeEach
    void setUp() {
        mapper = mock(PlatformAlertEventMapper.class);
        allocs = mock(PlatformTenantNamespaceMapper.class);
        svc = new AlertIngestService(mapper, allocs);
    }

    private static AlertWebhookRequest.Alert alert(String state, String ns, String startsAt) {
        AlertWebhookRequest.Alert a = new AlertWebhookRequest.Alert();
        a.setStatus(state);   // 字符串，不是对象（见 AlertWebhookRequest 的类注释）
        a.setLabels(new java.util.LinkedHashMap<>(Map.of(
                "alertname", "HighCPU", "severity", "warning", "namespace", ns == null ? "" : ns)));
        if (ns == null) {
            a.getLabels().remove("namespace");
        }
        a.setAnnotations(Map.of("summary", "CPU 高"));
        a.setStartsAt(startsAt);
        a.setFingerprint("fp-1");
        return a;
    }

    private static PlatformTenantNamespace alloc(String tenantId, String ns) {
        PlatformTenantNamespace r = new PlatformTenantNamespace();
        r.setTenantId(tenantId);
        r.setNamespace(ns);
        return r;
    }

    /** 基本落库：字段从 payload 拆对。 */
    @Test
    void ingest_maps_the_payload_onto_the_row() {
        when(allocs.selectByClusterAndNs("c1", "my-app")).thenReturn(List.of(alloc("t1", "my-app")));

        int n = ingestCount("c1", List.of(alert("firing", "my-app", "2026-10-17T10:00:00Z")));

        assertThat(n).isEqualTo(1);
        PlatformAlertEvent row = captured();
        assertThat(row.getClusterId()).isEqualTo("c1");
        assertThat(row.getAlertname()).isEqualTo("HighCPU");
        assertThat(row.getSeverity()).isEqualTo("warning");
        assertThat(row.getNamespace()).isEqualTo("my-app");
        assertThat(row.getStatus()).isEqualTo("firing");
        assertThat(row.getFingerprint()).isEqualTo("fp-1");
        assertThat(row.getLabels()).contains("\"alertname\":\"HighCPU\"");
        assertThat(row.getEndsAt()).isNull();
    }

    /** resolved 带 endsAt（firing 时 endsAt 是"预计结束"，也要照存 —— 但 status 才是判据）。 */
    @Test
    void a_resolved_alert_carries_ends_at() {
        when(allocs.selectByClusterAndNs(anyString(), anyString())).thenReturn(List.of(alloc("t1", "ns")));
        AlertWebhookRequest.Alert a = alert("resolved", "ns", "2026-10-17T10:00:00Z");
        a.setEndsAt("2026-10-17T10:30:00Z");

        svc.ingest("c1", List.of(a));

        PlatformAlertEvent row = captured();
        assertThat(row.getStatus()).isEqualTo("resolved");
        assertThat(row.getEndsAt()).isEqualTo(java.util.Date.from(Instant.parse("2026-10-17T10:30:00Z")));
    }

    /**
     * ⚠️ startsAt 缺失或解析不了 → **跳过该条**，不写半行。
     * 理由：startsAt 是幂等键的一半，缺了它每次重推都会插新行 —— 那比丢一条更糟。
     */
    @Test
    void an_alert_without_a_parseable_starts_at_is_skipped() {
        when(allocs.selectByClusterAndNs(anyString(), anyString())).thenReturn(List.of(alloc("t1", "ns")));

        assertThat(ingestCount("c1", List.of(alert("firing", "ns", "not-a-timestamp")))).isZero();
        verify(mapper, never()).upsert(any());
    }

    /**
     * ⚠️ 幂等键的语义靠 SQL 的 ON DUPLICATE KEY UPDATE 承担 ——
     * 本层要做的是**每次都给同一个 (clusterId, fingerprint, startsAt)**，
     * 而不是在 Java 里先查后写（那有并发窗口）。
     */
    @Test
    void ingest_always_calls_upsert_never_a_select_then_insert() {
        when(allocs.selectByClusterAndNs(anyString(), anyString())).thenReturn(List.of(alloc("t1", "ns")));

        svc.ingest("c1", List.of(alert("firing", "ns", "2026-10-17T10:00:00Z")));

        verify(mapper, times(1)).upsert(any());
        verify(mapper, never()).deleteById(any());
    }

    /**
     * ⚠️ firing 告警的 endsAt 是**零值时间** {@code 0001-01-01T00:00:00Z}（表示"未结束"），
     * 落库前必须归一成 null —— 否则库里会出现公元 1 年的结束时间。
     */
    @Test
    void a_zero_ends_at_is_normalised_to_null() {
        when(allocs.selectByClusterAndNs(anyString(), anyString())).thenReturn(List.of(alloc("t1", "ns")));
        AlertWebhookRequest.Alert a = alert("firing", "ns", "2026-10-17T10:00:00Z");
        a.setEndsAt("0001-01-01T00:00:00Z");

        svc.ingest("c1", List.of(a));

        assertThat(captured().getEndsAt()).isNull();
    }

    /** startsAt 归一：带小数秒的 RFC3339 解析成 Date 后，毫秒位稳定。 */
    @Test
    void starts_at_is_parsed_to_a_stable_millisecond_instant() {
        when(allocs.selectByClusterAndNs(anyString(), anyString())).thenReturn(List.of(alloc("t1", "ns")));

        svc.ingest("c1", List.of(alert("firing", "ns", "2026-10-17T10:00:00.123456Z")));

        assertThat(captured().getStartsAt()).isEqualTo(java.util.Date.from(Instant.parse("2026-10-17T10:00:00.123Z")));
    }

    /**
     * ⚠️ **Review Focus #3**：labels.namespace 缺失（规则是 kubectl 建的、没经平台）
     * → 照常落库（管理员要看得见），namespace 置 null，不抛。
     */
    @Test
    void a_missing_namespace_label_still_ingests_with_a_null_namespace() {
        int n = ingestCount("c1", List.of(alert("firing", null, "2026-10-17T10:00:00Z")));

        assertThat(n).isEqualTo(1);
        assertThat(captured().getNamespace()).isNull();
    }

    /** namespace 不在任何分配里 → 照常落库（管理员可见），不抛、不丢。 */
    @Test
    void a_namespace_outside_every_allocation_still_ingests() {
        when(allocs.selectByClusterAndNs("c1", "kube-system")).thenReturn(List.of());

        int n = ingestCount("c1", List.of(alert("firing", "kube-system", "2026-10-17T10:00:00Z")));

        assertThat(n).isEqualTo(1);
        assertThat(captured().getNamespace()).isEqualTo("kube-system");
    }

    /** 一批里混多个命名空间 → 逐条判定（不能只判第一条）。 */
    @Test
    void a_batch_is_resolved_per_alert_not_per_request() {
        when(allocs.selectByClusterAndNs("c1", "ns-a")).thenReturn(List.of(alloc("t1", "ns-a")));
        when(allocs.selectByClusterAndNs("c1", "ns-b")).thenReturn(List.of());

        svc.ingest("c1", List.of(
                alert("firing", "ns-a", "2026-10-17T10:00:00Z"),
                alert("firing", "ns-b", "2026-10-17T10:01:00Z")));

        verify(allocs).selectByClusterAndNs("c1", "ns-a");
        verify(allocs).selectByClusterAndNs("c1", "ns-b");
        verify(mapper, times(2)).upsert(any());
    }

    /**
     * 单条落库失败不能拖垮整批 —— 其余照常写，方法仍返回成功条数。
     * Alertmanager 只看 HTTP 状态码，所以这里吞掉异常、记 error 日志是对的。
     */
    @Test
    void one_failing_row_does_not_abort_the_rest_of_the_batch() {
        when(allocs.selectByClusterAndNs(anyString(), anyString())).thenReturn(List.of(alloc("t1", "ns")));
        when(mapper.upsert(any()))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(1);

        int n = ingestCount("c1", List.of(
                alert("firing", "ns", "2026-10-17T10:00:00Z"),
                alert("firing", "ns", "2026-10-17T10:02:00Z")));

        assertThat(n).isEqualTo(1);
    }

    /** 空批次 → 0，不炸。 */
    @Test
    void an_empty_batch_is_a_no_op() {
        assertThat(ingestCount("c1", List.of())).isZero();
        verifyNoInteractions(mapper);
    }

    /** alertname 缺失（理论上不该有，但 payload 是外部输入）→ 跳过该条并记 warn，不写半行。 */
    @Test
    void an_alert_without_an_alertname_is_skipped() {
        AlertWebhookRequest.Alert bad = alert("firing", "ns", "2026-10-17T10:00:00Z");
        bad.getLabels().remove("alertname");

        assertThat(svc.ingest("c1", List.of(bad))).isEmpty();
        verify(mapper, never()).upsert(any());
    }

    /**
     * 返回落库后的行（**含 id**），供投递引擎用。
     * id 必须回查：MySQL 的 ON DUPLICATE KEY UPDATE 只在"真插入"时回填自增 id。
     */
    @Test
    void ingest_returns_the_upserted_events_with_ids() {
        when(allocs.selectByClusterAndNs(anyString(), anyString())).thenReturn(List.of(alloc("t1", "ns")));
        PlatformAlertEvent stored = new PlatformAlertEvent();
        stored.setId(42L);
        stored.setAlertname("HighCPU");
        when(mapper.selectByEpisode(eq("c1"), eq("fp-1"), any())).thenReturn(stored);

        List<PlatformAlertEvent> out = svc.ingest("c1",
                List.of(alert("firing", "ns", "2026-10-17T10:00:00Z")));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getId()).isEqualTo(42L);
    }

    /**
     * 回查不到（并发删了 / 读失败）→ 行照常算落库，但**没有 id**；
     * 投递端看到 null id 会跳过它（投递记录按 event_id 去重，null 没法记）。
     */
    @Test
    void a_row_whose_id_cannot_be_read_back_is_returned_without_an_id() {
        when(allocs.selectByClusterAndNs(anyString(), anyString())).thenReturn(List.of(alloc("t1", "ns")));
        when(mapper.selectByEpisode(anyString(), anyString(), any())).thenReturn(null);

        List<PlatformAlertEvent> out = svc.ingest("c1",
                List.of(alert("firing", "ns", "2026-10-17T10:00:00Z")));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getId()).isNull();
    }

    /** 成功条数口径：老调用方要数字，这里只是把 list 折成 size。 */
    private int ingestCount(String clusterId, List<AlertWebhookRequest.Alert> alerts) {
        return svc.ingest(clusterId, alerts).size();
    }

    private PlatformAlertEvent captured() {
        ArgumentCaptor<PlatformAlertEvent> c = ArgumentCaptor.forClass(PlatformAlertEvent.class);
        verify(mapper).upsert(c.capture());
        return c.getValue();
    }
}
