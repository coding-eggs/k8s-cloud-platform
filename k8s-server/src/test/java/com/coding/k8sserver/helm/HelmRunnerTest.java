package com.coding.k8sserver.helm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HelmRunnerTest {

    @TempDir
    Path tmp;

    private HelmRunner runner(Path log, int exit) {
        return runner(log, exit, Map.of(), Duration.ofMinutes(15));
    }

    private HelmRunner runner(Path log, int exit, Map<String, String> extraEnv, Duration timeout) {
        // 假 helm 用同 JVM 启动的小程序，不依赖 shell：
        // Windows 上 PATH 里的 bash 可能是 WSL，不认 D:/ 路径（实测退出码 127）
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Map<String, String> env = new HashMap<>(extraEnv);
        env.put("FAKE_HELM_LOG", log.toString());
        env.put("FAKE_HELM_EXIT", String.valueOf(exit));
        return new HelmRunner("helm",
                List.of(java, "-cp", cp, "com.coding.k8sserver.helm.FakeHelm"),
                env, tmp, timeout);
    }

    private Path chart() throws Exception {
        Path chart = tmp.resolve("chart");
        Files.createDirectories(chart);
        Files.writeString(chart.resolve("Chart.yaml"), "apiVersion: v2\nname: x\nversion: 1.0.0\n");
        return chart;
    }

    @Test
    void applyUsesInstallUpgradeRollbackOnFailureAndLabels() throws Exception {
        Path log = tmp.resolve("argv.log");
        Path values = tmp.resolve("values.yaml");
        Files.writeString(values, "replicas: 1\n");

        HelmResult r = runner(log, 0).apply("apiVersion: v1\nkind: Config\n",
                chart(), values, "platform-metrics-server", "kube-system",
                Map.of("platform.coding/managed", "true",
                        "platform.coding/component", "metrics-server",
                        "platform.coding/cluster", "c1"));

        assertThat(r.ok()).isTrue();
        String argv = Files.readString(log);
        // ⭐ 少一个都会导致"看起来成功、实际没装"
        assertThat(argv).contains("upgrade").contains("--install");
        assertThat(argv).contains("--create-namespace");
        assertThat(argv).contains("kube-system");
        assertThat(argv).contains("platform-metrics-server");
        // ⭐ Helm 4：不是 --atomic
        assertThat(argv).contains("--rollback-on-failure");
        assertThat(argv).doesNotContain("--atomic");
        // ⭐ 接管手工 release 残留的 CRD：旧 manager 是 helm/Update，新装用 SSA 会报
        //   "Apply failed with 3 conflicts: conflicts with \"helm\""，必须显式 force
        assertThat(argv).contains("--force-conflicts");
        assertThat(argv).contains("platform.coding/managed=true");
        assertThat(argv).contains("--timeout");
    }

    @Test
    void serverDryRunIsAvailableButNotAFailureGate() throws Exception {
        Path values = tmp.resolve("values.yaml");
        Files.writeString(values, "replicas: 1\n");

        HelmResult r = runner(tmp.resolve("argv.log"), 0)
                .dryRunServer("k", chart(), values, "platform-metrics-server", "kube-system");

        assertThat(r.ok()).isTrue();
        assertThat(Files.readString(tmp.resolve("argv.log"))).contains("--dry-run=server");
    }

    @Test
    void templateIsTheHardPrecheck() throws Exception {
        Path log = tmp.resolve("argv.log");
        Path values = tmp.resolve("values.yaml");
        Files.writeString(values, "replicas: 1\n");

        runner(log, 0).render("k", chart(), values, "kube-system");

        assertThat(Files.readString(log)).contains("template");
    }

    @Test
    void aNonZeroExitIsReportedNotThrown() throws Exception {
        Path values = tmp.resolve("values.yaml");
        Files.writeString(values, "replicas: 1\n");

        HelmResult r = runner(tmp.resolve("argv.log"), 3).render("k", chart(), values, "kube-system");

        assertThat(r.ok()).isFalse();
        assertThat(r.exitCode()).isEqualTo(3);
    }

    @Test
    void aHungHelmIsKilledByTheTimeout() throws Exception {
        // ⭐ 评审发现：读输出若在 waitFor 之前，超时形同虚设。假 helm 睡 5s，超时 300ms。
        Path values = tmp.resolve("values.yaml");
        Files.writeString(values, "replicas: 1\n");
        long start = System.currentTimeMillis();

        HelmResult r = runner(tmp.resolve("argv.log"), 0,
                Map.of("FAKE_HELM_SLEEP_MS", "5000"), Duration.ofMillis(300))
                .render("k", chart(), values, "kube-system");

        long elapsed = System.currentTimeMillis() - start;
        assertThat(r.ok()).isFalse();
        assertThat(r.output()).contains("超时");
        assertThat(elapsed).as("必须在超时附近返回，而不是等 helm 自己结束").isLessThan(4000);
    }

    @Test
    void theTemporaryKubeconfigIsOwnerOnly() throws Exception {
        Path permLog = tmp.resolve("perm.log");
        Path values = tmp.resolve("values.yaml");
        Files.writeString(values, "replicas: 1\n");

        runner(tmp.resolve("argv.log"), 0, Map.of("FAKE_HELM_PERM_LOG", permLog.toString()), Duration.ofMinutes(15))
                .render("apiVersion: v1\nkind: Config\n", chart(), values, "kube-system");

        // POSIX 上必须是 rw-------；Windows 测试机上是 n/a（无法设 POSIX 权限，生产镜像是 Linux）
        assertThat(Files.readString(permLog)).isIn("rw-------", "n/a");
    }

    @Test
    void renderFullKeepsManifestsLargerThanTheLogTruncationLimit() throws Exception {
        // server-side apply 的清单必须完整：render() 为日志截到 64KB，
        // 用它去 apply 会静默少装对象（Calico CRD chart 渲染出来 3.5MB）
        Path log = tmp.resolve("argv.log");
        Path big = tmp.resolve("big.yaml");
        Files.writeString(big, "x".repeat(200_000));
        Path values = tmp.resolve("values.yaml");
        Files.writeString(values, "{}\n");
        HelmRunner runner = runner(log, 0, Map.of("FAKE_HELM_STDOUT_FILE", big.toString()),
                Duration.ofMinutes(15));

        HelmResult full = runner.renderFull("apiVersion: v1\nkind: Config\n", chart(), values, "tigera-operator");
        HelmResult truncated = runner.render("apiVersion: v1\nkind: Config\n", chart(), values, "tigera-operator");

        assertThat(full.output()).hasSize(200_000);
        assertThat(truncated.output()).hasSize(64 * 1024);
    }

    @Test
    void renderFullPassesTheClustersKubeVersionAndServedApiVersions() throws Exception {
        // 不带这两个参数时 helm 用自带默认 capabilities（≈1.35），
        // MutatingAdmissionPolicy 会渲染成 1.36+ 已不提供的 v1beta1
        Path log = tmp.resolve("argv.log");
        Path values = tmp.resolve("values.yaml");
        Files.writeString(values, "{}\n");

        runner(log, 0).renderFull("apiVersion: v1\nkind: Config\n", chart(), values, "tigera-operator",
                "1.37", List.of("admissionregistration.k8s.io/v1/MutatingAdmissionPolicy"));

        String argv = Files.readString(log);
        assertThat(argv).contains("--kube-version").contains("1.37");
        assertThat(argv).contains("--api-versions")
                .contains("admissionregistration.k8s.io/v1/MutatingAdmissionPolicy");
    }

    @Test
    void applyServerSideRunsKubectlWithServerSideApplyAndThePlatformFieldManager() throws Exception {
        // 超大 chart（Calico CRD）不能建 Helm release：Helm 把整个 chart 存进 release Secret，
        // 超过 1MiB 直接装不了。改用 kubectl apply --server-side（官方 docs 同款做法）。
        Path log = tmp.resolve("argv.log");

        HelmResult r = runner(log, 0).applyServerSide("apiVersion: v1\nkind: Config\n",
                "apiVersion: apiextensions.k8s.io/v1\nkind: CustomResourceDefinition\n"
                        + "metadata:\n  name: installations.operator.tigera.io\n");

        assertThat(r.ok()).isTrue();
        String argv = Files.readString(log);
        assertThat(argv).contains("apply").contains("--server-side").contains("--force-conflicts")
                // 与平台其它 SSA 写入同一个 fieldManager，managedFields 里能认出是谁改的
                .contains("--field-manager=platform-system")
                .contains("-f")
                .contains("--kubeconfig");
    }

    @Test
    void waitCrdsEstablishedWaitsOnTheGivenCrds() throws Exception {
        // CRD 写进去不等于已 Established；不等的话下一个 release 里的 CR 还是 no matches for kind
        Path log = tmp.resolve("argv.log");

        HelmResult r = runner(log, 0).waitCrdsEstablished("apiVersion: v1\nkind: Config\n",
                List.of("installations.operator.tigera.io", "ippools.projectcalico.org"));

        assertThat(r.ok()).isTrue();
        String argv = Files.readString(log);
        assertThat(argv).contains("wait").contains("--for=condition=Established");
        assertThat(argv).contains("customresourcedefinition/installations.operator.tigera.io");
        assertThat(argv).contains("customresourcedefinition/ippools.projectcalico.org");
        assertThat(argv).contains("--timeout");
    }

    @Test
    void theTemporaryKubeconfigIsRemovedEvenOnFailure() throws Exception {
        // 通过把 FAKE_HELM_LOG 指到不可写路径制造失败，断言 temp 文件仍被删
        Path bad = tmp.resolve("no-such-dir/argv.log");
        Path values = tmp.resolve("values.yaml");
        Files.writeString(values, "replicas: 1\n");

        runner(bad, 1).render("apiVersion: v1\n", chart(), values, "kube-system");

        try (var stream = Files.list(tmp)) {
            assertThat(stream.filter(p -> p.getFileName().toString().startsWith("kubeconfig-")))
                    .as("临时 kubeconfig 必须清理").isEmpty();
        }
    }
}
