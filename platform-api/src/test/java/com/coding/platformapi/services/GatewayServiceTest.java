package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.GatewayDTO;
import com.coding.common.models.k8s.dto.WaypointRefDTO;
import com.coding.platformapi.k8s.K8sClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GatewayService 的 waypoint 判定与类型投影（mock K8sClient，不触网）。
 *
 * <p>铁律四条：
 * <ol>
 *   <li><b>没有数量上限</b> —— 2026-10-09 起取消「每 ns 至多一个」：create/update 是纯透传，
 *       <b>不查兄弟列表</b>（既省一次调用，也去掉了"list 失败就保守拒绝"那条拖累可用性的路径）。</li>
 *   <li><b>判定口径 = gatewayClassName 含 {@code -waypoint}</b>，与 {@code istio.io/waypoint-for} 无关。</li>
 *   <li><b>类型缺省即 {@code service}</b>（官方默认值）—— 外部建的、不带 label 的 waypoint 必须按
 *       service 处理，否则会把"只处理服务流量"的 waypoint 当成能给 Pod 级用的。</li>
 *   <li><b>能力判定用白名单</b>：不认识的取值一律"不能"（宁可少给候选，也不给一个必然静默不生效的选项）。</li>
 * </ol>
 */
class GatewayServiceTest {

    private final K8sClient k8s = mock(K8sClient.class);
    private final GatewayService svc = new GatewayService(k8s);

    private static GatewayDTO gateway(String name, String gatewayClassName) {
        GatewayDTO g = new GatewayDTO();
        g.setName(name);
        g.setNamespace("team-a");
        g.setClusterId("c1");
        g.setGatewayClassName(gatewayClassName);
        return g;
    }

    private static GatewayDTO waypoint(String name, String waypointFor) {
        GatewayDTO g = gateway(name, "istio-waypoint");
        if (waypointFor != null) {
            g.setLabels(Map.of(GatewayService.WAYPOINT_FOR_LABEL, waypointFor));
        }
        return g;
    }

    // ---------- 没有数量上限：create/update 纯透传 ----------

    @Test
    void create_is_a_pure_passthrough_without_querying_siblings() {
        // 同名空间已有 waypoint 也照建（不设上限）；无论如何都不该多发一次 list
        GatewayDTO body = gateway("waypoint-2", "istio-waypoint");
        when(k8s.create(any(GatewayDTO.class))).thenReturn(body);

        assertThat(svc.create(body)).isSameAs(body);

        verify(k8s).create(body);
        verify(k8s, never()).list(any(GatewayDTO.class));
    }

