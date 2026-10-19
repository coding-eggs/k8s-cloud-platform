package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.metrics.PromRulesClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PrometheusRuleRuntimeEnrichTest {

    private K8sClient k8s;
    private K8sClusterMapper clusters;
    private PromRulesClient prom;
    private PrometheusRuleService svc;

    @BeforeEach
    void setUp() {
        k8s = mock(K8sClient.class);
        clusters = mock(K8sClusterMapper.class);
        prom = mock(PromRulesClient.class);
        svc = new PrometheusRuleService(k8s, clusters, prom, mock(RuleNotifyBinder.class));
    }

    /** list 的查询条件——clusterId 是探测的开关（控制器从 body 里拿到它）。 */
    private static PrometheusRuleDTO query() {
        PrometheusRuleDTO q = new PrometheusRuleDTO();
        q.setClusterId("c1");
        return q;
    }

    private static PrometheusRuleDTO rule(String ns, String name) {
        PrometheusRuleDTO dto = new PrometheusRuleDTO();
        dto.setNamespace(ns);
        dto.setName(name);
        dto.setClusterId("c1");
        // 建得很早 → 过了宽限期，missing 判定会真的生效
        dto.setCreationTime(java.time.Instant.now().minus(java.time.Duration.ofHours(1)).toString());
        PrometheusRuleDTO.Group g = new PrometheusRuleDTO.Group();
        g.setName("app");
        PrometheusRuleDTO.Rule r = new PrometheusRuleDTO.Rule();
        r.setAlert("HighCPU");
        r.setExpr("up == 0");
        g.setRules(List.of(r));
        dto.setGroups(List.of(g));
        return dto;
    }

    /** create/update 用的合法对象（走 Task 6 的前置校验）。 */
    private static PrometheusRuleDTO sampleValid() {
        return rule("ns1", "r1");
    }

    private static K8sCluster clusterWith(String prometheusUrl) {
        K8sCluster c = new K8sCluster();
        c.setClusterId("c1");
        c.setPrometheusUrl(prometheusUrl);
        return c;
    }

    /** Prometheus 不可达 → runtime = null，CRUD 照常，**不抛**。 */
    @Test
    void an_unreachable_prometheus_leaves_runtime_null_and_does_not_throw() {
        when(k8s.list(any())).thenReturn(List.of(rule("ns1", "r1")));
        when(clusters.selectByPrimaryKey(anyString())).thenReturn(clusterWith("http://127.0.0.1:1"));
        when(prom.groups(anyString())).thenThrow(new RuntimeException("connection refused"));

        List<PrometheusRuleDTO> out = svc.list(query());

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getRuntime()).isNull();
    }

    /** 没配 prometheusUrl → 不探测，也不报错（与 ServiceMonitorService 同口径）。 */
    @Test
    void a_blank_prometheus_url_skips_the_probe_entirely() {
        when(k8s.list(any())).thenReturn(List.of(rule("ns1", "r1")));
        when(clusters.selectByPrimaryKey(anyString())).thenReturn(clusterWith(null));

        List<PrometheusRuleDTO> out = svc.list(query());

        assertThat(out.get(0).getRuntime()).isNull();
        verify(prom, never()).groups(anyString());
    }

    /** 探到 → 每条都附上 runtime。 */
    @Test
    void a_successful_probe_attaches_runtime_to_every_rule() {
        when(k8s.list(any())).thenReturn(List.of(rule("ns1", "r1"), rule("ns2", "r2")));
        when(clusters.selectByPrimaryKey(anyString())).thenReturn(clusterWith("http://p:9090"));
        when(prom.groups("http://p:9090")).thenReturn(List.of(
                new PromRulesClient.RuleGroup("app", "/x/ns1-r1-abc.yaml",
                        List.of(new PromRulesClient.RuleView("HighCPU", "ok", null, "firing", 2))),
                new PromRulesClient.RuleGroup("app", "/x/ns2-r2-def.yaml",
                        List.of(new PromRulesClient.RuleView("HighCPU", "ok", null, "inactive", 0)))));

        List<PrometheusRuleDTO> out = svc.list(query());

        assertThat(out.get(0).getRuntime().getLoadState()).isEqualTo("loaded");
        assertThat(out.get(0).getRuntime().getRules().get(0).getState()).isEqualTo("firing");
        assertThat(out.get(1).getRuntime().getRules().get(0).getState()).isEqualTo("inactive");
    }

    /** 末位斜杠要被削掉，否则拼出 `http://p:9090//api/v1/rules`（有些反代会 301，有些直接 404）。 */
    @Test
    void a_trailing_slash_in_the_url_is_stripped() {
        when(k8s.list(any())).thenReturn(List.of(rule("ns1", "r1")));
        when(clusters.selectByPrimaryKey(anyString())).thenReturn(clusterWith("http://p:9090///"));
        when(prom.groups(anyString())).thenReturn(List.of());

        svc.list(query());

        verify(prom).groups("http://p:9090");
    }

    /** ⚠️ 一次 list 只打 Prometheus **一次**（254KB 的响应，别每条规则拉一遍）。 */
    @Test
    void the_probe_runs_once_per_list_not_once_per_rule() {
        when(k8s.list(any())).thenReturn(List.of(rule("ns1", "r1"), rule("ns1", "r2"), rule("ns2", "r3")));
        when(clusters.selectByPrimaryKey(anyString())).thenReturn(clusterWith("http://p:9090"));
        when(prom.groups(anyString())).thenReturn(List.of());

        svc.list(query());

        verify(prom, times(1)).groups("http://p:9090");
    }

    /** get 也附着。 */
    @Test
    void get_also_attaches_runtime() {
        when(k8s.get(any())).thenReturn(rule("ns1", "r1"));
        when(clusters.selectByPrimaryKey(anyString())).thenReturn(clusterWith("http://p:9090"));
        when(prom.groups("http://p:9090")).thenReturn(List.of(
                new PromRulesClient.RuleGroup("app", "/x/ns1-r1-abc.yaml",
                        List.of(new PromRulesClient.RuleView("HighCPU", "err", "parse error", null, null)))));

        PrometheusRuleDTO out = svc.get("r1", "t1", "c1", "ns1");

        assertThat(out.getRuntime().getRules().get(0).getHealth()).isEqualTo("err");
        assertThat(out.getRuntime().getRules().get(0).getLastError()).isEqualTo("parse error");
    }

    /**
     * ⚠️ 生产路径上的歧义守卫：service 逐条 match（宽限期要看各自的 creationTime），
     * 所以歧义必须先在**整个列表**上算一次 —— 否则两份同前缀的 CR 会各自认领同一个文件。
     */
    @Test
    void two_crs_whose_file_prefixes_collide_are_both_unknown() {
        when(k8s.list(any())).thenReturn(List.of(rule("a", "b-c"), rule("a-b", "c")));
        when(clusters.selectByPrimaryKey(anyString())).thenReturn(clusterWith("http://p:9090"));
        when(prom.groups(anyString())).thenReturn(List.of(
                new PromRulesClient.RuleGroup("app", "/x/a-b-c-uuid.yaml",
                        List.of(new PromRulesClient.RuleView("HighCPU", "ok", null, "firing", 1)))));

        List<PrometheusRuleDTO> out = svc.list(query());

        assertThat(out).allSatisfy(r -> assertThat(r.getRuntime().getLoadState()).isEqualTo("unknown"));
        assertThat(out.get(0).getRuntime().getRules()).isEmpty();
    }

    /** 对象不存在时不能因为探测而 NPE（get 返回 null 是既有语义）。 */
    @Test
    void a_missing_object_returns_null_without_probing() {
        when(k8s.get(any())).thenReturn(null);

        assertThat(svc.get("nope", "t1", "c1", "ns1")).isNull();
        verifyNoInteractions(prom);
    }

    /** create / update 返回的对象不带 runtime（探测只在读路径上）。 */
    @Test
    void writes_do_not_probe() {
        when(k8s.create(any())).thenAnswer(i -> i.getArgument(0));

        PrometheusRuleDTO out = svc.create(sampleValid());

        assertThat(out.getRuntime()).isNull();
        verify(prom, never()).groups(anyString());
    }
}
