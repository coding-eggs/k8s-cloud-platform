package com.coding.k8sserver.app;

import com.coding.common.models.app.AppNodeDTO;
import com.coding.common.models.app.AppRenderRequestDTO;
import com.coding.common.models.app.AppRenderResultDTO;
import com.coding.common.models.app.AppSpecDTO;
import com.coding.k8sserver.chart.Violation;
import com.coding.k8sserver.helm.ChartBundle;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppRenderServiceTest {

    /** 假 helm：不管 values，固定吐出 Deployment + Service，用来验证合并/去重/解析。 */
    private static HelmRunner fakeHelm(Path tempDir) {
        return new HelmRunner("true", List.of(), Map.of(), tempDir, Duration.ofSeconds(5)) {
            @Override
            public HelmResult render(String kubeconfig, Path chartDir, Path valuesFile, String namespace) {
                return new HelmResult(true, 0, """
                        apiVersion: apps/v1
                        kind: Deployment
                        metadata:
                          name: demo-web
                          namespace: %s
                        ---
                        apiVersion: v1
                        kind: Service
                        metadata:
                          name: demo-web
                          namespace: %s
                        """.formatted(namespace, namespace));
            }
        };
    }

    private static AppNodeDTO node(String key, String kind) {
        AppNodeDTO n = new AppNodeDTO();
        n.setKey(key);
        n.setKind(kind);
        n.setParams(Map.of("image", "nginx"));
        return n;
    }

    private static AppRenderRequestDTO request(AppNodeDTO... nodes) {
        AppSpecDTO spec = new AppSpecDTO();
        spec.setNamespace("demo");
        spec.setNodes(List.of(nodes));
        AppRenderRequestDTO req = new AppRenderRequestDTO();
        req.setClusterId("c1");
        req.setNamespace("demo");
        req.setAppId("app_1");
        req.setAppSlug("demo");
        req.setRevision(1);
        req.setSpec(spec);
        return req;
    }

    /** ChartBundle 会校验目录里有 Chart.yaml；测试里造一个最小 chart 目录即可。 */
    private static AppRenderService service(Path tempDir) throws java.io.IOException {
        Path chart = tempDir.resolve("app/workload/1.0.0/chart");
        java.nio.file.Files.createDirectories(chart);
        java.nio.file.Files.writeString(chart.resolve("Chart.yaml"), """
                apiVersion: v2
                name: app-workload
                version: 1.0.0
                """);
        return new AppRenderService(fakeHelm(tempDir), new ChartBundle(tempDir.toString()), null, null);
    }

    @Test
    void rendersAndListsObjects(@TempDir Path tempDir) throws Exception {
        AppRenderResultDTO r = service(tempDir).render(request(node("web", "SERVICE")));

        assertThat(r.getObjects()).extracting("kind").containsExactly("Deployment", "Service");
        assertThat(r.getObjects()).allSatisfy(o -> assertThat(o.getName()).isEqualTo("demo-web"));
        assertThat(r.getProblems()).isEmpty();
    }

    @Test
    void rejectsUnknownKindAsProblem(@TempDir Path tempDir) throws Exception {
        AppRenderResultDTO r = service(tempDir).render(request(node("job", "CRONJOB")));

        assertThat(r.getObjects()).isEmpty();
        assertThat(r.getProblems()).anySatisfy(p ->
                assertThat(p.getMessage()).contains("不支持的节点类型"));
    }

    @Test
    void reportsObjectNameConflictAcrossNodes(@TempDir Path tempDir) throws Exception {
        AppRenderResultDTO r = service(tempDir)
                .render(request(node("web", "SERVICE"), node("api", "SERVICE")));

        assertThat(r.getProblems()).anySatisfy(p -> assertThat(p.getMessage()).contains("对象名冲突"));
        // 冲突的第二份不进入清单（第一份保留）
        assertThat(r.getObjects()).hasSize(2);
    }

    @Test
    void mapsBlockingViolationToError() {
        assertThat(AppRenderService.problemFrom(new Violation(Violation.BLOCK, "HOST_PATH", "demo-web", "不允许"))
                .getSeverity()).isEqualTo("ERROR");
        assertThat(AppRenderService.problemFrom(new Violation(Violation.WARN, "HOOK", "demo-web", "提示"))
                .getSeverity()).isEqualTo("WARN");
    }
}
