package com.coding.k8sserver.app;

import com.coding.common.models.app.AppNodeDTO;
import com.coding.common.models.app.AppObjectRefDTO;
import com.coding.common.models.app.AppProblemDTO;
import com.coding.common.models.app.AppRenderRequestDTO;
import com.coding.common.models.app.AppRenderResultDTO;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8sserver.chart.ClusterScopeIndex;
import com.coding.k8sserver.chart.ManifestInspector;
import com.coding.k8sserver.chart.Scope;
import com.coding.k8sserver.chart.ScopeResolver;
import com.coding.k8sserver.chart.Violation;
import com.coding.k8sserver.helm.ChartBundle;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 逐节点渲染 + 合并 + 静态检查。只做事实与边界，不读平台台账。
 *
 * <p>为什么合并成单份 manifest：apply 走一次 SSA（fieldManager=platform-app），局部失败时平台台账与
 * 集群状态更容易对齐；prune 的差集计算也只有一处。
 */
@Service
public class AppRenderService {

    private static final String WORKLOAD_CHART_VERSION = "1.0.0";

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private final HelmRunner helm;
    private final ChartBundle charts;
    private final KubernetesClientFactory clients;
    private final ClusterScopeIndex scopes;

    public AppRenderService(HelmRunner helm, ChartBundle charts,
                            KubernetesClientFactory clients, ClusterScopeIndex scopes) {
        this.helm = helm;
        this.charts = charts;
        this.clients = clients;
        this.scopes = scopes;
    }

    public AppRenderResultDTO render(AppRenderRequestDTO req) {
        List<AppProblemDTO> problems = new ArrayList<>();
        List<String> docs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String kubeconfig = clients == null ? "" : clients.adminKubeconfig(req.getClusterId());
        Path values = null;
        try {
            Path chartDir = charts.chartDir("app", "workload", WORKLOAD_CHART_VERSION);
            List<AppNodeDTO> nodes = req.getSpec() == null || req.getSpec().getNodes() == null
                    ? List.of() : req.getSpec().getNodes();
            for (AppNodeDTO node : nodes) {
                if (!"SERVICE".equals(node.getKind()) && !"WORKER".equals(node.getKind())) {
                    problems.add(AppProblemDTO.error(node.getKey(), "不支持的节点类型：" + node.getKind()));
                    continue;
                }
                values = writeValues(AppWorkloadValues.build(req.getAppId(), req.getAppSlug(),
                        req.getRevision() == null ? 0 : req.getRevision(), node));
                HelmResult rendered = helm.render(kubeconfig, chartDir, values, req.getNamespace());
                if (!rendered.ok()) {
                    problems.add(AppProblemDTO.error(node.getKey(), "渲染失败：" + rendered.output()));
                    continue;
                }
                for (String doc : split(rendered.output())) {
                    AppObjectRefDTO ref = refOf(doc, req.getNamespace());
                    if (ref == null) {
                        continue;
                    }
                    String id = ref.getKind() + "/" + ref.getName();
                    if (!seen.add(id)) {
                        problems.add(AppProblemDTO.error(node.getKey(),
                                "对象名冲突（两个节点生成了同一个对象）：" + id));
                        continue;
                    }
                    docs.add(doc);
                }
            }
        } catch (RuntimeException e) {
            problems.add(AppProblemDTO.error(null, e.getMessage()));
        } finally {
            deleteQuietly(values);
        }

        String manifest = String.join("\n---\n", docs);
        for (Violation v : ManifestInspector.inspect(manifest, req.getNamespace(), true,
                resolver(req.getClusterId()))) {
            problems.add(problemFrom(v));
        }
        AppRenderResultDTO out = new AppRenderResultDTO();
        out.setManifest(manifest);
        out.setObjects(docs.stream().map(d -> refOf(d, req.getNamespace()))
                .filter(Objects::nonNull).toList());
        out.setProblems(problems);
        return out;
    }

    /** 没有 scope 索引时（单测）一律按命名空间级处理，避免把每份清单都判成集群级。 */
    private ScopeResolver resolver(String clusterId) {
        return scopes == null ? (apiVersion, kind) -> Scope.NAMESPACED : scopes.forCluster(clusterId);
    }

    /** 把静态检查的 Violation 归一到 AppProblemDTO：BLOCK → ERROR，其余 → WARN。 */
    static AppProblemDTO problemFrom(Violation v) {
        AppProblemDTO p = new AppProblemDTO();
        p.setSeverity(Violation.BLOCK.equals(v.severity()) ? "ERROR" : "WARN");
        p.setMessage(v.object() + "：" + v.message());
        return p;
    }

    static List<String> split(String manifest) {
        List<String> out = new ArrayList<>();
        if (manifest == null) {
            return out;
        }
        for (String doc : manifest.split("(?m)^---[ \\t]*$")) {
            if (!doc.isBlank()) {
                out.add(doc.strip() + "\n");
            }
        }
        return out;
    }

    static AppObjectRefDTO refOf(String doc, String fallbackNamespace) {
        JsonNode root;
        try {
            root = YAML.readTree(doc);
        } catch (RuntimeException e) {
            return null;
        }
        if (root == null || root.isNull() || root.isMissingNode()
                || !root.has("kind") || !root.has("metadata")) {
            return null;
        }
        AppObjectRefDTO ref = new AppObjectRefDTO();
        ref.setApiVersion(root.path("apiVersion").asString(""));
        ref.setKind(root.path("kind").asString(""));
        ref.setName(root.path("metadata").path("name").asString(""));
        ref.setNamespace(root.path("metadata").path("namespace").asString(fallbackNamespace));
        return ref;
    }

    private static Path writeValues(Map<String, Object> values) {
        try {
            Path f = Files.createTempFile("app-values-", ".yaml");
            Files.writeString(f, YAML.writeValueAsString(values));
            return f;
        } catch (Exception e) {
            throw new IllegalStateException("写临时 values 失败", e);
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
            // 临时文件清不掉不影响渲染结果
        }
    }
}
