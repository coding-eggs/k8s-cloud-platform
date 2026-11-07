package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.ContainerDTO;
import com.coding.common.models.k8s.dto.HpaDTO;
import com.coding.common.models.k8s.dto.HpaMetricSpecDTO;
import com.coding.common.models.k8s.dto.NamespaceDTO;
import com.coding.common.models.k8s.dto.PodMonitorDTO;
import com.coding.common.models.k8s.dto.PodSpecDTO;
import com.coding.common.models.k8s.dto.PodTemplateDTO;
import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import com.coding.common.models.k8s.dto.ServiceDTO;
import com.coding.common.models.k8s.dto.ServiceMonitorDTO;
import com.coding.common.models.k8s.dto.WorkloadDTO;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.configs.NamespaceProtectionProperties;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.k8s.K8sWorkloadClient;
import com.coding.platformapi.metrics.PromDiscoveryClient;
import com.coding.platformapi.metrics.PromRulesClient;
import com.coding.platformapi.models.ComponentFlagDTO;
import com.coding.platformapi.models.NamespaceUpsertRequest;
import com.coding.platformapi.models.WorkloadMeshToggleRequest;
import com.coding.platformapi.models.WorkloadObservabilityRequest;
import com.coding.platformapi.services.validation.WorkloadValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「依赖组件的功能，没装不让开启；已开启的允许关闭、不追溯」（2026-11-07）。
 *
 * <p>每个入口两条：组件不可用时<b>开启/新建被拒</b>、<b>关闭（或不涉及该字段的编辑）放行</b>。
 * 服务层用 mock 的 guard 模拟"不可用"（require 抛异常）；guard 自身的判定另用真实例 + mock 旗标测。
 */
class ComponentFeatureGateTest {

    private static final String DATAPLANE = "istio.io/dataplane-mode";
    private static final String USE_WAYPOINT = "istio.io/use-waypoint";
    private static final String INJECT_JAVA = "instrumentation.opentelemetry.io/inject-java";

    /** 所有功能都"没装"的 guard */
    private static ComponentFeatureGuard unavailableGuard() {
        ComponentFeatureGuard g = mock(ComponentFeatureGuard.class);
        doThrow(new CloudPlatformException(EnumResponseType.OPERATION_NOT_SUPPORTED, "无法开启：组件未安装"))
                .when(g).require(anyString(), anyString(), anyString());
        return g;
    }

    // ==================== guard 自身 ====================

    @Nested
    class Guard {
        private final ClusterComponentService components = mock(ClusterComponentService.class);
        private final ComponentFeatureGuard guard = new ComponentFeatureGuard(components);

        @Test
        void unavailable_flag_is_rejected_with_its_reason() {
            when(components.flags("c1")).thenReturn(Map.of("ambient-mesh",
                    new ComponentFlagDTO(false, "缺少组件：Istio", List.of("Istio"))));
            assertThatThrownBy(() -> guard.require("c1", "ambient-mesh", "开启网格"))
                    .isInstanceOf(CloudPlatformException.class)
                    .hasMessageContaining("开启网格")
                    .hasMessageContaining("缺少组件：Istio");
        }

        @Test
        void missing_flag_fails_closed() {
            when(components.flags("c1")).thenReturn(Map.of());
            assertThatThrownBy(() -> guard.require("c1", "servicemonitor", "创建 ServiceMonitor"))
                    .isInstanceOf(CloudPlatformException.class);
        }

        @Test
        void available_flag_passes() {
            when(components.flags("c1")).thenReturn(Map.of("ci", new ComponentFlagDTO(true, null, List.of())));
            assertThatCode(() -> guard.require("c1", "ci", "运行流水线")).doesNotThrowAnyException();
        }

        @Test
        void opens_ambient_only_on_off_to_on() {
            assertThat(ComponentFeatureGuard.opensAmbient("ambient", null, null, null)).isTrue();
            assertThat(ComponentFeatureGuard.opensAmbient("ambient", "ambient", null, null)).isFalse(); // 原值不变
            assertThat(ComponentFeatureGuard.opensAmbient("none", null, null, null)).isFalse();         // 显式退出
            assertThat(ComponentFeatureGuard.opensAmbient("", "ambient", "", "wp")).isFalse();          // 清除
            assertThat(ComponentFeatureGuard.opensAmbient(null, null, "wp", null)).isTrue();
            assertThat(ComponentFeatureGuard.opensAmbient(null, null, "wp", "wp")).isFalse();
            assertThat(ComponentFeatureGuard.opensAmbient(null, null, "wp2", "wp")).isTrue();           // 换 waypoint 也算
            assertThat(ComponentFeatureGuard.opensAmbient(null, null, "none", null)).isFalse();
        }
    }

