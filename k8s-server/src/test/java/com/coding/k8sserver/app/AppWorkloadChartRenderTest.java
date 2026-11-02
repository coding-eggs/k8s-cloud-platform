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

    @Test
    void noConfigFilesMeansNoFilesConfigMapAndNoVolumes() throws Exception {
        assumeTrue(HelmProbe.available(), "本机没有 helm，跳过");
        List<Map<String, Object>> docs = render(Map.of("image", "nginx", "port", 80));

        assertThat(find(docs, "ConfigMap", "shop-api-files")).isNull();
        assertThat(at(find(docs, "Deployment", "shop-api"), "spec", "template", "spec")).doesNotContainKey("volumes");
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
}
