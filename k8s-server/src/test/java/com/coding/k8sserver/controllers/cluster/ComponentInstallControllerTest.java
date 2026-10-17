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

    private static ComponentInstallController controller(HelmRunner helm) {
        KubernetesClientFactory factory = mock(KubernetesClientFactory.class);
        when(factory.adminKubeconfig("c1")).thenReturn("apiVersion: v1\nkind: Config\n");
        ChartBundle bundle = mock(ChartBundle.class);
        when(bundle.chartDir(anyString(), anyString())).thenReturn(Path.of("/tmp/chart"));
        return new ComponentInstallController(helm, bundle, factory, mock(ResourceAccessResolver.class));
    }

    private static ComponentInstallRequestDTO request() {
        ReleaseInstallSpecDTO spec = new ReleaseInstallSpecDTO();
        spec.setReleaseName("platform-metrics-server");
        spec.setNamespace("kube-system");
        spec.setChartCode("metrics-server");
        spec.setChartVersion("1.0.0");
        spec.setValues(Map.of("args", List.of("--kubelet-insecure-tls")));
        ComponentInstallRequestDTO req = new ComponentInstallRequestDTO();
        req.setClusterId("c1");
        req.setComponentCode("metrics-server");
        req.setAction("INSTALL");
        req.setReleases(List.of(spec));
        return req;
    }
}
