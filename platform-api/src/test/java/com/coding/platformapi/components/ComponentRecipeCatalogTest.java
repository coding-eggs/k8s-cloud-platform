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
        // tigera-operator 已从目录移除（随 calico 的 release 一起安装），所以每个组件都必须有 recipe
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
        // spec §5：ambient 依赖控制面、otel 依赖 cert-manager、tekton 由 operator 安装。
        // calico 不再依赖 tigera-operator —— 它自己就把 operator 一起装了
        assertThat(catalog.recipeOf("istio-ambient", "BUSINESS").requires()).containsExactly("istio");
        assertThat(catalog.recipeOf("tekton", "BUSINESS").requires()).containsExactly("tekton-operator");
        assertThat(catalog.recipeOf("calico", "BUSINESS").requires()).isEmpty();
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
        // OnNamespaceExceptForAlertmanagerNamespace：Alertmanager 所在 ns（monitor）的 AMC 处理全部告警，
        // 租户 ns 的 AMC 仍只处理本 ns —— 用 OnNamespace 平台收不到任何租户告警
        assertThat((Map<String, Object>) alertmanagerSpec.get("alertmanagerConfigMatcherStrategy"))
                .containsEntry("type", "OnNamespaceExceptForAlertmanagerNamespace");
    }

    @Test
    void prometheusDisablesTheControlPlaneScrapersAndEnablesTheCrdUpgradeJob() {
        Map<String, Object> values = catalogValues("prometheus-operator");
        for (String key : List.of("kubeApiServer", "kubeControllerManager", "kubeEtcd", "kubeScheduler")) {
            assertThat((Map<String, Object>) values.get(key)).as(key).containsEntry("enabled", false);
        }
        // Helm 不升级 crds/ 目录：官方 chart 的 Job 是 CRD 升级的唯一正确姿势
        assertThat((Map<String, Object>) values.get("crds"))
                .containsEntry("enabled", true)
                .containsEntry("upgradeJob", Map.of("enabled", true));
    }

    @Test
    void grafanaIsOnlyEnabledByDefaultOnTheManagementProfile() {
        Map<String, Object> business = catalog.recipeOf("prometheus-operator", "BUSINESS")
                .releases().get(0).editableValues();
        Map<String, Object> management = catalog.recipeOf("prometheus-operator", "MANAGEMENT")
                .releases().get(0).editableValues();

        assertThat(business).containsEntry("grafana.enabled", false);
        assertThat(management).containsEntry("grafana.enabled", true);
    }

    @Test
    void istioShipsTheAmbientDataplaneByDefault() {
        List<String> releases = catalog.recipeOf("istio", "BUSINESS").releases().stream()
                .map(ReleaseSpec::releaseName).toList();
        assertThat(releases).containsExactly("istio-base", "istiod", "istio-cni", "ztunnel", "external-gateway");

        ReleaseSpec cni = catalog.recipeOf("istio", "BUSINESS").releases().stream()
                .filter(r -> r.releaseName().equals("istio-cni")).findFirst().orElseThrow();
        assertThat((Map<String, Object>) cni.contractValues().get("ambient")).containsEntry("enabled", true);
    }

    @Test
    void metricsServerPinsTheKubeletAddressPreference() {
        // 不依赖 chart 的 defaultArgs 默认值：换版本时漂移会变成"连不上 kubelet"
        List<String> defaultArgs = (List<String>) catalog.recipeOf("metrics-server", "BUSINESS")
                .releases().get(0).contractValues().get("defaultArgs");
        assertThat(defaultArgs).contains("--kubelet-preferred-address-types=InternalIP");
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
    void theManagementPrometheusRecipeAlsoInstallsThanos() {
        var management = catalog.recipeOf("prometheus-operator", "MANAGEMENT");
        assertThat(management.releases()).extracting(ReleaseSpec::releaseName)
                .containsExactly("platform-kps", "platform-thanos");
        assertThat(management.thanosInstall()).isTrue();
        // 表单必须能改：跨集群场景要填外部地址（同集群才用 Service 名）
        assertThat(management.thanosRemoteWrite()).isTrue();
        assertThat(management.thanosReceiveDefault())
                .isEqualTo("http://platform-thanos-receive.monitor.svc:10908/api/v1/receive");

        // 管理集群的 remoteWrite 默认指向集群内 receive（thanos chart 的 remote-write 端口是 10908）
        Map<String, Object> spec = (Map<String, Object>) ((Map<String, Object>)
                management.releases().get(0).contractValues().get("prometheus")).get("prometheusSpec");
        assertThat(spec.get("remoteWrite").toString())
                .contains("http://platform-thanos-receive.monitor.svc:10908/api/v1/receive");

        // thanos 挂载的 objstore Secret 名必须与 platform-api 下发时用的一致
        Map<String, Object> thanos = management.releases().get(1).contractValues();
        Map<String, Object> objstore = (Map<String, Object>)
                ((Map<String, Object>) thanos.get("global")).get("objstore");
        assertThat(objstore).containsEntry("secretName", "platform-thanos-objstore")
                .containsEntry("createSecret", false);

        var business = catalog.recipeOf("prometheus-operator", "BUSINESS");
        assertThat(business.releases()).hasSize(1);
        assertThat(business.thanosRemoteWrite()).isTrue();
        assertThat(business.thanosInstall()).isFalse();
    }

    @Test
    void persistenceIsAStorageClassPlusSizeDefault() {
        // 持久化在表单里是「存储类 + 容量」两个字段（不是一个 JSON 文本域）：
        // StorageClass 默认空串 = 跟随集群默认存储类（不写进 values）
        Map<String, Object> editable = catalog.recipeOf("prometheus-operator", "BUSINESS")
                .releases().get(0).editableValues();
        assertThat(editable)
                .containsEntry("alertmanager.alertmanagerSpec.storage.volumeClaimTemplate.spec"
                        + ".resources.requests.storage", "10Gi")
                .containsEntry("alertmanager.alertmanagerSpec.storage.volumeClaimTemplate.spec"
                        + ".storageClassName", "")
                .containsEntry("prometheus.prometheusSpec.storageSpec.volumeClaimTemplate.spec"
                        + ".resources.requests.storage", "50Gi")
                .containsEntry("prometheus.prometheusSpec.storageSpec.volumeClaimTemplate.spec"
                        + ".storageClassName", "");
    }

    @Test
    void calicoIsInstalledAsAUnitAndDisablesUnneededSubComponents() {
        // operator + Installation 一个 release：不需要的 APIServer / Goldmane / Whisker 默认关闭
        var recipe = catalog.recipeOf("calico", "BUSINESS");
        assertThat(recipe.calicoNetwork()).as("安装表单要填 CIDR/封装").isTrue();
        Map<String, Object> values = catalogValues("calico");
        assertThat((Map<String, Object>) values.get("installation")).containsEntry("enabled", true);
        for (String key : List.of("apiServer", "goldmane", "whisker")) {
            assertThat((Map<String, Object>) values.get(key)).as(key).containsEntry("enabled", false);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> network = (Map<String, Object>) ((Map<String, Object>) values.get("installation"))
                .get("calicoNetwork");
        assertThat(network.toString()).contains("${calico.podCidr}").contains("${calico.encapsulation}");
    }
}
