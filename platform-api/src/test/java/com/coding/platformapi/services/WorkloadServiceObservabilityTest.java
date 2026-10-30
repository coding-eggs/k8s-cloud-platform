package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.ContainerDTO;
import com.coding.common.models.k8s.dto.EnvDTO;
import com.coding.common.models.k8s.dto.PodSpecDTO;
import com.coding.common.models.k8s.dto.PodTemplateDTO;
import com.coding.common.models.k8s.dto.WorkloadDTO;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.k8s.K8sWorkloadClient;
import com.coding.platformapi.models.WorkloadObservabilityRequest;
import com.coding.platformapi.services.validation.WorkloadValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作负载 OTel 接入开关（批次一，mock K8sClient）。
 *
 * <p>四条不变量：组合 → Instrumentation 名称；切换语言不残留旧注解；关闭只移除平台注解；
 * Node 在第一批被拒绝（operator 不会自动识别语言，写错注解不会报错但永远没有数据）。
 */
class WorkloadServiceObservabilityTest {

    private static final String INJECT_JAVA = "instrumentation.opentelemetry.io/inject-java";
    private static final String INJECT_PYTHON = "instrumentation.opentelemetry.io/inject-python";

    private final K8sClient k8s = mock(K8sClient.class);
    private final WorkloadService svc = new WorkloadService(k8s, mock(K8sWorkloadClient.class),
            mock(WorkloadValidator.class), mock(GatewayService.class));

    @Test
    void tracingAndLogsSelectTheAllInstrumentationAndSetTheServiceName() {
        WorkloadDTO existing = workload();

        WorkloadDTO updated = apply(existing, request("java", true, true, "movie"));

        assertThat(updated.getPodTemplate().getAnnotations())
                .containsEntry(INJECT_JAVA, "monitor/platform-instrumentation-all")
                .doesNotContainKey(INJECT_PYTHON)
                .containsEntry("user.example/keep", "x");
        assertThat(envOf(updated, "OTEL_SERVICE_NAME")).isEqualTo("movie");
        // 用户自己的 env 不能被吞掉
        assertThat(envOf(updated, "EXISTING")).isEqualTo("v");
    }

    @Test
    void logsOnlySelectsTheLogsInstrumentationAndTurnsTracingOff() {
        WorkloadDTO updated = apply(workload(), request("python", false, true, null));

        assertThat(updated.getPodTemplate().getAnnotations())
                .containsEntry(INJECT_PYTHON, "monitor/platform-instrumentation-logs");
        // 服务名缺省用工作负载名
        assertThat(envOf(updated, "OTEL_SERVICE_NAME")).isEqualTo("web");
    }

    @Test
    void switchingLanguageRemovesTheOldInjectionAnnotation() {
        WorkloadDTO existing = workload();
        existing.getPodTemplate().getAnnotations().put(INJECT_JAVA, "monitor/platform-instrumentation");

        WorkloadDTO updated = apply(existing, request("python", true, false, null));

        assertThat(updated.getPodTemplate().getAnnotations())
                .doesNotContainKey(INJECT_JAVA)
                .containsEntry(INJECT_PYTHON, "monitor/platform-instrumentation");
    }

    @Test
    void nodeIsRejectedInTheFirstBatch() {
        assertThatThrownBy(() -> svc.observability(request("nodejs", true, false, null)))
                .hasMessageContaining("不支持");
    }

    @Test
    void turningEverythingOffRemovesOnlyPlatformAnnotations() {
        WorkloadDTO existing = workload();
        existing.getPodTemplate().getAnnotations().put(INJECT_PYTHON, "monitor/platform-instrumentation");

        WorkloadDTO updated = apply(existing, request("python", false, false, null));

        assertThat(updated.getPodTemplate().getAnnotations())
                .doesNotContainKey(INJECT_PYTHON)
                .containsEntry("user.example/keep", "x");
        assertThat(envOf(updated, "EXISTING")).isEqualTo("v");
    }

