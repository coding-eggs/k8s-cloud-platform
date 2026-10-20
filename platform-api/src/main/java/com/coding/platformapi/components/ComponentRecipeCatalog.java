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
        // node_exporter 1.12 的 --collector.cpu.info 是 Bool 且**没有默认值**（默认 false），
        // 不开就没有 node_cpu_info（用户 2026-10-20 要求采集）。
        // ⚠️ extraArgs 是 list，Helm 合并**整体替换**：必须把 chart 默认的两条 filesystem
        //    exclude 原样写回，否则会丢掉「不采 overlay/proc/sys 等文件系统」的默认行为。
        contract.put("prometheus-node-exporter", Map.of("extraArgs", List.of(
                "--collector.filesystem.mount-points-exclude=^/(dev|proc|sys|run/containerd/.+|var/lib/docker/.+|var/lib/kubelet/.+)($|/)",
                "--collector.filesystem.fs-types-exclude=^(autofs|binfmt_misc|bpf|cgroup2?|configfs|debugfs|devpts|devtmpfs|fusectl|hugetlbfs|iso9660|mqueue|nsfs|overlay|proc|procfs|pstore|rpc_pipefs|securityfs|selinuxfs|squashfs|sysfs|tracefs|erofs)$",
                "--collector.cpu.info")));
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
                    "global", Map.of(
                            "objstore", Map.of(
                                    "createSecret", false,
                                    "secretName", "platform-thanos-objstore",
                                    "secretKey", "objstore.yml"),
                            // chart 默认 global.thanosRules.enabled=true，会下发
                            // thanos-component-absent（compact/query/receive/store 的 absent(up{...})）；
                            // 不开 ServiceMonitor 就没有 up 序列，四条规则会恒 firing（用户实报）。
                            "serviceMonitor", Map.of("enabled", true)),
                    "query", Map.of("replicaCount", 1),
                    "receive", Map.of("replicaCount", 1),
                    "storegateway", Map.of("replicaCount", 1),
                    "bucket", Map.of("enabled", false)),
            Map.of()));

    // ==================== istio（含 Gateway API CRD） ====================

    /**
     * 默认按 **ambient** 模式部署：Gateway API CRD → base → istiod → cni（ambient）→ ztunnel → 对外入口 gateway。
     * 六个 release 是一个安装单元，入口名固定 {@code external-gateway}（集群对外拓扑依赖它）。
     *
     * <p>Gateway API 是 Istio 组件的一部分（2026-10-19 合并）：只装 Istio 不装 Gateway API，
     * 网关 / 路由页面不可用 —— 拆成两个组件只会让用户漏装。experimental 通道：
     * TCP/TLS/UDP Route 只在 experimental 里。
     */
    private static final List<ReleaseSpec> ISTIO_RELEASES = List.of(
            new ReleaseSpec("platform-gateway-api", "kube-system", "gateway-api", "1.6.2",
                    Map.of(), Map.of()),
            new ReleaseSpec("istio-base", "istio-system", "istio-base", "1.30.6", Map.of(), Map.of()),
            // meshID 全局一致：管理平台下的所有集群共用同一个网格 ID（装完再改是破坏性操作）
            // revision 有意不设：平台的升级不做按 revision 的灰度，设了反而要求命名空间打 istio.io/rev 标签
            new ReleaseSpec("istiod", "istio-system", "istio-istiod", "1.30.6",
                    Map.of(
                            "global", Map.of("meshID", "platform-mesh"),
                            // 批次二：网格侧 tracing 的 provider（ingress/waypoint 的 Telemetry 引用它）。
                            // collector 不在时 Envoy 只丢 span，不影响流量
                            "meshConfig", Map.of(
                                    "enableTracing", true,
                                    "extensionProviders", List.of(Map.of(
                                            "name", "platform-otel",
                                            "opentelemetry", Map.of(
                                                    "service", "platform-otel-gateway-collector.monitor"
                                                            + ".svc.cluster.local",
                                                    "port", 4317))))),
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

    // ==================== tekton ====================

    /**
     * Tekton profile 的可选值与默认值（operator 官方语义，v0.81.2 文档）：
     * {@code lite} = 只有 TektonPipeline；{@code basic} = + Trigger / Result / Chain；
     * {@code all} = basic + Dashboard（K8s）。安装表单里可改。
     */
    public static final List<String> TEKTON_PROFILES = List.of("lite", "basic", "all");
    public static final String DEFAULT_TEKTON_PROFILE = "all";

    /**
     * 配额命名空间里 Tekton 的默认资源。Tekton 自己的容器（place-scripts / prepare /
     * working-dir-initializer）和没写资源的 step 容器默认不带 requests/limits，
     * 会被 ResourceQuota 直接拒（"must specify limits.cpu for: place-scripts, prepare, step-clone"）。
     *
     * <p>开关只能落在 Tekton Pipelines 的 config-defaults ConfigMap 上（operator 0.81.2 的
     * TektonConfig 没有独立字段），通过 {@code spec.pipeline.options.configMaps} 注入：
     * <ul>
     *   <li>{@code default:} —— 覆盖所有"没写资源"的 init/普通容器；</li>
     *   <li>四个内部容器再显式列一遍（官方文档推荐，named entry 优先于 default）。</li>
     * </ul>
     * 取值保守（requests 100m/128Mi，limits 500m/512Mi）：要能塞进常见的 requests.cpu=2 配额。
     */
    private static final String TEKTON_DEFAULT_RESOURCES = """
            default:
              requests:
                cpu: "100m"
                memory: "128Mi"
              limits:
                cpu: "500m"
                memory: "512Mi"
            prepare:
              requests:
                cpu: "100m"
                memory: "128Mi"
              limits:
                cpu: "500m"
                memory: "512Mi"
            place-scripts:
              requests:
                cpu: "100m"
                memory: "128Mi"
              limits:
                cpu: "500m"
                memory: "512Mi"
            working-dir-initializer:
              requests:
                cpu: "100m"
                memory: "128Mi"
              limits:
                cpu: "500m"
                memory: "512Mi"
            sidecar-tekton-log-results:
              requests:
                cpu: "100m"
                memory: "128Mi"
              limits:
                cpu: "500m"
                memory: "512Mi"
            """;

    /**
     * Tekton 由 operator 安装，契约值落在 TektonConfig 上（通过官方 chart 自带的 {@code extraObjects} 下发）：
     * <ul>
     *   <li>{@code coschedule: pipelineruns} —— **构建缓存的硬前提**：一个 TaskRun 要再挂一块长期
     *       PVC workspace，而 {@code workspaces}（Tekton 默认）下**不允许**一个 TaskRun 挂两个 PVC
     *       类 workspace，会直接被 {@code MarkResourceFailed}（ValidateOnlyOnePVCIsUsed）。
     *       1.15.x 的合法值：workspaces / pipelineruns / isolate-pipelinerun / disabled</li>
     *   <li>{@code options.configMaps['config-defaults']} —— 默认容器资源（见
     *       {@link #TEKTON_DEFAULT_RESOURCES}）；不写它，配额命名空间里 TaskRun 直接被拒。
     *       ⚠️ 不要写 {@code default-container-resource-requirements} 到 spec.pipeline 直属：
     *       0.81.2 的 CRD 没有这个字段，SSA 严格校验会拒。</li>
     *   <li>{@code profile: ${tekton.profile}} —— **安装表单里选**（lite / basic / all，默认 all）；
     *       占位符不能解析为空，否则装出的 Tekton 缺组件而 helm 显示成功</li>
     *   <li>{@code targetNamespace: tekton-pipelines} —— 探测判据就在这个命名空间</li>
     * </ul>
     */
    private static final Map<String, Object> TEKTON_CONFIG = Map.of(
            "apiVersion", "operator.tekton.dev/v1alpha1",
            "kind", "TektonConfig",
            "metadata", Map.of("name", "config"),
            "spec", Map.of(
                    "profile", "${tekton.profile}",
                    "targetNamespace", "tekton-pipelines",
                    "pipeline", Map.of(
                            "coschedule", "pipelineruns",
                            "options", Map.of(
                                    "configMaps", Map.of(
                                            "config-defaults", Map.of(
                                                    "data", Map.of(
                                                            "default-container-resource-requirements",
                                                            TEKTON_DEFAULT_RESOURCES)))))));

    /**
     * Tekton 是一个组件：官方 **operator chart** + TektonConfig。
     *
     * <p>⚠️ <b>同一个 release 跑两次</b>，不是笔误：
     * <ul>
     *   <li>这个 chart 的 CRD 在 {@code templates/kubernetes-crds.yaml}（由 {@code installCRDs} 控制，
     *       默认 false）—— 不开 {@code installCRDs}，TektonConfig 根本没有 CRD；</li>
     *   <li>而 TektonConfig 是 CR。Helm 在 **Build 阶段**就要把整份 manifest 映射成对象，
     *       那时 CRD 还没进集群，会报
     *       "no matches for kind TektonConfig ... ensure CRDs are installed first"。</li>
     * </ul>
     * 所以第一遍只装 CRD + operator（不含 extraObjects），第二遍再下发 TektonConfig ——
     * 第二遍启动新的 helm 进程，discovery 里已经有这些 CRD。
     * k8s-server 的多 release 执行是**顺序**的、失败即停，正好保证这个次序。
     */
    private static final Map<String, Object> TEKTON_CRD_VALUES = Map.of(
            "installCRDs", true,
            // operator 自己注册的 webhook 配置**硬编码** service `tekton-operator-webhook`
            // （官方默认 release 名）；我们的 release 带 platform- 前缀，不覆盖的话资源名对不上，
            // TektonConfig 会被 webhook 拒（service not found）。2026-10-18 实装踩到。
            "fullnameOverride", "tekton-operator",
            // TektonConfig 由平台下发：关掉 operator 的自动安装，否则两边抢同一个 config
            "operator", Map.of("autoInstallComponents", false));

    private static final Map<String, Object> TEKTON_FULL_VALUES = Map.of(
            "installCRDs", true,
            "fullnameOverride", "tekton-operator",
            "operator", Map.of("autoInstallComponents", false),
            "extraObjects", List.of(TEKTON_CONFIG));

    private static final List<ReleaseSpec> TEKTON_RELEASES = List.of(
            new ReleaseSpec("platform-tekton-operator", "tekton-operator", "tekton-operator", "0.81.2",
                    TEKTON_CRD_VALUES, Map.of()),
            new ReleaseSpec("platform-tekton-operator", "tekton-operator", "tekton-operator", "0.81.2",
                    TEKTON_FULL_VALUES, Map.of()));

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

    /**
     * OTel operator 两遍共用：第一遍装 CRD + operator，第二遍用 {@code extraObjects} 下发
     * Instrumentation / collector（CRD 是 templates，同一份 manifest 里会报
     * {@code no matches for kind ... ensure CRDs are installed first}，与 Tekton 同一个坑）。
     *
     * <p>collector 镜像固定 contrib：operator 默认的 {@code -k8s} 发行版不含 elasticsearch exporter。
     */
    private static final Map<String, Object> OTEL_OPERATOR_VALUES = Map.of(
            // 🔒 webhook 证书由 cert-manager 签发
            "admissionWebhooks", Map.of("certManager", Map.of("enabled", true)),
            "manager", Map.of("collectorImage", Map.of(
                    "repository", "ghcr.io/open-telemetry/opentelemetry-collector-releases/"
                            + "opentelemetry-collector-contrib",
                    "tag", "0.161.0")));

    private static final ReleaseSpec OTEL_OPERATOR_PASS1 = new ReleaseSpec(
            "platform-otel-operator", "opentelemetry-operator-system", "otel-operator", "0.126.0",
            OTEL_OPERATOR_VALUES, Map.of());

    private static final ReleaseSpec OTEL_OPERATOR_PASS2 = new ReleaseSpec(
            "platform-otel-operator", "opentelemetry-operator-system", "otel-operator", "0.126.0",
            OTEL_OPERATOR_VALUES, Map.of());

    /**
     * Tempo（traces 后端，仅管理集群）。
     *
     * <p>{@code preserveUnknownPlaceholders=true}：{@code ${MINIO_ACCESS_KEY}} /
     * {@code ${MINIO_SECRET_KEY}} 必须留到 Tempo 启动时由 {@code -config.expand-env=true} 展开，
     * 平台的 ValueTemplate 不能把它们当未知占位符拒掉。
     * bucket / endpoint 是平台占位符（表单值），由 ValueTemplate 解析。
     * ⚠️ 不写 {@code prefix}：Tempo 用独立桶（默认 {@code tempo}），block 直接落在桶根，
     *    不与 Thanos 的 {@code thanos} 桶/前缀混用。
     */
    private static final ReleaseSpec TEMPO_RELEASE = new ReleaseSpec(
            "platform-tempo", "monitor", "tempo", "3.2.0",
            Map.of(
                    "tempo", Map.of(
                            "storage", Map.of("trace", Map.of(
                                    "backend", "s3",
                                    "s3", Map.of(
                                            "bucket", "${tempo.minio.bucket}",
                                            "endpoint", "${tempo.minio.endpoint}",
                                            "access_key", "${MINIO_ACCESS_KEY}",
                                            "secret_key", "${MINIO_SECRET_KEY}"),
                                    "wal", Map.of("path", "/var/tempo/wal"))),
                            "extraArgs", Map.of("config.expand-env", "true"),
                            "extraEnvFrom", List.of(Map.of("secretRef",
                                    Map.of("name", "platform-tempo-objstore"))),
                            // 内网部署：不发 usage report；保留期由安装表单覆盖（默认 168h）
                            "reportingEnabled", false,
                            "retention", "168h",
                            "resources", Map.of(
                                    "requests", Map.of("cpu", "200m", "memory", "512Mi"),
                                    "limits", Map.of("cpu", "1", "memory", "2Gi"))),
                    "persistence", Map.of(
                            "enabled", true, "storageClassName", "nfs-client", "size", "20Gi")),
            Map.of(), true);

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
            Map.entry("istio", profiles(ISTIO_RELEASES, List.of())),
            // Tekton 是一个组件：operator chart + TektonConfig 同一个 release（与 calico 同形），
            // 不再单列 tekton-operator
            Map.entry("tekton", Map.of(
                    BUSINESS, new ComponentRecipe(BUSINESS, TEKTON_RELEASES, List.of(),
                            false, false, false, false, null, true),
                    MANAGEMENT, new ComponentRecipe(MANAGEMENT, TEKTON_RELEASES, List.of(),
                            false, false, false, false, null, true))),
            Map.entry("calico", Map.of(
                    BUSINESS, new ComponentRecipe(BUSINESS, CALICO_RELEASES, List.of(), false, false, false, true),
                    MANAGEMENT, new ComponentRecipe(MANAGEMENT, CALICO_RELEASES, List.of(), false, false, false, true))),
            Map.entry("cert-manager", profiles(CERT_MANAGER_RELEASES, List.of())),
            // OTel 采集栈：operator 两遍 + Tempo（管理集群）。Tempo 夹在两遍 operator 之间，
            // 第一遍先落 CRD，Tempo 作为后端就绪后再下发 Instrumentation/collector。
            Map.entry("otel-operator", Map.of(
                    BUSINESS, new ComponentRecipe(BUSINESS,
                            List.of(OTEL_OPERATOR_PASS1, OTEL_OPERATOR_PASS2), List.of("cert-manager"),
                            false, false, false, false, null, false,
                            new OtelRecipe(true, false, true, "")),
                    MANAGEMENT, new ComponentRecipe(MANAGEMENT,
                            List.of(OTEL_OPERATOR_PASS1, TEMPO_RELEASE, OTEL_OPERATOR_PASS2),
                            List.of("cert-manager"),
                            false, false, false, false, null, false,
                            new OtelRecipe(true, true, true, "platform-tempo.monitor.svc:4317")))));

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