    // ==================== 工作负载 ====================

    @Nested
    class Workload {
        private final K8sClient k8s = mock(K8sClient.class);
        private final ComponentFeatureGuard guard = unavailableGuard();
        private final WorkloadService svc = new WorkloadService(k8s, mock(K8sWorkloadClient.class),
                mock(WorkloadValidator.class), mock(GatewayService.class), guard);

        @BeforeEach
        void echoUpdates() {
            when(k8s.update(any(WorkloadDTO.class))).thenAnswer(i -> i.getArgument(0));
            when(k8s.create(any(WorkloadDTO.class))).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        void mesh_toggle_on_is_rejected_when_istio_missing() {
            when(k8s.get(any(WorkloadDTO.class))).thenReturn(workload(Map.of(), Map.of()));
            assertThatThrownBy(() -> svc.meshToggle(meshReq("ambient", null)))
                    .isInstanceOf(CloudPlatformException.class).hasMessageContaining("组件未安装");
            verify(k8s, never()).update(any());
        }

        @Test
        void mesh_toggle_off_passes_even_when_istio_missing() {
            when(k8s.get(any(WorkloadDTO.class))).thenReturn(workload(Map.of(DATAPLANE, "ambient", USE_WAYPOINT, "wp"), Map.of()));
            svc.meshToggle(meshReq("", ""));
            svc.meshToggle(meshReq("none", "none"));
            verify(guard, never()).require(any(), any(), any());
        }

        @Test
        void observability_on_is_rejected_and_off_passes() {
            when(k8s.get(any(WorkloadDTO.class))).thenReturn(workload(Map.of(), Map.of()));
            assertThatThrownBy(() -> svc.observability(otelReq(true, false)))
                    .isInstanceOf(CloudPlatformException.class);
            assertThatThrownBy(() -> svc.observability(otelReq(false, true)))
                    .isInstanceOf(CloudPlatformException.class);

            // 已开着追踪 → 关闭放行
            when(k8s.get(any(WorkloadDTO.class)))
                    .thenReturn(workload(Map.of(), Map.of(INJECT_JAVA, "monitor/platform-instrumentation")));
            svc.observability(otelReq(false, false));
            verify(k8s).update(any(WorkloadDTO.class));
        }

        @Test
        void observability_keeping_tracing_on_is_not_retroactive() {
            // 已开着追踪、只是改采样率：不是"从关到开"，组件没了也放行
            when(k8s.get(any(WorkloadDTO.class)))
                    .thenReturn(workload(Map.of(), Map.of(INJECT_JAVA, "monitor/platform-instrumentation")));
            WorkloadObservabilityRequest r = otelReq(true, false);
            r.setSampling(50);
            svc.observability(r);
            verify(guard, never()).require(any(), any(), any());
        }

        @Test
        void create_with_ambient_label_or_inject_annotation_is_rejected() {
            assertThatThrownBy(() -> svc.create(workload(Map.of(DATAPLANE, "ambient"), Map.of())))
                    .isInstanceOf(CloudPlatformException.class);
            assertThatThrownBy(() -> svc.create(workload(Map.of(), Map.of(INJECT_JAVA, "monitor/platform-instrumentation"))))
                    .isInstanceOf(CloudPlatformException.class);
            verify(k8s, never()).create(any());
        }

        @Test
        void update_keeping_existing_switches_passes_but_adding_one_is_rejected() {
            WorkloadDTO live = workload(Map.of(DATAPLANE, "ambient"), Map.of(INJECT_JAVA, "monitor/platform-instrumentation"));
            when(k8s.get(any(WorkloadDTO.class))).thenReturn(live);

            svc.update(workload(Map.of(DATAPLANE, "ambient"), Map.of(INJECT_JAVA, "monitor/platform-instrumentation")));
            verify(guard, never()).require(any(), any(), any());

            assertThatThrownBy(() -> svc.update(workload(Map.of(DATAPLANE, "ambient", USE_WAYPOINT, "wp"),
                    Map.of(INJECT_JAVA, "monitor/platform-instrumentation"))))
                    .isInstanceOf(CloudPlatformException.class);
        }

        @Test
        void plain_update_does_not_read_live_object_for_gating() {
            svc.update(workload(Map.of("app", "web"), Map.of()));
            verify(k8s, never()).get(any(WorkloadDTO.class));
        }

        private WorkloadDTO workload(Map<String, String> labels, Map<String, Object> annotations) {
            WorkloadDTO w = new WorkloadDTO();
            w.setName("web");
            w.setKind("deployment");
            w.setNamespace("team-a");
            w.setClusterId("c1");
            w.setTenantId("t1");
            PodTemplateDTO pt = new PodTemplateDTO();
            pt.setLabels(new LinkedHashMap<>(labels));
            pt.setAnnotations(new LinkedHashMap<>(annotations));
            PodSpecDTO spec = new PodSpecDTO();
            ContainerDTO c = new ContainerDTO();
            c.setName("app");
            c.setEnvs(new ArrayList<>());
            spec.setContainers(new ArrayList<>(List.of(c)));
            pt.setSpec(spec);
            w.setPodTemplate(pt);
            return w;
        }

        private WorkloadMeshToggleRequest meshReq(String mode, String waypoint) {
            WorkloadMeshToggleRequest r = new WorkloadMeshToggleRequest();
            r.setName("web");
            r.setTenantId("t1");
            r.setClusterId("c1");
            r.setNamespace("team-a");
            r.setDataplaneMode(mode);
            r.setUseWaypoint(waypoint);
            return r;
        }

        private WorkloadObservabilityRequest otelReq(boolean tracing, boolean logs) {
            WorkloadObservabilityRequest r = new WorkloadObservabilityRequest();
            r.setName("web");
            r.setTenantId("t1");
            r.setClusterId("c1");
            r.setNamespace("team-a");
            r.setLanguage("java");
            r.setTracing(tracing);
            r.setLogs(logs);
            return r;
        }
    }

