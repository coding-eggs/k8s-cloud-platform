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
    void fallsBackToTheClasspathWhenTheConfiguredDirDoesNotExist() throws Exception {
        // chart 在 src/main/resources/charts 下：IDE 的进程工作目录不一定是模块目录，
        // 按相对路径找不到时必须回退到 classpath，否则面板点安装会报"内置 chart 不存在"
        ChartBundle bundle = new ChartBundle("/no/such/charts-dir");

        Path chart = bundle.chartDir("metrics-server", "3.14.0");

        assertThat(Files.isRegularFile(chart.resolve("Chart.yaml"))).isTrue();
        assertThat(Files.readString(chart.resolve("Chart.yaml"))).contains("version: 3.14.0");
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
