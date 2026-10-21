package com.coding.k8sserver.chart;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.ChartApplyRequestDTO;
import com.coding.common.models.k8s.dto.ChartPreviewRequestDTO;
import com.coding.common.models.k8s.dto.ChartRefDTO;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChartInstallServiceTest {

    @TempDir
    Path tmp;

    private final HelmRunner helm = mock(HelmRunner.class);
    private final ChartFetcher fetcher = mock(ChartFetcher.class);
    private final ClusterScopeIndex scopes = mock(ClusterScopeIndex.class);
    private final com.coding.k8score.factory.KubernetesClientFactory clients =
            mock(com.coding.k8score.factory.KubernetesClientFactory.class);

    private final ChartInstallService service = new ChartInstallService(helm, fetcher, scopes, clients);

    private ChartRefDTO chart() {
        ChartRefDTO c = new ChartRefDTO();
        c.setSource("HARBOR");
        c.setRef("oci://harbor/demo/web");
        c.setChartName("web");
        c.setVersion("1.0.0");
        return c;
    }

    private void chartRenders(String manifest) throws Exception {
        Path tgz = tmp.resolve("web-1.0.0.tgz");
        Files.writeString(tgz, "x");
        when(fetcher.fetch(any())).thenReturn(new ChartFetcher.FetchedChart(tgz, "sha256:abc"));
        when(scopes.forCluster(anyString())).thenReturn((apiVersion, kind) -> Scope.NAMESPACED);
        when(helm.render(anyString(), any(), any(), anyString()))
                .thenReturn(new HelmResult(true, 0, manifest));
        // apply 走带 upgrade 标志的重载（复审 I4）
        when(helm.render(anyString(), any(), any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new HelmResult(true, 0, manifest));
    }

    @Test
    void previewReturnsBlockingViolationForCrossNamespace() throws Exception {
        chartRenders("""
                apiVersion: v1
                kind: Service
                metadata:
                  name: svc
                  namespace: other
                """);
        when(clients.adminKubeconfig("c1")).thenReturn("kc");

        ChartPreviewRequestDTO req = new ChartPreviewRequestDTO();
        req.setClusterId("c1");
        req.setNamespace("demo");
        req.setStrict(false);
        req.setChart(chart());
        req.setValues("a: 1\n");

        var result = service.preview(req);

        assertThat(result.getViolations())
                .anyMatch(v -> v.getType().equals("CROSS_NAMESPACE") && "BLOCK".equals(v.getSeverity()));
    }

    @Test
    void applyStopsWhenPreviewHasBlockingViolation() throws Exception {
        chartRenders("""
                apiVersion: rbac.authorization.k8s.io/v1
                kind: ClusterRole
                metadata:
                  name: r
                """);
        when(clients.adminKubeconfig("c1")).thenReturn("kc");
        when(scopes.forCluster("c1")).thenReturn((apiVersion, kind) -> Scope.CLUSTER);

        ChartApplyRequestDTO req = new ChartApplyRequestDTO();
        req.setClusterId("c1");
        req.setNamespace("demo");
        req.setReleaseName("app-1");
        req.setStrict(true);
        req.setChart(chart());
        req.setValues("a: 1\n");
        req.setLabels(Map.of());

        assertThatThrownBy(() -> service.apply(req))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("CLUSTER_SCOPED");
    }

    @Test
    void applyReinspectsWhatHelmActuallyApplied() throws Exception {
        String manifest = """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: web
                  namespace: demo
                """;
        chartRenders(manifest);
        when(clients.adminKubeconfig("c1")).thenReturn("kc");
        when(scopes.forCluster("c1")).thenReturn((apiVersion, kind) -> Scope.NAMESPACED);
        when(helm.apply(anyString(), any(), any(), eq("app-1"), eq("demo"),
                any(HelmRunner.InstallOptions.class)))
                .thenReturn(new HelmResult(true, 0, "release deployed"));
        when(helm.manifest(anyString(), eq("app-1"), eq("demo")))
                .thenReturn(new HelmResult(true, 0, manifest));

        ChartApplyRequestDTO req = new ChartApplyRequestDTO();
        req.setClusterId("c1");
        req.setNamespace("demo");
        req.setReleaseName("app-1");
        req.setStrict(false);
        req.setChart(chart());
        req.setValues("a: 1\n");
        req.setLabels(Map.of());

        var result = service.apply(req);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getViolations()).isEmpty();   // 复核结果来自 helm get manifest，不是预渲染
    }
}