    // ==================== Service waypoint ====================

    @Nested
    class Service {
        private final K8sClient k8s = mock(K8sClient.class);
        private final ComponentFeatureGuard guard = unavailableGuard();
        private final ServiceService svc = new ServiceService(k8s, guard);

        @Test
        void create_with_waypoint_is_rejected() {
            assertThatThrownBy(() -> svc.create(service(Map.of(USE_WAYPOINT, "wp"))))
                    .isInstanceOf(CloudPlatformException.class);
            verify(k8s, never()).create(any());
        }

        @Test
        void update_removing_or_keeping_waypoint_passes() {
            when(k8s.get(any(ServiceDTO.class))).thenReturn(service(Map.of(USE_WAYPOINT, "wp")));
            svc.update(service(Map.of()));
            svc.update(service(Map.of(USE_WAYPOINT, "wp")));
            svc.update(service(Map.of(USE_WAYPOINT, "none")));
            verify(guard, never()).require(any(), any(), any());
        }

        private ServiceDTO service(Map<String, String> labels) {
            ServiceDTO s = new ServiceDTO();
            s.setName("svc");
            s.setNamespace("team-a");
            s.setClusterId("c1");
            s.setTenantId("t1");
            s.setLabels(new LinkedHashMap<>(labels));
            return s;
        }
    }

    // ==================== HPA ====================

    @Nested
    class Hpa {
        private final K8sClient k8s = mock(K8sClient.class);
        private final ComponentFeatureGuard guard = unavailableGuard();
        private final ClusterCapabilityService capability = mock(ClusterCapabilityService.class);
        private final HpaService svc = new HpaService(k8s, guard, capability);

        @Test
        void create_with_resource_metric_or_default_is_rejected_without_metrics_server() {
            assertThatThrownBy(() -> svc.create(hpa("Resource"))).isInstanceOf(CloudPlatformException.class);
            assertThatThrownBy(() -> svc.create(hpa())).isInstanceOf(CloudPlatformException.class); // 空 = 默认 CPU
            verify(k8s, never()).create(any());
        }

        @Test
        void custom_and_external_still_follow_capability_group() {
            when(capability.versionsOf(eq("c1"), anyString())).thenReturn(List.of());
            assertThatThrownBy(() -> svc.create(hpa("Pods"))).hasMessageContaining("custom.metrics.k8s.io");
            assertThatThrownBy(() -> svc.create(hpa("External"))).hasMessageContaining("external.metrics.k8s.io");

            when(capability.versionsOf("c1", "custom.metrics.k8s.io")).thenReturn(List.of("v1beta2"));
            when(k8s.create(any(HpaDTO.class))).thenAnswer(i -> i.getArgument(0));
            svc.create(hpa("Object"));
            verify(guard, never()).require(any(), any(), any());
        }

