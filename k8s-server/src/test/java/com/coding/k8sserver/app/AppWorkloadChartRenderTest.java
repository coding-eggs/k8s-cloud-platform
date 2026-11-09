package com.coding.k8sserver.app;

import com.coding.common.models.app.AppNodeDTO;
import org.junit.jupiter.api.Test;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真 helm 渲染应用工作负载 chart（本机没有 helm 时跳过）：配置文件挂载在 Deployment 与 StatefulSet 两种形态下
 * 都要渲染成 {@code <name>-files} ConfigMap + 只读 subPath 卷，并且 Pod 模板带配置哈希。
 */
class AppWorkloadChartRenderTest {

    private static final Path CHART = Path.of("src/main/resources/charts/app/workload/1.0.0/chart");
    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private static AppNodeDTO node(Map<String, Object> params) {
        AppNodeDTO n = new AppNodeDTO();
        n.setKey("api");
        n.setKind("SERVICE");
        n.setParams(params);
        return n;
    }

    private static String renderRaw(Map<String, Object> params, String kind) throws Exception {
        AppNodeDTO n = node(params);
        n.setKind(kind);
        Map<String, Object> values = AppWorkloadValues.build("app_1", "shop", 1, n);
        Path file = Files.createTempFile("app-values", ".yaml");
        Files.writeString(file, YAML.writeValueAsString(values));
        Process p = new ProcessBuilder("helm", "template", "t", CHART.toAbsolutePath().toString(),
                "--namespace", "demo", "-f", file.toAbsolutePath().toString()).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isZero();
        return out.replace("\r\n", "\n");
    }

