package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.PodTemplateDTO;
import com.coding.common.models.k8s.dto.WaypointRefDTO;
import com.coding.common.models.k8s.dto.WorkloadDTO;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.models.WorkloadMeshToggleRequest;
import com.coding.platformapi.services.validation.WorkloadValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作负载 ambient 开关（B3 §11.3，mock K8sClient，不触网）。
 *
 * <p>四条不变量：
 * <ol>
 *   <li><b>三态</b>：null=不动、空串=移除、有值=覆写 —— 列表页两个开关各发各的，不得互相踩。</li>
 *   <li><b>只动这两个 label</b>：其余 pod template label / 其它字段原样带回。</li>
 *   <li><b>不跑 §5 校验</b>：规格不改，不该因为"这工作负载不合平台规格"而挡住 ambient 开关。</li>
 *   <li><b>waypoint 名要存在、且类型要匹配</b>：不存在的名字、或 {@code waypoint-for} 不是
 *       {@code workload}/{@code all} 的 waypoint，istio 都会静默放行（L7 策略悄悄失效）→ 拒绝；
 *       但校验自身的查询失败 → 放行（非破坏性写入，不因查不到候选而卡死）。</li>
 * </ol>
 */
class WorkloadServiceMeshToggleTest {

    private static final String DATAPLANE = "istio.io/dataplane-mode";
    private static final String USE_WAYPOINT = "istio.io/use-waypoint";

    private final K8sClient k8s = mock(K8sClient.class);
    private final WorkloadValidator validator = mock(WorkloadValidator.class);
    private final GatewayService gatewayService = mock(GatewayService.class);
    private final WorkloadService svc = new WorkloadService(k8s, validator, gatewayService);

    private static WorkloadDTO workload(Map<String, String> podLabels) {
        WorkloadDTO w = new WorkloadDTO();
        w.setName("web");
        w.setKind("deployment");
        w.setNamespace("team-a");
        w.setClusterId("c1");
        w.setTenantId("t1");
        PodTemplateDTO pt = new PodTemplateDTO();
        pt.setLabels(podLabels == null ? null : new LinkedHashMap<>(podLabels));
        w.setPodTemplate(pt);
        return w;
    }

    /** waypoint 引用候选（名字 + 处理哪类流量）——与 GatewayService.waypointRefsOf 的产出同形 */
    private static WaypointRefDTO ref(String name, String waypointFor) {
        WaypointRefDTO r = new WaypointRefDTO();
        r.setName(name);
        r.setWaypointFor(waypointFor);
        return r;
    }

    private static WorkloadMeshToggleRequest req(String dataplaneMode, String useWaypoint) {
        WorkloadMeshToggleRequest r = new WorkloadMeshToggleRequest();
        r.setName("web");
        r.setTenantId("t1");
        r.setClusterId("c1");
        r.setNamespace("team-a");
        r.setDataplaneMode(dataplaneMode);
        r.setUseWaypoint(useWaypoint);
        return r;
    }

    /** 跑一次开关，返回真正交给 k8s 的 pod template labels */
    private Map<String, String> updatedPodLabels(WorkloadMeshToggleRequest request, WorkloadDTO existing) {
        when(k8s.get(any(WorkloadDTO.class))).thenReturn(existing);
        when(k8s.update(any(WorkloadDTO.class))).thenAnswer(inv -> inv.getArgument(0));
        svc.meshToggle(request);
        ArgumentCaptor<WorkloadDTO> captor = ArgumentCaptor.forClass(WorkloadDTO.class);
        verify(k8s).update(captor.capture());
        return captor.getValue().getPodTemplate().getLabels();
    }

    @Test
    void ambient_on_writes_label_and_keeps_other_labels() {
        Map<String, String> out = updatedPodLabels(req("ambient", null), workload(Map.of("app", "web", "keep", "me")));
        assertThat(out)
                .containsEntry("app", "web")            // 其它 label 原样存活
                .containsEntry("keep", "me")
                .containsEntry(DATAPLANE, "ambient")
                .doesNotContainKey(USE_WAYPOINT);        // 未传 = 不动（不是"清空"）
    }

    @Test
    void empty_string_removes_label_while_null_keeps_it() {
        Map<String, String> out = updatedPodLabels(
                req(null, ""),                          // 只清 use-waypoint
                workload(Map.of(DATAPLANE, "ambient", USE_WAYPOINT, "waypoint")));
        assertThat(out)
                .containsEntry(DATAPLANE, "ambient")     // null = 不动 → 保留
                .doesNotContainKey(USE_WAYPOINT);        // 空串 = 移除
    }

