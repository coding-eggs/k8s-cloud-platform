package com.coding.k8sserver.helm;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 内置 chart 的定位器（S3.6）。
 *
 * <p>chart 以**目录形态**内置在 {code src/main/resources/charts}（不是 tgz）：git 里可读、可 review，
 * helm 直接吃目录。目录约定：{@code <charts-dir>/components/<code>/<version>/chart/}。
 *
 * <p><b>两种解析方式</b>：先按 {@code platform.components.charts-dir}（默认 {@code charts}，相对进程工作目录）
 * 找；找不到再回退到 classpath —— IDE 的进程工作目录不一定是模块目录，只按相对路径会读不到。
 * 打包进 jar 后资源不是文件系统路径（helm 需要真实目录），镜像里由 Dockerfile 把 charts 复制到
 * {@code /app/charts}，WORKDIR=/app 时默认值正好命中。
 *
 * <p>⚠️ 版本找不到时**必须报错**，不能回退到别的版本 —— 否则 platform-api 的 recipe 与
 * k8s-server 的产物一旦漂移，就会静默装出一个用户没选的版本。
 */
@Component
public class ChartBundle {

    private final Path root;

    public ChartBundle(@Value("${platform.components.charts-dir:charts}") String chartsDir) {
        this.root = resolveRoot(chartsDir);
    }

    private static Path resolveRoot(String chartsDir) {
        Path configured = Path.of(chartsDir).toAbsolutePath().normalize();
        if (Files.isDirectory(configured)) {
            return configured;
        }
        try {
            Resource resource = new ClassPathResource("charts");
            if (resource.isFile()) {
                return resource.getFile().toPath().toAbsolutePath().normalize();
            }
        } catch (IOException ignored) {
            // 打包进 jar：不是文件系统路径，退回配置目录
        }
        return configured;
    }

    /** 内置 chart 的根目录（镜像清单要遍历它） */
    public Path root() {
        return root;
    }

    public Path chartDir(String code, String version) {
        return chartDir("components", code, version);
    }

    /**
     * 按区域定位：{@code <charts-dir>/<area>/<code>/<version>/chart/}。
     * 组件在 {@code components/}，中间件 Operator 在 {@code middleware/}。
     */
    public Path chartDir(String area, String code, String version) {
        if (code == null || code.isBlank() || version == null || version.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "缺少 chart code 或 version");
        }
        if (code.contains("/") || code.contains("..") || version.contains("/") || version.contains("..")
                || area.contains("/") || area.contains("..")) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "非法的 chart 区域/code/version");
        }
        Path dir = root.resolve(area).resolve(code).resolve(version).resolve("chart");
        if (!Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve("Chart.yaml"))) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "内置 chart 不存在或缺少 Chart.yaml: " + area + "/" + code + " @" + version + "（" + dir + "）");
        }
        return dir;
    }
}