        @Test
        void update_keeping_resource_metric_is_not_retroactive() {
            when(k8s.get(any(HpaDTO.class))).thenReturn(hpa("Resource"));
            when(k8s.update(any(HpaDTO.class))).thenAnswer(i -> i.getArgument(0));
            HpaDTO body = hpa("Resource");
            body.setMaxReplicas(10);
            svc.update(body);
            verify(guard, never()).require(any(), any(), any());
        }

        private HpaDTO hpa(String... types) {
            HpaDTO h = new HpaDTO();
            h.setName("hpa");
            h.setNamespace("team-a");
            h.setClusterId("c1");
            h.setTenantId("t1");
            List<HpaMetricSpecDTO> metrics = new ArrayList<>();
            for (String t : types) {
                HpaMetricSpecDTO m = new HpaMetricSpecDTO();
                m.setType(t);
                metrics.add(m);
            }
            h.setMetrics(metrics);
            return h;
        }
    }

    // ==================== Prometheus Operator CRD ====================

    @Nested
    class Monitors {
        private final K8sClient k8s = mock(K8sClient.class);
        private final ComponentFeatureGuard guard = unavailableGuard();

        @Test
        void creates_are_rejected_without_prometheus_operator() {
            ServiceMonitorService sm = new ServiceMonitorService(k8s, mock(K8sClusterMapper.class),
                    mock(PromDiscoveryClient.class), guard);
            PodMonitorService pm = new PodMonitorService(k8s, mock(K8sClusterMapper.class),
                    mock(PromDiscoveryClient.class), guard);
            PrometheusRuleService pr = new PrometheusRuleService(k8s, mock(K8sClusterMapper.class),
                    mock(PromRulesClient.class), mock(RuleNotifyBinder.class), guard);

            ServiceMonitorDTO smBody = new ServiceMonitorDTO();
            smBody.setClusterId("c1");
            PodMonitorDTO pmBody = new PodMonitorDTO();
            pmBody.setClusterId("c1");
            PrometheusRuleDTO prBody = new PrometheusRuleDTO();
            prBody.setClusterId("c1");

            assertThatThrownBy(() -> sm.create(smBody)).isInstanceOf(CloudPlatformException.class);
            assertThatThrownBy(() -> pm.create(pmBody)).isInstanceOf(CloudPlatformException.class);
            assertThatThrownBy(() -> pr.create(prBody)).isInstanceOf(CloudPlatformException.class);
            verify(k8s, never()).create(any());
        }
    }

    // ==================== 命名空间 ====================

    @Nested
    class Namespace {
        private final K8sClient k8s = mock(K8sClient.class);
        private final K8sClusterMapper clusterMapper = mock(K8sClusterMapper.class);
        private final ComponentFeatureGuard guard = unavailableGuard();
        private final NamespaceService svc = new NamespaceService(k8s, clusterMapper,
                mock(PlatformTenantNamespaceMapper.class), mock(PlatformTenantMapper.class),
                new NamespaceProtectionProperties(), mock(NamespaceStatService.class), guard);

        @BeforeEach
        void cluster() {
            K8sCluster c = new K8sCluster();
            c.setClusterId("c1");
            when(clusterMapper.selectByPrimaryKey("c1")).thenReturn(c);
            when(k8s.update(any(NamespaceDTO.class))).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        void update_enabling_ambient_is_rejected() {
            when(k8s.get(any(NamespaceDTO.class))).thenReturn(ns(null, null));
            assertThatThrownBy(() -> svc.update(req("ambient", null))).isInstanceOf(CloudPlatformException.class);
            verify(k8s, never()).update(any());
        }

        @Test
        void update_clearing_or_keeping_ambient_passes() {
            when(k8s.get(any(NamespaceDTO.class))).thenReturn(ns("ambient", "wp"));
            svc.update(req("", ""));
            svc.update(req("ambient", "wp"));
            svc.update(req(null, null));
            verify(guard, never()).require(any(), any(), any());
        }

        private NamespaceDTO ns(String mode, String waypoint) {
            NamespaceDTO d = new NamespaceDTO();
            d.setName("app");
            d.setPhase("Active");
            d.setDataplaneMode(mode);
            d.setUseWaypoint(waypoint);
            return d;
        }

        private NamespaceUpsertRequest req(String mode, String waypoint) {
            NamespaceUpsertRequest r = new NamespaceUpsertRequest();
            r.setClusterId("c1");
            r.setName("app");
            r.setDataplaneMode(mode);
            r.setUseWaypoint(waypoint);
            return r;
        }
    }
}
