package com.coding.platformapi.images;

import com.coding.common.models.image.ImageReference;
import com.coding.common.models.image.ImageRewriter;
import org.junit.jupiter.api.Test;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 护栏：内置 chart 的默认 values 全过一遍改写，**每一个镜像都必须落在平台 Harbor 上**。
 *
 * <p>为什么要有这条：chart 里 image 的位置并不统一（{@code image} 整串 / {@code repository+tag} /
 * {@code registry+repository+tag} / Istio 的 {@code hub}，还散落在 sidecar、subchart、Job 里）。
 * 漏一处就是「一半镜像走 Harbor、一半走公网」，在真集群上只表现为某个 Pod {@code ImagePullBackOff}，
 * 很难从结果反推是哪一层漏了。
 *
 * <p>harbor 组件本身排除：它永远走官方（先有鸡还是先有蛋，见 spec §10）。
 */
class ImageCatalogGuardTest {

    private static final Path CHARTS = Path.of("../k8s-server/src/main/resources/charts");
    private static final String HOST = "harbor.ackcp.cloud";
    private static final String PROJECT = "platform";
    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    /** 直接承载完整镜像引用的键（与 ImageRewriter 保持一致） */
    private static final List<String> IMAGE_KEYS =
            List.of("image", "imageName", "imageRepository", "hub");

    @Test
    void everyBuiltInChartImagePointsAtThePlatformHarbor() throws Exception {
        List<String> problems = new ArrayList<>();
        int[] images = {0};

        try (Stream<Path> files = Files.walk(CHARTS)) {
            List<Path> valuesFiles = files
                    .filter(p -> "values.yaml".equals(p.getFileName().toString()))
                    .filter(p -> p.toString().replace('\\', '/').contains("/chart/"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/harbor/"))
                    .sorted()
                    .toList();

            assertThat(valuesFiles).as("内置 chart 的 values.yaml 至少要有十几份").hasSizeGreaterThan(10);

            for (Path file : valuesFiles) {
                String content = Files.readString(file);
                if (content.isBlank()) {
                    continue;   // subchart 的空 values.yaml（如 crds）
                }
                String name = CHARTS.relativize(file).toString().replace('\\', '/');
                @SuppressWarnings("unchecked")
                Map<String, Object> values = YAML.readValue(content, Map.class);
                Object rewritten = ImageRewriter.rewrite(values, HOST, PROJECT);
                collectAndCheck(rewritten, name, problems, images);
            }
        }

        assertThat(problems).as("以下镜像没有指向平台 Harbor").isEmpty();
        assertThat(images[0]).as("至少识别出几十个镜像（防「一个都没匹配到」的假通过）")
                .isGreaterThan(30);
    }

    private static void collectAndCheck(Object node, String path, List<String> problems, int[] count) {
        if (node instanceof Map<?, ?> map) {
            // 1) registry + repository (+tag) 这种拆分写法：拼起来检查
            Object repo = map.get("repository");
            if (repo instanceof String r && !r.isBlank()) {
                String registry = map.get("registry") instanceof String reg ? reg.trim() : "";
                String tag = map.get("tag") instanceof String t ? t.trim() : "";
                String full = (registry.isEmpty() ? "" : registry + "/") + r
                        + (tag.isEmpty() ? "" : ":" + tag);
                verify(full, path + "{registry+repository}", problems, count);
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                Object value = e.getValue();
                if (IMAGE_KEYS.contains(key) && value instanceof String s && !s.isBlank()) {
                    verify(s, path + "." + key, problems, count);
                } else {
                    collectAndCheck(value, path + "." + key, problems, count);
                }
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                collectAndCheck(list.get(i), path + "[" + i + "]", problems, count);
            }
        }
    }

    private static void verify(String image, String where, List<String> problems, int[] count) {
        if (!ImageReference.looksLikeImage(image)) {
            return;
        }
        count[0]++;
        String registry = ImageReference.parse(image).registry();
        if (!HOST.equalsIgnoreCase(registry)) {
            problems.add(where + " -> " + image);
        }
    }
}
