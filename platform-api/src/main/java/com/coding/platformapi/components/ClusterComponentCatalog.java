package com.coding.platformapi.components;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 集群组件目录 —— 本批的<b>唯一出处</b>：哪些组件、凭什么判它装了、它支撑平台哪些功能。
 *
 * <h2>为什么在 platform-api 而不是 k8s-server</h2>
 * 目录里是业务判断（哪个组件、影响哪个功能）。本仓的立场是"适配器不读 DB 列做业务判断"
 * （见 {@code MeshController} 类注释，2026-10-10）：k8s-server 只提供"按候选读工作负载"这件
 * K8s 侧的事，认不认得出那是什么组件由本层决定。
 *
 * <h2>判据为什么可以有两个</h2>
 * API group 回答"这个集群有没有这种 API"（装了才有），工作负载回答"它在不在跑、什么版本、健不健康"。
 * 两者都写就都验：group 在、工作负载也就绪才算「已装且健康」。只有 group 的（如 Gateway API，
 * 纯 CRD）版本列会空着 —— 这是诚实的，不是缺陷。
 *
 * <h2>功能 code 是稳定 id，不是展示文案</h2>
 * 门控按 code 查（{@code flags("ci")}），文案改一次不该让门控失灵。
 */
@Component
public class ClusterComponentCatalog {

    /**
     * ⚠️ 顺序即展示顺序（单测钉住）。工作负载判据的 {@code imageRepo} 按**镜像仓库片段**写：
     * Helm release 名可变（{@code ps-kube-prometheus-stack-operator}），仓库路径不变。
     * {@code name} 只给名字稳定的组件当快路径（istiod / ztunnel / calico-node）。
     */
    private static final List<ComponentDef> COMPONENTS = List.of(
            new ComponentDef("metrics-server", "metrics-server", "metrics.k8s.io",
                    List.of(new ComponentProbe("kube-system", "deployment", "metrics-server/metrics-server", "metrics-server")),
                    List.of("hpa-resource-metrics")),
            new ComponentDef("prometheus-operator", "Prometheus Operator", "monitoring.coreos.com",
                    List.of(new ComponentProbe("monitor", "deployment", "prometheus-operator/prometheus-operator", null)),
                    List.of("metrics-charts", "servicemonitor")),
            new ComponentDef("gateway-api", "Gateway API", "gateway.networking.k8s.io",
                    List.of(), List.of("service-mesh", "routes")),
            // 真实镜像是 docker.io/istio/pilot；app.kubernetes.io/version 标签只有 Helm 装的才有
            new ComponentDef("istio", "Istio", "networking.istio.io",
                    List.of(new ComponentProbe("istio-system", "deployment", "istio/pilot", "istiod")),
                    List.of("service-mesh")),
            new ComponentDef("istio-ambient", "Istio ambient 数据面", null,
                    List.of(new ComponentProbe("istio-system", "daemonset", "istio/ztunnel", "ztunnel")),
                    List.of("ambient-mesh")),
            new ComponentDef("tekton", "Tekton Pipelines", "tekton.dev",
                    List.of(new ComponentProbe("tekton-pipelines", "deployment", "tektoncd/pipeline/controller-", null)),
                    List.of("ci")),
            new ComponentDef("tekton-operator", "Tekton Operator", "operator.tekton.dev",
                    List.of(new ComponentProbe("tekton-operator", "deployment", "tektoncd/operator/operator-", null)),
                    List.of("ci")),
            new ComponentDef("calico", "Calico", "crd.projectcalico.org",
                    List.of(new ComponentProbe("calico-system", "daemonset", "calico/node", "calico-node")),
                    List.of("network", "ipam")),
            new ComponentDef("tigera-operator", "Tigera Operator", "operator.tigera.io",
                    List.of(new ComponentProbe("tigera-operator", "deployment", "tigera/operator", "tigera-operator")),
                    List.of("network")),
            new ComponentDef("cert-manager", "cert-manager", "cert-manager.io",
                    List.of(new ComponentProbe("cert-manager", "deployment", "jetstack/cert-manager-controller", null)),
                    List.of("gateway-tls")),
            new ComponentDef("otel-operator", "OpenTelemetry Operator", "opentelemetry.io",
                    List.of(new ComponentProbe("opentelemetry-operator-system", "deployment",
                            "opentelemetry-operator/opentelemetry-operator", null)),
                    List.of("otel-collection")));

    public List<ComponentDef> all() {
        return COMPONENTS;
    }

    public ComponentDef byCode(String code) {
        return COMPONENTS.stream().filter(d -> d.code().equals(code)).findFirst().orElse(null);
    }

    /** 全部功能 code（稳定 id），按字典序 —— 门控与展示共用 */
    public List<String> featureCodes() {
        return COMPONENTS.stream().flatMap(d -> d.features().stream()).distinct().sorted().toList();
    }

    /** 功能 → 组件：**算出来的**，不手写第二张表 */
    public List<ComponentDef> componentsOf(String featureCode) {
        return COMPONENTS.stream().filter(d -> d.features().contains(featureCode))
                .sorted(Comparator.comparing(ComponentDef::code)).toList();
    }
}
