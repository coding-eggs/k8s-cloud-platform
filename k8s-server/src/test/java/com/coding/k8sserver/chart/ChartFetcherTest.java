package com.coding.k8sserver.chart;

import com.coding.common.exception.CloudPlatformException;
import com.coding.k8sserver.chart.ChartFetcher.ChartRef;
import com.coding.k8sserver.helm.HelmRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChartFetcherTest {

    @TempDir
    Path tmp;

    private HelmRunner runner(Path log, String content) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new HelmRunner("helm", List.of(java, "-cp", cp, "com.coding.k8sserver.helm.FakeHelm"),
                Map.of("FAKE_HELM_LOG", log.toString(), "FAKE_HELM_EXIT", "0",
                        "FAKE_HELM_PULL_CONTENT", content), tmp, Duration.ofMinutes(1));
    }

    private static String sha256(String s) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(StandardCharsets.UTF_8));
        return "sha256:" + java.util.HexFormat.of().formatHex(d);
    }

    @Test
    void returnsDigestAndRejectsMismatch() throws Exception {
        Path log = tmp.resolve("argv.log");
        ChartFetcher fetcher = new ChartFetcher(
                runner(log, "chart-bytes"), tmp, 1024 * 1024);

        ChartFetcher.FetchedChart ok = fetcher.fetch(ref("", "oci://harbor/demo/web"));
        assertThat(ok.digest()).isEqualTo(sha256("chart-bytes"));

        assertThatThrownBy(() -> fetcher.fetch(ref(sha256("other"), "oci://harbor/demo/web")))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("digest");
    }

    @Test
    void passesVersionAndCredentials() throws Exception {
        Path log = tmp.resolve("argv.log");
        ChartFetcher fetcher = new ChartFetcher(runner(log, "x"), tmp, 1024 * 1024);

        ChartRef ref = new ChartRef("HARBOR", "oci://harbor/demo/web", "web", "1.2.3",
                "", "user", "pass");
        fetcher.fetch(ref);

        String argv = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(argv).contains("pull").contains("--version").contains("1.2.3")
                .contains("--username").contains("user").contains("--password").contains("pass")
                .contains("--destination");
    }

    @Test
    void rejectsOversizeChart() throws Exception {
        ChartFetcher fetcher = new ChartFetcher(runner(tmp.resolve("argv.log"), "0123456789"),
                tmp, 5);

        assertThatThrownBy(() -> fetcher.fetch(ref("", "oci://harbor/demo/web")))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("体积");
    }

    private static ChartRef ref(String digest, String ociRef) {
        return new ChartRef("HARBOR", ociRef, "web", "1.2.3", digest, null, null);
    }
}
