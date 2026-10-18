package com.coding.platformapi.components;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
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
 * 其中 Thanos Receive 地址可由安装表单填写（{@code thanosRemoteWrite} 标记的 recipe）。
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
            //  - defaultArgs：显式钉住（不依赖 chart 默认值漂移）——优先 InternalIP
            //  - fullnameOverride：官方 chart 默认用 release 名做 fullname（= platform-metrics-server），
            //    而组件探测认的是 deployment `metrics-server` —— 固定名字，探测与安装才对得上
            Map.of("args", List.of("--kubelet-insecure-tls"),
                    "defaultArgs", List.of(
                            "--cert-dir=/tmp",
                            "--kubelet-preferred-address-types=InternalIP",
                            "--kubelet-use-node-status-port",
                            "--metric-resolution=15s"),
                    "fullnameOverride", "metrics-server"),
            // ⚙️ 可编辑默认值
            Map.of("replicas", 1)));

    // ==================== prometheus-operator（kube-prometheus-stack） ====================

    /**
     * 平台对它的依赖：监控曲线（Thanos 按 cluster_name 过滤）、SM/PM 页、告警规则、租户 AlertmanagerConfig。
     *
     * <p>几个必须覆盖的默认值：
     * <ul>
     *   <li>四个 {@code *SelectorNilUsesHelmValues=false} + 四个 {@code *NamespaceSelector={}}：
     *       默认值下平台在租户 ns 创建的对象会被 Prometheus <b>静默忽略</b></li>
     *   <li>{@code alertmanagerConfigMatcherStrategy=OnNamespaceExceptForAlertmanagerNamespace}：
     *       Alertmanager 所在 ns（monitor）里的 AMC 处理**全部**告警（平台自己的联动），
     *       租户 ns 的 AMC 仍只处理本 ns 的告警。用 {@code OnNamespace} 平台将收不到任何租户告警</li>
     *   <li>控制面抓取四项关闭：kubeadm 类集群上这些端点只在 127.0.0.1，target 恒 DOWN</li>
     *   <li>{@code crds.upgradeJob.enabled=true}：Helm 不升级 {@code crds/} 目录，
     *       官方 chart 自带的 Job 会在 operator 部署前 apply CRD（CRD 升级的唯一正确姿势）</li>
     * </ul>
     */
    private static List<ReleaseSpec> prometheusReleases(boolean grafanaEnabled, String remoteWriteUrl) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("prometheus", Map.of("prometheusSpec", Map.of(
                "externalLabels", Map.of("cluster_name", "${cluster.name}"),
                "ruleSelectorNilUsesHelmValues", false,
                "serviceMonitorSelectorNilUsesHelmValues", false,
                "podMonitorSelectorNilUsesHelmValues", false,
                "probeSelectorNilUsesHelmValues", false,
                "ruleNamespaceSelector", Map.of(),
                "serviceMonitorNamespaceSelector", Map.of(),
                "podMonitorNamespaceSelector", Map.of(),
                "probeNamespaceSelector", Map.of(),
                "remoteWrite", List.of(Map.of("url", remoteWriteUrl)))));
        contract.put("alertmanager", Map.of("alertmanagerSpec", Map.of(
                "alertmanagerConfigMatcherStrategy", Map.of("type", "OnNamespaceExceptForAlertmanagerNamespace"),
                "alertmanagerConfigSelector", Map.of(),
                "alertmanagerConfigNamespaceSelector", Map.of())));
        contract.put("kubeApiServer", Map.of("enabled", false));
        contract.put("kubeControllerManager", Map.of("enabled", false));
        contract.put("kubeEtcd", Map.of("enabled", false));
        contract.put("kubeScheduler", Map.of("enabled", false));
        contract.put("crds", Map.of("enabled", true, "upgradeJob", Map.of("enabled", true)));

        return List.of(new ReleaseSpec(
                "platform-kps", "monitor", "prometheus-operator", "92.10.1",
                contract,
                prometheusEditable(grafanaEnabled)));
    }

    /**
     * ⚙️ management/business 共用的可编辑默认值。**顺序即表单顺序**（LinkedHashMap）。
     *
     * <p>存储拆成「StorageClass + 容量」两个字段，而不是整块 JSON —— SC 从集群下拉选、容量带单位提示。
     * StorageClass 默认空串 = 跟随集群默认存储类；空串**不会**写进 values
     * （K8s 里 {@code storageClassName: ""} 表示"不用动态供给"，会装出 Pending 的 PVC）。
     */
    private static Map<String, Object> prometheusEditable(boolean grafanaEnabled) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prometheus.prometheusSpec.retention", "15d");
        out.put("prometheus.prometheusSpec.replicas", 1);
        out.put("prometheus.prometheusSpec.storageSpec.volumeClaimTemplate.spec.storageClassName", "");
        out.put("prometheus.prometheusSpec.storageSpec.volumeClaimTemplate.spec.resources.requests.storage", "50Gi");
        out.put("alertmanager.alertmanagerSpec.storage.volumeClaimTemplate.spec.storageClassName", "");
        out.put("alertmanager.alertmanagerSpec.storage.volumeClaimTemplate.spec.resources.requests.storage", "10Gi");
        out.put("grafana.enabled", grafanaEnabled);
        return out;
    }

    /**
     * 管理集群内置 Thanos receive 的集群内地址（kps 的 remoteWrite 默认指向它）。
     *
     * <p>端口是 **10908** —— thanos chart 里 receive 的 {@code --remote-write.address=0.0.0.0:10908}
     * 与 Service 的 {@code remote-write} 端口。10902 是它的 HTTP/metrics 端口，19291 是旧版默认值，
     * 两者都不对（2026-10-18 用户指出，已按 chart 渲染结果核对）。
     */
    private static final String THANOS_RECEIVE_IN_CLUSTER =
            "http://platform-thanos-receive.monitor.svc:10908/api/v1/receive";

    /**
     * 管理集群的观测底座：query / receive / storegateway / compactor。
     * 对象存储配置由平台从"凭证 Secret + 表单字段"拼出来后写成
     * {@code platform-thanos-objstore}（见 ThanosObjstoreObjects）。
     */
    private static final List<ReleaseSpec> THANOS_RELEASES = List.of(new ReleaseSpec(
            "platform-thanos", "monitor", "thanos", "0.47.2",
            Map.of(
                    "global", Map.of("objstore", Map.of(
                            "createSecret", false,
                            "secretName", "platform-thanos-objstore",
                            "secretKey", "objstore.yml")),
                    "query", Map.of("replicaCount", 1),
                    "receive", Map.of("replicaCount", 1),
                    "storegateway", Map.of("replicaCount", 1),
                    "bucket", Map.of("enabled", false)),
            Map.of()));

    // ==================== gateway-api ====================

    /** 纯 CRD 组件：experimental 通道（TCP/TLS/UDP Route 只在 experimental 里）。 */
    private static final List<ReleaseSpec> GATEWAY_API_RELEASES = List.of(new ReleaseSpec(
            "platform-gateway-api", "kube-system", "gateway-api", "1.6.2",
            Map.of(), Map.of()));

    // ==================== istio / istio-ambient ====================

    /**
     * 默认按 **ambient** 模式部署：base → istiod → cni（ambient）→ ztunnel → 对外入口 gateway。
     * 入口名固定 {@code external-gateway}（集群对外拓扑依赖它）。
     */
    private static final List<ReleaseSpec> ISTIO_RELEASES = List.of(
            new ReleaseSpec("istio-base", "istio-system", "istio-base", "1.30.6", Map.of(), Map.of()),
            // meshID 全局一致：管理平台下的所有集群共用同一个网格 ID（装完再改是破坏性操作）
            // revision 有意不设：平台的升级不做按 revision 的灰度，设了反而要求命名空间打 istio.io/rev 标签
            new ReleaseSpec("istiod", "istio-system", "istio-istiod", "1.30.6",
                    Map.of("global", Map.of("meshID", "platform-mesh")),
                    Map.of("replicaCount", 1,
                            "autoscaleEnabled", true,
                            "resources", Map.of(
                                    "requests", Map.of("cpu", "100m", "memory", "256Mi"),
                                    "limits", Map.of("cpu", "1", "memory", "1Gi")))),
            new ReleaseSpec("istio-cni", "istio-system", "istio-cni", "1.30.6",
                    Map.of("ambient", Map.of("enabled", true)), Map.of()),
            new ReleaseSpec("ztunnel", "istio-system", "istio-ztunnel", "1.30.6", Map.of(), Map.of()),
            new ReleaseSpec("external-gateway", "istio-system", "istio-gateway", "1.30.6",
                    Map.of("name", "external-gateway"), Map.of()));

    /** ambient 数据面单装入口（istio 已包含它们；这个 recipe 幂等，供只补数据面的场景用）。 */
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

    // ==================== calico ====================

    /**
     * Calico 作为一个整体安装（operator + Installation CR）—— 同一个 release。
     *
     * <p>⚠️ 最高风险组件：CIDR / encapsulation 与集群现有网络不一致会**直接断网**。
     * 两个值来自平台配置（{@code platform.calico.*}），为空时安装前报错。
     *
     * <p>只保留平台用得到的东西：Calico API Server / Goldmane（流量日志）/ Whisker（可视化 UI）
     * 都不是平台功能所需，默认关闭（chart 默认三者都是开）。
     *
     * <p>{@code tigera-operator} 组件因此**没有独立 recipe**：operator 单独安装没有产品意义，
     * 而两个 recipe 共用同一 release 会让"升级 operator"把 Installation CR 删掉（断网）。
     */
    private static final List<ReleaseSpec> CALICO_RELEASES = List.of(new ReleaseSpec(
            "platform-tigera-operator", "tigera-operator", "tigera-operator", "v3.33.0",
            Map.of(
                    "installation", Map.of(
                            "enabled", true,
                            "calicoNetwork", Map.of(
                                    "ipPools", List.of(Map.of(
                                            "cidr", "${calico.podCidr}",
                                            "encapsulation", "${calico.encapsulation}")))),
                    "apiServer", Map.of("enabled", false),
                    "goldmane", Map.of("enabled", false),
                    "whisker", Map.of("enabled", false)),
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
            Map.entry("prometheus-operator", Map.of(
                    // 业务集群：上报到表单里填的 Thanos Receive
                    BUSINESS, new ComponentRecipe(BUSINESS, prometheusReleases(false, "${thanos.receive.url}"),
                            List.of(), true, true, false),
                    // 管理集群：自带 Thanos 底座，remoteWrite 默认指向集群内 receive；
                    // 表单仍然暴露（跨集群/外部地址场景要能改），预填这个集群内地址
                    MANAGEMENT, new ComponentRecipe(MANAGEMENT, withThanos(
                            prometheusReleases(true, THANOS_RECEIVE_IN_CLUSTER)), List.of(), true, true, true, false,
                            THANOS_RECEIVE_IN_CLUSTER))),
            Map.entry("gateway-api", profiles(GATEWAY_API_RELEASES, List.of())),
            Map.entry("istio", profiles(ISTIO_RELEASES, List.of())),
            Map.entry("istio-ambient", profiles(ISTIO_AMBIENT_RELEASES, List.of("istio"))),
            Map.entry("tekton", profiles(TEKTON_RELEASES, List.of("tekton-operator"))),
            Map.entry("tekton-operator", profiles(TEKTON_OPERATOR_RELEASES, List.of())),
            Map.entry("calico", Map.of(
                    BUSINESS, new ComponentRecipe(BUSINESS, CALICO_RELEASES, List.of(), false, false, false, true),
                    MANAGEMENT, new ComponentRecipe(MANAGEMENT, CALICO_RELEASES, List.of(), false, false, false, true))),
            Map.entry("cert-manager", profiles(CERT_MANAGER_RELEASES, List.of())),
            Map.entry("otel-operator", profiles(OTEL_OPERATOR_RELEASES, List.of("cert-manager"))));

    private static Map<String, ComponentRecipe> profiles(List<ReleaseSpec> releases, List<String> requires) {
        return profiles(releases, requires, false, false);
    }

    private static Map<String, ComponentRecipe> profiles(List<ReleaseSpec> releases, List<String> requires,
                                                         boolean platformAlerting, boolean thanosRemoteWrite) {
        return Map.of(
                BUSINESS, new ComponentRecipe(BUSINESS, releases, requires, platformAlerting, thanosRemoteWrite),
                MANAGEMENT, new ComponentRecipe(MANAGEMENT, releases, requires, platformAlerting, thanosRemoteWrite));
    }

    /** 管理集群的 Prometheus recipe 末尾追加 Thanos 底座 */
    private static List<ReleaseSpec> withThanos(List<ReleaseSpec> prometheus) {
        List<ReleaseSpec> out = new java.util.ArrayList<>(prometheus);
        out.addAll(THANOS_RELEASES);
        return List.copyOf(out);
    }

    /** 可编辑字段的展示文案（key 是路径形式）；没有登记的 key 原样显示 */
    private static final Map<String, String> EDITABLE_LABELS = Map.ofEntries(
            Map.entry("prometheus.prometheusSpec.retention", "指标保留时长（如 15d）"),
            Map.entry("prometheus.prometheusSpec.replicas", "Prometheus 副本数"),
            Map.entry("prometheus.prometheusSpec.storageSpec.volumeClaimTemplate.spec.storageClassName",
                    "Prometheus 存储类"),
            Map.entry("prometheus.prometheusSpec.storageSpec.volumeClaimTemplate.spec.resources.requests.storage",
                    "Prometheus 存储容量"),
            Map.entry("alertmanager.alertmanagerSpec.storage.volumeClaimTemplate.spec.storageClassName",
                    "Alertmanager 存储类（持久化静默与通知日志）"),
            Map.entry("alertmanager.alertmanagerSpec.storage.volumeClaimTemplate.spec.resources.requests.storage",
                    "Alertmanager 存储容量"),
            Map.entry("grafana.enabled", "部署 Grafana"),
            Map.entry("replicaCount", "副本数"),
            Map.entry("autoscaleEnabled", "自动扩缩容"),
            Map.entry("resources", "资源限制（JSON）"),
            Map.entry("replicas", "副本数"));

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
