package com.coding.platformapi.services;

import com.coding.data.models.system.UserTenantInfo;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.PlatformAlertEventMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.PlatformAlertEventQuery;
import com.coding.platformapi.models.AlertQueryRequest;
import com.coding.platformapi.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收窄是这一块唯一有安全含义的逻辑 —— 所有测试都盯着**传给 mapper 的条件**，而不是返回了什么。
 * mapper 是 mock，返回什么由测试给，所以断言只看"过滤条件对不对"。
 */
class AlertServiceTest {

    private PlatformAlertEventMapper mapper;
    private PlatformTenantNamespaceMapper allocs;
    private AuthContext auth;
    private AlertService svc;

    @BeforeEach
    void setUp() {
        mapper = mock(PlatformAlertEventMapper.class);
        allocs = mock(PlatformTenantNamespaceMapper.class);
        auth = mock(AuthContext.class);
        svc = new AlertService(mapper, allocs, auth);
    }

    private static AlertQueryRequest req() {
        AlertQueryRequest r = new AlertQueryRequest();
        r.setClusterId("c1");
        return r;
    }

    private static PlatformTenantNamespace alloc(String tenantId, String ns) {
        PlatformTenantNamespace r = new PlatformTenantNamespace();
        r.setTenantId(tenantId);
        r.setNamespace(ns);
        return r;
    }

    /** 让 authContext.current() 返回一个带 tenantInfo 的身份。 */
    private void asTenant(String tenantId) {
        var info = mock(com.coding.data.models.system.TokenUserInfo.class);
        UserTenantInfo ti = new UserTenantInfo();
        ti.setTenantId(tenantId);
        when(info.getTenantInfo()).thenReturn(ti);
        when(auth.current()).thenReturn(info);
    }

    /** 让 authContext.current() 返回一个**不带** tenantInfo 的平台身份。 */
    private void asPlatformAdmin() {
        var info = mock(com.coding.data.models.system.TokenUserInfo.class);
        when(info.getTenantInfo()).thenReturn(null);
        when(auth.current()).thenReturn(info);
    }

    /**
     * ⚠️ token 带 tenantInfo 时，**入参 tenantId 一律被覆盖**，
     * 只能看到本租户在本集群的命名空间里的告警（同 NamespaceAllocationService.list 的口径）。
     */
    @Test
    void a_tenant_token_can_only_see_its_own_namespaces() {
        asTenant("t1");
        when(allocs.listByTenantAndCluster("t1", "c1"))
                .thenReturn(List.of(alloc("t1", "ns-a"), alloc("t1", "ns-b")));

        AlertQueryRequest r = req();
        r.setNamespace("someone-elses-ns");     // ← 入参想越界，应被无视
        svc.list(r);

        PlatformAlertEventQuery q = capturedQuery();
        assertThat(q.getNamespaces()).containsExactlyInAnyOrder("ns-a", "ns-b");
    }

    /** 平台管理员（无租户 claim）→ 不过滤，看全部（含不属于任何租户的）。 */
    @Test
    void an_admin_token_sees_everything() {
        asPlatformAdmin();

        svc.list(req());

        assertThat(capturedQuery().getNamespaces()).isNull();   // null = 不过滤
    }

    /**
     * ⚠️ **本批最危险的一条**：租户在本集群**一个命名空间都没分配** → 必须返回空，
     * **绝不能退化成"不过滤"**（那会让它看到全平台的告警）。
     */
    @Test
    void a_tenant_with_no_allocations_sees_nothing_not_everything() {
        asTenant("t1");
        when(allocs.listByTenantAndCluster("t1", "c1")).thenReturn(List.of());

        svc.list(req());

        assertThat(capturedQuery().getNamespaces()).isEmpty();  // 空 list，**不是** null
    }

    /** 分配表里有重复/空白项时要去重去空（否则拼进 in (...) 会带上 NULL）。 */
    @Test
    void duplicate_and_blank_namespaces_are_collapsed() {
        asTenant("t1");
        when(allocs.listByTenantAndCluster("t1", "c1")).thenReturn(List.of(
                alloc("t1", "ns-a"), alloc("t1", "ns-a"), alloc("t1", "  ")));

        svc.list(req());

        assertThat(capturedQuery().getNamespaces()).containsExactly("ns-a");
    }

    /** 一个命名空间分给两个租户 → 各自都查得到（所以归属不能定死一个 tenantId）。 */
    @Test
    void a_shared_namespace_is_visible_to_both_tenants() {
        asTenant("t2");
        // ns-shared 同时分给 t1 和 t2 —— mapper 返回两行，但本层的查询条件是"t2 自己的 ns 列表"，
        // 所以 t2 照样查得到它。
        when(allocs.listByTenantAndCluster("t2", "c1")).thenReturn(List.of(alloc("t2", "ns-shared")));

        svc.list(req());

        assertThat(capturedQuery().getNamespaces()).containsExactly("ns-shared");
    }

    /** limit 由本层钳制：0/null → 50，> 200 → 200。 */
    @Test
    void the_limit_is_clamped_in_this_layer() {
        asPlatformAdmin();

        svc.list(req());
        assertThat(capturedQuery(1).getLimit()).isEqualTo(50);

        AlertQueryRequest big = req();
        big.setLimit(9999);
        svc.list(big);
        assertThat(capturedQuery(2).getLimit()).isEqualTo(200);

        AlertQueryRequest zero = req();
        zero.setLimit(0);
        svc.list(zero);
        assertThat(capturedQuery(3).getLimit()).isEqualTo(50);
    }

    /** Watchdog 默认被排除；显式要求时能看到。 */
    @Test
    void watchdog_is_excluded_by_default_and_visible_on_request() {
        asPlatformAdmin();

        svc.list(req());
        assertThat(capturedQuery(1).getExcludeWatchdog()).isTrue();   // 默认排除

        AlertQueryRequest show = req();
        show.setShowWatchdog(true);
        svc.list(show);
        assertThat(capturedQuery(2).getExcludeWatchdog()).isFalse();
    }

    private PlatformAlertEventQuery capturedQuery() {
        ArgumentCaptor<PlatformAlertEventQuery> c = ArgumentCaptor.forClass(PlatformAlertEventQuery.class);
        verify(mapper, atLeastOnce()).query(c.capture());
        List<PlatformAlertEventQuery> all = c.getAllValues();
        return all.get(all.size() - 1);
    }

    /** 第 n 次（1 起）调用传给 mapper 的条件 —— 一个用例里连续调多次时用。 */
    private PlatformAlertEventQuery capturedQuery(int n) {
        ArgumentCaptor<PlatformAlertEventQuery> c = ArgumentCaptor.forClass(PlatformAlertEventQuery.class);
        verify(mapper, org.mockito.Mockito.times(n)).query(c.capture());
        return c.getAllValues().get(n - 1);
    }
}
