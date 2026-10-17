package com.coding.platformapi.components;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ComponentRecipeCatalogTest {

    private final ComponentRecipeCatalog catalog = new ComponentRecipeCatalog();

    @Test
    void everyCatalogComponentHasARecipeForBothProfiles() {
        // 目录（探测）与 recipe（安装）必须一一对应：加了组件却忘了 recipe，面板会显示"暂不支持"
        for (ComponentDef def : new ClusterComponentCatalog().all()) {
            assertThat(catalog.supports(def.code())).as(def.code()).isTrue();
            for (String profile : List.of("BUSINESS", "MANAGEMENT")) {
                assertThat(catalog.recipeOf(def.code(), profile))
                        .as("%s / %s", def.code(), profile).isNotNull();
            }
        }
    }

    @Test
    void metricsServerRecipeCarriesTheKubeletInsecureTlsContract() {
        // 契约值：自签 kubelet 证书的集群不加这个参数，metrics-server 永远 NotReady
        ReleaseSpec spec = catalog.recipeOf("metrics-server", "BUSINESS").releases().get(0);
        assertThat(String.valueOf(spec.contractValues().get("args")))
                .contains("--kubelet-insecure-tls");
    }

    @Test
    void releaseNameAndNamespaceAreFixed() {
        ReleaseSpec spec = catalog.recipeOf("metrics-server", "BUSINESS").releases().get(0);
        assertThat(spec.releaseName()).isEqualTo("platform-metrics-server");
        assertThat(spec.namespace()).isEqualTo("kube-system");
    }

    @Test
    void theContractPinsTheDeploymentNameTheProbeLooksFor() {
        // 官方 chart 默认用 release 名做 fullname（platform-metrics-server），
        // 而 ClusterComponentCatalog 探测的是 deployment `metrics-server` —— 名字对不上就永远"未安装"
        ReleaseSpec spec = catalog.recipeOf("metrics-server", "BUSINESS").releases().get(0);
        assertThat(spec.contractValues().get("fullnameOverride")).isEqualTo("metrics-server");
    }

    @Test
    void anUnknownComponentOrProfileHasNoRecipe() {
        assertThat(catalog.recipeOf("no-such", "BUSINESS")).isNull();
        assertThat(catalog.supports("metrics-server")).isTrue();
    }

    @Test
    void everyRecipePointsAtAChartThatExistsInTheAdapterBundle() {
        // ⭐ 跨模块一致性：recipe 指向的 (chartCode, chartVersion) 必须真的在 k8s-server 的内置 chart 里
        for (String code : catalog.codes()) {
            for (String profile : List.of("BUSINESS", "MANAGEMENT")) {
                for (ReleaseSpec r : catalog.recipeOf(code, profile).releases()) {
                    Path chart = Path.of("../k8s-server/src/main/resources/charts/components/"
                            + r.chartCode() + "/" + r.chartVersion() + "/chart/Chart.yaml");
                    assertThat(Files.exists(chart))
                            .as("%s / %s → %s", code, profile, chart).isTrue();
                }
            }
        }
    }

    @Test
    void dependenciesAreDeclared() {
        // spec §5：ambient 依赖控制面、calico 依赖 operator（operator 单独装不改网络）、
        // otel 依赖 cert-manager、tekton 由 operator 安装
        assertThat(catalog.recipeOf("istio-ambient", "BUSINESS").requires()).containsExactly("istio");
        assertThat(catalog.recipeOf("tekton", "BUSINESS").requires()).containsExactly("tekton-operator");
        assertThat(catalog.recipeOf("calico", "BUSINESS").requires()).containsExactly("tigera-operator");
        assertThat(catalog.recipeOf("otel-operator", "BUSINESS").requires()).containsExactly("cert-manager");
    }

    @Test
    void everyDependencyHasItsOwnRecipe() {
        for (String code : catalog.codes()) {
            for (String dep : catalog.recipeOf(code, "BUSINESS").requires()) {
                assertThat(catalog.supports(dep)).as("%s 依赖 %s", code, dep).isTrue();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> specOf(String code, String... path) {
        Object current = catalogValues(code);
        for (String p : path) {
            current = ((Map<String, Object>) current).get(p);
        }
        return (Map<String, Object>) current;
    }

    private static Map<String, Object> catalogValues(String code) {
        return new ComponentRecipeCatalog().recipeOf(code, "BUSINESS").releases().get(0).contractValues();
    }

    @Test
    void prometheusContractForcesTheSelectorOverridesAndTheClusterLabel() {
        // 这几条写错的后果是"装上了但告警/监控静默失效"（spec §5.1）
        Map<String, Object> prometheusSpec = specOf("prometheus-operator", "prometheus", "prometheusSpec");
        assertThat(prometheusSpec)
                .containsEntry("ruleSelectorNilUsesHelmValues", false)
                .containsEntry("serviceMonitorSelectorNilUsesHelmValues", false)
                .containsEntry("podMonitorSelectorNilUsesHelmValues", false)
                .containsEntry("probeSelectorNilUsesHelmValues", false)
                .containsEntry("ruleNamespaceSelector", Map.of())
                .containsEntry("remoteWrite", List.of(Map.of("url", "${thanos.receive.url}")));
        assertThat((Map<String, Object>) prometheusSpec.get("externalLabels"))
                .containsEntry("cluster_name", "${cluster.name}");

        Map<String, Object> alertmanagerSpec = specOf("prometheus-operator", "alertmanager", "alertmanagerSpec");
        assertThat((Map<String, Object>) alertmanagerSpec.get("alertmanagerConfigMatcherStrategy"))
                .containsEntry("type", "OnNamespace");
    }

    @Test
    void tektonOperatorContractCarriesTheTektonConfig() {
        // coschedule 决定构建缓存能不能用；配额下 init 容器需要默认资源
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> extra = (List<Map<String, Object>>)
                catalog.recipeOf("tekton-operator", "BUSINESS").releases().get(0)
                        .contractValues().get("extraObjects");
        assertThat(extra).hasSize(1);
        Map<String, Object> spec = (Map<String, Object>) extra.get(0).get("spec");
        Map<String, Object> pipeline = (Map<String, Object>) spec.get("pipeline");
        assertThat(pipeline).containsEntry("coschedule", "workspaces");
        assertThat(pipeline).containsKey("default-container-resource-requirements");
    }

    @Test
    void onlyPrometheusDeclaresPlatformAlerting() {
        // 平台告警联动只对 Prometheus 有意义（Alertmanager 由 kube-prometheus-stack 带出来）
        assertThat(catalog.recipeOf("prometheus-operator", "BUSINESS").platformAlerting()).isTrue();
        for (String code : catalog.codes()) {
            if (!code.equals("prometheus-operator")) {
                assertThat(catalog.recipeOf(code, "BUSINESS").platformAlerting()).as(code).isFalse();
            }
        }
    }

    @Test
    void tigeraOperatorDoesNotTakeOverTheNetworkOnItsOwn() {
        // 只有 Installation CR 才接管 CNI：operator 单独安装必须是 installation.enabled=false
        Map<String, Object> installation = specOf("tigera-operator", "installation");
        assertThat(installation).containsEntry("enabled", false);

        Map<String, Object> calico = specOf("calico", "installation");
        assertThat(calico).containsEntry("enabled", true);
    }
}