    @Test
    void fileLogsCollectOnlyTheGivenFileInItsParentDirectory() {
        WorkloadDTO updated = apply(workload(), fileLogReq("/var/log/nginx/access.log"));

        assertThat(updated.getPodTemplate().getAnnotations())
                .containsEntry("sidecar.opentelemetry.io/inject", "monitor/platform-otel-sidecar")
                // sidecar 视角的具体文件：挂载点是父目录，文件名原样保留
                .containsEntry("platform.coding/otel-log-file", "/var/log/app/access.log");
        assertThat(updated.getPodTemplate().getSpec().getVolumes())
                .anySatisfy(v -> assertThat(v.getName()).isEqualTo("platform-otel-logs"));
        assertThat(updated.getPodTemplate().getSpec().getContainers().get(0).getVolumeMounts())
                .anySatisfy(m -> {
                    assertThat(m.getName()).isEqualTo("platform-otel-logs");
                    // 挂的是文件的父目录（文件路径本身不能挂 emptyDir）
                    assertThat(m.getMountPath()).isEqualTo("/var/log/nginx");
                });
        // 文件日志不带应用侧 Instrumentation（没有 tracing 时）
        assertThat(updated.getPodTemplate().getAnnotations())
                .doesNotContainKey(INJECT_JAVA)
                .doesNotContainKey(INJECT_PYTHON);
    }

    @Test
    void fileLogsRequireAnAbsoluteFilePathNotADirectory() {
        assertThatThrownBy(() -> svc.observability(fileLogReq("relative/access.log")))
                .hasMessageContaining("绝对路径");
        // 目录结尾（以 / 收尾）说明用户没填到具体文件
        assertThatThrownBy(() -> svc.observability(fileLogReq("/var/log/nginx/")))
                .hasMessageContaining("具体日志文件");
    }

    @Test
    void fileLogsWithTracingUseTheTracingOnlyInstrumentation() {
        WorkloadObservabilityRequest req = fileLogReq("/var/log/nginx/access.log");
        req.setTracing(true);

        WorkloadDTO updated = apply(workload(), req);

        // 文件日志不应该打开应用日志 appender：即使 logs=true 也只选 tracing 版
        assertThat(updated.getPodTemplate().getAnnotations())
                .containsEntry(INJECT_JAVA, "monitor/platform-instrumentation")
                .containsEntry("sidecar.opentelemetry.io/inject", "monitor/platform-otel-sidecar");
    }

    @Test
    void switchingBackToAppLogsRemovesTheSidecarBits() {
        WorkloadDTO existing = workload();
        apply(existing, fileLogReq("/var/log/nginx/access.log"));
        clearInvocations(k8s);

        WorkloadDTO updated = apply(existing, request("java", false, true, null));

        assertThat(updated.getPodTemplate().getAnnotations())
                .doesNotContainKey("sidecar.opentelemetry.io/inject")
                .doesNotContainKey("platform.coding/otel-log-file");
        assertThat(updated.getPodTemplate().getSpec().getVolumes() == null
                || updated.getPodTemplate().getSpec().getVolumes().isEmpty()).isTrue();
        assertThat(updated.getPodTemplate().getSpec().getContainers().get(0).getVolumeMounts() == null
                || updated.getPodTemplate().getSpec().getContainers().get(0).getVolumeMounts().isEmpty())
                .isTrue();
    }

    @Test
    void aNonEmptyDirVolumeWithTheSameNameIsRejected() {
        WorkloadDTO existing = workload();
        var volume = new com.coding.common.models.k8s.dto.VolumeDTO();
        volume.setName("platform-otel-logs");
        volume.setType("configMap");
        existing.getPodTemplate().getSpec().setVolumes(new ArrayList<>(List.of(volume)));
        when(k8s.get(any(WorkloadDTO.class))).thenReturn(existing);

        assertThatThrownBy(() -> svc.observability(fileLogReq("/var/log/nginx/access.log")))
                .hasMessageContaining("同名卷");
    }

    // ==================== 工作负载级采样率 ====================

