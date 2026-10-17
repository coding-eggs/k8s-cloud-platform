package com.coding.k8sserver.helm;

import com.coding.common.exception.CloudPlatformException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChartBundleTest {

    @Test
    void resolvesTheBundledMetricsServerChart(@TempDir Path root) throws Exception {
        Path chart = root.resolve("components/metrics-server/1.0.0/chart");
        Files.createDirectories(chart);
        Files.writeString(chart.resolve("Chart.yaml"), "apiVersion: v2\nname: metrics-server\nversion: 1.0.0\n");

        ChartBundle bundle = new ChartBundle(root.toString());

        assertThat(bundle.chartDir("metrics-server", "1.0.0")).isEqualTo(chart.toAbsolutePath().normalize());
    }

    @Test
    void anUnknownVersionFailsLoudlyInsteadOfFallingBack(@TempDir Path root) {
        ChartBundle bundle = new ChartBundle(root.toString());

        // ⭐ 版本漂移必须报错：静默回退会装出一个用户没选的版本
        assertThatThrownBy(() -> bundle.chartDir("metrics-server", "9.9.9"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("9.9.9");
    }

    @Test
    void aChartWithoutChartYamlIsRejected(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("components/metrics-server/1.0.0/chart"));

        assertThatThrownBy(() -> new ChartBundle(root.toString()).chartDir("metrics-server", "1.0.0"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("Chart.yaml");
    }

    @Test
    void pathTraversalInCodeOrVersionIsRejected(@TempDir Path root) {
        ChartBundle bundle = new ChartBundle(root.toString());

        assertThatThrownBy(() -> bundle.chartDir("../etc", "1.0.0"))
                .isInstanceOf(CloudPlatformException.class);
        assertThatThrownBy(() -> bundle.chartDir("metrics-server", "../../x"))
                .isInstanceOf(CloudPlatformException.class);
    }
}