    @Test
    void blank_pod_labels_are_pruned_before_write() {
        when(gatewayService.waypointRefs("t1", "c1", "team-a")).thenReturn(List.of(ref("waypoint", "workload")));
        Map<String, String> out = updatedPodLabels(
                req(null, "waypoint"), workload(new LinkedHashMap<>(Map.of("empty", ""))));
        assertThat(out)
                .doesNotContainKey("empty")              // pod template 里挂空串 label 无意义，顺手清掉
                .containsEntry(USE_WAYPOINT, "waypoint");
    }

    @Test
    void validator_is_not_invoked() {
        when(gatewayService.waypointRefs(any(), any(), any())).thenReturn(List.of(ref("waypoint", "all")));
        when(k8s.get(any(WorkloadDTO.class))).thenReturn(workload(Map.of()));
        when(k8s.update(any(WorkloadDTO.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.meshToggle(req("ambient", null));

        verify(validator, never()).validate(any());      // 只加/删 label，不跑 §5 规格校验
    }

    @Test
    void unknown_waypoint_is_rejected_with_existing_candidates_in_message() {
        when(gatewayService.waypointRefs("t1", "c1", "team-a")).thenReturn(List.of(ref("waypoint-a", "workload")));
        assertThatThrownBy(() -> svc.meshToggle(req(null, "typo")))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("typo")
                .hasMessageContaining("waypoint-a");
        verify(k8s, never()).update(any());
    }

    @Test
    void service_type_waypoint_is_rejected_for_a_pod_level_label() {
        // Pod 上的 use-waypoint 只影响"最初目标是 Pod/VM IP"的流量 → waypoint 必须是 workload/all。
        // 平台建 waypoint 的默认值就是 service，所以这个组合是最容易踩、且 istio 侧完全静默的那个。
        when(gatewayService.waypointRefs("t1", "c1", "team-a"))
                .thenReturn(List.of(ref("waypoint", "service")));   // 也代表"没打标签"（缺省即 service）

        assertThatThrownBy(() -> svc.meshToggle(req(null, "waypoint")))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("istio.io/waypoint-for")
                .hasMessageContaining("service")
                .hasMessageContaining("workload 或 all");
        verify(k8s, never()).update(any());
    }

    @Test
    void all_type_waypoint_is_accepted_for_a_pod_level_label() {
        // all = 服务 + 工作负载都处理 —— 一个 all 型 waypoint 覆盖整个命名空间，必须可选
        when(gatewayService.waypointRefs("t1", "c1", "team-a")).thenReturn(List.of(ref("waypoint", "all")));
        Map<String, String> out = updatedPodLabels(req(null, "waypoint"), workload(Map.of()));
        assertThat(out).containsEntry(USE_WAYPOINT, "waypoint");
    }

    @Test
    void none_type_waypoint_is_rejected_too() {
        // waypoint-for: none = 不处理任何流量（用于测试）→ 指向它必然不生效
        when(gatewayService.waypointRefs("t1", "c1", "team-a")).thenReturn(List.of(ref("waypoint", "none")));
        assertThatThrownBy(() -> svc.meshToggle(req(null, "waypoint")))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("workload 或 all");
    }

    @Test
    void none_and_blank_waypoint_skip_the_existence_check() {
        when(k8s.get(any(WorkloadDTO.class))).thenReturn(workload(Map.of()));
        when(k8s.update(any(WorkloadDTO.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.meshToggle(req(null, "none"));
        svc.meshToggle(req(null, ""));

        verify(gatewayService, never()).waypointRefs(any(), any(), any());
    }

    @Test
    void waypoint_lookup_failure_is_fail_open() {
        // 查不到候选（集群断开 / 租户 SA 的 K8s RBAC 未覆盖 gateway group）不该卡住一次可回退的 label 写入
        when(gatewayService.waypointRefs(any(), any(), any())).thenThrow(new RuntimeException("cluster unreachable"));
        when(k8s.get(any(WorkloadDTO.class))).thenReturn(workload(Map.of()));
        when(k8s.update(any(WorkloadDTO.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(svc.meshToggle(req(null, "waypoint-a"))).isNotNull();
        verify(k8s).update(any(WorkloadDTO.class));
    }

    @Test
    void rejects_both_fields_absent_and_bad_dataplane_mode() {
        assertThatThrownBy(() -> svc.meshToggle(req(null, null)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("至少要传一个");
        assertThatThrownBy(() -> svc.meshToggle(req("sidecar", null)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("dataplane-mode");
        verify(k8s, never()).get(any());
    }

    @Test
    void missing_workload_throws_not_found() {
        when(k8s.get(any(WorkloadDTO.class))).thenReturn(null);
        assertThatThrownBy(() -> svc.meshToggle(req("ambient", null)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("工作负载不存在");
    }

    @Test
    void null_pod_template_is_created_on_demand() {
        WorkloadDTO existing = workload(Map.of());
        existing.setPodTemplate(null);
        Map<String, String> out = updatedPodLabels(req("ambient", null), existing);
        assertThat(out).containsEntry(DATAPLANE, "ambient");
    }
}
