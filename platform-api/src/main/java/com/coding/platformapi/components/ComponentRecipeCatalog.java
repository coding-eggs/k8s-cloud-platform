package com.coding.platformapi.components;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组件的安装方式目录（S3.6）。
 *
 * <p>与 {@link ClusterComponentCatalog} 的分工：那边回答"装没装"（探测判据），
 * 这边回答"怎么装"（chart / 版本 / values 契约）。两者用同一个 component code 关联。
 *
 * <p><b>契约值（🔒）</b>由本目录固定、UI 不可改 —— 写错的后果不是"装不上"，而是"装上了但平台功能用不了"
 * （见 spec §5）。<b>可编辑默认值（⚙️）</b>的 key 是**路径形式**（如
 * {@code prometheus.prometheusSpec.retention}），由 {@code ComponentInstallService} 转成嵌套 Map 后深合并。
 *
 * <p><b>占位符</b>：{@code ${cluster.name}} / {@code ${thanos.receive.url}} / {@code ${calico.podCidr}} /
 * {@code ${calico.encapsulation}} 在安装前由 {@link ValueTemplate} 解析，缺失或为空直接报错。
 *
 * <p>⚠️ 同一组件的多个 chart 版本按**新 → 旧**排列：前端升级弹窗直接按这个顺序展示并默认选第一个。
 */
@Component
public class ComponentRecipeCatalog {

    private static final String BUSINESS = "BUSINESS";
    private static final String MANAGEMENT = "MANAGEMENT";

    // ==================== metrics-server（批① 试点） ====================

    private static final List<ReleaseSpec> METRICS_SERVER_RELEASES = List.of(new ReleaseSpec(
            "platform-metrics-server", "kube-system", "metrics-server", "3.14.0",
            // 🔒 契约值
            //  - args：自签 kubelet 证书的集群不加这个参数，metrics-server 永远 NotReady
            //  - fullnameOverride：官方 chart 默认用 release 名做 fullname（= platform-metrics-server），
            //    而组件探测认的是 deployment `metrics-server` —— 固定名字，探测与安装才对得上
            Map.of("args", List.of("--kubelet-insecure-tls"),
                    "fullnameOverride", "metrics-server"),
            // ⚙️ 可编辑默认值
            Map.of("replicas", 1)));

    // ==================== prometheus-operator（kube-prometheus-stack） ====================

    /**
     * 平台对它的依赖：监控曲线（Thanos 按 cluster_name 过滤）、SM/PM 页、告警规则、租户 AlertmanagerConfig。
     * <p>四个 {@code *NilUsesHelmValues=false} 与四个 {@code *NamespaceSelector={}} 是**必须**的：
     * 默认值下平台在租户 ns 创建的对象会被 Prometheus **静默忽略**。
     * <p>{@code remoteWrite} 指向管理集群 Thanos receive —— 空配置会在安装前报错（见 ValueTemplate）。
     */
    private static final List<ReleaseSpec> PROMETHEUS_RELEASES = List.of(new ReleaseSpec(
            "platform-kps", "monitor", "prometheus-operator", "92.10.1",
            Map.of(
                    "prometheus", Map.of("prometheusSpec", Map.of(
                            "externalLabels", Map.of("cluster_name", "${cluster.name}"),
                            "ruleSelectorNilUsesHelmValues", false,
                            "serviceMonitorSelectorNilUsesHelmValues", false,
                            "podMonitorSelectorNilUsesHelmValues", false,
                            "probeSelectorNilUsesHelmValues", false,
                            "ruleNamespaceSelector", Map.of(),
                            "serviceMonitorNamespaceSelector", Map.of(),
                            "podMonitorNamespaceSelector", Map.of(),
                            "probeNamespaceSelector", Map.of(),
                            "remoteWrite", List.of(Map.of("url", "${thanos.receive.url}")))),
                    "alertmanager", Map.of("alertmanagerSpec", Map.of(
                            "alertmanagerConfigMatcherStrategy", Map.of("type", "OnNamespace"),
                            "alertmanagerConfigSelector", Map.of(),
                            "alertmanagerConfigNamespaceSelector", Map.of()))),
            Map.of(
                    "prometheus.prometheusSpec.retention", "15d",
                    "prometheus.prometheusSpec.replicas", 1,
                    // 默认不写 storageClassName：空串在 K8s 里表示"不用动态供给"，会装出一个 Pending 的 PVC
                    "prometheus.prometheusSpec.storageSpec", Map.of(
                            "volumeClaimTemplate", Map.of("spec", Map.of(
                                    "resources", Map.of("requests", Map.of("storage", "50Gi"))))),
                    "grafana.enabled", true)));

