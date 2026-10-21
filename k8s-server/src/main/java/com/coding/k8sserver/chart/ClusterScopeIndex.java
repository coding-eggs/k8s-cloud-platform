package com.coding.k8sserver.chart;

import com.coding.k8score.factory.KubernetesClientFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 判定 kind 的作用域：内置 kind 用静态表，自定义资源读 CRD 的 {@code spec.scope}。
 *
 * <p>为什么不用 API discovery：fabric8 7.x 没有暴露 {@code APIResourceList} 的便捷入口，
 * 而 CRD 对象本身就带 scope —— 对自定义资源这是唯一权威来源。内置 kind 的集群级集合是
 * 封闭且稳定的，写死比"猜"可靠。
 *
 * <p><b>fail-closed</b>：既不认识也不是已安装 CRD 的 kind 返回 {@link Scope#UNKNOWN}，
 * 由调用方决定拒绝（租户）还是提示（管理员）。
 */
@Component
public class ClusterScopeIndex {

    /** 内置集群级 kind：group（核心组用空串）→ kind 集合 */
    private static final Map<String, Set<String>> CLUSTER_SCOPED = Map.ofEntries(
            Map.entry("", Set.of("Namespace", "Node", "PersistentVolume", "ComponentStatus")),
            Map.entry("rbac.authorization.k8s.io", Set.of("ClusterRole", "ClusterRoleBinding")),
            Map.entry("storage.k8s.io", Set.of("StorageClass", "VolumeAttachment", "CSIDriver", "CSINode",
                    "VolumeAttributesClass")),
            Map.entry("scheduling.k8s.io", Set.of("PriorityClass")),
            Map.entry("networking.k8s.io", Set.of("IngressClass", "IPAddress", "ServiceCIDR")),
            Map.entry("node.k8s.io", Set.of("RuntimeClass")),
            Map.entry("apiextensions.k8s.io", Set.of("CustomResourceDefinition")),
            Map.entry("apiregistration.k8s.io", Set.of("APIService")),
            Map.entry("admissionregistration.k8s.io", Set.of("MutatingWebhookConfiguration",
                    "ValidatingWebhookConfiguration", "ValidatingAdmissionPolicy",
                    "ValidatingAdmissionPolicyBinding", "MutatingAdmissionPolicy",
                    "MutatingAdmissionPolicyBinding")),
            Map.entry("certificates.k8s.io", Set.of("CertificateSigningRequest")),
            Map.entry("flowcontrol.apiserver.k8s.io", Set.of("FlowSchema", "PriorityLevelConfiguration")),
            Map.entry("metrics.k8s.io", Set.of("NodeMetrics")));

    /**
     * 内置**命名空间级** kind 的显式清单。
     *
     * <p>为什么不用"内置组里没列进集群级集合就是命名空间级"：新版本会新增集群级内置 kind
     * （如 1.32 的 MutatingAdmissionPolicy），那种兜底会让它被误判成命名空间级而绕过阻断。
     * 这里两个集合都没有的 kind 一律 {@link Scope#UNKNOWN}，由调用方 fail-closed。
     */
    private static final Map<String, Set<String>> NAMESPACED_SCOPED = Map.ofEntries(
            Map.entry("", Set.of("Pod", "Service", "ConfigMap", "Secret", "ServiceAccount",
                    "PersistentVolumeClaim", "Endpoints", "ReplicationController", "ResourceQuota",
                    "LimitRange", "Event", "PodTemplate", "Binding")),
            Map.entry("apps", Set.of("Deployment", "StatefulSet", "DaemonSet", "ReplicaSet",
                    "ControllerRevision")),
            Map.entry("batch", Set.of("Job", "CronJob")),
            Map.entry("policy", Set.of("PodDisruptionBudget")),
            Map.entry("autoscaling", Set.of("HorizontalPodAutoscaler")),
            Map.entry("coordination.k8s.io", Set.of("Lease")),
            Map.entry("discovery.k8s.io", Set.of("EndpointSlice")),
            Map.entry("networking.k8s.io", Set.of("NetworkPolicy", "Ingress")),
            Map.entry("events.k8s.io", Set.of("Event")),
            Map.entry("rbac.authorization.k8s.io", Set.of("Role", "RoleBinding")),
            Map.entry("storage.k8s.io", Set.of("CSIStorageCapacity")),
            Map.entry("metrics.k8s.io", Set.of("PodMetrics")));

    /** 我们"认识"的内置组；组在表里但 kind 不在集群级集合 → 命名空间级 */
    private static final Set<String> BUILT_IN_GROUPS = Set.of(
            "", "apps", "batch", "policy", "autoscaling", "coordination", "discovery.k8s.io",
            "rbac.authorization.k8s.io", "storage.k8s.io", "scheduling.k8s.io", "networking.k8s.io",
            "node.k8s.io", "apiextensions.k8s.io", "apiregistration.k8s.io",
            "admissionregistration.k8s.io", "certificates.k8s.io", "flowcontrol.apiserver.k8s.io",
            "authentication.k8s.io", "authorization.k8s.io", "metrics.k8s.io", "events.k8s.io");

    private final Function<String, List<CrdScope>> crdLoader;
    private final Duration ttl;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    /** 生产构造器。类里有第二个包级构造器（单测注入），Spring 不会自己挑 —— 必须标 @Autowired。 */
    @Autowired
    public ClusterScopeIndex(KubernetesClientFactory clients,
                             @Value("${platform.chart.discovery-ttl:5m}") Duration ttl) {
        this(clusterId -> clients.getAdminClient(clusterId)
                .apiextensions().v1().customResourceDefinitions().list().getItems().stream()
                .map(c -> new CrdScope(c.getSpec().getGroup(), c.getSpec().getNames().getKind(),
                        "Namespaced".equalsIgnoreCase(c.getSpec().getScope())))
                .toList(), ttl);
    }

    ClusterScopeIndex(Function<String, List<CrdScope>> crdLoader, Duration ttl) {
        this.crdLoader = crdLoader;
        this.ttl = ttl;
    }

    public ScopeResolver forCluster(String clusterId) {
        Entry entry = cache.get(clusterId);
        long now = System.currentTimeMillis();
        if (entry == null || now - entry.loadedAt() > ttl.toMillis()) {
            Map<String, Scope> scopes = new HashMap<>();
            for (CrdScope crd : crdLoader.apply(clusterId)) {
                scopes.put(crd.group() + "/" + crd.kind(), crd.namespaced() ? Scope.NAMESPACED : Scope.CLUSTER);
            }
            entry = new Entry(Map.copyOf(scopes), now);
            cache.put(clusterId, entry);
        }
        Map<String, Scope> scopes = entry.scopes();
        return (apiVersion, kind) -> {
            // 核心组的 apiVersion 就是 "v1"（没有斜杠）→ group 是空串
            String group = apiVersion == null || !apiVersion.contains("/")
                    ? "" : apiVersion.substring(0, apiVersion.indexOf('/'));
            if (CLUSTER_SCOPED.getOrDefault(group, Set.of()).contains(kind)) {
                return Scope.CLUSTER;
            }
            if (NAMESPACED_SCOPED.getOrDefault(group, Set.of()).contains(kind)) {
                return Scope.NAMESPACED;
            }
            if (BUILT_IN_GROUPS.contains(group)) {
                // 内置组里没列过的 kind = 无法判定（fail-closed），不猜"命名空间级"
                return Scope.UNKNOWN;
            }
            return scopes.getOrDefault(group + "/" + kind, Scope.UNKNOWN);
        };
    }

    /** 一条 CRD 的 scope 事实（单测注入用） */
    public record CrdScope(String group, String kind, boolean namespaced) {
    }

    private record Entry(Map<String, Scope> scopes, long loadedAt) {
    }
}
