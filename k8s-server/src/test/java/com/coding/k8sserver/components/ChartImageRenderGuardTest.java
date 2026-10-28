package com.coding.k8sserver.components;

import org.junit.jupiter.api.Test;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 护栏（真渲染）：把每份内置 chart 用 {@link ChartImageOverlay} 生成的 overlay 交给 helm，
 * 检查渲染出来的每一个 {@code image:} 都落在平台 Harbor 上。
 *
 * <p>为什么不能只做「values 里扫一遍」：镜像并不总是 values 里的一个字符串 ——
 * 有的在子 chart 的 values 里（overlay 不覆盖就是漏），有的在模板里用 registry/repository/name
 * 拼出来（扫 values 根本看不见），有的把 registry 与 image 写成兄弟键（只改一个就拼出两层前缀）。
 * 只有真渲染一次，才知道 Pod 最后会去哪个仓库拉。
 *
 * <p>harbor 组件本身排除（永远官方）。{@code auto} 是 Istio 的哨兵值、
 * {@code busybox:1.28} 是注入模板里的样例内容，都不会真被拉取。
 */
class ChartImageRenderGuardTest {

    private static final Path CHARTS = Path.of("src/main/resources/charts");
    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final String HOST = "harbor.ackcp.cloud";
    private static final Pattern IMAGE =
            Pattern.compile("^\\s*-?\\s*image:\\s*[\"']?([^\\s\"']+)[\"']?\\s*$");
    private static final List<String> NOT_PULLED = List.of("auto", "busybox:1.28");

    @Test
    void renderedManifestsPullOnlyFromThePlatformHarbor() throws Exception {
        assumeTrue(helmAvailable(), "本机没有 helm，跳过渲染护栏");

        List<String> problems = new ArrayList<>();
        int[] seen = {0};
        List<Path> charts = chartDirs();

        assertThat(charts).as("内置 chart 数量").hasSizeGreaterThan(20);

        for (Path chartDir : charts) {
            String name = CHARTS.relativize(chartDir).toString().replace('\\', '/');
            Map<String, Object> overlay = ChartImageOverlay.of(chartDir, HOST, "");
            List<String> command = new ArrayList<>(List.of("helm", "template", "t",
                    chartDir.toAbsolutePath().toString(),
                    "--namespace", "t-check"));
            if (!overlay.isEmpty()) {
                Path file = Files.createTempFile("image-overlay", ".yaml");
                Files.writeString(file, YAML.writeValueAsString(overlay));
                command.add("-f");
                command.add(file.toAbsolutePath().toString());
            }

            Process process = new ProcessBuilder(command).start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor(180, TimeUnit.SECONDS))
                    .as("helm 渲染超时：" + name).isTrue();
            assertThat(process.exitValue()).as("helm 渲染失败 %s：%s", name, stderr).isZero();

            for (String line : stdout.split("\n")) {
                Matcher m = IMAGE.matcher(line);
                if (!m.matches()) {
                    continue;
                }
                String image = m.group(1);
                if (image.startsWith("{") || image.contains("{{") || NOT_PULLED.contains(image)) {
                    continue;
                }
                seen[0]++;
                if (!image.startsWith(HOST + "/")) {
                    problems.add(name + " -> " + image);
                }
            }
        }

        assertThat(problems).as("PLATFORM 模式下仍会从上游拉的镜像").isEmpty();
        assertThat(seen[0]).as("至少渲染出几十个镜像（防「一个都没匹配到」的假通过）")
                .isGreaterThan(30);
    }

    /** 顶层 chart：{@code charts/<kind>/<name>/<version>/chart}，harbor 自己排除 */
    private static List<Path> chartDirs() throws Exception {
        try (Stream<Path> files = Files.walk(CHARTS, 5)) {
            return files
                    .filter(p -> "Chart.yaml".equals(p.getFileName().toString()))
                    .map(Path::getParent)
                    .filter(p -> {
                        String path = CHARTS.relativize(p).toString().replace('\\', '/');
                        return path.split("/").length == 4 && !path.contains("/harbor/");
                    })
                    .sorted()
                    .toList();
        }
    }

    private static boolean helmAvailable() {
        try {
            Process p = new ProcessBuilder("helm", "version", "--short").start();
            p.getInputStream().readAllBytes();
            p.getErrorStream().readAllBytes();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