    private static List<Map<String, Object>> render(Map<String, Object> params) throws Exception {
        Map<String, Object> values = AppWorkloadValues.build("app_1", "shop", 1, node(params));
        Path file = Files.createTempFile("app-values", ".yaml");
        Files.writeString(file, YAML.writeValueAsString(values));
        Process p = new ProcessBuilder("helm", "template", "t", CHART.toAbsolutePath().toString(),
                "--namespace", "demo", "-f", file.toAbsolutePath().toString()).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(err).isZero();
        List<Map<String, Object>> docs = new ArrayList<>();
        for (String doc : out.split("(?m)^---\\s*$")) {
            if (doc.isBlank()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = YAML.readValue(doc, Map.class);
            if (m != null) {
                docs.add(m);
            }
        }
        return docs;
    }

    private static Map<String, Object> find(List<Map<String, Object>> docs, String kind, String name) {
        return docs.stream()
                .filter(d -> kind.equals(d.get("kind")) && name.equals(((Map<?, ?>) d.get("metadata")).get("name")))
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> at(Object m, String... path) {
        Object cur = m;
        for (String p : path) {
            cur = ((Map<String, Object>) cur).get(p);
        }
        return (Map<String, Object>) cur;
    }

    @SuppressWarnings("unchecked")
    private static void assertMounted(Map<String, Object> workload) {
        Map<String, Object> podSpec = at(workload, "spec", "template", "spec");
        List<Map<String, Object>> volumes = (List<Map<String, Object>>) podSpec.get("volumes");
        assertThat(volumes).anySatisfy(v -> {
            assertThat(v.get("name")).isEqualTo("config-files");
            assertThat(at(v, "configMap").get("name")).isEqualTo("shop-api-files");
        });
        Map<String, Object> container = ((List<Map<String, Object>>) podSpec.get("containers")).get(0);
        List<Map<String, Object>> mounts = (List<Map<String, Object>>) container.get("volumeMounts");
        assertThat(mounts).anySatisfy(m -> {
            assertThat(m.get("name")).isEqualTo("config-files");
            assertThat(m.get("mountPath")).isEqualTo("/etc/app/app.yaml");
            assertThat(m.get("subPath")).isEqualTo("file-0");
            assertThat(m.get("readOnly")).isEqualTo(true);
        });
        assertThat(at(workload, "spec", "template", "metadata", "annotations"))
                .containsKey("platform.coding/config-hash");
    }

    private static final Map<String, Object> FILE = Map.of("path", "/etc/app/app.yaml", "content", "a: 1\nb: \"x\"\n");

    @Test
    void deploymentMountsConfigFilesFromTheFilesConfigMap() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        List<Map<String, Object>> docs = render(Map.of("image", "nginx", "port", 80,
                "configFiles", List.of(FILE)));

        Map<String, Object> cm = find(docs, "ConfigMap", "shop-api-files");
        assertThat(cm).isNotNull();
        assertThat(at(cm, "data")).containsEntry("file-0", "a: 1\nb: \"x\"\n");
        assertMounted(find(docs, "Deployment", "shop-api"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void statefulSetKeepsTheDataMountAndAddsConfigFiles() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        List<Map<String, Object>> docs = render(Map.of("image", "nginx", "port", 80,
                "persistenceEnabled", true, "configFiles", List.of(FILE)));

        Map<String, Object> sts = find(docs, "StatefulSet", "shop-api");
        assertMounted(sts);
        Map<String, Object> container = ((List<Map<String, Object>>) at(sts, "spec", "template", "spec")
                .get("containers")).get(0);
        assertThat((List<Map<String, Object>>) container.get("volumeMounts"))
                .anySatisfy(m -> assertThat(m.get("name")).isEqualTo("data"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> servicePort(List<Map<String, Object>> docs) {
        Map<String, Object> svc = find(docs, "Service", "shop-api");
        assertThat(svc).isNotNull();
        return ((List<Map<String, Object>>) at(svc, "spec").get("ports")).get(0);
    }

    @Test
    void serviceIsClusterIpUnlessExposed() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        List<Map<String, Object>> docs = render(Map.of("image", "nginx", "port", 80));

        assertThat(at(find(docs, "Service", "shop-api"), "spec").get("type")).isEqualTo("ClusterIP");
        assertThat(servicePort(docs)).doesNotContainKey("nodePort");
    }

    @Test
    void nodePortWithoutAFixedPortLetsTheClusterAllocate() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        List<Map<String, Object>> docs = render(Map.of("image", "nginx", "port", 80, "expose", "nodeport"));

        assertThat(at(find(docs, "Service", "shop-api"), "spec").get("type")).isEqualTo("NodePort");
        assertThat(servicePort(docs)).doesNotContainKey("nodePort");
    }

    @Test
    void fixedNodePortIsRendered() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        List<Map<String, Object>> docs = render(Map.of("image", "nginx", "port", 80,
                "expose", "nodeport", "nodePort", 30080));

        assertThat(at(find(docs, "Service", "shop-api"), "spec").get("type")).isEqualTo("NodePort");
        assertThat(servicePort(docs)).containsEntry("nodePort", 30080).containsEntry("port", 80);
    }

    @Test
    void declaredNamespaceReadsOnlyWhatTheDocumentSays() {
        assertThat(AppRenderService.declaredNamespace("kind: ConfigMap\nmetadata:\n  name: a\n  namespace: demo\n"))
                .isEqualTo("demo");
        assertThat(AppRenderService.declaredNamespace("kind: ConfigMap\nmetadata:\n  name: a\n")).isNull();
    }

    /** apply 走 kubectl 且不带 -n：清单里没写 namespace 的对象会落进 kubeconfig 的默认命名空间（真集群踩过） */
    @Test
    void everyRenderedObjectDeclaresTheReleaseNamespace() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        for (Map<String, Object> params : List.of(
                Map.<String, Object>of("image", "nginx", "port", 80, "configFiles", List.of(FILE),
                        "env", List.of(Map.of("name", "A", "value", "1"))),
                Map.<String, Object>of("image", "nginx", "port", 80, "persistenceEnabled", true,
                        "configFiles", List.of(FILE)))) {
            List<Map<String, Object>> docs = render(params);
            assertThat(docs).isNotEmpty().allSatisfy(d ->
                    assertThat(at(d, "metadata").get("namespace")).as(d.get("kind") + "").isEqualTo("demo"));
        }
    }

    @Test
    void noConfigFilesMeansNoFilesConfigMapAndNoVolumes() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        List<Map<String, Object>> docs = render(Map.of("image", "nginx", "port", 80));

        assertThat(find(docs, "ConfigMap", "shop-api-files")).isNull();
        assertThat(at(find(docs, "Deployment", "shop-api"), "spec", "template", "spec")).doesNotContainKey("volumes");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> hpaMetrics(List<Map<String, Object>> docs) {
        Map<String, Object> hpa = find(docs, "HorizontalPodAutoscaler", "shop-api");
        assertThat(hpa).isNotNull();
        return (List<Map<String, Object>>) at(hpa, "spec").get("metrics");
    }

    /** HPA 开启时清单不写 replicas：SSA forceConflicts 会把 HPA 扩出的副本每次发布都打回去 */
    @Test
    void hpaEnabledOmitsReplicasOnDeployment() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        List<Map<String, Object>> docs = render(Map.of("image", "nginx", "port", 80,
                "replicas", 3, "autoscalingEnabled", true));

        assertThat(at(find(docs, "Deployment", "shop-api"), "spec")).doesNotContainKey("replicas");
        assertThat(hpaMetrics(docs)).hasSize(1);
        assertThat(at(hpaMetrics(docs).get(0), "resource").get("name")).isEqualTo("cpu");
        assertThat(at(hpaMetrics(docs).get(0), "resource", "target").get("averageUtilization")).isEqualTo(60);
    }

    @Test
    void hpaEnabledOmitsReplicasOnStatefulSet() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        List<Map<String, Object>> docs = render(Map.of("image", "nginx", "port", 80,
                "replicas", 3, "persistenceEnabled", true, "autoscalingEnabled", true));

        assertThat(at(find(docs, "StatefulSet", "shop-api"), "spec")).doesNotContainKey("replicas");
    }

