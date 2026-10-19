package com.coding.platformapi.services;

import com.coding.data.models.system.UserTenantInfo;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.PlatformAlertEventMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.PlatformAlertEventQuery;
import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import com.coding.data.models.k8s.AlertSource;
import com.coding.data.models.k8s.PlatformAlertEvent;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.models.AlertQueryRequest;
import com.coding.platformapi.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private K8sClient k8s;
    private com.coding.data.mapper.alert.NotificationChannelMapper channels;
    private com.coding.data.mapper.alert.AlertDeliveryMapper deliveries;
    private com.coding.platformapi.notify.AlertNotifyContextService notifyContext;
    private AlertService svc;

    @BeforeEach
    void setUp() {
        mapper = mock(PlatformAlertEventMapper.class);
        allocs = mock(PlatformTenantNamespaceMapper.class);
        auth = mock(AuthContext.class);
        k8s = mock(K8sClient.class);
        channels = mock(com.coding.data.mapper.alert.NotificationChannelMapper.class);
        deliveries = mock(com.coding.data.mapper.alert.AlertDeliveryMapper.class);
        notifyContext = mock(com.coding.platformapi.notify.AlertNotifyContextService.class);
        when(notifyContext.ownership(any(), any()))
                .thenReturn(com.coding.platformapi.notify.NotifyContext.EMPTY);
        svc = new AlertService(mapper, allocs, auth, new RuleSourceResolver(k8s), channels, deliveries,
                notifyContext, org.springframework.web.client.RestClient.builder(), "t", 8081);
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

    // ---------- 来源反查（告警 → PrometheusRule/组） ----------

    private static PlatformAlertEvent event(String ns, String alertname) {
        PlatformAlertEvent e = new PlatformAlertEvent();
        e.setClusterId("c1");
        e.setNamespace(ns);
        e.setAlertname(alertname);
        return e;
    }

    private static PrometheusRuleDTO cr(String ns, String name, String group, String alert) {
        PrometheusRuleDTO dto = new PrometheusRuleDTO();
        dto.setNamespace(ns);
        dto.setName(name);
        PrometheusRuleDTO.Rule r = new PrometheusRuleDTO.Rule();
        r.setAlert(alert);
        r.setExpr("up == 0");
        PrometheusRuleDTO.Group g = new PrometheusRuleDTO.Group();
        g.setName(group);
        g.setRules(List.of(r));
        dto.setGroups(List.of(g));
        return dto;
    }

    /** 租户：按结果里的 namespace 拉规则，反查出 CR/组。 */
    @Test
    void tenant_path_resolves_the_source_rule_via_namespace_list() {
        asTenant("t1");
        when(allocs.listByTenantAndCluster("t1", "c1")).thenReturn(List.of(alloc("t1", "mon-app")));
        when(mapper.query(any())).thenReturn(List.of(event("mon-app", "HighCPU")));
        doReturn(List.of(cr("mon-app", "app-rules", "app", "HighCPU"))).when(k8s).list(any());

        List<PlatformAlertEvent> out = svc.list(req());

        assertThat(out.get(0).getSources())
                .containsExactly(new AlertSource("app-rules", "app"));
        verify(k8s).list(any());
        verify(k8s, never()).listAll(any());
    }

    /** 管理员（无租户 claim）：一次 listAll，不过滤命名空间。 */
    @Test
    void admin_path_resolves_the_source_rule_via_list_all() {
        asPlatformAdmin();
        when(mapper.query(any())).thenReturn(List.of(event("kube-system", "NodeDown")));
        doReturn(List.of(cr("kube-system", "node-rules", "node", "NodeDown"))).when(k8s).listAll(any());

        List<PlatformAlertEvent> out = svc.list(req());

        assertThat(out.get(0).getSources()).containsExactly(new AlertSource("node-rules", "node"));
        verify(k8s).listAll(any());
        verify(k8s, never()).list(any());
    }

    /** 反查失败只降级为空来源 —— 列表照常返回。 */
    @Test
    void a_failed_source_lookup_leaves_sources_empty_without_breaking_the_list() {
        asTenant("t1");
        when(allocs.listByTenantAndCluster("t1", "c1")).thenReturn(List.of(alloc("t1", "mon-app")));
        when(mapper.query(any())).thenReturn(List.of(event("mon-app", "HighCPU")));
        doThrow(new RuntimeException("k8s down")).when(k8s).list(any());

        List<PlatformAlertEvent> out = svc.list(req());

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getSources()).isNull();   // 没附着
    }

    // ---------- 通知方式（labels.platform_notify → 渠道显示名） ----------

    /** 能找到渠道 → 回显示名。 */
    @Test
    void notify_channel_name_is_filled_from_the_label() {
        asPlatformAdmin();
        PlatformAlertEvent e = event("mon-app", "HighCPU");
        e.setLabels("{\"platform_notify\":\"ops-mail\"}");
        when(mapper.query(any())).thenReturn(List.of(e));
        com.coding.data.models.alert.NotificationChannel c =
                new com.coding.data.models.alert.NotificationChannel();
        c.setCode("ops-mail");
        c.setName("运维邮件");
        when(channels.selectByCode("ops-mail")).thenReturn(c);

        svc.list(req());

        assertThat(e.getNotifyChannelName()).isEqualTo("运维邮件");
    }

    /** 渠道已被删 → 回填 code 本身（「配置坏了」要看得见，而不是显示成走默认）。 */
    @Test
    void an_unknown_channel_falls_back_to_the_code_itself() {
        asPlatformAdmin();
        PlatformAlertEvent e = event("mon-app", "HighCPU");
        e.setLabels("{\"platform_notify\":\"gone-mail\"}");
        when(mapper.query(any())).thenReturn(List.of(e));
        when(channels.selectByCode("gone-mail")).thenReturn(null);

        svc.list(req());

        assertThat(e.getNotifyChannelName()).isEqualTo("gone-mail");
    }

    /** 没有 label → 保持 null（前端显示「默认」），且不去查库。 */
    @Test
    void no_label_means_the_default_channel() {
        asPlatformAdmin();
        PlatformAlertEvent e = event("mon-app", "HighCPU");
        e.setLabels("{\"severity\":\"critical\"}");
        when(mapper.query(any())).thenReturn(List.of(e));

        svc.list(req());

        assertThat(e.getNotifyChannelName()).isNull();
        verify(channels, never()).selectByCode(any());
    }

    // ---------- 投递状态 ----------

    /** 回填最新一次投递的状态（SQL 已按 id desc，第一条就是最新）。 */
    @Test
    void delivery_status_is_filled_from_the_latest_delivery_row() {
        asPlatformAdmin();
        PlatformAlertEvent e = event("mon-app", "HighCPU");
        e.setId(7L);
        when(mapper.query(any())).thenReturn(List.of(e));
        com.coding.data.models.alert.AlertDelivery d = new com.coding.data.models.alert.AlertDelivery();
        d.setEventId(7L);
        d.setStatus("FAILED");
        d.setLastError("smtp down");
        when(deliveries.selectLatestByEventIds(List.of(7L))).thenReturn(List.of(d));

        svc.list(req());

        assertThat(e.getDeliveryStatus()).isEqualTo("FAILED");
        assertThat(e.getDeliveryError()).isEqualTo("smtp down");
    }

    /** 没有投递记录 → 两个字段保持 null（前端显示「—」）。 */
    @Test
    void no_delivery_row_leaves_the_status_null() {        asPlatformAdmin();
        PlatformAlertEvent e = event("mon-app", "HighCPU");
        e.setId(8L);
        when(mapper.query(any())).thenReturn(List.of(e));
        when(deliveries.selectLatestByEventIds(List.of(8L))).thenReturn(List.of());

        svc.list(req());

        assertThat(e.getDeliveryStatus()).isNull();
    }

    /** 列表回填「集群名 / 租户名」（告警详情页要回答「在哪、是谁的」）。 */
    @Test
    void ownership_names_are_filled_on_each_row() {
        asPlatformAdmin();
        PlatformAlertEvent e = event("mon-app", "HighCPU");
        e.setId(9L);
        when(mapper.query(any())).thenReturn(List.of(e));
        when(notifyContext.ownership(any(), any())).thenReturn(
                new com.coding.platformapi.notify.NotifyContext("coding-cluster",
                        java.util.Map.of(9L, "t1"),
                        java.util.Map.of(9L, "平台默认租户"),
                        java.util.Map.of()));

        svc.list(req());

        assertThat(e.getClusterName()).isEqualTo("coding-cluster");
        assertThat(e.getTenantName()).isEqualTo("平台默认租户");
    }

    private PlatformAlertEventQuery capturedQuery() {        ArgumentCaptor<PlatformAlertEventQuery> c = ArgumentCaptor.forClass(PlatformAlertEventQuery.class);
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
