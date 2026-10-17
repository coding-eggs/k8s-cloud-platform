package com.coding.k8score.operations.core;

import com.coding.common.models.k8s.dto.WorkloadProbeResultDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeTargetDTO;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ObjectMeta;
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
        Map<String, List<WorkloadProbeTargetDTO>> byChain = new LinkedHashMap<>();
        for (WorkloadProbeTargetDTO t : targets) {
            byChain.computeIfAbsent(t.getNamespace() + "\u0000" + t.getKind(), k -> new ArrayList<>()).add(t);
        }
        List<WorkloadProbeResultDTO> out = new ArrayList<>();
        for (List<WorkloadProbeTargetDTO> group : byChain.values()) {
            // 一次 list 服务这一组的全部候选
            WorkloadProbeTargetDTO head = group.get(0);
            List<Cand> listed;
            String error = null;
            try {
                listed = list(head.getNamespace(), head.getKind());
            } catch (Exception e) {
                listed = List.of();
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            for (WorkloadProbeTargetDTO t : group) {
                out.add(error == null ? match(t, listed) : failed(t, error));
            }
        }
        return out;
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
            // ⚠️ 措辞很讲究：候选命名空间之外的安装位置我们**没去看**，所以不能说"没装"
            r.setDetail("在平台已知的位置（" + t.getNamespace() + "）没有找到");
            return r;
        }
        Cand hit = matched.get(0);
        r.setFound(true);
        r.setName(hit.name());
        r.setReady(hit.ready());
        r.setDesired(hit.desired());
        r.setVersion(versionOf(hit));
        r.setDetail(matched.size() == 1
                ? hit.ready() + "/" + hit.desired() + " 就绪"
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