    // ==================== gateway-api ====================

    /** 纯 CRD 组件：experimental 通道（TCP/TLS/UDP Route 只在 experimental 里）。 */
    private static final List<ReleaseSpec> GATEWAY_API_RELEASES = List.of(new ReleaseSpec(
            "platform-gateway-api", "kube-system", "gateway-api", "1.6.2",
            Map.of(), Map.of()));

    // ==================== istio / istio-ambient ====================

    /** 控制面：base（CRD/集群资源）→ istiod → 对外入口 gateway（名字固定 external-gateway）。 */
    private static final List<ReleaseSpec> ISTIO_RELEASES = List.of(
            new ReleaseSpec("istio-base", "istio-system", "istio-base", "1.30.6", Map.of(), Map.of()),
            new ReleaseSpec("istiod", "istio-system", "istio-istiod", "1.30.6", Map.of(),
                    Map.of("replicaCount", 1, "autoscaleEnabled", true)),
            new ReleaseSpec("external-gateway", "istio-system", "istio-gateway", "1.30.6",
                    Map.of("name", "external-gateway"), Map.of()));

    /** ambient 数据面：cni（开启 ambient）+ ztunnel；必须先有控制面。 */
    private static final List<ReleaseSpec> ISTIO_AMBIENT_RELEASES = List.of(
            new ReleaseSpec("istio-cni", "istio-system", "istio-cni", "1.30.6",
                    Map.of("ambient", Map.of("enabled", true)), Map.of()),
            new ReleaseSpec("ztunnel", "istio-system", "istio-ztunnel", "1.30.6", Map.of(), Map.of()));

    // ==================== tekton / tekton-operator ====================

    /**
     * Tekton 由 operator 安装，契约值落在 TektonConfig 上（通过官方 chart 自带的 {@code extraObjects} 下发）：
     * <ul>
     *   <li>{@code coschedule: workspaces} —— 允许一个 TaskRun 挂多块 PVC workspace（构建缓存）。
     *       取值以 2026-10-17 集群实测的 TektonConfig 为准（1.15.x 的合法值是 workspaces /
     *       pipelineruns / isolate-pipelinerun，默认就是 workspaces）</li>
     *   <li>{@code default-container-resource-requirements} —— 命名空间配额下 init 容器需要默认资源
     *       （平台侧设不到 init 容器）</li>
     *   <li>{@code profile: lite} + {@code targetNamespace: tekton-pipelines} —— 与集群现状一致，
     *       探测判据就在这个命名空间</li>
     * </ul>
     */
    private static final Map<String, Object> TEKTON_CONFIG = Map.of(
            "apiVersion", "operator.tekton.dev/v1alpha1",
            "kind", "TektonConfig",
            "metadata", Map.of("name", "config"),
            "spec", Map.of(
                    "profile", "lite",
                    "targetNamespace", "tekton-pipelines",
                    "pipeline", Map.of(
                            "coschedule", "workspaces",
                            "default-container-resource-requirements", Map.of(
                                    "containers", Map.of(
                                            "requests", Map.of("cpu", "100m", "memory", "128Mi"),
                                            "limits", Map.of("cpu", "1", "memory", "1Gi"))))));

    private static final List<ReleaseSpec> TEKTON_OPERATOR_RELEASES = List.of(new ReleaseSpec(
            "platform-tekton-operator", "tekton-operator", "tekton-operator", "0.81.2",
            Map.of("extraObjects", List.of(TEKTON_CONFIG)), Map.of()));

    /** pipelines 由 operator 依据 TektonConfig 安装：与 operator 是同一个 release（幂等），但要求前置健康。 */
    private static final List<ReleaseSpec> TEKTON_RELEASES = TEKTON_OPERATOR_RELEASES;

    // ==================== calico / tigera-operator ====================

    /** 只装 operator，**不接管网络**（installation.enabled=false）；装 calico 才下发 Installation CR。 */
    private static final List<ReleaseSpec> TIGERA_OPERATOR_RELEASES = List.of(new ReleaseSpec(
            "platform-tigera-operator", "tigera-operator", "tigera-operator", "v3.33.0",
            Map.of("installation", Map.of("enabled", false)), Map.of()));

