package com.coding.k8sserver.middleware;

import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8sserver.helm.ChartBundle;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NacosChartInstallerTest {

    private final HelmRunner helm = mock(HelmRunner.class);
    private final ChartBundle charts = mock(ChartBundle.class);
    private final KubernetesClientFactory clients = mock(KubernetesClientFactory.class);

    private final NacosChartInstaller installer = new NacosChartInstaller(helm, charts, clients);

    private MiddlewareCreateRequestDTO req(String mode, int replicas) {
        MiddlewareCreateRequestDTO r = new MiddlewareCreateRequestDTO();
        r.setInstanceId("mwi_1");
        r.setClusterId("c1");
        r.setCode("nacos");
        r.setMode(mode);
        r.setNamespace("demo");
        r.setName("mw-nacos-abc12345");
        r.setReplicas(replicas);
        r.setCpuRequest("200m");
        r.setMemoryRequest("512Mi");
        r.setStorageSize("5Gi");
        return r;
    }

    @Test
    void clusterModeRendersClusterAndEmbeddedStorage() {
        var values = installer.valuesFor(req("CLUSTER", 3));

        assertThat(values)
                .contains("global:")
                .contains("mode: cluster")
                .contains("replicaCount: 3")
                .contains("type: embedded")
                .contains("type: ClusterIP")
                .contains("storage: 5Gi")
                // 留空必须是 null（= 用集群默认 SC）；写成空串 "": 真集群上 PVC 永远 Pending
                .contains("storageClassName: null")
                .contains("enabled: true")
                .contains("cpu: 200m");
    }

    @Test
    void standaloneRendersStandalone() {
        assertThat(installer.valuesFor(req("STANDALONE", 1))).contains("mode: standalone");
    }

    @Test
    void explicitStorageClassOverridesChartDefault() {
        var r = req("CLUSTER", 3);
        r.setStorageClass("fast-ssd");

        assertThat(installer.valuesFor(r)).contains("storageClassName: \"fast-ssd\"");
    }

    @Test
    void createInstallsHelmWithInstanceNameAsRelease() throws Exception {
        Path chart = java.nio.file.Files.createTempDirectory("nacos-chart");
        when(charts.chartDir("middleware", "nacos", NacosChartInstaller.CHART_VERSION)).thenReturn(chart);
        when(clients.adminKubeconfig("c1")).thenReturn("kc");
        when(helm.apply(eq("kc"), eq(chart), any(), eq("mw-nacos-abc12345"), eq("demo"),
                any(HelmRunner.InstallOptions.class))).thenReturn(new HelmResult(true, 0, "ok"));

        installer.create(req("CLUSTER", 3));

        verify(helm).apply(eq("kc"), eq(chart), any(), eq("mw-nacos-abc12345"), eq("demo"),
                any(HelmRunner.InstallOptions.class));
    }

    @Test
    void deleteUsesHelmUninstall() {
        when(clients.adminKubeconfig("c1")).thenReturn("kc");
        when(helm.uninstall(anyString(), anyString(), anyString()))
                .thenReturn(new HelmResult(true, 0, "release uninstalled"));

        installer.delete("c1", "demo", "mw-nacos-abc12345");

        verify(helm).uninstall("kc", "mw-nacos-abc12345", "demo");
    }

    @Test
    void scaleIsExplicitlyUnsupported() {
        assertThatThrownBy(() -> installer.scale("c1", "nacos"))
                .hasMessageContaining("暂不支持");
    }
}
