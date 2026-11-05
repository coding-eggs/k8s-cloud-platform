package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformAuditLogMapper;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.models.auth.PlatformPermission;
import com.coding.data.models.system.PlatformAuditLogQuery;
import com.coding.common.audit.AuditAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuditService}：查询的 limit 钳制、清理、动作码候选。写入（fail-open）见 platform-common 的 AuditRecorderTest；403 拒绝审计在网关（GatewayDeniedAuditorTest）。
 */
class AuditServiceTest {

    PlatformAuditLogMapper mapper = mock(PlatformAuditLogMapper.class);
    PlatformPermissionMapper permMapper = mock(PlatformPermissionMapper.class);
    AuditService svc = new AuditService(mapper, permMapper);

    @Test
    void queryDefaultsLimitWhenAbsent() {
        when(mapper.query(any())).thenReturn(List.of());

        svc.query(new PlatformAuditLogQuery());

        assertThat(capturedQuery().getLimit()).isEqualTo(50);
    }

    @Test
    void queryClampsLimitToUpperBound() {
        PlatformAuditLogQuery q = new PlatformAuditLogQuery();
        q.setLimit(9999);
        when(mapper.query(any())).thenReturn(List.of());

        svc.query(q);

        assertThat(capturedQuery().getLimit()).isEqualTo(200);
    }

    @Test
    void queryPassesFiltersThrough() {
        PlatformAuditLogQuery q = new PlatformAuditLogQuery();
        q.setResult("DENIED");
        q.setActorUsername("alice");
        when(mapper.query(any())).thenReturn(List.of());

        svc.query(q);

        PlatformAuditLogQuery passed = capturedQuery();
        assertThat(passed.getResult()).isEqualTo("DENIED");
        assertThat(passed.getActorUsername()).isEqualTo("alice");
    }

    @Test
    void purgeDelegatesToMapper() {
        when(mapper.deleteByTimeRange(any(), any())).thenReturn(7);

        assertThat(svc.purge(null, null)).isEqualTo(7);
    }

    @Test
    void listActionsMergesActionCodesThenApiPermissionCodes() {
        when(permMapper.selectAllActive()).thenReturn(List.of(
                perm("API", "platform:audit:read"),
                perm("API", "tenant:workload:update"),
                perm("Page", "platform:page:audit"),   // 非 API 域应被排除
                perm("K8S", "k8s:pod:get")));

        List<String> got = svc.listActions();

        assertThat(got).contains("workload:delete", "workload:update", "platform:audit:read");
        assertThat(got).doesNotContain("platform:page:audit", "k8s:pod:get");
        // 动作码在前、权限码在后（空查询时的顺序可预期）
        int firstPerm = got.indexOf("platform:audit:read");
        assertThat(firstPerm).isGreaterThan(got.indexOf("workload:update"));
    }

    @Test
    void listActionsTruncatesToCapWhenOverLimit() {
        // 权限行异常膨胀 → 本层截到 MAX 并打 WARN（不静默返回一份看起来完整的候选）
        when(permMapper.selectAllActive()).thenReturn(
                IntStream.range(0, 2100).mapToObj((i) -> perm("API", "code:" + i)).toList());

        assertThat(svc.listActions()).hasSize(2000);
    }

    @Test
    void actionCodesAreExposedByReflectionAndSorted() {
        assertThat(AuditAction.all())
                .contains(AuditAction.WORKLOAD_DELETE, AuditAction.CLUSTER_PROVISION)
                .isSorted()
                .hasSizeGreaterThanOrEqualTo(20);
    }

    private PlatformPermission perm(String domain, String code) {
        PlatformPermission p = new PlatformPermission();
        p.setDomain(domain);
        p.setCode(code);
        return p;
    }

    private PlatformAuditLogQuery capturedQuery() {
        var captor = org.mockito.ArgumentCaptor.forClass(PlatformAuditLogQuery.class);
        verify(mapper).query(captor.capture());
        return captor.getValue();
    }

}
