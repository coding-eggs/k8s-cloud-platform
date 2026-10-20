package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.ComponentInstallResultDTO;
import com.coding.common.models.k8s.dto.GatewayListenerDTO;
import com.coding.common.models.k8s.dto.ReleaseInstallSpecDTO;
import com.coding.data.mapper.system.PlatformComponentOperationMapper;
import com.coding.data.models.system.PlatformComponentOperation;
import com.coding.platformapi.components.ComponentRecipeCatalog;
import com.coding.platformapi.k8s.K8sComponentClient;
import com.coding.platformapi.models.ComponentApplyRequest;
import com.coding.platformapi.models.ComponentStatusDTO;
import com.coding.platformapi.models.EsConfig;
import com.coding.platformapi.models.OtelInstallRequest;
import com.coding.platformapi.models.SecretRef;
import com.coding.platformapi.models.TempoStorageConfig;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
    void istioOperationRecordsThePrimaryChartVersionNotGatewayApi() {
        // Istio 的第一个 release 是 Gateway API（1.6.2），组件本体是 istiod（1.30.6）。
        // mergeValues 重建 ReleaseSpec 时必须保留 primary 标记，否则操作记录会写成 1.6.2，
        // 页面此后一直显示「可升级」（2026-10-20 用户指出）
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(mapper.countRunning("c1", "istio")).thenReturn(0);
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());
        ArgumentCaptor<PlatformComponentOperation> inserted =
                ArgumentCaptor.forClass(PlatformComponentOperation.class);

        service().apply(new ComponentApplyRequest("c1", "istio", "INSTALL", null, Map.of()), "admin");

        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getToVersion()).isEqualTo("1.30.6");
    }

    @Test
    void istioInstallCreatesTheExternalGatewayAndStripsListenerFieldsFromHelmValues() {
        // 组件负责建 external-gateway CR（并把 addresses 绑到 Service）；
        // 端口行里的 listener/tlsSecret 只给 Gateway 用，不能进 Helm 的 Service values
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(mapper.countRunning("c1", "istio")).thenReturn(0);
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());
        when(client.ensureExternalGateway(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);

        service().apply(new ComponentApplyRequest("c1", "istio", "INSTALL", null, Map.of()), "admin");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReleaseInstallSpecDTO>> specs = ArgumentCaptor.forClass(List.class);
        verify(client, timeout(5000)).applyComponent(eq("c1"), eq("istio"), eq("INSTALL"), specs.capture());
        ReleaseInstallSpecDTO gatewayRelease = specs.getValue().stream()
                .filter(r -> "external-gateway".equals(r.getReleaseName())).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ports = (List<Map<String, Object>>)
                ((Map<String, Object>) gatewayRelease.getValues().get("service")).get("ports");
        assertThat(ports).hasSize(3);
        assertThat(ports).extracting(p -> p.get("port")).containsExactly(15021, 80, 443);
        assertThat(ports).allSatisfy(p -> assertThat(p).doesNotContainKeys("listener", "tlsSecret"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GatewayListenerDTO>> listeners = ArgumentCaptor.forClass(List.class);
        verify(client, timeout(5000)).ensureExternalGateway(eq("c1"), eq("istio-system"),
                eq("external-gateway"), eq("external-gateway"), listeners.capture());
        // 15021 仅 Service（不监听）；80 → HTTP；443 → TLS Passthrough
        assertThat(listeners.getValue()).extracting(GatewayListenerDTO::getPort)
                .containsExactly(80, 443);
        assertThat(listeners.getValue()).extracting(GatewayListenerDTO::getProtocol)
                .containsExactly("HTTP", "TLS");
        assertThat(listeners.getValue()).extracting(GatewayListenerDTO::getTlsMode)
                .containsExactly(null, "Passthrough");
    }

    @Test
    void replayingAnOlderSnapshotFillsTheMissingPortListeners() {
        // 功能上线前装的 istio：快照里的端口行没有 listener。重放要按默认行补齐，
        // 否则 external-gateway CR 不会生成（用户会看到"重放了但没变化"）
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(clusterService.requireName("c1")).thenReturn("coding-cluster");
        when(mapper.countRunning("c1", "istio")).thenReturn(0);
        PlatformComponentOperation last = new PlatformComponentOperation();
        last.setComponentCode("istio");
        last.setStatus("SUCCESS");
        last.setToVersion("1.30.6");
        last.setValuesSnapshot("{\"external-gateway\":{\"name\":\"external-gateway\",\"service\":{"
                + "\"type\":\"NodePort\",\"ports\":["
                + "{\"name\":\"status-port\",\"port\":15021,\"targetPort\":15021,\"protocol\":\"TCP\"},"
                + "{\"name\":\"http2\",\"port\":80,\"targetPort\":80,\"protocol\":\"TCP\",\"nodePort\":80},"
                + "{\"name\":\"https\",\"port\":443,\"targetPort\":443,\"protocol\":\"TCP\",\"nodePort\":443}]}}}");
        when(mapper.selectLatestSuccess("c1", "istio")).thenReturn(last);
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());
        when(client.ensureExternalGateway(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);

        service().apply(new ComponentApplyRequest("c1", "istio", "UPGRADE", null, Map.of()), "admin");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GatewayListenerDTO>> listeners = ArgumentCaptor.forClass(List.class);
        verify(client, timeout(5000)).ensureExternalGateway(eq("c1"), eq("istio-system"),
                eq("external-gateway"), eq("external-gateway"), listeners.capture());
        assertThat(listeners.getValue()).extracting(GatewayListenerDTO::getPort)
                .containsExactly(80, 443);
        assertThat(listeners.getValue()).extracting(GatewayListenerDTO::getProtocol)
                .containsExactly("HTTP", "TLS");
        assertThat(listeners.getValue()).extracting(GatewayListenerDTO::getTlsMode)
                .containsExactly(null, "Passthrough");
    }

    @Test
    void anExplicitServiceOnlyPortIsNotReintroducedByTheListenerDefaults() {
        // 用户把 443 改成「仅 Service」：提交的端口行里 listener=""。
        // 不能被"存量快照补齐"逻辑当成缺失再补回 TLS（2026-10-20 实报：选了仅 Service 仍出现在 Gateway CR）
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(clusterService.requireName("c1")).thenReturn("coding-cluster");
        when(mapper.countRunning("c1", "istio")).thenReturn(0);
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());
        when(client.ensureExternalGateway(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);
        Map<String, Object> editable = Map.of("service.ports", List.of(
                Map.of("name", "status-port", "port", 15021, "targetPort", 15021,
                        "protocol", "TCP", "listener", ""),
                Map.of("name", "http2", "port", 80, "targetPort", 80,
                        "protocol", "TCP", "listener", "HTTP"),
                Map.of("name", "https", "port", 443, "targetPort", 443,
                        "protocol", "TCP", "listener", "")));

        service().apply(new ComponentApplyRequest("c1", "istio", "INSTALL", null, editable), "admin");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GatewayListenerDTO>> listeners = ArgumentCaptor.forClass(List.class);
        verify(client, timeout(5000)).ensureExternalGateway(eq("c1"), eq("istio-system"),
                eq("external-gateway"), eq("external-gateway"), listeners.capture());
        assertThat(listeners.getValue()).extracting(GatewayListenerDTO::getPort)
                .containsExactly(80);
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
    void anUpgradeAfterAFailedPlatformInstallIsStillAllowed() {
        // 平台自己装过但没成功（例如 Tempo 起不来、helm 超时）：release 是平台建的，
        // 不能因为一次失败就把组件永久锁成"外部安装"，否则用户连改配置重试都做不到
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(mapper.countRunning("c1", "metrics-server")).thenReturn(0);
        when(mapper.selectLatestSuccess("c1", "metrics-server")).thenReturn(null);
        when(mapper.countAttempted("c1", "metrics-server")).thenReturn(1);
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());

        service().apply(new ComponentApplyRequest("c1", "metrics-server", "UPGRADE", null, null), "admin");

        verify(client, timeout(5000)).applyComponent(anyString(), anyString(), anyString(), any());
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

        assertThatThrownBy(() -> svc.apply(new ComponentApplyRequest(
                "c1", "prometheus-operator", "INSTALL", null, Map.of()), "admin"))
                .hasMessageContaining("thanos.receive.url");
        verify(mapper, never()).insert(any());
        verify(client, never()).applyComponent(anyString(), anyString(), anyString(), any());
    }

    @Test
    void platformAlertingIsPushedAfterASuccessfulInstall() {
        // 告警联动：安装成功后下发 Secret + AlertmanagerConfig；token 必须剥掉 Bearer 前缀
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
                                Map.of(), Map.of())), List.of(), true));

        ComponentInstallService svc = service(catalog);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "alertWebhookToken", "Bearer sd");

        svc.apply(new ComponentApplyRequest("c1", "prometheus-operator", "INSTALL", null, Map.of(),
                "http://10.0.0.5:8081/alert/webhook"), "admin");

        verify(client, timeout(5000)).applyPlatformAlerting(
                org.mockito.ArgumentMatchers.eq("c1"),
                org.mockito.ArgumentMatchers.eq("monitor"),
                org.mockito.ArgumentMatchers.eq("http://10.0.0.5:8081/alert/webhook?clusterId=c1"),
                org.mockito.ArgumentMatchers.eq("sd"));
        verify(mapper, timeout(5000)).updateById(org.mockito.ArgumentMatchers.argThat(
                op -> "SUCCESS".equals(op.getStatus())));
    }

    @Test
    void aFailedPlatformAlertingMarksTheOperationFailedWithAClearMessage() {
        // helm 成功但告警联动失败：不能报 SUCCESS 后静默丢掉 —— 告警收不到，页面却显示装好了
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(clusterService.requireName("c1")).thenReturn("coding-cluster");
        when(mapper.countRunning("c1", "prometheus-operator")).thenReturn(0);
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());
        doThrow(new RuntimeException("subjectaccessreviews forbidden"))
                .when(client).applyPlatformAlerting(anyString(), anyString(), anyString(), anyString());
        com.coding.platformapi.components.ComponentRecipeCatalog catalog =
                mock(com.coding.platformapi.components.ComponentRecipeCatalog.class);
        when(catalog.recipeOf("prometheus-operator", "BUSINESS")).thenReturn(
                new com.coding.platformapi.components.ComponentRecipe("BUSINESS", List.of(
                        new com.coding.platformapi.components.ReleaseSpec(
                                "platform-kps", "monitor", "prometheus-operator", "92.10.1",
                                Map.of(), Map.of())), List.of(), true));

        ComponentInstallService svc = service(catalog);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "alertWebhookToken", "Bearer sd");

        svc.apply(new ComponentApplyRequest("c1", "prometheus-operator", "INSTALL", null, Map.of(),
                "http://10.0.0.5:8081/alert/webhook"), "admin");

        verify(mapper, timeout(5000)).updateById(org.mockito.ArgumentMatchers.argThat(
                op -> "FAILED".equals(op.getStatus()) && op.getMessage().contains("告警联动")));
    }

    @Test
    void theFormSuppliedWebhookUrlIsAppendedWithTheClusterId() {
        // 用户在建表里填的地址优先；clusterId 仍由后端拼
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
                                Map.of(), Map.of())), List.of(), true));

        ComponentInstallService svc = service(catalog);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "alertWebhookToken", "Bearer sd");

        svc.apply(new ComponentApplyRequest("c1", "prometheus-operator", "INSTALL", null, Map.of(),
                "http://10.1.2.3:9000/hook"), "admin");

        verify(client, timeout(5000)).applyPlatformAlerting(
                org.mockito.ArgumentMatchers.eq("c1"),
                org.mockito.ArgumentMatchers.eq("monitor"),
                org.mockito.ArgumentMatchers.eq("http://10.1.2.3:9000/hook?clusterId=c1"),
                org.mockito.ArgumentMatchers.eq("sd"));
    }

    @Test
    void aMissingWebhookUrlIsRejectedBeforeInstalling() {
        // 没有地址就别开始装 —— 装完再失败会让用户以为告警已生效
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        com.coding.platformapi.components.ComponentRecipeCatalog catalog =
                mock(com.coding.platformapi.components.ComponentRecipeCatalog.class);
        when(catalog.recipeOf("prometheus-operator", "BUSINESS")).thenReturn(
                new com.coding.platformapi.components.ComponentRecipe("BUSINESS", List.of(
                        new com.coding.platformapi.components.ReleaseSpec(
                                "platform-kps", "monitor", "prometheus-operator", "92.10.1",
                                Map.of(), Map.of())), List.of(), true));

        ComponentInstallService svc = service(catalog);

        assertThatThrownBy(() -> svc.apply(new ComponentApplyRequest(
                "c1", "prometheus-operator", "INSTALL", null, Map.of()), "admin"))
                .hasMessageContaining("告警回调地址");
        verify(mapper, never()).insert(any());
        verify(client, never()).applyComponent(anyString(), anyString(), anyString(), any());
    }

    @Test
    void theFormSuppliedThanosUrlIsUsed() {
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
                                Map.of("remoteWrite", List.of(Map.of("url", "${thanos.receive.url}"))),
                                Map.of())), List.of(), false, true));

        ComponentInstallService svc = service(catalog);

        svc.apply(new ComponentApplyRequest("c1", "prometheus-operator", "INSTALL", null, Map.of(),
                null, "http://thanos:9999/receive"), "admin");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<ReleaseInstallSpecDTO>> captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(client, timeout(5000)).applyComponent(anyString(), anyString(), anyString(), captor.capture());
        Object remoteWrite = captor.getValue().get(0).getValues().get("remoteWrite");
        assertThat(remoteWrite.toString()).contains("http://thanos:9999/receive").doesNotContain("default:19291");
    }

    @Test
    void aMissingThanosUrlIsRejectedBeforeInstalling() {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        com.coding.platformapi.components.ComponentRecipeCatalog catalog =
                mock(com.coding.platformapi.components.ComponentRecipeCatalog.class);
        when(catalog.recipeOf("prometheus-operator", "BUSINESS")).thenReturn(
                new com.coding.platformapi.components.ComponentRecipe("BUSINESS", List.of(
                        new com.coding.platformapi.components.ReleaseSpec(
                                "platform-kps", "monitor", "prometheus-operator", "92.10.1",
                                Map.of(), Map.of())), List.of(), false, true));

        ComponentInstallService svc = service(catalog);

        assertThatThrownBy(() -> svc.apply(new ComponentApplyRequest(
                "c1", "prometheus-operator", "INSTALL", null, Map.of()), "admin"))
                .hasMessageContaining("Thanos Receive");
        verify(mapper, never()).insert(any());
    }

    @Test
    void thePreviouslyUsedAddressesAreReusedWhenTheFormDoesNotSendThem() {
        // 表单只在"改过"时才发地址；没发就沿用上次成功操作里记的（__platform），别被平台默认值覆盖
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(clusterService.requireName("c1")).thenReturn("coding-cluster");
        when(mapper.countRunning("c1", "prometheus-operator")).thenReturn(0);
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());
        PlatformComponentOperation last = new PlatformComponentOperation();
        last.setId(1L);
        last.setToVersion("92.10.1");
        last.setValuesSnapshot("{\"platform-kps\":{}, \"__platform\":{"
                + "\"alertWebhookUrl\":\"http://prev:8081/alert/webhook\","
                + "\"thanosReceiveUrl\":\"http://prev-thanos:19291/receive\"}}");
        when(mapper.selectLatestSuccess("c1", "prometheus-operator")).thenReturn(last);
        com.coding.platformapi.components.ComponentRecipeCatalog catalog =
                mock(com.coding.platformapi.components.ComponentRecipeCatalog.class);
        when(catalog.recipeOf("prometheus-operator", "BUSINESS")).thenReturn(
                new com.coding.platformapi.components.ComponentRecipe("BUSINESS", List.of(
                        new com.coding.platformapi.components.ReleaseSpec(
                                "platform-kps", "monitor", "prometheus-operator", "92.10.1",
                                Map.of("remoteWrite", List.of(Map.of("url", "${thanos.receive.url}"))),
                                Map.of())), List.of(), true, true));

        ComponentInstallService svc = service(catalog);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "alertWebhookToken", "sd");

        svc.apply(new ComponentApplyRequest("c1", "prometheus-operator", "UPGRADE", null, Map.of()), "admin");

        verify(client, timeout(5000)).applyPlatformAlerting(
                org.mockito.ArgumentMatchers.eq("c1"),
                org.mockito.ArgumentMatchers.eq("monitor"),
                org.mockito.ArgumentMatchers.eq("http://prev:8081/alert/webhook?clusterId=c1"),
                org.mockito.ArgumentMatchers.eq("sd"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<ReleaseInstallSpecDTO>> captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(client, timeout(5000)).applyComponent(anyString(), anyString(), anyString(), captor.capture());
        assertThat(captor.getValue().get(0).getValues().toString()).contains("http://prev-thanos:19291/receive");
    }

    @Test
    void editableDefaultsApplyOnFirstInstallAndBlankStorageClassIsOmitted() {
        // 表单没改的字段 = recipe 默认值（第一次安装没有"上次快照"可继承）。
        // 空的 StorageClass 表示"跟随集群默认存储类"，不能以 storageClassName: "" 落进 values ——
        // K8s 里空串是"不用动态供给"，会装出一个永远 Pending 的 PVC。
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
                                        "externalLabels", Map.of("cluster_name", "${cluster.name}")))),
                                Map.of(
                                        "prometheus.prometheusSpec.retention", "15d",
                                        "prometheus.prometheusSpec.storageSpec"
                                                + ".volumeClaimTemplate.spec.storageClassName", "",
                                        "prometheus.prometheusSpec.storageSpec"
                                                + ".volumeClaimTemplate.spec.resources.requests.storage", "50Gi"))),
                        List.of()));

        service(catalog).apply(new ComponentApplyRequest("c1", "prometheus-operator", "INSTALL",
                null, null), "admin");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<ReleaseInstallSpecDTO>> captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(client, timeout(5000)).applyComponent(
                org.mockito.ArgumentMatchers.eq("c1"), org.mockito.ArgumentMatchers.eq("prometheus-operator"),
                org.mockito.ArgumentMatchers.eq("INSTALL"), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) ((Map<String, Object>)
                captor.getValue().get(0).getValues().get("prometheus")).get("prometheusSpec");

        assertThat(spec).containsEntry("retention", "15d");
        @SuppressWarnings("unchecked")
        Map<String, Object> claimSpec = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                spec.get("storageSpec")).get("volumeClaimTemplate")).get("spec");
        @SuppressWarnings("unchecked")
        Map<String, Object> requests = (Map<String, Object>) ((Map<String, Object>)
                claimSpec.get("resources")).get("requests");
        assertThat(requests).containsEntry("storage", "50Gi");
        assertThat(claimSpec).doesNotContainKey("storageClassName");
    }

    @Test
    void aCredentialSecretOutsideTheThanosNamespaceIsRejected() {
        // 凭证只允许在 Thanos 自己的命名空间（monitor）里找
        when(clusterService.requireProfile("c1")).thenReturn("MANAGEMENT");
        com.coding.platformapi.components.ComponentRecipeCatalog catalog =
                mock(com.coding.platformapi.components.ComponentRecipeCatalog.class);
        when(catalog.recipeOf("prometheus-operator", "MANAGEMENT")).thenReturn(
                new com.coding.platformapi.components.ComponentRecipe("MANAGEMENT", List.of(
                        new com.coding.platformapi.components.ReleaseSpec(
                                "platform-thanos", "monitor", "thanos", "0.47.2", Map.of(), Map.of())),
                        List.of(), false, false, true));

        ComponentInstallService svc = service(catalog);

        assertThatThrownBy(() -> svc.apply(new ComponentApplyRequest("c1", "prometheus-operator",
                "INSTALL", null, null, null, null, new com.coding.platformapi.models.ThanosInstallRequest(
                        "default", "minio-creds", "minio.default.svc:9000", "thanos", false)), "admin"))
                .hasMessageContaining("monitor");
        verify(mapper, never()).insert(any());
    }

    @Test
    void aBundledReleaseVersionIsNotAValidTargetVersion() {
        // 0.47.2 是随附的 platform-thanos 的版本，不是 prometheus-operator 的版本。
        // 允许它当 targetVersion 会把操作记录写成"已升级到 0.47.2"，下次页面就按这个版本比
        when(clusterService.requireProfile("c1")).thenReturn("MANAGEMENT");
        when(clusterService.requireName("c1")).thenReturn("coding-cluster");
        when(mapper.countRunning("c1", "prometheus-operator")).thenReturn(0);
        com.coding.platformapi.components.ComponentRecipeCatalog catalog =
                mock(com.coding.platformapi.components.ComponentRecipeCatalog.class);
        when(catalog.recipeOf("prometheus-operator", "MANAGEMENT")).thenReturn(
                new com.coding.platformapi.components.ComponentRecipe("MANAGEMENT", List.of(
                        new com.coding.platformapi.components.ReleaseSpec(
                                "platform-kps", "monitor", "prometheus-operator", "92.10.1", Map.of(), Map.of()),
                        new com.coding.platformapi.components.ReleaseSpec(
                                "platform-thanos", "monitor", "thanos", "0.47.2", Map.of(), Map.of())),
                        List.of()));

        assertThatThrownBy(() -> service(catalog).apply(new ComponentApplyRequest(
                "c1", "prometheus-operator", "INSTALL", "0.47.2", null), "admin"))
                .hasMessageContaining("未知的目标版本");
        verify(mapper, never()).insert(any());
    }

    @Test
    void managementPrometheusDefaultsRemoteWriteToTheInClusterReceiver() {
        // 表单不填时用 recipe 的预填值（集群内 receive，10908）。
        // 平台配置 thanos.receive.url 是业务集群的跨集群入口，不能拿来当管理集群的默认值
        when(clusterService.requireProfile("c1")).thenReturn("MANAGEMENT");
        when(clusterService.requireName("c1")).thenReturn("coding-cluster");
        when(mapper.countRunning("c1", "prometheus-operator")).thenReturn(0);
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());
        com.coding.platformapi.components.ComponentRecipeCatalog catalog =
                mock(com.coding.platformapi.components.ComponentRecipeCatalog.class);
        when(catalog.recipeOf("prometheus-operator", "MANAGEMENT")).thenReturn(
                new com.coding.platformapi.components.ComponentRecipe("MANAGEMENT", List.of(
                        new com.coding.platformapi.components.ReleaseSpec(
                                "platform-kps", "monitor", "prometheus-operator", "92.10.1",
                                Map.of("prometheus", Map.of("prometheusSpec", Map.of(
                                        "remoteWrite", List.of(Map.of("url", "${thanos.receive.url}"))))),
                                Map.of()),
                        new com.coding.platformapi.components.ReleaseSpec(
                                "platform-thanos", "monitor", "thanos", "0.47.2", Map.of(), Map.of())),
                        List.of(), false, true, true, false,
                        "http://platform-thanos-receive.monitor.svc:10908/api/v1/receive"));

        ComponentInstallService svc = service(catalog);

        svc.apply(new ComponentApplyRequest("c1", "prometheus-operator", "INSTALL", null, null, null, null,
                new com.coding.platformapi.models.ThanosInstallRequest(
                        "monitor", "minio-creds", "minio.default.svc:9000", "thanos", false)), "admin");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<ReleaseInstallSpecDTO>> captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(client, timeout(5000)).applyComponent(
                org.mockito.ArgumentMatchers.eq("c1"), org.mockito.ArgumentMatchers.eq("prometheus-operator"),
                org.mockito.ArgumentMatchers.eq("INSTALL"), captor.capture());
        assertThat(captor.getValue().get(0).getValues().toString())
                .contains("platform-thanos-receive.monitor.svc:10908")
                .doesNotContain("9999");
    }

    @Test
    void tektonProfileFromTheFormLandsInTektonConfig() {
        // profile 是安装表单参数：占位符 ${tekton.profile} 必须替换成用户选的值
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(clusterService.requireName("c1")).thenReturn("coding-cluster");
        when(mapper.countRunning("c1", "tekton")).thenReturn(0);
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());
        com.coding.platformapi.components.ComponentRecipeCatalog catalog =
                mock(com.coding.platformapi.components.ComponentRecipeCatalog.class);
        when(catalog.recipeOf("tekton", "BUSINESS")).thenReturn(
                new com.coding.platformapi.components.ComponentRecipe("BUSINESS", List.of(
                        new com.coding.platformapi.components.ReleaseSpec(
                                "platform-tekton-operator", "tekton-operator", "tekton-operator", "0.81.2",
                                Map.of(
                                        "operator", Map.of("autoInstallComponents", false),
                                        "extraObjects", List.of(Map.of(
                                                "kind", "TektonConfig",
                                                "spec", Map.of("profile", "${tekton.profile}")))),
                                Map.of())),
                        List.of(), false, false, false, false, null, true));

        ComponentApplyRequest req = new ComponentApplyRequest("c1", "tekton", "INSTALL", null, null);
        req.setTektonProfile("basic");
        service(catalog).apply(req, "admin");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<ReleaseInstallSpecDTO>> captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(client, timeout(5000)).applyComponent(
                org.mockito.ArgumentMatchers.eq("c1"), org.mockito.ArgumentMatchers.eq("tekton"),
                org.mockito.ArgumentMatchers.eq("INSTALL"), captor.capture());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> extra = (List<Map<String, Object>>)
                captor.getValue().get(0).getValues().get("extraObjects");
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) extra.get(0).get("spec");
        assertThat(spec).containsEntry("profile", "basic");
    }

    @Test
    void anUnknownTektonProfileIsRejectedBeforeInstalling() {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        com.coding.platformapi.components.ComponentRecipeCatalog catalog =
                mock(com.coding.platformapi.components.ComponentRecipeCatalog.class);
        when(catalog.recipeOf("tekton", "BUSINESS")).thenReturn(
                new com.coding.platformapi.components.ComponentRecipe("BUSINESS", List.of(
                        new com.coding.platformapi.components.ReleaseSpec(
                                "platform-tekton-operator", "tekton-operator", "tekton-operator", "0.81.2",
                                Map.of(), Map.of())),
                        List.of(), false, false, false, false, null, true));

        ComponentApplyRequest req = new ComponentApplyRequest("c1", "tekton", "INSTALL", null, null);
        req.setTektonProfile("full");
        assertThatThrownBy(() -> service(catalog).apply(req, "admin"))
                .hasMessageContaining("不支持的 Tekton profile");
        verify(mapper, never()).insert(any());
    }

    @Test
    void thanosObjstoreIsWrittenBeforeHelm() {
        // Pod 会挂载 objstore Secret：必须先生成它，再跑 helm
        when(clusterService.requireProfile("c1")).thenReturn("MANAGEMENT");
        when(clusterService.requireName("c1")).thenReturn("coding-cluster");
        when(mapper.countRunning("c1", "prometheus-operator")).thenReturn(0);
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());
        com.coding.platformapi.components.ComponentRecipeCatalog catalog =
                mock(com.coding.platformapi.components.ComponentRecipeCatalog.class);
        when(catalog.recipeOf("prometheus-operator", "MANAGEMENT")).thenReturn(
                new com.coding.platformapi.components.ComponentRecipe("MANAGEMENT", List.of(
                        new com.coding.platformapi.components.ReleaseSpec(
                                "platform-kps", "monitor", "prometheus-operator", "92.10.1", Map.of(), Map.of())),
                        List.of(), true, false, true));

        ComponentInstallService svc = service(catalog);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "alertWebhookToken", "sd");

        svc.apply(new ComponentApplyRequest("c1", "prometheus-operator", "INSTALL", null, Map.of(),
                "http://10.0.0.5:8081/alert/webhook", null,
                new com.coding.platformapi.models.ThanosInstallRequest(
                        "monitor", "minio-creds", "minio.default.svc:9000", "thanos", true)), "admin");

        verify(mapper, timeout(5000)).updateById(org.mockito.ArgumentMatchers.argThat(
                op -> "SUCCESS".equals(op.getStatus())));
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(client);
        inOrder.verify(client).applyThanosObjstore("c1", "monitor", "minio-creds",
                "minio.default.svc:9000", "thanos", true, "monitor", "platform-thanos-objstore");
        inOrder.verify(client).applyComponent(anyString(), anyString(), anyString(), any());
    }

    @Test
    void aMissingThanosParameterIsRejectedBeforeInstalling() {
        when(clusterService.requireProfile("c1")).thenReturn("MANAGEMENT");
        com.coding.platformapi.components.ComponentRecipeCatalog catalog =
                mock(com.coding.platformapi.components.ComponentRecipeCatalog.class);
        when(catalog.recipeOf("prometheus-operator", "MANAGEMENT")).thenReturn(
                new com.coding.platformapi.components.ComponentRecipe("MANAGEMENT", List.of(
                        new com.coding.platformapi.components.ReleaseSpec(
                                "platform-kps", "monitor", "prometheus-operator", "92.10.1", Map.of(), Map.of())),
                        List.of(), true, false, true));

        ComponentInstallService svc = service(catalog);

        assertThatThrownBy(() -> svc.apply(new ComponentApplyRequest(
                "c1", "prometheus-operator", "INSTALL", null, Map.of(),
                "http://10.0.0.5:8081/alert/webhook"), "admin"))
                .hasMessageContaining("凭证 Secret");
        verify(mapper, never()).insert(any());
    }

    @Test
    void theFormSuppliedCalicoParametersAreUsed() {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(clusterService.requireName("c1")).thenReturn("coding-cluster");
        when(mapper.countRunning("c1", "calico")).thenReturn(0);
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());
        com.coding.platformapi.components.ComponentRecipeCatalog catalog =
                mock(com.coding.platformapi.components.ComponentRecipeCatalog.class);
        when(catalog.recipeOf("calico", "BUSINESS")).thenReturn(
                new com.coding.platformapi.components.ComponentRecipe("BUSINESS", List.of(
                        new com.coding.platformapi.components.ReleaseSpec(
                                "platform-tigera-operator", "tigera-operator", "tigera-operator", "v3.33.0",
                                Map.of("installation", Map.of("calicoNetwork", Map.of(
                                        "ipPools", List.of(Map.of(
                                                "cidr", "${calico.podCidr}",
                                                "encapsulation", "${calico.encapsulation}"))))),
                                Map.of())), List.of(), false, false, false, true));

        ComponentInstallService svc = service(catalog);

        svc.apply(new ComponentApplyRequest("c1", "calico", "INSTALL", null, Map.of(), null, null, null,
                new com.coding.platformapi.models.CalicoInstallRequest("172.16.0.0/16", "IPIP")), "admin");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<ReleaseInstallSpecDTO>> captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(client, timeout(5000)).applyComponent(anyString(), anyString(), anyString(), captor.capture());
        assertThat(captor.getValue().get(0).getValues().toString())
                .contains("172.16.0.0/16").contains("IPIP")
                .doesNotContain("10.0.0.0/16");
    }

    @Test
    void aMissingCalicoCidrIsRejectedBeforeInstalling() {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        com.coding.platformapi.components.ComponentRecipeCatalog catalog =
                mock(com.coding.platformapi.components.ComponentRecipeCatalog.class);
        when(catalog.recipeOf("calico", "BUSINESS")).thenReturn(
                new com.coding.platformapi.components.ComponentRecipe("BUSINESS", List.of(
                        new com.coding.platformapi.components.ReleaseSpec(
                                "platform-tigera-operator", "tigera-operator", "tigera-operator", "v3.33.0",
                                Map.of(), Map.of())), List.of(), false, false, false, true));

        ComponentInstallService svc = service(catalog);

        assertThatThrownBy(() -> svc.apply(new ComponentApplyRequest(
                "c1", "calico", "INSTALL", null, Map.of()), "admin"))
                .hasMessageContaining("Pod CIDR");
        verify(mapper, never()).insert(any());
    }

    @Test
    void abandonMarksARunningOperationAsCanceled() {
        // worker 随进程重启消失时，RUNNING 记录会一直占着并发锁；允许管理员主动中止解锁
        PlatformComponentOperation running = new PlatformComponentOperation();
        running.setId(16L);
        running.setStatus("RUNNING");
        when(mapper.selectByPrimaryKey(16L)).thenReturn(running);

        service().abandon(16L, "admin");

        ArgumentCaptor<PlatformComponentOperation> captor =
                ArgumentCaptor.forClass(PlatformComponentOperation.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("CANCELED");
        assertThat(captor.getValue().getMessage()).contains("中止").contains("admin");
        assertThat(captor.getValue().getFinishedAt()).isNotNull();
    }

    @Test
    void abandonRejectsAnOperationThatAlreadyFinished() {
        PlatformComponentOperation done = new PlatformComponentOperation();
        done.setId(16L);
        done.setStatus("SUCCESS");
        when(mapper.selectByPrimaryKey(16L)).thenReturn(done);

        assertThatThrownBy(() -> service().abandon(16L, "admin"))
                .hasMessageContaining("已结束");
        verify(mapper, never()).updateById(any());
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

    // ==================== OTel 采集栈（批次一） ====================

    @Test
    void otelRequiresATempoEndpoint() {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(mapper.countRunning("c1", "otel-operator")).thenReturn(0);
        when(components.status("c1")).thenReturn(List.of(healthy("cert-manager")));
        OtelInstallRequest otel = baseOtel();
        otel.setTempoEndpoint(" ");
        ComponentApplyRequest req = new ComponentApplyRequest(
                "c1", "otel-operator", "INSTALL", null, Map.of());
        req.setOtel(otel);

        assertThatThrownBy(() -> service().apply(req, "admin"))
                .hasMessageContaining("Tempo");
        verify(mapper, never()).insert(any());
    }

    @Test
    void otelSamplingAndReplicasAreValidated() {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(mapper.countRunning("c1", "otel-operator")).thenReturn(0);
        when(components.status("c1")).thenReturn(List.of(healthy("cert-manager")));
        OtelInstallRequest otel = baseOtel();
        ComponentApplyRequest req = new ComponentApplyRequest(
                "c1", "otel-operator", "INSTALL", null, Map.of());
        req.setOtel(otel);

        otel.setSamplingPercentage(0);
        assertThatThrownBy(() -> service().apply(req, "admin"))
                .hasMessageContaining("采样率");

        otel.setSamplingPercentage(10);
        otel.setGatewayReplicas(0);
        assertThatThrownBy(() -> service().apply(req, "admin"))
                .hasMessageContaining("副本数");

        verify(mapper, never()).insert(any());
    }

    @Test
    void otelInjectsCrObjectsOnlyIntoTheSecondOperatorPass() {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(clusterService.requireName("c1")).thenReturn("coding-cluster");
        when(mapper.countRunning("c1", "otel-operator")).thenReturn(0);
        when(components.status("c1")).thenReturn(List.of(healthy("cert-manager")));
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());
        ComponentApplyRequest req = new ComponentApplyRequest(
                "c1", "otel-operator", "INSTALL", null, Map.of());
        req.setOtel(baseOtel());

        service().apply(req, "admin");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReleaseInstallSpecDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(client, timeout(5000)).applyComponent(
                org.mockito.ArgumentMatchers.eq("c1"),
                org.mockito.ArgumentMatchers.eq("otel-operator"),
                org.mockito.ArgumentMatchers.eq("INSTALL"), captor.capture());
        List<ReleaseInstallSpecDTO> dto = captor.getValue();
        assertThat(dto).hasSize(2);
        // 第一遍只装 CRD+operator，不能带 CR；带上就会在 Helm Build 阶段找不到 CRD
        assertThat(dto.get(0).getValues()).doesNotContainKey("extraObjects");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> extra =
                (List<Map<String, Object>>) dto.get(1).getValues().get("extraObjects");
        assertThat(extra).hasSize(5);
        assertThat(extra.toString()).contains("platform-instrumentation")
                .contains("platform-otel-gateway").contains("platform-otel-sidecar").contains("4318");
    }

    @Test
    void otelSnapshotKeepsAddressesAndSecretRefsButNoCredentialValues() {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(clusterService.requireName("c1")).thenReturn("coding-cluster");
        when(mapper.countRunning("c1", "otel-operator")).thenReturn(0);
        when(components.status("c1")).thenReturn(List.of(healthy("cert-manager")));
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());
        ComponentApplyRequest req = new ComponentApplyRequest(
                "c1", "otel-operator", "INSTALL", null, Map.of());
        req.setOtel(baseOtel());

        service().apply(req, "admin");

        ArgumentCaptor<PlatformComponentOperation> captor =
                ArgumentCaptor.forClass(PlatformComponentOperation.class);
        verify(mapper).insert(captor.capture());
        String snapshot = captor.getValue().getValuesSnapshot();
        assertThat(snapshot).contains("tempoEndpoint")
                .contains("es-es-elastic-user")
                .contains("\"samplingPercentage\":10")
                .doesNotContain("codingegg").doesNotContain("minio-secret");
    }

    @Test
    void esSecretMustLiveInMonitorWhenLogsAreEnabled() {
        // 表单上 ES 凭证不再给命名空间选择：所有 OTel 凭证统一放 monitor。
        // 后端也钉死，避免绕过 UI 后 collector 读到别的命名空间的 Secret。
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(mapper.countRunning("c1", "otel-operator")).thenReturn(0);
        when(components.status("c1")).thenReturn(List.of(healthy("cert-manager")));
        OtelInstallRequest otel = baseOtel();
        otel.setLogsEnabled(true);
        otel.setEs(new EsConfig("https://es-es-http.default.svc:9200",
                new SecretRef("default", "es-es-elastic-user"), true));
        ComponentApplyRequest req = new ComponentApplyRequest(
                "c1", "otel-operator", "INSTALL", null, Map.of());
        req.setOtel(otel);

        assertThatThrownBy(() -> service().apply(req, "admin"))
                .hasMessageContaining("monitor");
        verify(mapper, never()).insert(any());
    }

    @Test
    void tempoUsesHttpsForBareHostAndSkipsVerifyWhenAsked() {
        // MinIO 默认是 HTTPS（表单「传输方式」显式选择）；自签证书由「跳过 TLS 校验」开关控制。
        // 之前 values 里写死 insecure: true → Tempo 用明文连 HTTPS 端口，连接被断开后 CrashLoop，
        // helm --wait 一直等到超时，操作卡在 RUNNING。
        @SuppressWarnings("unchecked")
        Map<String, Object> s3 = captureTempoS3(new TempoStorageConfig(
                "192.168.31.80:9002", "tempo", new SecretRef("monitor", "minio-creds"), true, false));

        assertThat(s3).containsEntry("insecure", false);
        assertThat(s3).containsEntry("tls_insecure_skip_verify", true);
        assertThat(s3).containsEntry("endpoint", "192.168.31.80:9002");
    }

    @Test
    void tempoTransportComesFromTheFormEvenIfTheAddressHasAScheme() {
        // 地址里手滑写了 http:// 不该偷偷切换成明文：传输方式只认表单的显式选择，
        // scheme 只负责剥掉（Tempo 的 s3.endpoint 只接受 host:port）
        @SuppressWarnings("unchecked")
        Map<String, Object> s3 = captureTempoS3(new TempoStorageConfig(
                "http://192.168.31.80:9002", "tempo", new SecretRef("monitor", "minio-creds"), true, false));

        assertThat(s3).containsEntry("insecure", false);
        assertThat(s3).containsEntry("endpoint", "192.168.31.80:9002");
    }

    @Test
    void tempoTreatsAnHttpEndpointAsPlaintextForLegacyCallers() {
        // 不带 plaintext 字段的旧调用（或旧快照）：仍按 scheme 推断，避免明文 MinIO 被切成 HTTPS
        @SuppressWarnings("unchecked")
        Map<String, Object> s3 = captureTempoS3(new TempoStorageConfig(
                "http://minio.default.svc:9000", "tempo", new SecretRef("monitor", "minio-creds"), false, null));

        assertThat(s3).containsEntry("insecure", true);
        assertThat(s3).containsEntry("tls_insecure_skip_verify", false);
        assertThat(s3).containsEntry("endpoint", "minio.default.svc:9000");
    }

    /** 跑一次 MANAGEMENT 的 otel-operator 安装，取出 Tempo release 的 s3 段 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> captureTempoS3(TempoStorageConfig storage) {
        when(clusterService.requireProfile("c1")).thenReturn("MANAGEMENT");
        when(clusterService.requireName("c1")).thenReturn("coding-cluster");
        when(mapper.countRunning("c1", "otel-operator")).thenReturn(0);
        when(components.status("c1")).thenReturn(List.of(healthy("cert-manager")));
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());

        OtelInstallRequest otel = baseOtel();
        otel.setTempoStorage(storage);
        ComponentApplyRequest req = new ComponentApplyRequest(
                "c1", "otel-operator", "INSTALL", null, Map.of());
        req.setOtel(otel);

        service().apply(req, "admin");

        ArgumentCaptor<List<ReleaseInstallSpecDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(client, timeout(5000)).applyComponent(
                org.mockito.ArgumentMatchers.eq("c1"),
                org.mockito.ArgumentMatchers.eq("otel-operator"),
                org.mockito.ArgumentMatchers.eq("INSTALL"), captor.capture());
        Map<String, Object> tempo = (Map<String, Object>) captor.getValue().get(1).getValues().get("tempo");
        Map<String, Object> storageNode = (Map<String, Object>) tempo.get("storage");
        Map<String, Object> trace = (Map<String, Object>) storageNode.get("trace");
        return (Map<String, Object>) trace.get("s3");
    }

    @Test
    void tempoStorageDefaultsToItsOwnBucketWithoutAPrefix() {
        // Tempo 不跟 Thanos 共用桶：默认落到独立桶 tempo，且不写 prefix（直接写桶根）
        when(clusterService.requireProfile("c1")).thenReturn("MANAGEMENT");
        when(clusterService.requireName("c1")).thenReturn("coding-cluster");
        when(mapper.countRunning("c1", "otel-operator")).thenReturn(0);
        when(components.status("c1")).thenReturn(List.of(healthy("cert-manager")));
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());

        TempoStorageConfig storage = new TempoStorageConfig();
        storage.setEndpoint("192.168.31.80:9002");
        storage.setCredentialSecret(new SecretRef("monitor", "minio-creds"));
        storage.setInsecureSkipVerify(true);
        OtelInstallRequest otel = baseOtel();
        otel.setTempoStorage(storage);
        ComponentApplyRequest req = new ComponentApplyRequest(
                "c1", "otel-operator", "INSTALL", null, Map.of());
        req.setOtel(otel);

        service().apply(req, "admin");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReleaseInstallSpecDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(client, timeout(5000)).applyComponent(
                org.mockito.ArgumentMatchers.eq("c1"),
                org.mockito.ArgumentMatchers.eq("otel-operator"),
                org.mockito.ArgumentMatchers.eq("INSTALL"), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> tempo = (Map<String, Object>) captor.getValue().get(1).getValues().get("tempo");
        assertThat(tempo.toString()).contains("bucket=tempo");
        // 不写 prefix：Tempo 直接把 block 写在桶根，不再和 Thanos 的 thanos/ 前缀混在一起
        assertThat(tempo.toString()).doesNotContain("prefix");
    }

    @Test
    void tempoRetentionAndTlsModeGoIntoTheReleaseValues() {
        when(clusterService.requireProfile("c1")).thenReturn("MANAGEMENT");
        when(clusterService.requireName("c1")).thenReturn("coding-cluster");
        when(mapper.countRunning("c1", "otel-operator")).thenReturn(0);
        when(components.status("c1")).thenReturn(List.of(healthy("cert-manager")));
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());

        OtelInstallRequest otel = baseOtel();
        otel.setTempoStorage(new TempoStorageConfig("192.168.31.80:9002", "tempo",
                new SecretRef("monitor", "minio-creds"), true, false));
        otel.setTempoRetention("336h");
        otel.setTempoTlsMode("tls-skip-verify");
        ComponentApplyRequest req = new ComponentApplyRequest(
                "c1", "otel-operator", "INSTALL", null, Map.of());
        req.setOtel(otel);

        service().apply(req, "admin");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReleaseInstallSpecDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(client, timeout(5000)).applyComponent(
                org.mockito.ArgumentMatchers.eq("c1"),
                org.mockito.ArgumentMatchers.eq("otel-operator"),
                org.mockito.ArgumentMatchers.eq("INSTALL"), captor.capture());
        List<ReleaseInstallSpecDTO> dto = captor.getValue();
        assertThat(dto).hasSize(3);
        @SuppressWarnings("unchecked")
        Map<String, Object> tempo = (Map<String, Object>) dto.get(1).getValues().get("tempo");
        assertThat(tempo).containsEntry("retention", "336h");
        assertThat(dto.get(2).getValues().get("extraObjects").toString())
                .contains("insecure_skip_verify=true");
    }

    @Test
    void otelSecretsAreWrittenBeforeHelm() {
        // Tempo/collector 的 Pod 会引用这两个 Secret：必须先生成，再跑 helm
        when(clusterService.requireProfile("c1")).thenReturn("MANAGEMENT");
        when(clusterService.requireName("c1")).thenReturn("coding-cluster");
        when(mapper.countRunning("c1", "otel-operator")).thenReturn(0);
        when(components.status("c1")).thenReturn(List.of(healthy("cert-manager")));
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());

        OtelInstallRequest otel = baseOtel();
        otel.setLogsEnabled(true);
        otel.setTempoStorage(new TempoStorageConfig("192.168.31.80:9002", "tempo",
                new SecretRef("monitor", "minio-creds"), true, false));
        ComponentApplyRequest req = new ComponentApplyRequest(
                "c1", "otel-operator", "INSTALL", null, Map.of());
        req.setOtel(otel);

        service().apply(req, "admin");

        // 等 worker 跑完再验顺序（apply 是异步的）
        verify(mapper, timeout(5000)).updateById(org.mockito.ArgumentMatchers.argThat(
                op -> "SUCCESS".equals(op.getStatus())));
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(client);
        inOrder.verify(client).applyTempoObjstore("c1", "monitor", "minio-creds",
                "monitor", "platform-tempo-objstore");
        inOrder.verify(client).applyOtelEsSecret("c1", "monitor", "es-es-elastic-user",
                "monitor", "platform-otel-es");
        inOrder.verify(client).applyComponent(anyString(), anyString(), anyString(), any());
    }

    @Test
    void anUpgradeDoesNotInheritExtraObjectsIntoTheFirstOperatorPass() {
        // 两遍同名 release 的快照会互相覆盖；extraObjects 必须每次重建，
        // 否则第一遍（只装 CRD）会从历史快照里继承到 CR，重新踩 CRD 时序
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(clusterService.requireName("c1")).thenReturn("coding-cluster");
        when(mapper.countRunning("c1", "otel-operator")).thenReturn(0);
        when(components.status("c1")).thenReturn(List.of(healthy("cert-manager")));
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());

        PlatformComponentOperation last = new PlatformComponentOperation();
        last.setId(1L);
        last.setToVersion("0.126.0");
        last.setValuesSnapshot(
                "{\"platform-otel-operator\":{\"extraObjects\":[{\"kind\":\"Instrumentation\"}]}}");
        when(mapper.selectLatestSuccess("c1", "otel-operator")).thenReturn(last);

        ComponentApplyRequest req = new ComponentApplyRequest(
                "c1", "otel-operator", "UPGRADE", null, Map.of());
        req.setOtel(baseOtel());
        service().apply(req, "admin");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReleaseInstallSpecDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(client, timeout(5000)).applyComponent(
                org.mockito.ArgumentMatchers.eq("c1"),
                org.mockito.ArgumentMatchers.eq("otel-operator"),
                org.mockito.ArgumentMatchers.eq("UPGRADE"), captor.capture());
        assertThat(captor.getValue().get(0).getValues()).doesNotContainKey("extraObjects");
        assertThat(captor.getValue().get(1).getValues()).containsKey("extraObjects");
    }

    @Test
    void anOtelInstallAlsoCreatesTheIngressTelemetry() {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(clusterService.requireName("c1")).thenReturn("coding-cluster");
        when(mapper.countRunning("c1", "otel-operator")).thenReturn(0);
        when(components.status("c1")).thenReturn(List.of(healthy("cert-manager")));
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());

        ComponentApplyRequest req = new ComponentApplyRequest(
                "c1", "otel-operator", "INSTALL", null, Map.of());
        req.setOtel(baseOtel());
        service().apply(req, "admin");

        verify(client, timeout(5000)).applyOtelTelemetry("c1", "istio-system",
                "platform-tracing-ingress", "external-gateway", 10, true);
    }

    @Test
    void anOtelServiceMonitorFailureDoesNotFailTheOperation() {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(clusterService.requireName("c1")).thenReturn("coding-cluster");
        when(mapper.countRunning("c1", "otel-operator")).thenReturn(0);
        when(components.status("c1")).thenReturn(List.of(healthy("cert-manager")));
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());
        when(client.applyOtelServiceMonitor("c1", "monitor"))
                .thenThrow(new RuntimeException("没有 ServiceMonitor CRD"));

        ComponentApplyRequest req = new ComponentApplyRequest(
                "c1", "otel-operator", "INSTALL", null, Map.of());
        req.setOtel(baseOtel());
        service().apply(req, "admin");

        verify(client, timeout(5000)).applyOtelServiceMonitor("c1", "monitor");
        verify(mapper, timeout(5000)).updateById(org.mockito.ArgumentMatchers.argThat(
                op -> "SUCCESS".equals(op.getStatus())));
    }

    private static ComponentStatusDTO healthy(String code) {
        ComponentStatusDTO s = new ComponentStatusDTO();
        s.setCode(code);
        s.setState(ClusterComponentService.HEALTHY);
        return s;
    }

    private static OtelInstallRequest baseOtel() {
        OtelInstallRequest o = new OtelInstallRequest();
        o.setTempoEndpoint("platform-tempo.monitor.svc:4317");
        o.setSamplingPercentage(10);
        o.setGatewayReplicas(1);
        o.setLogsEnabled(false);
        o.setEs(new EsConfig("https://es-es-http.default.svc:9200",
                new SecretRef("monitor", "es-es-elastic-user"), true));
        return o;
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
