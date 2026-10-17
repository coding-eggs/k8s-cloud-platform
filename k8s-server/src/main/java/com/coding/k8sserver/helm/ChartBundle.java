package com.coding.k8sserver.helm;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 内置 chart 的定位器（S3.6）。
 *
 * <p>chart 以**目录形态**内置在 k8s-server（不是 tgz）：git 里可读、可 review，helm 直接吃目录。
 * 目录约定：{@code <charts-dir>/components/<code>/<version>/chart/}。
 *
 * <p>⚠️ 版本找不到时**必须报错**，不能回退到别的版本 —— 否则 platform-api 的 recipe 与
 * k8s-server 的产物一旦漂移，就会静默装出一个用户没选的版本。
 */
@Component
public class ChartBundle {

    private final Path root;

    public ChartBundle(@Value("${platform.components.charts-dir:charts}") String chartsDir) {
        this.root = Path.of(chartsDir).toAbsolutePath().normalize();
    }

    public Path chartDir(String code, String version) {
        if (code == null || code.isBlank() || version == null || version.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "缺少 chart code 或 version");
        }
        if (code.contains("/") || code.contains("..") || version.contains("/") || version.contains("..")) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "非法的 chart code/version");
        }
        Path dir = root.resolve("components").resolve(code).resolve(version).resolve("chart");
        if (!Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve("Chart.yaml"))) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "内置 chart 不存在或缺少 Chart.yaml: " + code + " @" + version + "（" + dir + "）");
        }
        return dir;
    }
}
