package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.GatewayDTO;
import com.coding.platformapi.k8s.K8sClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GatewayService 的 waypoint per-ns 唯一性校验（Phase 3，mock K8sClient，不触网）。
 *
 * <p>铁律三条：
 * <ol>
 *   <li>普通 Gateway 不占名额 —— 连 list 都不发（否则每个 Gateway 创建都多一次无谓调用）。</li>
 *   <li>同命名空间已有一个 waypoint → 第二个被拒（创建与"改成 waypoint"都拒）；编辑它自己不拒。</li>
 *   <li>判定所需的 list 失败 → <b>保守拒绝</b>（无法确认就不能放行一个可能破坏不变式的写入）。</li>
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

    @Test
    void create_plain_gateway_does_not_even_query_siblings() {
        GatewayDTO body = gateway("web-gw", "istio");
        when(k8s.create(any(GatewayDTO.class))).thenReturn(body);

        assertThat(svc.create(body)).isSameAs(body);

        verify(k8s, never()).list(any(GatewayDTO.class)); // 普通 Gateway 不占名额 → 零额外开销
    }

    @Test
    void create_first_waypoint_in_namespace_is_allowed() {
        GatewayDTO body = gateway("waypoint", "istio-waypoint");
        when(k8s.list(any(GatewayDTO.class))).thenReturn(List.of());
        when(k8s.create(any(GatewayDTO.class))).thenReturn(body);

        assertThat(svc.create(body)).isSameAs(body);

        verify(k8s).create(body);
    }

    @Test
    void create_waypoint_allowed_when_only_plain_gateways_exist() {
        GatewayDTO body = gateway("waypoint", "istio-waypoint");
        when(k8s.list(any(GatewayDTO.class))).thenReturn(List.of(gateway("web-gw", "istio"), gateway("api-gw", "istio")));
        when(k8s.create(any(GatewayDTO.class))).thenReturn(body);

        assertThat(svc.create(body)).isSameAs(body);
    }

    @Test
    void create_second_waypoint_is_rejected() {
        GatewayDTO body = gateway("waypoint-2", "istio-waypoint");
        when(k8s.list(any(GatewayDTO.class))).thenReturn(List.of(gateway("waypoint", "istio-waypoint")));

        assertThatThrownBy(() -> svc.create(body))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("waypoint")
                .hasMessageContaining("已存在 waypoint Gateway「waypoint」")
                .hasMessageContaining("team-a");

        verify(k8s, never()).create(any(GatewayDTO.class));
    }

    @Test
    void update_waypoint_itself_is_not_blocked_by_itself() {
        GatewayDTO body = gateway("waypoint", "istio-waypoint");
        // 同命名空间列表里含自己 —— 自我排除后无冲突
        when(k8s.list(any(GatewayDTO.class))).thenReturn(List.of(body));
        when(k8s.update(any(GatewayDTO.class))).thenReturn(body);

        assertThat(svc.update(body)).isSameAs(body);
    }

    @Test
    void update_plain_gateway_into_waypoint_is_rejected_when_another_exists() {
        GatewayDTO body = gateway("web-gw", "istio-waypoint"); // 把普通网关改成 waypoint
        when(k8s.list(any(GatewayDTO.class))).thenReturn(List.of(gateway("waypoint", "istio-waypoint"), body));

        assertThatThrownBy(() -> svc.update(body))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("已存在 waypoint Gateway「waypoint」");

        verify(k8s, never()).update(any(GatewayDTO.class));
    }

    @Test
    void update_plain_gateway_skips_the_check_entirely() {
        GatewayDTO body = gateway("web-gw", "istio");
        when(k8s.update(any(GatewayDTO.class))).thenReturn(body);

        assertThat(svc.update(body)).isSameAs(body);

        verify(k8s, never()).list(any(GatewayDTO.class));
    }

    @Test
    void create_is_refused_when_the_check_cannot_be_resolved() {
        GatewayDTO body = gateway("waypoint", "istio-waypoint");
        when(k8s.list(any(GatewayDTO.class))).thenThrow(new CloudPlatformException(500, "k8s-server 调用失败: 连接被拒绝"));

        // 保守拒绝：无法确认是否已有 waypoint，就不放行
        assertThatThrownBy(() -> svc.create(body))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("无法确认命名空间「team-a」是否已有 waypoint Gateway")
                .hasMessageContaining("已拒绝");

        verify(k8s, never()).create(any(GatewayDTO.class));
    }

    @Test
    void the_check_only_sends_the_boundary_triple_as_list_query() {
        GatewayDTO body = gateway("waypoint", "istio-waypoint");
        body.setTenantId("t1");
        body.setLabels(java.util.Map.of("keep", "me"));
        when(k8s.list(any(GatewayDTO.class))).thenReturn(List.of());
        when(k8s.create(any(GatewayDTO.class))).thenReturn(body);

        svc.create(body);

        var captor = org.mockito.ArgumentCaptor.forClass(GatewayDTO.class);
        verify(k8s).list(captor.capture());
        GatewayDTO query = captor.getValue();
        assertThat(query.getClusterId()).isEqualTo("c1");
        assertThat(query.getNamespace()).isEqualTo("team-a");
        assertThat(query.getTenantId()).isEqualTo("t1");
        // 表单里其余字段不该被当成查询条件发出去
        assertThat(query.getGatewayClassName()).isNull();
        assertThat(query.getLabels()).isNull();
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
        labelledButPlain.setLabels(java.util.Map.of("istio.io/waypoint-for", "service"));
        assertThat(GatewayService.isWaypointGateway(labelledButPlain)).isFalse();

        GatewayDTO unlabelledWaypoint = gateway("w", "istio-waypoint");
        assertThat(GatewayService.isWaypointGateway(unlabelledWaypoint)).isTrue();
    }

    @Test
    void waypoint_names_projection_filters_drops_blanks_and_sorts() {
        // 判定与投影的唯一实现（tenant 侧 /gateways 与平台侧 /mesh/gateways 两条数据源共用）：
        // 只留 waypoint、按名升序、空名剔除 —— 否则两侧会各自漂移
        GatewayDTO plain = gateway("web-gw", "istio");
        GatewayDTO blankName = gateway("  ", "istio-waypoint");
        assertThat(GatewayService.waypointNamesOf(List.of(
                gateway("wp-b", "istio-waypoint"),
                plain,
                gateway("wp-a", "istio-agentgateway-waypoint"),
                blankName)))
                .containsExactly("wp-a", "wp-b");
        assertThat(GatewayService.waypointNamesOf(null)).isEmpty();
        assertThat(GatewayService.waypointNamesOf(List.of(plain))).isEmpty();
    }

}
