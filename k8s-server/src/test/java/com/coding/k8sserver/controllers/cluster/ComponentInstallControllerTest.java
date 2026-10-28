package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.dto.ComponentInstallRequestDTO;
import com.coding.common.models.k8s.dto.ComponentInstallResultDTO;
import com.coding.common.models.k8s.dto.ReleaseInstallSpecDTO;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.helm.ChartBundle;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import com.coding.k8score.factory.KubernetesClientFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComponentInstallControllerTest {

    @Test
    void theEndpointIsPlatformBoundary() {
        assertThat(controller(mock(HelmRunner.class)).accessBoundary()).isEqualTo(AccessBoundary.PLATFORM);
    }

    @Test
    void itRendersThenAppliesAndReportsSuccess() {
        HelmRunner helm = mock(HelmRunner.class);
        when(helm.render(anyString(), any(), any(), anyString()))
                .thenReturn(new HelmResult(true, 0, "rendered"));
        when(helm.dryRunServer(anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(new HelmResult(true, 0, "dry-ok"));
        when(helm.apply(anyString(), any(), any(), anyString(), anyString(), anyMap()))
                .thenReturn(new HelmResult(true, 0, "deployed"));

        ComponentInstallResultDTO r = controller(helm).apply(request()).getData();

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getReleases()).hasSize(1);
        assertThat(r.getReleases().get(0).getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void aRenderFailureStopsBeforeTouchingTheCluster() {
        HelmRunner helm = mock(HelmRunner.class);
        when(helm.render(anyString(), any(), any(), anyString()))
                .thenReturn(new HelmResult(false, 1, "values path not found"));

        ComponentInstallResultDTO r = controller(helm).apply(request()).getData();

        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getMessage()).contains("预检");
        org.mockito.Mockito.verify(helm, org.mockito.Mockito.never())
                .apply(anyString(), any(), any(), anyString(), anyString(), anyMap());
    }

    @Test
    void aServerSideApplyReleaseSkipsHelmAndWaitsForItsCrds() {
        // 超大 chart（Calico CRD，渲染后 3.5MB）不能建 Helm release：Secret 1MiB 上限。
        // 这条 release 走 kubectl apply --server-side，并且要等 CRD Established 再装下一条
        // （否则 operator release 里的 Installation 还是 no matches for kind）
        HelmRunner helm = mock(HelmRunner.class);
        when(helm.renderFull(anyString(), any(), any(), anyString(), anyString(), any()))
                .thenReturn(new HelmResult(true, 0, CRD_MANIFEST));
        when(helm.applyServerSide(anyString(), anyString()))
                .thenReturn(new HelmResult(true, 0, "customresourcedefinition.apiextensions.k8s.io/"
                        + "installations.operator.tigera.io serverside-applied"));
        when(helm.waitCrdsEstablished(anyString(), any()))
                .thenReturn(new HelmResult(true, 0, "condition met"));

        ComponentInstallResultDTO r = controller(helm, kubeClient("1", "37"))
                .apply(request("SERVER_SIDE_APPLY", "platform-calico-crds")).getData();

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getReleases().get(0).getStatus()).isEqualTo("SUCCESS");
        org.mockito.Mockito.verify(helm, org.mockito.Mockito.never())
                .apply(anyString(), any(), any(), anyString(), anyString(), anyMap());
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.List<String>> crds =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        org.mockito.Mockito.verify(helm).waitCrdsEstablished(anyString(), crds.capture());
        assertThat(crds.getValue()).containsExactly("installations.operator.tigera.io");
    }

    @Test
    void theDirectApplyRenderUsesTheClustersRealKubeVersionAndApiVersions() {
        // helm template 默认 capabilities（≈1.35）会把 MutatingAdmissionPolicy 渲染成 v1beta1；
        // 1.37 只认 v1（2026-10-22 实测：no matches for kind ... in version
        // "admissionregistration.k8s.io/v1beta1"）。必须按集群探测器渲染。
        HelmRunner helm = mock(HelmRunner.class);
        when(helm.renderFull(anyString(), any(), any(), anyString(), anyString(), any()))
                .thenReturn(new HelmResult(true, 0, CRD_MANIFEST));
        when(helm.applyServerSide(anyString(), anyString())).thenReturn(new HelmResult(true, 0, "applied"));
        when(helm.waitCrdsEstablished(anyString(), any())).thenReturn(new HelmResult(true, 0, "ok"));

        controller(helm, kubeClient("1", "37+"))
                .apply(request("SERVER_SIDE_APPLY", "platform-calico-crds"));

        org.mockito.Mockito.verify(helm).renderFull(
                anyString(), any(), any(), anyString(),
                org.mockito.ArgumentMatchers.eq("1.37"),
                org.mockito.ArgumentMatchers.eq(
                        List.of("admissionregistration.k8s.io/v1/MutatingAdmissionPolicy")));
    }

    @Test
    void aServerSideApplyFailureStopsTheFollowingHelmRelease() {
        HelmRunner helm = mock(HelmRunner.class);
        when(helm.renderFull(anyString(), any(), any(), anyString(), anyString(), any()))
                .thenReturn(new HelmResult(true, 0, CRD_MANIFEST));
        when(helm.applyServerSide(anyString(), anyString()))
                .thenReturn(new HelmResult(false, 1, "no matches"));

        ComponentInstallRequestDTO req = request("SERVER_SIDE_APPLY", "platform-calico-crds");
        ReleaseInstallSpecDTO second = new ReleaseInstallSpecDTO();
        second.setReleaseName("platform-tigera-operator");
        second.setNamespace("tigera-operator");
        second.setChartCode("tigera-operator");
        second.setChartVersion("v3.33.0");
        second.setValues(Map.of());
        req.setReleases(List.of(req.getReleases().get(0), second));

        ComponentInstallResultDTO r = controller(helm).apply(req).getData();

        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getReleases()).hasSize(1);
        org.mockito.Mockito.verify(helm, org.mockito.Mockito.never())
                .apply(anyString(), any(), any(), anyString(), anyString(), anyMap());
    }

    private static final String CRD_MANIFEST = """
            apiVersion: apiextensions.k8s.io/v1
            kind: CustomResourceDefinition
            metadata:
              name: installations.operator.tigera.io
            ---
            apiVersion: operator.tigera.io/v1
            kind: Installation
            metadata:
              name: default
            """;

    @Test
    void theHarborAdminPasswordEndpointReturnsOnlyThePassword() {
        // Harbor 的组件命名空间（harbor）不在租户分配表里：走通用 GET /secrets（命名空间域，
        // 要求显式 tenantId + 分配关系）会被边界拒成「管理员代操作需显式指定 tenantId」。
        // 平台侧读自己组件的口令，只能走这扇 PLATFORM 小门，而且只回口令这一个键。
        String encoded = java.util.Base64.getEncoder().encodeToString(
                "s3cret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var secret = new io.fabric8.kubernetes.api.model.SecretBuilder()
                .addToData("HARBOR_ADMIN_PASSWORD", encoded)
                .addToData("secretKey", encoded)
                .build();

        assertThat(controller(mock(HelmRunner.class), kubeWithHarborSecret(secret))
                .harborAdminPassword(harborSecrets()).getData()).isEqualTo("s3cret");
    }

    @Test
    void aMissingHarborSecretYieldsNoPasswordRatherThanAnError() {
        assertThat(controller(mock(HelmRunner.class), kubeWithHarborSecret(null))
                .harborAdminPassword(harborSecrets()).getData()).isNull();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static io.fabric8.kubernetes.client.KubernetesClient kubeWithHarborSecret(
            io.fabric8.kubernetes.api.model.Secret secret) {
        io.fabric8.kubernetes.client.KubernetesClient client = kubeClient("1", "35");
        io.fabric8.kubernetes.client.dsl.MixedOperation secrets =
                mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        io.fabric8.kubernetes.client.dsl.NonNamespaceOperation namespaceOp =
                mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        io.fabric8.kubernetes.client.dsl.Resource named =
                mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(client.secrets()).thenReturn(secrets);
        when(secrets.inNamespace("harbor")).thenReturn(namespaceOp);
        when(namespaceOp.withName("platform-harbor-secrets")).thenReturn(named);
        when(named.get()).thenReturn(secret);
        return client;
    }

    private static com.coding.common.models.k8s.dto.HarborSecretsRequestDTO harborSecrets() {
        com.coding.common.models.k8s.dto.HarborSecretsRequestDTO req =
                new com.coding.common.models.k8s.dto.HarborSecretsRequestDTO();
        req.setClusterId("c1");
        req.setNamespace("harbor");
        req.setSecretName("platform-harbor-secrets");
        return req;
    }

    /** 模拟集群发现：k8s 1.3x 只提供 MutatingAdmissionPolicy 的 v1 */
    private static io.fabric8.kubernetes.client.KubernetesClient kubeClient(String major, String minor) {
        io.fabric8.kubernetes.client.KubernetesClient client =
                mock(io.fabric8.kubernetes.client.KubernetesClient.class);
        io.fabric8.kubernetes.client.VersionInfo version =
                mock(io.fabric8.kubernetes.client.VersionInfo.class);
        when(version.getMajor()).thenReturn(major);
        when(version.getMinor()).thenReturn(minor);
        when(client.getKubernetesVersion()).thenReturn(version);
        when(client.getApiResources("admissionregistration.k8s.io/v1")).thenReturn(
                new io.fabric8.kubernetes.api.model.APIResourceListBuilder()
                        .withResources(List.of(new io.fabric8.kubernetes.api.model.APIResourceBuilder()
                                .withName("mutatingadmissionpolicies").build()))
                        .build());
        when(client.getApiResources("admissionregistration.k8s.io/v1beta1")).thenReturn(
                new io.fabric8.kubernetes.api.model.APIResourceListBuilder().build());
        when(client.getApiResources("admissionregistration.k8s.io/v1alpha1")).thenReturn(
                new io.fabric8.kubernetes.api.model.APIResourceListBuilder().build());
        return client;
    }

    private static ComponentInstallController controller(HelmRunner helm) {
        return controller(helm, kubeClient("1", "35"));
    }

    private static ComponentInstallController controller(
            HelmRunner helm, io.fabric8.kubernetes.client.KubernetesClient kube) {
        KubernetesClientFactory factory = mock(KubernetesClientFactory.class);
        when(factory.adminKubeconfig("c1")).thenReturn("apiVersion: v1\nkind: Config\n");
        when(factory.getAdminClient("c1")).thenReturn(kube);
        ChartBundle bundle = mock(ChartBundle.class);
        when(bundle.chartDir(anyString(), anyString())).thenReturn(Path.of("/tmp/chart"));
        return new ComponentInstallController(helm, bundle, factory,
                mock(ResourceAccessResolver.class), mock(com.coding.k8sserver.components.ChartImageCatalogService.class));
    }

    private static ComponentInstallRequestDTO request() {
        return request(null, "platform-metrics-server");
    }

    private static ComponentInstallRequestDTO request(String applyMode, String releaseName) {
        ReleaseInstallSpecDTO spec = new ReleaseInstallSpecDTO();
        spec.setReleaseName(releaseName);
        spec.setNamespace("kube-system");
        spec.setChartCode("metrics-server");
        spec.setChartVersion("1.0.0");
        spec.setValues(Map.of("args", List.of("--kubelet-insecure-tls")));
        spec.setApplyMode(applyMode);
        ComponentInstallRequestDTO req = new ComponentInstallRequestDTO();
        req.setClusterId("c1");
        req.setComponentCode("metrics-server");
        req.setAction("INSTALL");
        req.setReleases(List.of(spec));
        return req;
    }
}
