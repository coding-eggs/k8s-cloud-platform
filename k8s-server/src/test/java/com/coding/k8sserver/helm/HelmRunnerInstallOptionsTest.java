package com.coding.k8sserver.helm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HelmRunnerInstallOptionsTest {

    @TempDir
    Path tmp;

    private HelmRunner runner(Path log) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new HelmRunner("helm", List.of(java, "-cp", cp, "com.coding.k8sserver.helm.FakeHelm"),
                Map.of("FAKE_HELM_LOG", log.toString(), "FAKE_HELM_EXIT", "0"), tmp, Duration.ofMinutes(1));
    }

    private Path chart() throws Exception {
        Path chart = tmp.resolve("x-1.0.0.tgz");
        Files.writeString(chart, "fake");
        return chart;
    }

    @Test
    void tenantOptionsAddSkipCrdsAndNoHooks() throws Exception {
        Path log = tmp.resolve("argv.log");
        Path values = tmp.resolve("values.yaml");
        Files.writeString(values, "a: 1\n");

        runner(log).apply("kubeconfig", chart(), values, "app-1", "demo",
                new HelmRunner.InstallOptions(true, true, Map.of("platform.coding/app", "app-1")));

        String argv = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(argv).contains("--skip-crds").contains("--no-hooks").contains("--labels");
    }

    @Test
    void platformOptionsDoNotAddTenantFlags() throws Exception {
        Path log = tmp.resolve("argv.log");
        Path values = tmp.resolve("values.yaml");
        Files.writeString(values, "a: 1\n");

        runner(log).apply("kubeconfig", chart(), values, "app-1", "demo",
                new HelmRunner.InstallOptions(false, false, Map.of()));

        String argv = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(argv).doesNotContain("--skip-crds").doesNotContain("--no-hooks");
    }

    @Test
    void manifestRunsHelmGetManifestWithNamespace() throws Exception {
        Path log = tmp.resolve("argv.log");

        runner(log).manifest("kubeconfig", "app-1", "demo");

        String argv = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(argv).contains("get").contains("manifest").contains("app-1").contains("--namespace");
    }

    @Test
    void uninstallRunsHelmUninstallWithNamespace() throws Exception {
        Path log = tmp.resolve("argv.log");

        runner(log).uninstall("kubeconfig", "app-1", "demo");

        String argv = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(argv).contains("uninstall").contains("app-1").contains("--namespace").contains("demo");
    }

    @Test
    void renderForUpgradePassesIsUpgradeFlag() throws Exception {
        // 复审 I4：升级时必须按 upgrade 模式渲染，否则 {{ if .Release.IsUpgrade }} 的模板预检看不到
        Path log = tmp.resolve("argv.log");
        Path values = tmp.resolve("values.yaml");
        Files.writeString(values, "a: 1\n");

        runner(log).render("kubeconfig", chart(), values, "demo", true);

        String argv = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(argv).contains("template").contains("--is-upgrade");
    }
}
