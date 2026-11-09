package com.coding.k8sserver.app;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.app.AppApplyRequestDTO;
import com.coding.common.models.app.AppObjectRefDTO;
import com.coding.common.models.app.AppPruneRequestDTO;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.kube.ManifestApplier;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 只做事实与落盘：apply 一次合并后的 manifest；prune 按平台给的清单删。 */
@Service
public class AppApplyService {

    /**
     * 副本数交接用的 fieldManager。HPA 开启时清单不写 {@code spec.replicas}；如果此前只有 platform-app 拥有该字段
     * （手动副本，或 HPA 还没改过它），SSA 省略它会把字段删掉、回默认 1 —— StatefulSet 会被缩到 1 个序号。
     * 所以先用这个 manager 按线上当前值认领一次（K8s SSA 文档「Transferring ownership」的做法），再正常 apply。
     */
    public static final String REPLICAS_HANDOVER_MANAGER = "platform-app-replicas-handover";
    private static final Set<String> SCALABLE_KINDS = Set.of("Deployment", "StatefulSet");

    private final ManifestApplier manifests;
    private final KubernetesClientFactory clients;

    public AppApplyService(ManifestApplier manifests, KubernetesClientFactory clients) {
        this.manifests = manifests;
        this.clients = clients;
    }

    public void apply(AppApplyRequestDTO req) {
        String manifest = filter(req.getManifest(), req.getOnlyKinds(), req.getSkipKinds());
        if (manifest.isBlank()) {
            return;   // 过滤后没有对象：什么都不做
        }
        String kubeconfig = kubeconfig(req.getClusterId());
        // 渲染阶段已强制每个对象声明目标命名空间（AppRenderService），这里不需要默认命名空间
        handOverReplicas(kubeconfig, manifest);
        HelmResult r = manifests.applyServerSide(kubeconfig, manifest, null);
        if (!r.ok()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用清单写入失败：" + r.output());
        }
    }

    /**
     * 按 kind 过滤多文档清单。按 {@code ---} 行切文档、保留原文（不重新序列化，避免 YAML 1.1 取值被改写）；
     * 一个文档里任一对象不通过就整篇丢掉。两个集合都为空时原样返回。
     */
    static String filter(String manifest, Set<String> only, Set<String> skip) {
        if (manifest == null) {
            return "";
        }
        boolean hasOnly = only != null && !only.isEmpty();
        boolean hasSkip = skip != null && !skip.isEmpty();
        if (!hasOnly && !hasSkip) {
            return manifest;
        }
        List<String> kept = new ArrayList<>();
        for (String doc : DOC_SEPARATOR.split(manifest)) {
            List<GenericKubernetesResource> objs = ManifestApplier.parse(doc);
            if (objs.isEmpty()) {
                continue;
            }
            boolean pass = objs.stream().map(GenericKubernetesResource::getKind)
                    .allMatch(k -> (!hasOnly || only.contains(k)) && (!hasSkip || !skip.contains(k)));
            if (pass) {
                kept.add(doc.strip());
            }
        }
        return kept.isEmpty() ? "" : String.join("\n---\n", kept) + "\n";
    }

    private static final java.util.regex.Pattern DOC_SEPARATOR =
            java.util.regex.Pattern.compile("(?m)^---[ \\t]*(#.*)?\\r?$");

    /** 清单里不写 replicas 的 Deployment / StatefulSet：线上存在则先按线上副本交接所有权（见 {@link #REPLICAS_HANDOVER_MANAGER}） */
    void handOverReplicas(String kubeconfig, String manifest) {
        for (GenericKubernetesResource obj : ManifestApplier.parse(manifest)) {
            if (!SCALABLE_KINDS.contains(obj.getKind()) || obj.getMetadata() == null) {
                continue;
            }
            Object spec = obj.getAdditionalProperties().get("spec");
            if (spec instanceof Map<?, ?> m && m.containsKey("replicas")) {
                continue;
            }
            String ns = obj.getMetadata().getNamespace();
            String name = obj.getMetadata().getName();
            Integer live = manifests.liveReplicas(kubeconfig, obj.getApiVersion(), obj.getKind(), ns, name);
            if (live == null) {
                continue; // 新对象：没有可保留的副本
            }
            String handover = """
                    apiVersion: %s
                    kind: %s
                    metadata:
                      name: %s
                      namespace: %s
                    spec:
                      replicas: %d
                    """.formatted(obj.getApiVersion(), obj.getKind(), name, ns, live);
            HelmResult r = manifests.applyServerSide(kubeconfig, handover, null, REPLICAS_HANDOVER_MANAGER);
            if (!r.ok()) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "交接副本数失败（" + obj.getKind() + "/" + name + "），为避免副本被重置未写入清单：" + r.output());
            }
        }
    }

    /**
     * 删除差集对象。PVC 默认跳过：它承载数据，误删不可恢复；只有节点被移除且用户显式勾选时才删。
     */
    public int prune(AppPruneRequestDTO req) {
        int deleted = 0;
        for (AppObjectRefDTO ref : req.getObjects()) {
            if ("PersistentVolumeClaim".equals(ref.getKind()) && !req.isDeletePvc()) {
                continue;
            }
            deleteObject(req.getClusterId(), ref);
            deleted++;
        }
        return deleted;
    }

    /** 最小 manifest 按引用删除；对象已不存在视为成功。 */
    void deleteObject(String clusterId, AppObjectRefDTO ref) {
        String manifest = """
                apiVersion: %s
                kind: %s
                metadata:
                  name: %s
                  namespace: %s
                """.formatted(ref.getApiVersion(), ref.getKind(), ref.getName(), ref.getNamespace());
        HelmResult r = manifests.deleteManifest(kubeconfig(clusterId), manifest, ref.getNamespace());
        if (!r.ok()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "清理对象失败（" + ref.getKind() + "/" + ref.getName() + "）：" + r.output());
        }
    }

    private String kubeconfig(String clusterId) {
        return clients == null ? "" : clients.adminKubeconfig(clusterId);
    }
}
