package com.coding.k8sserver.helm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 执行 helm（S3.6）。唯一一个"平台自己往集群写"的原语。
 *
 * <p><b>安全</b>：helm 需要 kubeconfig 文件 —— 明文只落在 0600 的临时文件里，方法返回前删除；
 * 日志绝不打印内容。
 *
 * <p><b>Helm 4</b>：失败回滚用 {@code --rollback-on-failure}（3.x 的 {@code --atomic} 已不存在）。
 *
 * <p><b>预检分级</b>：{@link #render} 是硬门禁（不连集群）；{@link #dryRunServer} 由调用方按警告处理
 * （chart 同时装 CRD 与 CR 时会报假阴性）。
 */
@Slf4j
@Component
public class HelmRunner {

    private final String binary;
    /** 测试注入：非空时命令以这个前缀开头（通常是 java -cp ... FakeHelm） */
    private final List<String> commandPrefix;
    private final Map<String, String> extraEnv;
    /** 临时 kubeconfig 的落点；测试注入 @TempDir 才能断言"确实删了" */
    private final Path tempDir;
    /** 单次 helm 调用的超时（超过则 destroyForcibly） */
    private final Duration timeout;

    /** 生产构造器。类里有第二个构造器，Spring 不会自己挑 —— 必须标 @Autowired。 */
    @Autowired
    public HelmRunner(@Value("${platform.components.helm-binary:helm}") String binary,
                      @Value("${platform.components.helm-timeout:15m}") Duration timeout) {
        this(binary, null, Map.of(), Path.of(System.getProperty("java.io.tmpdir")), timeout);
    }

    HelmRunner(String binary, List<String> commandPrefix, Map<String, String> extraEnv,
               Path tempDir, Duration timeout) {
        this.binary = binary;
        this.commandPrefix = commandPrefix;
        this.extraEnv = extraEnv;
        this.tempDir = tempDir;
        this.timeout = timeout;
    }

    /**
     * 服务端 dry-run：**只作警告**。chart 同时装 CRD 与 CR 时（kube-prometheus-stack）会因
     * "CRD 尚不存在"报假阴性，当硬门禁会导致永远装不上。
     */
    public HelmResult dryRunServer(String kubeconfig, Path chartDir, Path valuesFile,
                                   String release, String namespace) {
        List<String> args = new ArrayList<>(List.of(
                "upgrade", "--install", release, chartDir.toString(),
                "--namespace", namespace, "--create-namespace",
                "--values", valuesFile.toString(),
                "--dry-run=server"));
        return exec(kubeconfig, args);
    }

    /** 客户端渲染校验（不连集群）—— 模板/values 错误在这一步硬失败 */
    public HelmResult render(String kubeconfig, Path chartDir, Path valuesFile, String namespace) {
        List<String> args = new ArrayList<>(List.of(
                "template", "precheck", chartDir.toString(),
                "--namespace", namespace,
                "--values", valuesFile.toString()));
        return exec(kubeconfig, args);
    }

    /**
     * 安装或升级。幂等：同一 release 上重复调用就是 upgrade。
     * {@code --rollback-on-failure} 在 Helm 4 里同时把 wait 默认成 watcher。
     *
     * <p><b>{@code --force-conflicts}</b>：Helm 4 用 SSA 装 chart 里的 {@code crds/}，而
     * 手工 {@code helm install} 过的集群会留下 manager=helm、operation=Update 的 CRD ——
     * SSA 不认这个身份，报 "Apply failed with 3 conflicts: conflicts with \"helm\""。
     * 这些 CRD 是"卸载 release 后残留"的孤儿（Helm 从不删除 CRD，见 S1.2）。
     * 拿它们的所有权是安装 kube-prometheus-stack 这类带 CRD 的 chart 的前提；
     * 没有 force 时任何接管场景都会永远装不上，而 CR 实例（其他 operator 的 monitor 等）不受影响。
     */
    public HelmResult apply(String kubeconfig, Path chartDir, Path valuesFile,
                            String release, String namespace, Map<String, String> labels) {
        List<String> args = new ArrayList<>(List.of(
                "upgrade", "--install", release, chartDir.toString(),
                "--namespace", namespace,
                "--create-namespace",
                "--values", valuesFile.toString(),
                "--rollback-on-failure",
                "--force-conflicts",
                "--timeout", "10m"));
        if (!labels.isEmpty()) {
            List<String> kv = new ArrayList<>();
            labels.forEach((k, v) -> kv.add(k + "=" + v));
            args.add("--labels");
            args.add(String.join(",", kv));
        }
        return exec(kubeconfig, args);
    }

    // ---------- 内部 ----------

    private HelmResult exec(String kubeconfig, List<String> args) {
        Path kubeconfigFile = null;
        try {
            kubeconfigFile = writeTemp(kubeconfig);
            List<String> command = new ArrayList<>();
            if (commandPrefix != null) {
                command.addAll(commandPrefix);
            } else {
                command.add(binary);
            }
            command.addAll(args);
            command.add("--kubeconfig");
            command.add(kubeconfigFile.toString());
            return run(command, args);
        } catch (Exception e) {
            return new HelmResult(false, -1, "helm 执行失败: " + e.getMessage());
        } finally {
            deleteQuietly(kubeconfigFile);
        }
    }

    private HelmResult run(List<String> command, List<String> logicalArgs) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.environment().putAll(extraEnv);
        Process p = pb.start();

        // ⚠️ 输出**必须**在另一个线程读：readAllBytes 会阻塞到进程关闭 stdout，
        //    放在 waitFor 之前的话超时永远不会触发（helm 卡住 = 线程永久阻塞）。
        StringBuilder out = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream in = p.getInputStream()) {
                out.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // 进程被 kill 时读端会报错，输出按已读到的部分处理
            }
        });

        boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            reader.join(2000);
            return new HelmResult(false, -1, "helm 超时（" + timeout.toSeconds() + " 秒）");
        }
        reader.join(5000);
        int exit = p.exitValue();
        String output = out.toString();
        log.info("helm {} 退出码 {}（{} 字节输出）", logicalArgs.get(0), exit, output.length());
        return new HelmResult(exit == 0, exit, truncate(output));
    }

    private Path writeTemp(String kubeconfig) throws IOException {
        Path file = Files.createTempFile(tempDir, "kubeconfig-", ".yaml");
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统（Windows 测试机）：退化为默认权限，生产镜像里是 Linux
        }
        try {
            Files.writeString(file, kubeconfig, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 写失败时 exec() 还没拿到路径 —— 必须在这里删掉，否则 0600 的明文会留在临时目录
            deleteQuietly(file);
            throw e;
        }
        return file;
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除临时 kubeconfig 失败（{}）：{}", file, e.getMessage());
        }
    }

    private static String truncate(String output) {
        int max = 64 * 1024;
        return output.length() <= max ? output : output.substring(output.length() - max);
    }
}