    @Test
    void hpaWithMemoryAddsSecondMetric() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        List<Map<String, Object>> docs = render(Map.of("image", "nginx", "port", 80,
                "autoscalingEnabled", true, "targetMemory", 70));

        List<Map<String, Object>> metrics = hpaMetrics(docs);
        assertThat(metrics).hasSize(2);
        assertThat(at(metrics.get(1), "resource").get("name")).isEqualTo("memory");
        assertThat(at(metrics.get(1), "resource", "target").get("averageUtilization")).isEqualTo(70);
    }

    @Test
    void hpaDisabledKeepsReplicas() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        List<Map<String, Object>> docs = render(Map.of("image", "nginx", "port", 80, "replicas", 3));

        assertThat(at(find(docs, "Deployment", "shop-api"), "spec")).containsEntry("replicas", 3);
        assertThat(find(docs, "HorizontalPodAutoscaler", "shop-api")).isNull();
    }

    // ---------- 通用注入参数（OTel / 网格，2026-11-07） ----------

    private static final Path BASELINE = Path.of("src/test/resources/app-workload-baseline");

    private static Map<String, Map<String, Object>> baselineShapes() {
        Map<String, Map<String, Object>> m = new java.util.LinkedHashMap<>();
        m.put("service", Map.of("image", "nginx", "port", 80, "env", List.of(Map.of("name", "A", "value", "1")),
                "configFiles", List.of(FILE), "autoscalingEnabled", true, "metricsEnabled", true));
        m.put("worker", Map.of("image", "busybox", "command", List.of("sleep", "1d")));
        m.put("stateful", Map.of("image", "nginx", "port", 80, "persistenceEnabled", true,
                "expose", "nodeport", "nodePort", 30080, "replicas", 2));
        return m;
    }

    /** 没有注入参数的服务渲染结果必须与引入注入前逐字相同（否则存量服务升级后会滚动）。基线由改模板前的 chart 生成。 */
    @Test
    void noInjectionRendersUnchanged() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        for (Map.Entry<String, Map<String, Object>> e : baselineShapes().entrySet()) {
            String kind = "worker".equals(e.getKey()) ? "WORKER" : "SERVICE";
            String out = renderRaw(e.getValue(), kind);
            Path file = BASELINE.resolve(e.getKey() + ".yaml");
            if (Boolean.getBoolean("app.chart.writeBaseline")) {
                Files.createDirectories(BASELINE);
                Files.writeString(file, out);
            }
            assertThat(out).as(e.getKey()).isEqualTo(Files.readString(file).replace("\r\n", "\n"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectionRendered() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("image", "nginx");
        params.put("port", 80);
        params.put("env", List.of(Map.of("name", "A", "value", "1")));
        params.put("podAnnotations", Map.of("instrumentation.opentelemetry.io/inject-java", "monitor/platform-instrumentation"));
        params.put("podLabels", Map.of("istio.io/dataplane-mode", "ambient"));
        params.put("serviceLabels", Map.of("istio.io/use-waypoint", "waypoint"));
        params.put("extraEnv", List.of(Map.of("name", "OTEL_SERVICE_NAME", "value", "shop-api")));
        params.put("logMountDir", "/var/log/nginx");
        List<Map<String, Object>> docs = render(params);

        Map<String, Object> dep = find(docs, "Deployment", "shop-api");
        assertThat(at(dep, "spec", "template", "metadata", "annotations"))
                .containsKey("platform.coding/config-hash")
                .containsEntry("instrumentation.opentelemetry.io/inject-java", "monitor/platform-instrumentation");
        assertThat(at(dep, "spec", "template", "metadata", "labels")).containsEntry("istio.io/dataplane-mode", "ambient");
        assertThat(at(dep, "spec", "selector", "matchLabels")).doesNotContainKey("istio.io/dataplane-mode");
        assertThat(at(find(docs, "Service", "shop-api"), "metadata", "labels"))
                .containsEntry("istio.io/use-waypoint", "waypoint");
        Map<String, Object> podSpec = at(dep, "spec", "template", "spec");
        Map<String, Object> container = ((List<Map<String, Object>>) podSpec.get("containers")).get(0);
        List<Map<String, Object>> env = (List<Map<String, Object>>) container.get("env");
        assertThat(env.get(env.size() - 1)).isEqualTo(Map.of("name", "OTEL_SERVICE_NAME", "value", "shop-api"));
        assertThat((List<Map<String, Object>>) podSpec.get("volumes")).anySatisfy(v -> {
            assertThat(v.get("name")).isEqualTo("platform-otel-logs");
            assertThat(v).containsKey("emptyDir");
        });
        assertThat((List<Map<String, Object>>) container.get("volumeMounts")).anySatisfy(m -> {
            assertThat(m.get("name")).isEqualTo("platform-otel-logs");
            assertThat(m.get("mountPath")).isEqualTo("/var/log/nginx");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void statefulSetGetsInjectionToo() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        List<Map<String, Object>> docs = render(Map.of("image", "nginx", "port", 80, "persistenceEnabled", true,
                "podLabels", Map.of("istio.io/dataplane-mode", "none"), "logMountDir", "/var/log/app"));
        Map<String, Object> sts = find(docs, "StatefulSet", "shop-api");
        assertThat(at(sts, "spec", "template", "metadata", "labels")).containsEntry("istio.io/dataplane-mode", "none");
        Map<String, Object> container = ((List<Map<String, Object>>) at(sts, "spec", "template", "spec")
                .get("containers")).get(0);
        assertThat((List<Map<String, Object>>) container.get("volumeMounts"))
                .anySatisfy(m -> assertThat(m.get("name")).isEqualTo("data"))
                .anySatisfy(m -> assertThat(m.get("name")).isEqualTo("platform-otel-logs"));
    }

    /** helm 是否在 PATH 上。 */
    static final class HelmProbe {
        static boolean available() {
            try {
                Process p = new ProcessBuilder("helm", "version", "--short").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
            } catch (Exception e) {
                return false;
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void routeTemplateRendersTcpRouteV1() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        List<Map<String, Object>> docs = render(Map.of("image", "nginx", "port", 8080, "route",
                Map.of("kind", "tcp", "parentName", "platform-ingress", "sectionName", "p20002")));

        Map<String, Object> route = find(docs, "TCPRoute", "shop-api");
        assertThat(route).isNotNull();
        assertThat(route.get("apiVersion")).isEqualTo("gateway.networking.k8s.io/v1");
        Map<String, Object> parent = ((List<Map<String, Object>>) at(route, "spec").get("parentRefs")).get(0);
        assertThat(parent).containsEntry("group", "gateway.networking.k8s.io").containsEntry("kind", "ListenerSet")
                .containsEntry("name", "platform-ingress").containsEntry("sectionName", "p20002");
        Map<String, Object> rule = ((List<Map<String, Object>>) at(route, "spec").get("rules")).get(0);
        assertThat((List<Map<String, Object>>) rule.get("backendRefs"))
                .containsExactly(Map.of("name", "shop-api", "port", 8080));
        assertThat(find(docs, "HTTPRoute", "shop-api")).isNull();
        assertThat(at(find(docs, "Service", "shop-api"), "spec").get("type")).isEqualTo("ClusterIP");
    }

    @Test
    void routeTemplateRendersHttpRouteV1() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        List<Map<String, Object>> docs = render(Map.of("image", "nginx", "port", 80, "route",
                Map.of("kind", "http", "parentName", "platform-ingress", "sectionName", "p20000")));

        Map<String, Object> route = find(docs, "HTTPRoute", "shop-api");
        assertThat(route).isNotNull();
        assertThat(route.get("apiVersion")).isEqualTo("gateway.networking.k8s.io/v1");
        assertThat(find(docs, "TCPRoute", "shop-api")).isNull();
    }

    @Test
    void noRouteRenderedByDefault() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        List<Map<String, Object>> docs = render(Map.of("image", "nginx", "port", 80));
        assertThat(docs).noneMatch(d -> "HTTPRoute".equals(d.get("kind")) || "TCPRoute".equals(d.get("kind")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tcpProbeRendersTcpSocket() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        Map<String, Object> tcpC = ((List<Map<String, Object>>) at(find(
                render(Map.of("image", "nginx", "port", 80, "probeType", "tcp")), "Deployment", "shop-api"),
                "spec", "template", "spec").get("containers")).get(0);
        assertThat(at(tcpC, "readinessProbe", "tcpSocket")).containsEntry("port", "http");
        assertThat(at(tcpC, "livenessProbe", "tcpSocket")).containsEntry("port", "http");
        assertThat((Map<String, Object>) tcpC.get("readinessProbe")).doesNotContainKey("httpGet");

        Map<String, Object> httpC = ((List<Map<String, Object>>) at(find(
                render(Map.of("image", "nginx", "port", 80)), "Deployment", "shop-api"),
                "spec", "template", "spec").get("containers")).get(0);
        assertThat(at(httpC, "readinessProbe", "httpGet")).containsEntry("path", "/");
    }

    // ---------- 入口灰度（2026-11-09） ----------

    private static Map<String, Object> canaryRoute(String kind) {
        Map<String, Object> canary = new java.util.LinkedHashMap<>();
        canary.put("service", "shop-api-canary");
        canary.put("weight", 20);
        canary.put("header", Map.of("name", "x-canary", "value", "true"));
        return Map.of("kind", kind, "parentName", "platform-ingress", "sectionName", "p20000", "canary", canary);
    }

    @Test
    @SuppressWarnings("unchecked")
    void httpRouteWithCanaryHasHeaderRuleFirstThenWeights() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        Map<String, Object> route = find(render(Map.of("image", "nginx", "port", 80, "route", canaryRoute("http"))),
                "HTTPRoute", "shop-api");
        List<Map<String, Object>> rules = (List<Map<String, Object>>) at(route, "spec").get("rules");
        assertThat(rules).hasSize(2);
        Map<String, Object> match = ((List<Map<String, Object>>) rules.get(0).get("matches")).get(0);
        assertThat(((List<Map<String, Object>>) match.get("headers")).get(0))
                .containsEntry("name", "x-canary").containsEntry("value", "true");
        assertThat((List<Map<String, Object>>) rules.get(0).get("backendRefs"))
                .containsExactly(Map.of("name", "shop-api-canary", "port", 80));
        assertThat((List<Map<String, Object>>) rules.get(1).get("backendRefs")).containsExactly(
                Map.of("name", "shop-api", "port", 80, "weight", 80),
                Map.of("name", "shop-api-canary", "port", 80, "weight", 20));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tcpRouteWithCanaryHasOnlyWeights() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        Map<String, Object> route = find(render(Map.of("image", "nginx", "port", 80, "route", canaryRoute("tcp"))),
                "TCPRoute", "shop-api");
        List<Map<String, Object>> rules = (List<Map<String, Object>>) at(route, "spec").get("rules");
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0)).doesNotContainKey("matches");
        assertThat((List<Map<String, Object>>) rules.get(0).get("backendRefs")).containsExactly(
                Map.of("name", "shop-api", "port", 80, "weight", 80),
                Map.of("name", "shop-api-canary", "port", 80, "weight", 20));
    }

    @Test
    @SuppressWarnings("unchecked")
    void routeWithoutCanaryIsUnchanged() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        Map<String, Object> route = find(render(Map.of("image", "nginx", "port", 80, "route",
                Map.of("kind", "http", "parentName", "platform-ingress", "sectionName", "p20000"))),
                "HTTPRoute", "shop-api");
        List<Map<String, Object>> rules = (List<Map<String, Object>>) at(route, "spec").get("rules");
        assertThat(rules).containsExactly(Map.of("backendRefs", List.of(Map.of("name", "shop-api", "port", 80))));
    }

    // ---------- 内部灰度（2026-11-09）：网格路由、TCP 端口名 ----------

    private static Map<String, Object> meshRouteParam(String kind) {
        Map<String, Object> canary = new java.util.LinkedHashMap<>();
        canary.put("service", "shop-api-canary");
        canary.put("weight", 20);
        canary.put("header", Map.of("name", "x-canary", "value", "true"));
        return Map.of("kind", kind, "canary", canary);
    }

    @Test
    @SuppressWarnings("unchecked")
    void meshRouteRendersServiceParentWithHeaderThenWeights() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        Map<String, Object> route = find(render(Map.of("image", "nginx", "port", 8080,
                "meshRoute", meshRouteParam("http"))), "HTTPRoute", "shop-api-mesh");
        assertThat(route).isNotNull();
        assertThat(((List<Map<String, Object>>) at(route, "spec").get("parentRefs")).get(0))
                .isEqualTo(Map.of("group", "", "kind", "Service", "name", "shop-api", "port", 8080));
        List<Map<String, Object>> rules = (List<Map<String, Object>>) at(route, "spec").get("rules");
        assertThat(rules).hasSize(2);
        Map<String, Object> match = ((List<Map<String, Object>>) rules.get(0).get("matches")).get(0);
        assertThat(((List<Map<String, Object>>) match.get("headers")).get(0))
                .containsEntry("name", "x-canary").containsEntry("value", "true");
        assertThat((List<Map<String, Object>>) rules.get(0).get("backendRefs"))
                .containsExactly(Map.of("name", "shop-api-canary", "port", 8080));
        assertThat((List<Map<String, Object>>) rules.get(1).get("backendRefs")).containsExactly(
                Map.of("name", "shop-api", "port", 8080, "weight", 80),
                Map.of("name", "shop-api-canary", "port", 8080, "weight", 20));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tcpMeshRouteHasNoHeaderRule() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        Map<String, Object> route = find(render(Map.of("image", "nginx", "port", 8080,
                "meshRoute", meshRouteParam("tcp"))), "TCPRoute", "shop-api-mesh");
        assertThat(route).isNotNull();
        List<Map<String, Object>> rules = (List<Map<String, Object>>) at(route, "spec").get("rules");
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0)).doesNotContainKey("matches");
    }

    @Test
    void noMeshRouteByDefault() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        List<Map<String, Object>> docs = render(Map.of("image", "nginx", "port", 8080));
        assertThat(find(docs, "HTTPRoute", "shop-api-mesh")).isNull();
        assertThat(find(docs, "TCPRoute", "shop-api-mesh")).isNull();
    }

    @Test
    void tcpProtocolNamesServicePortTcp() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        assertThat(servicePort(render(Map.of("image", "nginx", "port", 8080, "protocol", "tcp"))))
                .containsEntry("name", "tcp").containsEntry("targetPort", "http");
        assertThat(servicePort(render(Map.of("image", "nginx", "port", 8080)))).containsEntry("name", "http");
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceMonitorFollowsServicePortName() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        Map<String, Object> sm = find(render(Map.of("image", "nginx", "port", 8080, "protocol", "tcp",
                "metricsEnabled", true)), "ServiceMonitor", "shop-api");
        assertThat(sm).isNotNull();
        assertThat(((List<Map<String, Object>>) at(sm, "spec").get("endpoints")).get(0)).containsEntry("port", "tcp");
    }

    // ---------- 调度与挂载（2026-11-09） ----------

    private static Map<String, Object> schedulingParams() {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("image", "nginx");
        p.put("port", 80);
        p.put("nodeSelector", Map.of("disktype", "ssd"));
        p.put("tolerations", List.of(Map.of("key", "dedicated", "operator", "Equal", "value", "app",
                "effect", "NoSchedule")));
        p.put("podAffinity", Map.of("podAntiAffinity",
                Map.of("requiredDuringSchedulingIgnoredDuringExecution", List.of())));
        p.put("podVolumes", List.of(
                Map.of("name", "cache", "emptyDir", Map.of()),
                Map.of("name", "cfg", "configMap", Map.of("name", "my-cm"))));
        p.put("podVolumeMounts", List.of(
                Map.of("name", "cache", "mountPath", "/cache"),
                Map.of("name", "cfg", "mountPath", "/etc/extra", "readOnly", true)));
        return p;
    }

    @Test
    @SuppressWarnings("unchecked")
    void schedulingAndExtraVolumesAreRendered() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        Map<String, Object> podSpec = at(find(render(schedulingParams()), "Deployment", "shop-api"),
                "spec", "template", "spec");

        assertThat(at(podSpec, "nodeSelector")).containsEntry("disktype", "ssd");
        assertThat(((List<Map<String, Object>>) podSpec.get("tolerations")).get(0))
                .containsEntry("key", "dedicated").containsEntry("operator", "Equal")
                .containsEntry("effect", "NoSchedule");
        assertThat(podSpec.get("affinity")).isInstanceOf(Map.class);
        assertThat((List<Map<String, Object>>) podSpec.get("volumes")).anySatisfy(v -> {
            assertThat(v.get("name")).isEqualTo("cache");
            assertThat(v).containsKey("emptyDir");
        }).anySatisfy(v -> {
            assertThat(v.get("name")).isEqualTo("cfg");
            assertThat(at(v, "configMap").get("name")).isEqualTo("my-cm");
        });
        Map<String, Object> container = ((List<Map<String, Object>>) podSpec.get("containers")).get(0);
        assertThat((List<Map<String, Object>>) container.get("volumeMounts")).anySatisfy(m -> {
            assertThat(m.get("name")).isEqualTo("cache");
            assertThat(m).containsEntry("mountPath", "/cache");
        }).anySatisfy(m -> {
            assertThat(m.get("name")).isEqualTo("cfg");
            assertThat(m).containsEntry("mountPath", "/etc/extra").containsEntry("readOnly", true);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void statefulSetKeepsDataVolumeAndAddsExtraOnes() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        Map<String, Object> params = new java.util.LinkedHashMap<>(schedulingParams());
        params.put("persistenceEnabled", true);
        Map<String, Object> podSpec = at(find(render(params), "StatefulSet", "shop-api"),
                "spec", "template", "spec");

        assertThat(at(podSpec, "nodeSelector")).containsEntry("disktype", "ssd");
        assertThat((List<Map<String, Object>>) podSpec.get("volumes")).anySatisfy(v -> {
            assertThat(v.get("name")).isEqualTo("cache");
            assertThat(v).containsKey("emptyDir");
        });
        Map<String, Object> container = ((List<Map<String, Object>>) podSpec.get("containers")).get(0);
        assertThat((List<Map<String, Object>>) container.get("volumeMounts"))
                .anySatisfy(m -> assertThat(m.get("name")).isEqualTo("data"))
                .anySatisfy(m -> assertThat(m.get("name")).isEqualTo("cache"));
    }
}
