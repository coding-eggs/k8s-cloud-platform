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
 * <b>有活判据（工作负载 / CRD 探测）时，活判据是"装没装"的唯一权威</b>：{@code apiGroup} 只是
 * capability 快照里的附带信息，<b>不能单独当"已装"</b> —— Helm 卸载不删 CRD、快照也不会自己刷新，
 * group 会永久残留（2026-10-18 实测：`helm uninstall` 旧 Prometheus 后页面仍显示「已安装」）。
 * 只有目录里没有任何活判据的组件才退到 group 判据（当前没有这样的组件）。
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
            // Istio 是一个安装单元：Gateway API CRD + base + istiod + cni + ztunnel + external-gateway
            // 同一个 recipe（2026-10-19 把 gateway-api 组件合并进来 —— 装 Istio 不带 Gateway API
            // 等于路由/网关页不可用，拆成两个组件只会让用户漏装一个）。
            // ambient 数据面（ztunnel/cni）随它一起装 —— 不再单列 istio-ambient（2026-10-18）。
            // 实测镜像是 registry.istio.io/release/pilot（含 `istio.io/release/pilot` 片段）；
            // app.kubernetes.io/version 标签只有部分安装方式才有，故 tag 是主来源
            // 两条候选同一 (ns,kind)：先匹配 registry.istio.io/release/pilot（本集群实测），
            // 再兜底 docker.io/istio/pilot（标准 istioctl/Helm 安装）。name 快路径吃 revision 安装
            // （部署名可能是 istiod-1-30，但镜像不变）。
            // ⚠️ 不把 Gateway API 的 CRD 判据挂到这里：多判据是 ANY-命中，istiod 没了但 CRD 还在时
            //    会把整个组件判成"已安装"。Gateway API 是否可用由 MeshService 读 capability 版本回答。
            new ComponentDef("istio", "Istio", "networking.istio.io",
                    List.of(new ComponentProbe("istio-system", "deployment", "istio.io/release/pilot", "istiod"),
                            new ComponentProbe("istio-system", "deployment", "istio/pilot", null)),
                    List.of("service-mesh", "ambient-mesh", "routes")),
            new ComponentDef("tekton", "Tekton Pipelines", "tekton.dev",
                    List.of(new ComponentProbe("tekton-pipelines", "deployment", "tektoncd/pipeline/controller-",
                            "tekton-pipelines-controller")),
                    List.of("ci")),
            new ComponentDef("calico", "Calico", "crd.projectcalico.org",
                    List.of(new ComponentProbe("calico-system", "daemonset", "calico/node", "calico-node")),
                    List.of("network", "ipam")),
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
