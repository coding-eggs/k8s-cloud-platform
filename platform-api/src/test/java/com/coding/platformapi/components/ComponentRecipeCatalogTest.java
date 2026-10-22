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
        assertThat(catalog.supports("istio-ambient")).as("ambient 随 istio 同一个安装单元").isFalse();
        assertThat(catalog.recipeOf("tekton", "BUSINESS").requires()).isEmpty();
        assertThat(catalog.supports("tekton-operator")).as("operator 不再单列，随 tekton 同一个 release").isFalse();
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

    @Test
    void everyReleaseDeclaresTheChartAndApplicationVersions() {
        // chart 版本与应用版本是两套编号（kube-prometheus-stack 92.10.1 → v0.94.1；
        // tempo 3.2.0 → 3.1.0）。组件页要逐个 release 展示，recipe 必须自带 appVersion，
        // 且与内置 chart 的 Chart.yaml 一致（跨模块校验，防止入库后忘记同步）。
        for (String code : catalog.codes()) {
            for (String profile : List.of("BUSINESS", "MANAGEMENT")) {
                for (ReleaseSpec r : catalog.recipeOf(code, profile).releases()) {
                    Path chart = Path.of("../k8s-server/src/main/resources/charts/components/"
                            + r.chartCode() + "/" + r.chartVersion() + "/chart/Chart.yaml");
                    assertThat(r.appVersion())
                            .as("%s / %s → %s 的 appVersion", code, profile, r.releaseName())
                            .isEqualTo(appVersionOf(chart));
                }
            }
        }
    }

    @Test
    void everyRecipeMarksExactlyOnePrimaryRelease() {
        // 主 release = 组件本体的版本（availableVersions / 升级目标只认它）。
        // 同名 release 的多遍安装（tekton / otel）算同一个。
        for (String code : catalog.codes()) {
            for (String profile : List.of("BUSINESS", "MANAGEMENT")) {
                var primaryNames = catalog.recipeOf(code, profile).releases().stream()
                        .filter(ReleaseSpec::primary)
                        .map(ReleaseSpec::releaseName)
                        .distinct()
                        .toList();
                assertThat(primaryNames).as("%s / %s", code, profile).hasSize(1);
            }
        }
    }

    @Test
    void thanosRetentionIsEditableWithTheChartDefaults() {
        // compactor 的保留期做成表单（默认值与 chart 一致）；改小会立即删历史，所以默认必须显式
        var management = catalog.recipeOf("prometheus-operator", "MANAGEMENT");
        var thanos = management.releases().stream()
                .filter(r -> r.releaseName().equals("platform-thanos")).findFirst().orElseThrow();

        assertThat(thanos.editableValues())
                .containsEntry("compactor.retention.resolutionRaw", "30d")
                .containsEntry("compactor.retention.resolution5m", "90d")
                .containsEntry("compactor.retention.resolution1h", "365d");
    }

    private static String appVersionOf(Path chart) {
        try {
            return Files.readAllLines(chart).stream()
                    .filter(line -> line.startsWith("appVersion:"))
                    .findFirst()
                    .map(line -> line.substring("appVersion:".length()).trim())
                    .orElse(null);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
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
    void nodeExporterEnablesTheCpuInfoCollectorWithoutLosingTheFilesystemExcludes() {
        // node_exporter 1.12 的 --collector.cpu.info **没有默认值**（Bool，默认 false），
        // 不开就没有 node_cpu_info。extraArgs 是 list，Helm 合并时**整体替换** ——
        // 只写 cpu.info 会把 chart 默认的两条 filesystem exclude 冲掉，必须一起写回。
        ReleaseSpec kps = catalog.recipeOf("prometheus-operator", "BUSINESS").releases().get(0);
        Map<String, Object> nodeExporter = (Map<String, Object>)
                kps.contractValues().get("prometheus-node-exporter");
        List<String> args = (List<String>) nodeExporter.get("extraArgs");
        assertThat(args).contains("--collector.cpu.info");
        assertThat(args).anyMatch(a -> a.startsWith("--collector.filesystem.mount-points-exclude="));
        assertThat(args).anyMatch(a -> a.startsWith("--collector.filesystem.fs-types-exclude="));
    }

    @Test
    void istioShipsGatewayApiAndTheAmbientDataplaneByDefault() {
        // Gateway API 于 2026-10-19 并入 istio：装 Istio 必带 CRD，且排在最前
        List<String> releases = catalog.recipeOf("istio", "BUSINESS").releases().stream()
                .map(ReleaseSpec::releaseName).toList();
        assertThat(releases).containsExactly("platform-gateway-api", "istio-base", "istiod",
                "istio-cni", "ztunnel", "external-gateway");
        assertThat(catalog.recipeOf("istio", "BUSINESS").releases().get(0).chartCode())
                .isEqualTo("gateway-api");

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
    void tektonContractCarriesTheTektonConfig() {
        // 同一个 release 两遍：第一遍 CRD+operator，第二遍再下发 TektonConfig（见 recipe 注释）
        var releases = catalog.recipeOf("tekton", "BUSINESS").releases();
        assertThat(releases).hasSize(2)
                .allSatisfy(r -> assertThat(r.contractValues())
                        .containsEntry("installCRDs", true)
                        // operator 注册的 webhook 配置硬编码 `tekton-operator-webhook`，资源名必须对齐
                        .containsEntry("fullnameOverride", "tekton-operator"));
        assertThat(releases.get(0).contractValues()).doesNotContainKey("extraObjects");
        assertThat(releases.get(1).contractValues()).containsKey("extraObjects");

        // coschedule 决定构建缓存能不能用（一个 TaskRun 要挂两块 PVC workspace → 必须 pipelineruns）；
        // 配额下 init 容器需要默认资源
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> extra = (List<Map<String, Object>>)
                releases.get(1).contractValues().get("extraObjects");
        assertThat(extra).hasSize(1);
        Map<String, Object> spec = (Map<String, Object>) extra.get(0).get("spec");
        Map<String, Object> pipeline = (Map<String, Object>) spec.get("pipeline");
        assertThat(pipeline).containsEntry("coschedule", "pipelineruns");
        // tekton-operator 0.81.2 的 TektonConfig CRD **没有** default-container-resource-requirements 字段
        // （SSA 严格校验会直接拒）。默认资源走 pipeline.options.configMaps → config-defaults
        assertThat(pipeline).doesNotContainKey("default-container-resource-requirements");
        Map<String, Object> options = (Map<String, Object>) pipeline.get("options");
        Map<String, Object> configMaps = (Map<String, Object>) options.get("configMaps");
        Map<String, Object> configDefaults = (Map<String, Object>) configMaps.get("config-defaults");
        Map<String, Object> data = (Map<String, Object>) configDefaults.get("data");
        assertThat(data.get("default-container-resource-requirements").toString())
                .contains("default:").contains("place-scripts").contains("prepare");
        // profile 由安装表单选（占位符），默认 all
        assertThat(spec).containsEntry("profile", "${tekton.profile}");
        assertThat(catalog.recipeOf("tekton", "BUSINESS").tektonProfile()).isTrue();
        // 我们自己下发 TektonConfig，就必须关掉 operator 的自动安装，否则两边抢同一个 config
        Map<String, Object> operator = (Map<String, Object>)
                releases.get(1).contractValues().get("operator");
        assertThat(operator).containsEntry("autoInstallComponents", false);
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
        Map<String, Object> thanosGlobal = (Map<String, Object>) thanos.get("global");
        Map<String, Object> objstore = (Map<String, Object>) thanosGlobal.get("objstore");
        assertThat(objstore).containsEntry("secretName", "platform-thanos-objstore")
                .containsEntry("createSecret", false);

        // chart 默认 global.thanosRules.enabled=true，会下发 thanos-component-absent 的 absent(up{...})
        // 规则；必须同时开 ServiceMonitor，否则 Prometheus 里根本没有 thanos 的 up 序列，
        // 四条（compact/query/receive/store）会恒 firing。
        assertThat((Map<String, Object>) thanosGlobal.get("serviceMonitor"))
                .containsEntry("enabled", true);

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
    void calicoInstallsTheCrdChartBeforeTheOperator() {
        // 官方 tigera-operator chart 里**没有 CRD**（CRD 在独立 chart projectcalico.org.v3 里，
        // 官方 docs 是 helm template | kubectl apply）。不先装它，operator release 里的
        // Installation 在 manifest Build 阶段就会报
        // "no matches for kind Installation in version operator.tigera.io/v1 ... ensure CRDs are installed first"
        // （2026-10-22 实锤）。
        var releases = catalog.recipeOf("calico", "BUSINESS").releases();
        assertThat(releases).extracting(ReleaseSpec::releaseName)
                .containsExactly("platform-calico-crds", "platform-tigera-operator");
        ReleaseSpec crds = releases.get(0);
        assertThat(crds.chartCode()).isEqualTo("projectcalico-crds");
        assertThat(crds.chartVersion()).isEqualTo("v3.33.0");
        assertThat(crds.namespace()).isEqualTo("tigera-operator");
        assertThat(crds.primary()).as("CRD chart 不是组件本体").isFalse();
        assertThat(ReleaseSpec.primaryChartVersion(releases)).isEqualTo("v3.33.0");
    }

    @Test
    void calicoCrdChartIsAppliedWithServerSideApplyInsteadOfHelm() {
        // CRD chart 渲染后 3.5MB：Helm 把**整个 chart** 存进 release Secret，超过 1MiB 上限
        // （2026-10-22 实测 "Secret sh.helm.release.v1.platform-calico-crds.v1 ... Too long:
        // may not be more than 1048576 bytes"）。官方 docs 也是 helm template | kubectl apply --server-side。
        // 所以这条 release 走 server-side apply、不建 Helm release；operator 仍是正常 Helm release。
        var releases = catalog.recipeOf("calico", "BUSINESS").releases();
        assertThat(releases.get(0).applyMode()).isEqualTo(ReleaseSpec.APPLY_SERVER_SIDE);
        assertThat(releases.get(1).applyMode()).isNull();
    }

    @Test
    void calicoIsInstalledAsAUnitAndDisablesUnneededSubComponents() {
        // operator + Installation 一个 release：不需要的 APIServer / Goldmane / Whisker 默认关闭
        var recipe = catalog.recipeOf("calico", "BUSINESS");
        assertThat(recipe.calicoNetwork()).as("安装表单要填 CIDR/封装").isTrue();
        Map<String, Object> values = calicoOperatorValues();
        assertThat((Map<String, Object>) values.get("installation")).containsEntry("enabled", true);
        for (String key : List.of("apiServer", "goldmane", "whisker")) {
            assertThat((Map<String, Object>) values.get(key)).as(key).containsEntry("enabled", false);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> network = (Map<String, Object>) ((Map<String, Object>) values.get("installation"))
                .get("calicoNetwork");
        assertThat(network.toString()).contains("${calico.podCidr}").contains("${calico.encapsulation}");
    }

    /** calico 的主 release（operator+Installation）；第 0 个是 CRD chart，values 为空 */
    private Map<String, Object> calicoOperatorValues() {
        return catalog.recipeOf("calico", "BUSINESS").releases().stream()
                .filter(ReleaseSpec::primary).findFirst().orElseThrow().contractValues();
    }

    // ==================== otel-operator 采集栈（2026-10-18） ====================

    @Test
    void theOtelRecipeHasThreeReleasesOnManagementAndTwoOnBusiness() {
        // 两遍同名 operator release 之间夹一个 Tempo：先有 CRD，再有后端，最后下发 CR
        var mgmt = catalog.recipeOf("otel-operator", "MANAGEMENT");
        assertThat(mgmt.releases()).extracting(ReleaseSpec::releaseName)
                .containsExactly("platform-otel-operator", "platform-tempo", "platform-otel-operator");
        assertThat(mgmt.releases().get(1).chartCode()).isEqualTo("tempo");
        assertThat(mgmt.releases().get(1).chartVersion()).isEqualTo("3.2.0");
        assertThat(mgmt.releases().get(1).preserveUnknownPlaceholders()).isTrue();
        assertThat(mgmt.otel()).isNotNull();
        assertThat(mgmt.otel().tempoInstall()).isTrue();
        assertThat(mgmt.otel().tempoEndpointDefault()).isEqualTo("platform-tempo.monitor.svc:4317");

        var biz = catalog.recipeOf("otel-operator", "BUSINESS");
        assertThat(biz.releases()).extracting(ReleaseSpec::releaseName)
                .containsExactly("platform-otel-operator", "platform-otel-operator");
        assertThat(biz.otel().tempoInstall()).isFalse();
        assertThat(biz.otel().tempoRemoteWrite()).isTrue();
    }

    @Test
    void theOtelCollectorImageIsContrib() {
        // operator 默认的 -k8s 发行版不含 elasticsearch exporter
        @SuppressWarnings("unchecked")
        Map<String, Object> pass2 = catalog.recipeOf("otel-operator", "BUSINESS").releases().get(1)
                .contractValues();
        Map<String, Object> manager = (Map<String, Object>) pass2.get("manager");
        Map<String, Object> image = (Map<String, Object>) manager.get("collectorImage");
        assertThat(image).containsEntry("repository",
                "ghcr.io/open-telemetry/opentelemetry-collector-releases/opentelemetry-collector-contrib");
        assertThat(image).containsEntry("tag", "0.161.0");
    }

    @Test
    void istioDeclaresTheOtelTracingProvider() {
        // 网格侧只声明 provider；具体哪些 Gateway 用它由 Telemetry CR 决定（批次二）
        var istiod = catalog.recipeOf("istio", "BUSINESS").releases().stream()
                .filter(r -> r.releaseName().equals("istiod")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> mesh = (Map<String, Object>) istiod.contractValues().get("meshConfig");
        assertThat(mesh).containsEntry("enableTracing", true);
        assertThat(mesh.get("extensionProviders").toString())
                .contains("platform-otel")
                .contains("platform-otel-gateway-collector.monitor.svc.cluster.local")
                .contains("4317");
    }

    @Test
    void istioIstiodCarriesTheAmbientProfileValues() {        // 只装 ztunnel/cni、不给 istiod 开 ambient 的后果：ztunnel 连 15012 直接被拒，
        // 日志刷 "ztunnel requires PILOT_ENABLE_AMBIENT=true"（2026-10-20 实锤）。
        // ISTIO_META_ENABLE_HBONE 来自官方 profile-ambient.yaml，waypoint（Envoy）靠它走 HBONE
        var istiod = catalog.recipeOf("istio", "BUSINESS").releases().stream()
                .filter(r -> r.releaseName().equals("istiod")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> pilot = (Map<String, Object>) istiod.contractValues().get("pilot");
        assertThat((Map<String, Object>) pilot.get("env"))
                .containsEntry("PILOT_ENABLE_AMBIENT", "true");

        @SuppressWarnings("unchecked")
        Map<String, Object> mesh = (Map<String, Object>) istiod.contractValues().get("meshConfig");
        @SuppressWarnings("unchecked")
        Map<String, Object> defaultConfig = (Map<String, Object>) mesh.get("defaultConfig");
        assertThat((Map<String, Object>) defaultConfig.get("proxyMetadata"))
                .containsEntry("ISTIO_META_ENABLE_HBONE", "true");
    }

    @Test
    void externalGatewayExposesServiceTypeAndPortsAsEditableValues() {
        // 对外入口的 Service 必须可配：默认 LoadBalancer + 80/443/15021；
        // 端口列表写进 recipe 默认值意味着每次重放都显式声明整组端口（Helm 的 list 是整体替换）
        var gateway = catalog.recipeOf("istio", "BUSINESS").releases().stream()
                .filter(r -> r.releaseName().equals("external-gateway")).findFirst().orElseThrow();

        assertThat(gateway.editableValues()).containsEntry("service.type", "LoadBalancer");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ports = (List<Map<String, Object>>) gateway.editableValues().get("service.ports");
        assertThat(ports).hasSize(3);
        assertThat(ports).extracting(p -> p.get("name"))
                .containsExactly("status-port", "http2", "https");
        assertThat(ports).extracting(p -> p.get("port")).containsExactly(15021, 80, 443);
        assertThat(ports).extracting(p -> p.get("targetPort")).containsExactly(15021, 80, 443);
        assertThat(ports).allSatisfy(p -> assertThat(p).containsEntry("protocol", "TCP"));
        // listener 由端口行派生：15021 仅 Service；80 → HTTP；443 → TLS Passthrough（免证书）
        assertThat(ports).extracting(p -> p.get("listener")).containsExactly("", "HTTP", "TLS");
        assertThat(ports.get(2)).containsEntry("tlsMode", "Passthrough");
    }
}
