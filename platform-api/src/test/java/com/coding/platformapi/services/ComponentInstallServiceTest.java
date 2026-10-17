package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.ComponentInstallResultDTO;
import com.coding.common.models.k8s.dto.ReleaseInstallSpecDTO;
import com.coding.data.mapper.system.PlatformComponentOperationMapper;
import com.coding.data.models.system.PlatformComponentOperation;
import com.coding.platformapi.components.ComponentRecipeCatalog;
import com.coding.platformapi.k8s.K8sComponentClient;
import com.coding.platformapi.models.ComponentApplyRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComponentInstallServiceTest {

    private final PlatformComponentOperationMapper mapper = mock(PlatformComponentOperationMapper.class);
    private final K8sComponentClient client = mock(K8sComponentClient.class);
    private final ClusterService clusterService = mock(ClusterService.class);
    private final ClusterComponentService components = mock(ClusterComponentService.class);

    @BeforeEach
    void insertReturnsAnId() {
        // MyBatis 的 useGeneratedKeys 在真实 DB 上回填 id；mock 必须模拟这件事
        doAnswer(inv -> {
            inv.getArgument(0, PlatformComponentOperation.class).setId(1L);
            return 1;
        }).when(mapper).insert(any());
    }

    private ComponentInstallService service() {
        return service(new ComponentRecipeCatalog());
    }

    private ComponentInstallService service(ComponentRecipeCatalog catalog) {
        return new ComponentInstallService(mapper, client, catalog, clusterService, components);
    }

    @Test
    void applyRejectsWhenTheComponentHasNoRecipe() {
        ComponentApplyRequest req = new ComponentApplyRequest("c1", "gateway-api", "INSTALL", null, Map.of());

        assertThatThrownBy(() -> service().apply(req, "admin"))
                .hasMessageContaining("尚未支持安装");
        verify(mapper, never()).insert(any());
    }

    @Test
    void applyRejectsASecondRunningOperationForTheSameComponent() {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(mapper.countRunning("c1", "metrics-server")).thenReturn(1);
        ComponentApplyRequest req = new ComponentApplyRequest("c1", "metrics-server", "INSTALL", null, Map.of());

        assertThatThrownBy(() -> service().apply(req, "admin"))
                .hasMessageContaining("正在执行");
        verify(mapper, never()).insert(any());
    }

    @Test
    void aSuccessfulRunMarksSuccessAndRefreshesTheProbe() throws Exception {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(mapper.countRunning("c1", "metrics-server")).thenReturn(0);
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(inv -> {
            done.countDown();
            return null;
        }).when(components).refresh("c1");

        Long id = service().apply(new ComponentApplyRequest(
                "c1", "metrics-server", "INSTALL", null, Map.of()), "admin");

        assertThat(id).isEqualTo(1L);
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        // ⭐ 装完必须刷新探测，否则页面还显示"未安装"
        verify(components).refresh("c1");
        verify(mapper, timeout(5000)).updateById(org.mockito.ArgumentMatchers.argThat(
                op -> "SUCCESS".equals(op.getStatus())));
    }

    @Test
    void aFailedRunRecordsTheHelmMessage() throws Exception {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(mapper.countRunning("c1", "metrics-server")).thenReturn(0);
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(failureResult());

        service().apply(new ComponentApplyRequest(
                "c1", "metrics-server", "INSTALL", null, Map.of()), "admin");

        verify(mapper, timeout(5000)).updateById(org.mockito.ArgumentMatchers.argThat(
                op -> "FAILED".equals(op.getStatus()) && op.getMessage().contains("预检")));
    }

    @Test
    void upgradeRequiresAPriorPlatformInstall() {
        // spec 决策 10：手工装的 release 平台不接管
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(mapper.countRunning("c1", "metrics-server")).thenReturn(0);
        when(mapper.selectLatestSuccess("c1", "metrics-server")).thenReturn(null);

        assertThatThrownBy(() -> service().apply(new ComponentApplyRequest(
                "c1", "metrics-server", "UPGRADE", null, Map.of()), "admin"))
                .hasMessageContaining("不接管");
        verify(mapper, never()).insert(any());
    }

    @Test
    void valuesFromTheLastSuccessAreReusedOnUpgrade() {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(mapper.countRunning("c1", "metrics-server")).thenReturn(0);
        PlatformComponentOperation last = new PlatformComponentOperation();
        last.setId(1L);
        last.setToVersion("1.0.0");
        last.setValuesSnapshot(
                "{\"platform-metrics-server\":{\"args\":[\"--kubelet-insecure-tls\"],\"replicas\":3}}");
        when(mapper.selectLatestSuccess("c1", "metrics-server")).thenReturn(last);
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());

        service().apply(new ComponentApplyRequest("c1", "metrics-server", "UPGRADE", null, null), "admin");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<ReleaseInstallSpecDTO>> captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(client, timeout(5000)).applyComponent(org.mockito.ArgumentMatchers.eq("c1"),
                org.mockito.ArgumentMatchers.eq("metrics-server"),
                org.mockito.ArgumentMatchers.eq("UPGRADE"), captor.capture());
        assertThat(captor.getValue().get(0).getValues()).containsEntry("replicas", 3);
    }

    @Test
    void aVeryLongHelmMessageIsTruncatedBeforePersisting() {
        // message 是 VARCHAR(1024)：超长会让 UPDATE 失败，真正的失败原因反而丢失
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(mapper.countRunning("c1", "metrics-server")).thenReturn(0);
        ComponentInstallResultDTO failure = failureResult();
        failure.setMessage("x".repeat(5000));
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(failure);

        service().apply(new ComponentApplyRequest("c1", "metrics-server", "INSTALL", null, Map.of()), "admin");

        verify(mapper, timeout(5000)).updateById(org.mockito.ArgumentMatchers.argThat(
                op -> "FAILED".equals(op.getStatus()) && op.getMessage().length() == 1000));
    }

    @Test
    void aConcurrentInsertIsRejectedWithAFriendlyMessage() {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(mapper.countRunning("c1", "metrics-server")).thenReturn(0);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("dup")).when(mapper).insert(any());

        assertThatThrownBy(() -> service().apply(new ComponentApplyRequest(
                "c1", "metrics-server", "INSTALL", null, Map.of()), "admin"))
                .hasMessageContaining("正在执行");
    }

    @Test
    void theWorkerCarriesTheRequestersAuthorization() {
        // 真实故障：worker 在虚拟线程里跑，没有请求上下文 → k8s-server 401 → 操作 FAILED
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(mapper.countRunning("c1", "metrics-server")).thenReturn(0);
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenAnswer(inv -> {
            seen.set(com.coding.platformapi.k8s.K8sServerGateway.delegatedAuthorization());
            return successResult();
        });

        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader(org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer from-user");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            service().apply(new ComponentApplyRequest(
                    "c1", "metrics-server", "INSTALL", null, Map.of()), "admin");

            verify(mapper, timeout(5000)).updateById(org.mockito.ArgumentMatchers.argThat(
                    op -> "SUCCESS".equals(op.getStatus())));
            assertThat(seen.get()).isEqualTo("Bearer from-user");
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void nestedContractValuesAreDeepMergedAndPlaceholdersResolved() {
        // 批② 的契约是嵌套的（prometheus.prometheusSpec.*），浅合并会把整棵子树替换掉；
        // 占位符 ${cluster.name} 必须换成真实集群名
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(clusterService.requireName("c1")).thenReturn("coding-cluster");
        when(mapper.countRunning("c1", "prometheus-operator")).thenReturn(0);
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());
        com.coding.platformapi.components.ComponentRecipeCatalog catalog =
                mock(com.coding.platformapi.components.ComponentRecipeCatalog.class);
        when(catalog.recipeOf("prometheus-operator", "BUSINESS")).thenReturn(
                new com.coding.platformapi.components.ComponentRecipe("BUSINESS", List.of(
                        new com.coding.platformapi.components.ReleaseSpec(
                                "platform-kps", "monitor", "prometheus-operator", "92.10.1",
                                Map.of("prometheus", Map.of("prometheusSpec", Map.of(
                                        "externalLabels", Map.of("cluster_name", "${cluster.name}"),
                                        "ruleSelectorNilUsesHelmValues", false))),
                                Map.of("prometheus.prometheusSpec.retention", "10d"))), List.of()));

        service(catalog).apply(new ComponentApplyRequest("c1", "prometheus-operator", "INSTALL",
                null, Map.of("prometheus.prometheusSpec.retention", "30d")), "admin");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<ReleaseInstallSpecDTO>> captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(client, timeout(5000)).applyComponent(
                org.mockito.ArgumentMatchers.eq("c1"), org.mockito.ArgumentMatchers.eq("prometheus-operator"),
                org.mockito.ArgumentMatchers.eq("INSTALL"), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) ((Map<String, Object>)
                captor.getValue().get(0).getValues().get("prometheus")).get("prometheusSpec");
        assertThat(spec).containsEntry("retention", "30d")            // ⚙️ 用户值覆盖默认
                .containsEntry("ruleSelectorNilUsesHelmValues", false); // 🔒 契约值仍在
        @SuppressWarnings("unchecked")
        Map<String, Object> labels = (Map<String, Object>) spec.get("externalLabels");
        assertThat(labels).containsEntry("cluster_name", "coding-cluster");
    }

    @Test
    void anEmptyThanosReceiveUrlFailsBeforeCreatingAnOperation() {
        // 空 remoteWrite url 装出来的 Prometheus 永远上报不了，且运行时没有任何报错
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(clusterService.requireName("c1")).thenReturn("coding-cluster");
        when(mapper.countRunning("c1", "prometheus-operator")).thenReturn(0);
        com.coding.platformapi.components.ComponentRecipeCatalog catalog =
                mock(com.coding.platformapi.components.ComponentRecipeCatalog.class);
        when(catalog.recipeOf("prometheus-operator", "BUSINESS")).thenReturn(
                new com.coding.platformapi.components.ComponentRecipe("BUSINESS", List.of(
                        new com.coding.platformapi.components.ReleaseSpec(
                                "platform-kps", "monitor", "prometheus-operator", "92.10.1",
                                Map.of("remoteWrite", List.of(Map.of("url", "${thanos.receive.url}"))),
                                Map.of())), List.of()));

        ComponentInstallService svc = service(catalog);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "thanosReceiveUrl", "");

        assertThatThrownBy(() -> svc.apply(new ComponentApplyRequest(
                "c1", "prometheus-operator", "INSTALL", null, Map.of()), "admin"))
                .hasMessageContaining("thanos.receive.url");
        verify(mapper, never()).insert(any());
        verify(client, never()).applyComponent(anyString(), anyString(), anyString(), any());
    }

    @Test
    void staleRunningOperationsAreMarkedUnknown() {
        PlatformComponentOperation stale = new PlatformComponentOperation();
        stale.setId(7L);
        stale.setStatus("RUNNING");
        when(mapper.selectStaleRunning(15)).thenReturn(List.of(stale));

        service().markStaleRunning();

        verify(mapper).updateById(org.mockito.ArgumentMatchers.argThat(
                op -> "UNKNOWN".equals(op.getStatus())));
    }

    private static com.coding.common.models.k8s.dto.ComponentInstallResultDTO successResult() {
        var r = new com.coding.common.models.k8s.dto.ComponentInstallResultDTO();
        r.setSuccess(true);
        var s = new com.coding.common.models.k8s.dto.ComponentInstallResultDTO.ReleaseStatusDTO();
        s.setReleaseName("platform-metrics-server");
        s.setStatus("SUCCESS");
        r.setReleases(List.of(s));
        return r;
    }

    private static com.coding.common.models.k8s.dto.ComponentInstallResultDTO failureResult() {
        var r = successResult();
        r.setSuccess(false);
        r.setMessage("预检失败：values path not found");
        r.getReleases().get(0).setStatus("FAILED");
        return r;
    }
}