    @Test
    void aWorkloadLevelSamplingRateOverridesTheComponentDefault() {
        // 两个 env 必须一起写：operator 发现容器里已有 OTEL_TRACES_SAMPLER 就整块跳过组件级 sampler
        WorkloadObservabilityRequest req = request("java", true, false, null);
        req.setSampling(50);

        WorkloadDTO updated = apply(workload(), req);

        assertThat(envOf(updated, "OTEL_TRACES_SAMPLER")).isEqualTo("parentbased_traceidratio");
        assertThat(envOf(updated, "OTEL_TRACES_SAMPLER_ARG")).isEqualTo("0.5");
        // 用户自己的 env 仍然保留
        assertThat(envOf(updated, "EXISTING")).isEqualTo("v");
    }

    @Test
    void anOddPercentageBecomesACleanRatio() {
        WorkloadObservabilityRequest req = request("java", true, false, null);
        req.setSampling(33);

        assertThat(envOf(apply(workload(), req), "OTEL_TRACES_SAMPLER_ARG")).isEqualTo("0.33");
    }

    @Test
    void clearingTheSamplingRateFallsBackToTheComponentDefault() {
        WorkloadObservabilityRequest withRate = request("java", true, false, null);
        withRate.setSampling(10);
        WorkloadDTO existing = workload();
        apply(existing, withRate);
        clearInvocations(k8s);

        WorkloadDTO updated = apply(existing, request("java", true, false, null));

        assertThat(envOf(updated, "OTEL_TRACES_SAMPLER")).isNull();
        assertThat(envOf(updated, "OTEL_TRACES_SAMPLER_ARG")).isNull();
    }

    @Test
    void theSamplingRateIsAPercentage() {
        WorkloadObservabilityRequest req = request("java", true, false, null);
        req.setSampling(0);
        assertThatThrownBy(() -> svc.observability(req)).hasMessageContaining("1..100");
        req.setSampling(101);
        assertThatThrownBy(() -> svc.observability(req)).hasMessageContaining("1..100");
    }

    // ==================== 夹具 ====================

    private static WorkloadObservabilityRequest fileLogReq(String file) {
        WorkloadObservabilityRequest r = request("java", false, true, null);
        r.setLogMode("file");
        r.setLogFile(file);
        return r;
    }

    private WorkloadDTO apply(WorkloadDTO existing, WorkloadObservabilityRequest request) {
        when(k8s.get(any(WorkloadDTO.class))).thenReturn(existing);
        when(k8s.update(any(WorkloadDTO.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.observability(request);

        ArgumentCaptor<WorkloadDTO> captor = ArgumentCaptor.forClass(WorkloadDTO.class);
        verify(k8s).update(captor.capture());
        return captor.getValue();
    }

    private static WorkloadDTO workload() {
        WorkloadDTO w = new WorkloadDTO();
        w.setName("web");
        w.setKind("deployment");
        w.setNamespace("team-a");
        w.setClusterId("c1");
        w.setTenantId("t1");

        ContainerDTO app = new ContainerDTO();
        app.setName("app");
        EnvDTO existing = new EnvDTO();
        existing.setName("EXISTING");
        existing.setValue("v");
        app.setEnvs(new ArrayList<>(List.of(existing)));

        PodSpecDTO spec = new PodSpecDTO();
        spec.setContainers(new ArrayList<>(List.of(app)));

        PodTemplateDTO pt = new PodTemplateDTO();
        pt.setAnnotations(new LinkedHashMap<>(Map.of("user.example/keep", "x")));
        pt.setSpec(spec);
        w.setPodTemplate(pt);
        return w;
    }

    private static WorkloadObservabilityRequest request(String language, boolean tracing, boolean logs,
                                                        String serviceName) {
        WorkloadObservabilityRequest r = new WorkloadObservabilityRequest();
        r.setName("web");
        r.setTenantId("t1");
        r.setClusterId("c1");
        r.setNamespace("team-a");
        r.setLanguage(language);
        r.setTracing(tracing);
        r.setLogs(logs);
        r.setServiceName(serviceName);
        return r;
    }

    private static String envOf(WorkloadDTO workload, String name) {
        return workload.getPodTemplate().getSpec().getContainers().get(0).getEnvs().stream()
                .filter(e -> name.equals(e.getName()))
                .map(EnvDTO::getValue)
                .findFirst().orElse(null);
    }
}
