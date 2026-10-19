package com.coding.platformapi.notify;

import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.data.models.k8s.PlatformAlertEvent;
import com.coding.platformapi.metrics.PromRulesClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertNotifyContextServiceTest {

    private K8sClusterMapper clusters;
    private PlatformTenantMapper tenants;
    private PlatformTenantNamespaceMapper allocs;
    private PromRulesClient promRules;
    private AlertNotifyContextService svc;

    @BeforeEach
    void setUp() {
        clusters = mock(K8sClusterMapper.class);
        tenants = mock(PlatformTenantMapper.class);
        allocs = mock(PlatformTenantNamespaceMapper.class);
        promRules = mock(PromRulesClient.class);
        svc = new AlertNotifyContextService(clusters, tenants, allocs, promRules);
    }

    private static PlatformAlertEvent event(long id, String ns, String alertname) {
        PlatformAlertEvent e = new PlatformAlertEvent();
        e.setId(id);
        e.setClusterId("c1");
        e.setNamespace(ns);
        e.setAlertname(alertname);
        return e;
    }

    private void cluster(String name, String prometheusUrl) {
        K8sCluster c = new K8sCluster();
        c.setClusterName(name);
        c.setPrometheusUrl(prometheusUrl);
        when(clusters.selectByPrimaryKey("c1")).thenReturn(c);
    }

    private void allocation(String ns, String tenantId) {
        PlatformTenantNamespace a = new PlatformTenantNamespace();
        a.setNamespace(ns);
        a.setTenantId(tenantId);
        when(allocs.listByCluster("c1")).thenReturn(List.of(a));
    }

    /** 命名空间 → 分配表 → 租户显示名（邮箱正文与详情页都要「哪个租户」）。 */
    @Test
    void ownership_resolves_the_tenant_name_from_the_namespace() {
        cluster("coding-cluster", null);
        allocation("monitor", "t1");
        PlatformTenant t = new PlatformTenant();
        t.setName("平台默认租户");
        when(tenants.selectByPrimaryKey("t1")).thenReturn(t);

        NotifyContext ctx = svc.ownership("c1", List.of(event(1, "monitor", "HighCPU")));

        assertThat(ctx.clusterName()).isEqualTo("coding-cluster");
        assertThat(ctx.tenantId(1L)).isEqualTo("t1");
        assertThat(ctx.tenantName(1L)).isEqualTo("平台默认租户");
    }

    /** 没分配给任何租户的命名空间 → 不编一个租户出来（详情页显示「不属于任何租户」）。 */
    @Test
    void an_unallocated_namespace_has_no_tenant() {
        cluster("coding-cluster", null);
        when(allocs.listByCluster("c1")).thenReturn(List.of());

        NotifyContext ctx = svc.ownership("c1", List.of(event(1, "orphan", "HighCPU")));

        assertThat(ctx.tenantId(1L)).isNull();
        assertThat(ctx.tenantName(1L)).isNull();
    }

    /** 有 prometheusUrl → 一次调用拿到整批的表达式。 */
    @Test
    void build_fills_the_expression_per_event() {
        cluster("coding-cluster", "http://prom:9090");
        allocation("monitor", "t1");
        when(promRules.alertExpressions("http://prom:9090"))
                .thenReturn(Map.of("HighCPU", "rate(x[5m]) > 1"));

        NotifyContext ctx = svc.build("c1", List.of(event(1, "monitor", "HighCPU")));

        assertThat(ctx.expression(1L)).isEqualTo("rate(x[5m]) > 1");
    }

    /** 集群没配 prometheusUrl → 不打 Prometheus，表达式留空。 */
    @Test
    void a_cluster_without_prometheus_url_makes_no_call() {
        cluster("coding-cluster", "  ");

        NotifyContext ctx = svc.build("c1", List.of(event(1, "monitor", "HighCPU")));

        assertThat(ctx.expression(1L)).isNull();
        verify(promRules, never()).alertExpressions(anyString());
    }

    /** Prometheus 挂了 → 正文少一行表达式，**不影响投递**（不能抛）。 */
    @Test
    void a_failing_prometheus_leaves_expressions_empty() {
        cluster("coding-cluster", "http://prom:9090");
        when(promRules.alertExpressions(anyString()))
                .thenThrow(new RuntimeException("prom down"));

        NotifyContext ctx = svc.build("c1", List.of(event(1, "monitor", "HighCPU")));

        assertThat(ctx.expressions()).isEmpty();
        assertThat(ctx.clusterName()).isEqualTo("coding-cluster");
    }

    /** 读集群/分配失败也不抛 —— 页面与邮件少几行而已。 */
    @Test
    void infrastructure_failures_degrade_instead_of_throwing() {
        when(clusters.selectByPrimaryKey("c1")).thenThrow(new RuntimeException("db down"));
        when(allocs.listByCluster("c1")).thenThrow(new RuntimeException("db down"));

        NotifyContext ctx = svc.ownership("c1", List.of(event(1, "monitor", "HighCPU")));

        assertThat(ctx.clusterName()).isNull();
        assertThat(ctx.tenantIds()).isEmpty();
    }

    /** 空批次 → 什么都不查。 */
    @Test
    void an_empty_batch_is_a_no_op() {
        NotifyContext ctx = svc.ownership("c1", List.of());

        assertThat(ctx).isEqualTo(NotifyContext.EMPTY);
        verify(clusters, never()).selectByPrimaryKey(any());
    }
}
