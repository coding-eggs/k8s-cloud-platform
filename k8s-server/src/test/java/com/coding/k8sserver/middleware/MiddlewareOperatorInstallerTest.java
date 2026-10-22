package com.coding.k8sserver.middleware;

import com.coding.k8sserver.helm.ChartBundle;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import com.coding.k8score.factory.KubernetesClientFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MiddlewareOperatorInstallerTest {

    private final HelmRunner helm = mock(HelmRunner.class);
    private final ChartBundle charts = mock(ChartBundle.class);
    private final KubernetesClientFactory clients = mock(KubernetesClientFactory.class);

    private final MiddlewareOperatorInstaller installer =
            new MiddlewareOperatorInstaller(helm, charts, clients, new MiddlewareOperatorCatalog()) {
                @Override
                io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition crdOrNull(
                        io.fabric8.kubernetes.client.KubernetesClient client, String name) {
                    return null;   // 预检：CRD 都不存在
                }
            };

    @Test
    void installUsesThePinnedReleaseNameAndNamespace() throws Exception {
        Path chart = Files.createTempDirectory("chart");
        when(charts.chartDir("middleware", "redis", new MiddlewareOperatorCatalog().require("redis").chartVersion()))
                .thenReturn(chart);
        when(clients.adminKubeconfig("c1")).thenReturn("kc");
        when(helm.apply(eq("kc"), eq(chart), any(), eq("platform-middleware-redis"),
                eq("platform-middleware-system"), any(HelmRunner.InstallOptions.class)))
                .thenReturn(new HelmResult(true, 0, "deployed"));

        var kube = mock(io.fabric8.kubernetes.client.KubernetesClient.class,
                org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(clients.getAdminClient("c1")).thenReturn(kube);

        installer.installHelm("c1", "redis");

        verify(helm).apply(eq("kc"), eq(chart), any(), eq("platform-middleware-redis"),
                eq("platform-middleware-system"), any(HelmRunner.InstallOptions.class));
    }

    @Test
    void statusIsReadyWhenManifestWorkloadsAreReady() {
        var installer = new MiddlewareOperatorInstaller(helm, charts, clients,
                new MiddlewareOperatorCatalog()) {
            @Override
            io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition crdOrNull(
                    io.fabric8.kubernetes.client.KubernetesClient client, String name) {
                return mock(io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition.class);
            }

            @Override
            boolean workloadReady(io.fabric8.kubernetes.client.KubernetesClient client, String kind, String name) {
                return "strimzi-cluster-operator".equals(name);
            }
        };
        when(clients.adminKubeconfig("c1")).thenReturn("kc");
        when(helm.manifest("kc", "platform-middleware-kafka", "platform-middleware-system"))
                .thenReturn(new HelmResult(true, 0, """
                        apiVersion: apps/v1
                        kind: Deployment
                        metadata:
                          name: strimzi-cluster-operator
                        ---
                        apiVersion: v1
                        kind: Service
                        metadata:
                          name: ignored
                        """));

        var status = installer.status("c1", "kafka");

        assertThat(status.isInstalled()).isTrue();
        assertThat(status.isReady()).isTrue();
    }

    @Test
    void statusReportsMissingRelease() {
        when(clients.adminKubeconfig("c1")).thenReturn("kc");
        when(helm.manifest(any(), any(), any())).thenReturn(new HelmResult(false, 1, "release: not found"));

        var status = installer.status("c1", "redis");

        assertThat(status.isInstalled()).isFalse();
        assertThat(status.isReady()).isFalse();
        assertThat(status.getMessage()).contains("尚未安装");
    }

    @Test
    void unknownCodeIsRejected() {
        assertThatThrownBy(() -> installer.install("c1", "minio"))
                .hasMessageContaining("不支持");
    }

    @Test
    void failedHelmSurfacesTheOutput() throws Exception {
        Path chart = Files.createTempDirectory("chart");
        when(charts.chartDir(any(), any(), any())).thenReturn(chart);
        when(clients.adminKubeconfig("c1")).thenReturn("kc");
        when(helm.apply(any(), any(), any(), any(), any(), any(HelmRunner.InstallOptions.class)))
                .thenReturn(new HelmResult(false, 1, "Error: chart requires kubeVersion"));

        assertThatThrownBy(() -> installer.install("c1", "redis"))
                .hasMessageContaining("chart requires kubeVersion");
    }
}
