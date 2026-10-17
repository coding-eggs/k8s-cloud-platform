package com.coding.k8score.operations.core;

import com.coding.common.models.k8s.dto.WorkloadProbeResultDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeTargetDTO;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 按候选读工作负载的状态（组件探测的数据面）。
 *
 * <h2>成本是固定的，与集群规模无关</h2>
 * 每个 {@code (namespace, kind)} 恰好一次 {@code list} —— 三个候选落在同一 (ns, kind) 上也只 list 一次。
 * 这条约束照 {@code ClusterAggregationOperations} 的"固定 9 次 list"同形，有单测盯着。
 *
 * <h2>为什么按镜像仓库匹配而不是按名字</h2>
 * 真集群实测：相当一部分基础设施工作负载的名字带 Helm release 前缀
 * （{@code ps-kube-prometheus-stack-operator} / {@code th-thanos-query}）。按名字写死，
 * 用户换个 release 名就探测不到 —— 而"找得到"正是这个功能要解决的问题。
 * 名字只当快路径（istiod / ztunnel / calico-node 这些名字稳定，能少比几次）。
 */
@RequiredArgsConstructor
public class WorkloadProbeOperations {

    private final KubernetesClient client;

    public List<WorkloadProbeResultDTO> probe(List<WorkloadProbeTargetDTO> targets) {
        // ⭐ 两件事分开：**成本**按候选归并（同一 (namespace, kind) 只 list 一次；CRD 每个名字一次 get），
        //    **结果顺序**严格跟着入参走。消费方（ClusterComponentService）按下标把结果贴回组件 code。
        Map<String, List<Cand>> fetched = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (WorkloadProbeTargetDTO t : targets) {
            String key = fetchKey(t);
            if (fetched.containsKey(key) || errors.containsKey(key)) {
                continue;
            }
            try {
                fetched.put(key, fetch(t));
            } catch (Exception e) {
                errors.put(key, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }

        List<WorkloadProbeResultDTO> out = new ArrayList<>(targets.size());
        for (WorkloadProbeTargetDTO t : targets) {
            String key = fetchKey(t);
            out.add(errors.containsKey(key) ? failed(t, errors.get(key)) : match(t, fetched.get(key)));
        }
        return out;
    }

    /**
     * 成本键：工作负载按 {@code (namespace, kind)} 合并成一次 list；CRD 是集群级资源、按名字各取一次。
     * 两者都与集群的命名空间数量无关。
     */
    private static String fetchKey(WorkloadProbeTargetDTO t) {
        return "crd".equals(t.getKind())
                ? "crd\u0000" + t.getName()
                : t.getNamespace() + "\u0000" + t.getKind();
    }

    private List<Cand> fetch(WorkloadProbeTargetDTO t) {
        return "crd".equals(t.getKind()) ? getCrd(t) : list(t.getNamespace(), t.getKind());
    }

    /** 纯 CRD 组件（如 Gateway API）没有工作负载，版本只能读 CRD 自己的标签/注解 */
    private List<Cand> getCrd(WorkloadProbeTargetDTO t) {
        CustomResourceDefinition crd = client.apiextensions().v1().customResourceDefinitions()
                .withName(t.getName())
                .get();
        if (crd == null) {
            return List.of();
        }
        ObjectMeta md = crd.getMetadata();
        return List.of(new Cand(md.getName(), List.of(), crdVersion(md, t.getVersionLabel()), null, null));
    }

    /**
     * CRD 版本：显式 {@code versionLabel} → {@code app.kubernetes.io/version} → 任意 {@code *bundle-version}
     * （Gateway API 的约定是 {@code gateway.networking.k8s.io/bundle-version}）。标签与注解都看。
     */
    static String crdVersion(ObjectMeta md, String versionLabel) {
        Map<String, String> labels = md.getLabels() == null ? Map.of() : md.getLabels();
        Map<String, String> annotations = md.getAnnotations() == null ? Map.of() : md.getAnnotations();
        if (versionLabel != null && !versionLabel.isBlank()) {
            String v = labels.get(versionLabel);
            if (v == null || v.isBlank()) {
                v = annotations.get(versionLabel);
            }
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        String app = labels.get("app.kubernetes.io/version");
        if (app != null && !app.isBlank()) {
            return app;
        }
        return findBundleVersion(labels).or(() -> findBundleVersion(annotations)).orElse(null);
    }

    private static Optional<String> findBundleVersion(Map<String, String> m) {
        return m.entrySet().stream()
                .filter(e -> e.getKey().endsWith("bundle-version")
                        && e.getValue() != null && !e.getValue().isBlank())
                .map(Map.Entry::getValue)
                .findFirst();
    }

    // ---------- 内部 ----------

    /** 一个候选命中的统一视图：不同 kind 的对象在这里被拍平成同样的四件事 */
    private record Cand(String name, List<Container> containers, String versionLabel,
                        Integer ready, Integer desired) {}

    private List<Cand> list(String namespace, String kind) {
        return switch (kind) {
            case "deployment" -> client.apps().deployments().inNamespace(namespace).list().getItems().stream()
                    .map(d -> new Cand(d.getMetadata().getName(),
                            containers(d.getSpec().getTemplate().getSpec().getContainers()),
                            versionLabelOf(d.getMetadata()),
                            nz(d.getStatus() == null ? null : d.getStatus().getReadyReplicas()),
                            nz(d.getStatus() == null ? null : d.getStatus().getReplicas())))
                    .toList();
            case "statefulset" -> client.apps().statefulSets().inNamespace(namespace).list().getItems().stream()
                    .map(s -> new Cand(s.getMetadata().getName(),
                            containers(s.getSpec().getTemplate().getSpec().getContainers()),
                            versionLabelOf(s.getMetadata()),
                            nz(s.getStatus() == null ? null : s.getStatus().getReadyReplicas()),
                            nz(s.getStatus() == null ? null : s.getStatus().getReplicas())))
                    .toList();
            // ⚠️ DaemonSet 的健康字段**不是** readyReplicas（实测为空），是 numberReady/desiredNumberScheduled
            case "daemonset" -> client.apps().daemonSets().inNamespace(namespace).list().getItems().stream()
                    .map(s -> new Cand(s.getMetadata().getName(),
                            containers(s.getSpec().getTemplate().getSpec().getContainers()),
                            versionLabelOf(s.getMetadata()),
                            nz(s.getStatus() == null ? null : s.getStatus().getNumberReady()),
                            nz(s.getStatus() == null ? null : s.getStatus().getDesiredNumberScheduled())))
                    .toList();
            default -> throw new IllegalArgumentException("不支持的 kind：" + kind);
        };
    }

    private static String versionLabelOf(ObjectMeta md) {
        Map<String, String> labels = md == null ? null : md.getLabels();
        return labels == null ? null : labels.get("app.kubernetes.io/version");
    }

    private static List<Container> containers(List<Container> cs) {
        return cs == null ? List.of() : cs;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private WorkloadProbeResultDTO match(WorkloadProbeTargetDTO t, List<Cand> listed) {
        // 名字序排序：命中多个时取谁必须稳定（否则同一个集群每次刷新看到的版本号会跳）
        List<Cand> matched = listed.stream().filter(c -> matches(t, c))
                .sorted(Comparator.comparing(Cand::name)).toList();

        WorkloadProbeResultDTO r = new WorkloadProbeResultDTO();
        r.setNamespace(t.getNamespace());
        r.setKind(t.getKind());
        if (matched.isEmpty()) {
            r.setFound(false);
            // ⚠️ 措辞很讲究：候选位置之外我们**没去看**，所以不能说"没装"
            r.setDetail("crd".equals(t.getKind())
                    ? "集群内没有找到 CRD " + t.getName()
                    : "在平台已知的位置（" + t.getNamespace() + "）没有找到");
            return r;
        }
        Cand hit = matched.get(0);
        r.setFound(true);
        r.setName(hit.name());
        r.setReady(hit.ready());
        r.setDesired(hit.desired());
        r.setVersion(versionOf(hit));
        // CRD 没有副本语义（ready/desired 均为 null）——别渲染成 "null/null 就绪"
        String readyText = hit.ready() == null ? "已安装（CRD）" : hit.ready() + "/" + hit.desired() + " 就绪";
        r.setDetail(matched.size() == 1
                ? readyText
                : "命中 " + matched.size() + " 个（" + String.join(", ", matched.stream().map(Cand::name).toList())
                        + "），取 " + hit.name());
        return r;
    }

    private static WorkloadProbeResultDTO failed(WorkloadProbeTargetDTO t, String error) {
        WorkloadProbeResultDTO r = new WorkloadProbeResultDTO();
        r.setNamespace(t.getNamespace());
        r.setKind(t.getKind());
        r.setFound(false);
        r.setError(error);
        return r;
    }

    /** 镜像仓库片段命中**任一**容器的镜像即算（多容器组件存在，如 whisker） */
    private static boolean matches(WorkloadProbeTargetDTO t, Cand c) {
        if (t.getName() != null && !t.getName().isBlank() && t.getName().equals(c.name())) {
            return true;
        }
        if (t.getImageRepo() == null || t.getImageRepo().isBlank()) {
            return false;
        }
        return c.containers().stream()
                .map(Container::getImage)
                .filter(Objects::nonNull)
                .anyMatch(img -> img.contains(t.getImageRepo()));
    }

    /**
     * {@code app.kubernetes.io/version} 标签优先 —— 但实测多数组件没有它
     * （coredns / otel-operator / prometheus-operator 都没有，只有 Helm 装的 istiod 有），
     * 所以 image tag 才是主来源，标签只是"更干净的那个"。
     */
    private static String versionOf(Cand c) {
        if (c.versionLabel() != null && !c.versionLabel().isBlank()) {
            return c.versionLabel();
        }
        return c.containers().stream()
                .map(Container::getImage)
                .filter(Objects::nonNull)
                .map(WorkloadProbeOperations::tagOf)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** image ref → tag；无 tag（只带 digest / 无 tag）返回 null */
    static String tagOf(String image) {
        if (image == null) {
            return null;
        }
        // 剥 digest：`x:v1@sha256:…` → `x:v1`
        String noDigest = image.contains("@") ? image.substring(0, image.indexOf('@')) : image;
        int colon = noDigest.lastIndexOf(':');
        int slash = noDigest.lastIndexOf('/');
        // 冒号在最后一个 / 之前 = registry 端口（registry.internal:5000/foo），不是 tag
        return colon <= slash ? null : noDigest.substring(colon + 1);
    }
}