    @Test
    void update_is_a_pure_passthrough_for_waypoint_and_plain_alike() {
        GatewayDTO wp = gateway("waypoint", "istio-waypoint");
        GatewayDTO plain = gateway("web-gw", "istio");
        when(k8s.update(any(GatewayDTO.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(svc.update(wp)).isSameAs(wp);
        assertThat(svc.update(plain)).isSameAs(plain);

        verify(k8s, never()).list(any(GatewayDTO.class));
    }

    // ---------- 类别判定（口径见 GatewayService 类注释）----------

    @Test
    void waypoint_classification_covers_istio_constants_derived_names_and_rejects_the_rest() {
        // 命中：Istio 的两个源码常量 + 名字后面再挂后缀的派生命名（用 endsWith 会漏掉后两类）
        assertThat(GatewayService.isWaypointGateway(gateway("w", "istio-waypoint"))).isTrue();
        assertThat(GatewayService.isWaypointGateway(gateway("w", "istio-agentgateway-waypoint"))).isTrue();
        assertThat(GatewayService.isWaypointGateway(gateway("w", "istio-waypoint-1-20-0"))).isTrue();
        assertThat(GatewayService.isWaypointGateway(gateway("w", "my-waypoint"))).isTrue();

        // 不命中：普通网关类（含同族的 east-west / remote / mesh）
        assertThat(GatewayService.isWaypointGateway(gateway("w", "istio"))).isFalse();
        assertThat(GatewayService.isWaypointGateway(gateway("w", "istio-east-west"))).isFalse();
        assertThat(GatewayService.isWaypointGateway(gateway("w", "istio-remote"))).isFalse();
        assertThat(GatewayService.isWaypointGateway(gateway("w", "envoy-gateway"))).isFalse();
        assertThat(GatewayService.isWaypointGateway(gateway("w", ""))).isFalse();
        assertThat(GatewayService.isWaypointGateway(gateway("w", null))).isFalse();
        assertThat(GatewayService.isWaypointGateway(null)).isFalse();
    }

    @Test
    void ambient_traffic_label_is_not_a_waypoint_marker() {
        // istio.io/waypoint-for 声明"处理哪类流量"，不是"是不是 waypoint" ——
        // 带这个 label 但类名是普通网关的，不算 waypoint；不带 label 的 istio-waypoint 才算。
        GatewayDTO labelledButPlain = gateway("w", "istio");
        labelledButPlain.setLabels(Map.of(GatewayService.WAYPOINT_FOR_LABEL, "service"));
        assertThat(GatewayService.isWaypointGateway(labelledButPlain)).isFalse();

        GatewayDTO unlabelledWaypoint = gateway("w", "istio-waypoint");
        assertThat(GatewayService.isWaypointGateway(unlabelledWaypoint)).isTrue();
    }

    // ---------- 类型投影 ----------

    @Test
    void waypoint_for_defaults_to_service_and_normalizes_blanks() {
        // 官方："This label is optional and the default value is service."
        assertThat(GatewayService.waypointForOf(waypoint("w", null))).isEqualTo("service");
        assertThat(GatewayService.waypointForOf(waypoint("w", "  "))).isEqualTo("service");
        assertThat(GatewayService.waypointForOf(waypoint("w", " workload "))).isEqualTo("workload");
        assertThat(GatewayService.waypointForOf(waypoint("w", "all"))).isEqualTo("all");
        assertThat(GatewayService.waypointForOf(waypoint("w", "none"))).isEqualTo("none");
        assertThat(GatewayService.waypointForOf(gateway("w", "istio"))).isEqualTo("service"); // 无 label
        assertThat(GatewayService.waypointForOf(null)).isEqualTo("service");
    }

    @Test
    void capability_whitelists_only_accept_known_combinations() {
        assertThat(GatewayService.canHandleService("service")).isTrue();
        assertThat(GatewayService.canHandleService("all")).isTrue();
        assertThat(GatewayService.canHandleService("workload")).isFalse();
        assertThat(GatewayService.canHandleService("none")).isFalse();
        assertThat(GatewayService.canHandleService("sidecar")).isFalse(); // 不认识的值 → 不能
        assertThat(GatewayService.canHandleService(null)).isFalse();

        assertThat(GatewayService.canHandleWorkload("workload")).isTrue();
        assertThat(GatewayService.canHandleWorkload("all")).isTrue();
        assertThat(GatewayService.canHandleWorkload("service")).isFalse();
        assertThat(GatewayService.canHandleWorkload("none")).isFalse();
        assertThat(GatewayService.canHandleWorkload(null)).isFalse();
    }

    @Test
    void waypoint_refs_projection_filters_drops_blanks_sorts_and_carries_the_type() {
        // 判定与投影的唯一实现（租户侧 /gateways 与平台侧 /mesh/gateways 两条数据源共用）：
        // 只留 waypoint、按名升序、空名剔除、名字与类型成对
        GatewayDTO plain = gateway("web-gw", "istio");
        GatewayDTO blankName = gateway("  ", "istio-waypoint");

        List<WaypointRefDTO> refs = GatewayService.waypointRefsOf(List.of(
                waypoint("wp-b", "workload"),
                plain,
                waypoint("wp-a", null),            // 缺省 → service
                blankName));

        assertThat(refs).hasSize(2);
        assertThat(refs.get(0).getName()).isEqualTo("wp-a");
        assertThat(refs.get(0).getWaypointFor()).isEqualTo("service");
        assertThat(refs.get(1).getName()).isEqualTo("wp-b");
        assertThat(refs.get(1).getWaypointFor()).isEqualTo("workload");

        assertThat(GatewayService.waypointRefsOf(null)).isEmpty();
        assertThat(GatewayService.waypointRefsOf(List.of(plain))).isEmpty();
        // 不设上限：三个 waypoint 全部保留
        assertThat(GatewayService.waypointRefsOf(List.of(
                waypoint("w1", "service"), waypoint("w2", "service"), waypoint("w3", "workload")))).hasSize(3);
    }

    @Test
    void waypoint_refs_queries_the_boundary_triple() {
        when(k8s.list(any(GatewayDTO.class))).thenReturn(List.of(waypoint("wp", "all")));

        var captor = org.mockito.ArgumentCaptor.forClass(GatewayDTO.class);
        assertThat(svc.waypointRefs("t1", "c1", "team-a")).hasSize(1);
        verify(k8s).list(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("t1");
        assertThat(captor.getValue().getClusterId()).isEqualTo("c1");
        assertThat(captor.getValue().getNamespace()).isEqualTo("team-a");
        assertThat(captor.getValue().getGatewayClassName()).isNull(); // 表单字段不得混进查询条件
    }

}
