package com.coding.k8sserver.chart;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * 拉取 chart 包（{@code helm pull}）并校验 digest。
 *
 * <p>下载的是 **tgz**，不展开：{@code helm template} / {@code helm upgrade} 都直接接受打包后的 chart 路径，
 * 少一层解压就少一类路径漏洞。
 *
 * <p>digest 语义：tar.gz 文件的 sha256（前缀 {@code sha256:}）。上架时记录、安装时逐字节比对 ——
 * 这是"上架时看过的 chart"和"装的时候拿到的 chart"是同一种东西的唯一证据。
 */
@Component
public class ChartFetcher {

    private final HelmRunner helm;
    private final Path workDir;
    private final long maxBytes;

    @Autowired
    public ChartFetcher(HelmRunner helm,
                        @Value("${platform.chart.max-size-bytes:52428800}") long maxBytes) {
        this(helm, Path.of(System.getProperty("java.io.tmpdir")), maxBytes);
    }

    ChartFetcher(HelmRunner helm, Path workDir, long maxBytes) {
        this.helm = helm;
        this.workDir = workDir;
        this.maxBytes = maxBytes;
    }

    public FetchedChart fetch(ChartRef ref) {
        Path dir;
        try {
            dir = Files.createTempDirectory(workDir, "chart-");
        } catch (IOException e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "无法创建 chart 临时目录：" + e.getMessage());
        }
        List<String> args = new ArrayList<>();
        args.add("pull");
        if ("HARBOR".equalsIgnoreCase(ref.source()) && ref.ref() != null && ref.ref().startsWith("oci://")) {
            args.add(ref.ref());
        } else {
            args.add(ref.chartName());
            args.add("--repo");
            args.add(ref.ref());
        }
        args.add("--version");
        args.add(ref.version());
        args.add("--destination");
        args.add(dir.toString());
        if (ref.username() != null && !ref.username().isBlank()) {
            args.add("--username");
            args.add(ref.username());
            args.add("--password");
            args.add(ref.password() == null ? "" : ref.password());
        }
        HelmResult result = helm.pull(args);
        if (!result.ok()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "拉取 chart 失败（" + ref.chartName() + " " + ref.version() + "）：" + result.output());
        }
        Path tgz;
        try (Stream<Path> files = Files.list(dir)) {
            tgz = files.filter(p -> p.getFileName().toString().endsWith(".tgz")).findFirst()
                    .orElseThrow(() -> new CloudPlatformException(EnumResponseType.ERROR,
                            "helm pull 未产出 chart 包（" + ref.chartName() + " " + ref.version() + "）"));
            long size = Files.size(tgz);
            if (size > maxBytes) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "chart 体积 " + size + " 字节超过上限 " + maxBytes + " 字节");
            }
        } catch (IOException e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "读取 chart 包失败：" + e.getMessage());
        }
        String digest = "sha256:" + hex(sha256(tgz));
        if (ref.digest() != null && !ref.digest().isBlank() && !ref.digest().equals(digest)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "chart digest 与目录记录不一致（期望 " + ref.digest() + "，实际 " + digest + "）");
        }
        return new FetchedChart(tgz, digest);
    }

    private static byte[] sha256(Path file) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "计算 digest 失败：" + e.getMessage());
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** 本次拉取的 chart（tgz 在临时目录，调用方负责清理） */
    public record FetchedChart(Path tgz, String digest) {
    }

    /** chart 引用：source=HARBOR 时 ref 是 OCI 地址，否则 ref 是 HTTP repo URL。 */
    public record ChartRef(String source, String ref, String chartName, String version,
                           String digest, String username, String password) {
    }
}