    /**
     * ⚠️ 最高风险组件：CIDR / encapsulation 与集群现有网络不一致会**直接断网**。
     * 两个值来自平台配置（{@code platform.calico.*}），为空时安装前报错。
     */
    private static final List<ReleaseSpec> CALICO_RELEASES = List.of(new ReleaseSpec(
            "platform-tigera-operator", "tigera-operator", "tigera-operator", "v3.33.0",
            Map.of("installation", Map.of(
                    "enabled", true,
                    "calicoNetwork", Map.of(
                            "ipPools", List.of(Map.of(
                                    "cidr", "${calico.podCidr}",
                                    "encapsulation", "${calico.encapsulation}"))))),
            Map.of()));

    // ==================== cert-manager / otel-operator ====================

    private static final List<ReleaseSpec> CERT_MANAGER_RELEASES = List.of(new ReleaseSpec(
            "platform-cert-manager", "cert-manager", "cert-manager", "v1.21.2",
            // 🔒 CRD 必须跟着 chart 走且卸载保留：否则升级/卸载会删掉全部证书对象
            Map.of("crds", Map.of("enabled", true, "keep", true)), Map.of()));

    private static final List<ReleaseSpec> OTEL_OPERATOR_RELEASES = List.of(new ReleaseSpec(
            "platform-otel-operator", "opentelemetry-operator-system", "otel-operator", "0.126.0",
            // 🔒 webhook 证书由 cert-manager 签发
            Map.of("admissionWebhooks", Map.of("certManager", Map.of("enabled", true))), Map.of()));

    // ==================== 汇总 ====================

    private static final Map<String, Map<String, ComponentRecipe>> RECIPES = Map.ofEntries(
            Map.entry("metrics-server", profiles(METRICS_SERVER_RELEASES, List.of())),
            Map.entry("prometheus-operator", profiles(PROMETHEUS_RELEASES, List.of())),
            Map.entry("gateway-api", profiles(GATEWAY_API_RELEASES, List.of())),
            Map.entry("istio", profiles(ISTIO_RELEASES, List.of())),
            Map.entry("istio-ambient", profiles(ISTIO_AMBIENT_RELEASES, List.of("istio"))),
            Map.entry("tekton", profiles(TEKTON_RELEASES, List.of("tekton-operator"))),
            Map.entry("tekton-operator", profiles(TEKTON_OPERATOR_RELEASES, List.of())),
            Map.entry("calico", profiles(CALICO_RELEASES, List.of("tigera-operator"))),
            Map.entry("tigera-operator", profiles(TIGERA_OPERATOR_RELEASES, List.of())),
            Map.entry("cert-manager", profiles(CERT_MANAGER_RELEASES, List.of())),
            Map.entry("otel-operator", profiles(OTEL_OPERATOR_RELEASES, List.of("cert-manager"))));

    private static Map<String, ComponentRecipe> profiles(List<ReleaseSpec> releases, List<String> requires) {
        return Map.of(
                BUSINESS, new ComponentRecipe(BUSINESS, releases, requires),
                MANAGEMENT, new ComponentRecipe(MANAGEMENT, releases, requires));
    }

    /** 可编辑字段的展示文案（key 是路径形式）；没有登记的 key 原样显示 */
    private static final Map<String, String> EDITABLE_LABELS = Map.of(
            "prometheus.prometheusSpec.retention", "指标保留时长（如 15d）",
            "prometheus.prometheusSpec.storageSpec", "存储配置（JSON）",
            "prometheus.prometheusSpec.replicas", "Prometheus 副本数",
            "grafana.enabled", "部署 Grafana",
            "replicaCount", "副本数",
            "autoscaleEnabled", "自动扩缩容",
            "replicas", "副本数");

    public static String labelOf(String key) {
        return EDITABLE_LABELS.getOrDefault(key, key);
    }

    /** 没有安装方式的组件返回 null；调用方据此禁用安装按钮 */
    public ComponentRecipe recipeOf(String code, String profile) {
        if (code == null || profile == null) {
            return null; // Map.of(...).get(null) 会 NPE
        }
        Map<String, ComponentRecipe> byProfile = RECIPES.get(code);
        return byProfile == null ? null : byProfile.get(profile);
    }

    public boolean supports(String code) {
        return RECIPES.containsKey(code);
    }

    /** 支持安装的组件 code（测试与自检用） */
    public Set<String> codes() {
        return RECIPES.keySet();
    }
}
