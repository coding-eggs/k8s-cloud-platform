package com.coding.k8sserver.chart;

import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 渲染结果的静态检查（spec §4.2）。
 *
 * <p>输入是 {@code helm template} 的 stdout，逐文档判定。顺序有意义：
 * 先排除空文档与解析失败，再判跨命名空间/集群级/hook。
 *
 * <p>为什么跨命名空间对管理员也阻断：{@code helm uninstall} 会把跨 ns 的对象一起删掉，
 * 而管理员在装的时候通常没意识到自己授权了这件事。
 */
public final class ManifestInspector {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private ManifestInspector() {
    }

    public static List<Violation> inspect(String manifest, String targetNamespace,
                                          boolean strict, ScopeResolver scopes) {
        List<Violation> out = new ArrayList<>();
        if (manifest == null || manifest.isBlank()) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (String doc : manifest.split("(?m)^---[ \t]*$")) {
            if (doc.isBlank()) {
                continue;
            }
            JsonNode root;
            try {
                root = YAML.readTree(doc);
            } catch (RuntimeException e) {
                out.add(Violation.block("INVALID_OBJECT", "?", "无法解析的 YAML 文档：" + e.getMessage()));
                continue;
            }
            if (root == null || root.isNull() || root.isMissingNode()) {
                continue;
            }
            String apiVersion = text(root, "apiVersion");
            String kind = text(root, "kind");
            JsonNode metadata = root.path("metadata");
            String name = text(metadata, "name");
            String namespace = text(metadata, "namespace");
            if (kind.isEmpty() || name.isEmpty()) {
                out.add(Violation.block("INVALID_OBJECT", kind + "/" + name, "对象缺少 kind 或 metadata.name"));
                continue;
            }
            String id = apiVersion + "/" + kind + "/" + namespace + "/" + name;
            if (!seen.add(id)) {
                out.add(Violation.block("DUPLICATE_OBJECT", id, "同一对象在清单里出现多次"));
            }
            if (metadata.path("annotations").has("helm.sh/hook")) {
                out.add(strict
                        ? Violation.block("HOOK", kind + "/" + name, "租户安装不允许 helm hook 资源")
                        : Violation.warn("HOOK", kind + "/" + name, "chart 含 hook 资源，安装时会按其生命周期执行"));
            }
            if ("Namespace".equals(kind)) {
                out.add(Violation.block("NAMESPACE_OBJECT", kind + "/" + name, "应用不能创建命名空间"));
                continue;
            }
            Scope scope = scopes.scopeOf(apiVersion, kind);
            if (scope == Scope.UNKNOWN) {
                out.add(strict
                        ? Violation.block("UNKNOWN_KIND", kind + "/" + name,
                            "集群里没有该 kind 的 CRD，无法判定作用域（fail-closed）")
                        : Violation.warn("UNKNOWN_KIND", kind + "/" + name,
                            "集群里没有该 kind 的 CRD，作用域未知"));
                continue;
            }
            if (scope == Scope.CLUSTER) {
                out.add(strict
                        ? Violation.block("CLUSTER_SCOPED", kind + "/" + name, "租户安装不允许集群级对象")
                        : Violation.warn("CLUSTER_SCOPED", kind + "/" + name, "该对象是集群级的"));
                continue;
            }
            if (!namespace.isEmpty() && !namespace.equals(targetNamespace)) {
                out.add(Violation.block("CROSS_NAMESPACE", kind + "/" + name,
                        "对象声明了命名空间 " + namespace + "，与目标 " + targetNamespace + " 不一致"));
            }
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asString("");
    }
}
