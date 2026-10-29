package com.coding.k8sserver.helm;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.k8score.operations.ServerSideApply;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
 * <p><b>Helm 4</b>：升级失败回滚用 {@code --rollback-on-failure}（3.x 的 {@code --atomic} 已不存在）；
 * 首次安装失败**不卸载**，用 {@code --wait} 等就绪、把现场留下（见 {@code apply}）。
 *
 * <p><b>预检分级</b>：{@link #render} 是硬门禁（不连集群）；{@link #dryRunServer} 由调用方按警告处理
 * （chart 同时装 CRD 与 CR 时会报假阴性）。
 */
@Slf4j
@Component
public class HelmRunner {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String binary;
    /** 直接落盘（server-side apply）用的 kubectl；超大 chart 不走 Helm release，见 {@link #applyServerSide} */
    private final String kubectlBinary;
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
                      @Value("${platform.components.kubectl-binary:kubectl}") String kubectlBinary,
                      @Value("${platform.components.helm-timeout:15m}") Duration timeout) {
        this(binary, kubectlBinary, null, Map.of(), Path.of(System.getProperty("java.io.tmpdir")), timeout);
    }

    /**
     * 测试注入构造器（公开是为了让 {@code chart} 包的 ChartFetcherTest 也能用假 helm）。
     * 生产代码只用上面的 {@link #HelmRunner(String, String, Duration)}。
     */
    public HelmRunner(String binary, List<String> commandPrefix, Map<String, String> extraEnv,
                      Path tempDir, Duration timeout) {
        this(binary, "kubectl", commandPrefix, extraEnv, tempDir, timeout);
    }

    public HelmRunner(String binary, String kubectlBinary, List<String> commandPrefix,
                      Map<String, String> extraEnv, Path tempDir, Duration timeout) {
        this.binary = binary;
        this.kubectlBinary = kubectlBinary;
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
        return render(kubeconfig, chartDir, valuesFile, namespace, false);
    }

    /**
     * 升级路径必须用 {@code --is-upgrade} 渲染：{@code helm template} 默认是 install 模式，
     * 而 {@code upgrade} 时 {@code .Release.IsUpgrade=true} —— 不传这个标志，预检看到的清单
     * 与真正落盘的可能不是同一份（复审 I4）。
     */
    public HelmResult render(String kubeconfig, Path chartDir, Path valuesFile, String namespace,
                             boolean upgrade) {
        return exec(kubeconfig, renderArgs(chartDir, valuesFile, namespace, upgrade));
    }

    /**
     * 渲染**完整**清单（不截断 64KB），并带上集群真实的 capabilities。
     *
     * <p>只给 server-side apply 路径用：那条路径把渲染结果原样写进集群，
     * 截断后的清单会静默少装对象（Calico 的 CRD chart 渲染出来 3.5MB）。
     *
     * <p>{@code kubeVersion + apiVersions} 来自集群探测：{@code helm template} 默认用 helm 自带的
     * 版本（4.2.0 ≈ k8s 1.35），会把 {@code MutatingAdmissionPolicy} 渲染成 {@code v1beta1}；
     * 在 k8s 1.37（只有 {@code v1}）上 apply 直接报
     * {@code no matches for kind "MutatingAdmissionPolicy" in version "admissionregistration.k8s.io/v1beta1"}。
     */
    /** 不带集群 capabilities 的渲染（与 {@link #render} 同样的默认值，但输出不截断） */
    public HelmResult renderFull(String kubeconfig, Path chartDir, Path valuesFile, String namespace) {
        return renderFull(kubeconfig, chartDir, valuesFile, namespace, null, List.of());
    }

    public HelmResult renderFull(String kubeconfig, Path chartDir, Path valuesFile, String namespace,
                                 String kubeVersion, List<String> apiVersions) {
        List<String> args = renderArgs(chartDir, valuesFile, namespace, false);
        if (kubeVersion != null && !kubeVersion.isBlank()) {
            args.add("--kube-version");
            args.add(kubeVersion);
        }
        for (String apiVersion : apiVersions) {
            args.add("--api-versions");
            args.add(apiVersion);
        }
        return exec(kubeconfig, args, false);
    }

    private static List<String> renderArgs(Path chartDir, Path valuesFile, String namespace, boolean upgrade) {
        List<String> args = new ArrayList<>(List.of(
                "template", "precheck", chartDir.toString(),
                "--namespace", namespace,
                "--values", valuesFile.toString()));
        if (upgrade) {
            args.add("--is-upgrade");
        }
        return args;
    }

    /**
     * 安装或升级。幂等：同一 release 上重复调用就是 upgrade。
     *
     * <p><b>失败时的行为按「有没有成功部署过」分两种</b>（2026-10-27 用户定）：
     * <ul>
     *   <li>**已经成功部署过** → {@code --rollback-on-failure}：升级失败回滚到上一个成功版本，
     *       别把能用的组件升成半截；</li>
     *   <li>**第一次装**（含上次失败留下的 failed release）→ 只用 {@code --wait}：失败**不卸载**，
     *       release 与已建出来的对象留在集群里。以前这里也加 rollback-on-failure，Helm 4 对
     *       install 的语义就是「失败即 uninstall」：装到一半的 PVC / Service 被拆掉，
     *       带 {@code resource-policy: keep} 的盘还得手工删了才能重试。留着现场再点一次
     *       「安装」走的就是 upgrade，能接着装。</li>
     * </ul>
     * 两种都会等资源就绪（Helm 4 里 {@code --rollback-on-failure} 也把 wait 默认成 watcher）。
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
        return apply(kubeconfig, chartDir, valuesFile, release, namespace,
                new InstallOptions(false, false, labels));
    }

    /** 安装选项：租户路径要收掉 chart 自带的 CRD 与 hook。 */
    public record InstallOptions(boolean skipCrds, boolean noHooks, Map<String, String> labels) {
    }

    public HelmResult apply(String kubeconfig, Path chartPath, Path valuesFile,
                            String release, String namespace, InstallOptions options) {
        // 先把「中止/被杀打断」留下的 pending-* 收尾，否则下面这句 upgrade 一律被拒：
        // Error: another operation (install/upgrade/rollback) is in progress
        String recovered = recoverPendingRelease(kubeconfig, release, namespace);
        boolean deployed = hasDeployedRevision(kubeconfig, release, namespace);
        List<String> args = new ArrayList<>(List.of(
                "upgrade", "--install", release, chartPath.toString(),
                "--namespace", namespace,
                "--create-namespace",
                "--values", valuesFile.toString(),
                deployed ? "--rollback-on-failure" : "--wait",
                "--force-conflicts",
                "--timeout", "10m"));
        if (options.skipCrds()) {
            args.add("--skip-crds");
        }
        if (options.noHooks()) {
            args.add("--no-hooks");
        }
        if (!options.labels().isEmpty()) {
            List<String> kv = new ArrayList<>();
            options.labels().forEach((k, v) -> kv.add(k + "=" + v));
            args.add("--labels");
            args.add(String.join(",", kv));
        }
        HelmResult result = exec(kubeconfig, args);
        return recovered == null || result == null
                ? result
                : new HelmResult(result.ok(), result.exitCode(),
                        recovered + "\n" + (result.output() == null ? "" : result.output()));
    }

    /**
     * 把卡在 {@code pending-*} 的 release 收尾，免得下一次 upgrade 被
     * {@code another operation (install/upgrade/rollback) is in progress} 拦死。
     *
     * <p>pending 是怎么来的：管理员点了「中止」，平台只释放自己的并发锁、**不接管 helm**
     * （见 {@code ComponentInstallService.abort} 的提示），而被打断的 helm 进程不会把 release
     * 状态改回去 —— 2026-10-29 metrics-server 就这么卡住，之后几次安装全失败在同一个报错上。
     *
     * <p>收尾规则：回滚到**最近一个部署成功过**的版本；一次都没有（首次安装被打断）就回滚到它
     * 自己那一版，等于把那次安装补完。收尾失败直接抛 —— 让操作记录里写清楚原因，而不是留给
     * 下一个人对着 "another operation" 发愣。
     *
     * @return 收尾说明（会拼进操作日志）；没做事返回 null
     */
    private String recoverPendingRelease(String kubeconfig, String release, String namespace) {
        HelmResult list = exec(kubeconfig, List.of("list", "--namespace", namespace, "--all",
                "--filter", "^" + release + "$", "-o", "json"));
        if (!list.ok() || list.output() == null || list.output().isBlank()) {
            return null;   // 查不到（release 不存在 / helm 出错）：交给后面的 upgrade 自己报
        }
        String state;
        long revision;
        try {
            JsonNode items = JSON.readTree(list.output());
            if (!items.isArray() || items.isEmpty()) {
                return null;
            }
            JsonNode me = items.get(0);
            state = me.path("status").asString("");
            revision = me.path("revision").asLong(0);
        } catch (Exception e) {
            return null;   // 解析不了就别拦着装
        }
        if (state == null || !state.startsWith("pending")) {
            return null;
        }
        long target = revision;
        HelmResult history = exec(kubeconfig, List.of("history", release,
                "--namespace", namespace, "-o", "json"));
        if (history.ok() && history.output() != null) {
            try {
                long deployed = -1;
                for (JsonNode node : JSON.readTree(history.output())) {
                    String s = node.path("status").asString("");
                    if ("deployed".equals(s) || "superseded".equals(s)) {
                        deployed = Math.max(deployed, node.path("revision").asLong(0));
                    }
                }
                if (deployed > 0) {
                    target = deployed;
                }
            } catch (Exception ignored) {
                // 解析失败就退回「回滚到当前版本」
            }
        }
        HelmResult rollback = exec(kubeconfig, List.of("rollback", release, String.valueOf(target),
                "--namespace", namespace, "--force-conflicts"));
        if (!rollback.ok()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "release " + release + " 卡在 " + state + "，自动收尾失败（回滚到 rev " + target
                            + "）：" + rollback.output());
        }
        log.warn("release {} 处于 {}，已自动回滚到 rev {} 再继续安装", release, state, target);
        return "检测到 " + release + " 卡在 " + state + "（上次操作被中止），已自动回滚到 rev " + target;
    }

    /**
     * 这个 release 现在有没有**成功部署过**的版本（决定失败时回滚还是留现场）。
     *
     * <p>用 {@code helm list --deployed -q --filter ^name$} 而不是 {@code helm status}：
     * 只要一个布尔，不必解析 JSON；release 不存在时列表为空，等价于 false。
     */
    private boolean hasDeployedRevision(String kubeconfig, String release, String namespace) {
        HelmResult r = exec(kubeconfig, List.of("list", "--namespace", namespace,
                "--deployed", "--filter", "^" + release + "$", "--short"));
        return r.ok() && r.output() != null && !r.output().isBlank();
    }

    /**
     * 渲染结果直接用 {@code kubectl apply --server-side} 落盘，**不建 Helm release**。
     *
     * <p>为什么需要它：Helm 会把整个 chart 存进 release Secret（1MiB 上限）。Calico 的 CRD chart
     * 渲染后 3.5MB、chart 本身同量级，{@code helm install} 直接报
     * {@code Secret "sh.helm.release.v1...v1" is invalid: data: Too long}（2026-10-22 实测）。
     * 官方 docs 对这套 CRD 也是 {@code helm template | kubectl apply --server-side}。
     *
     * <p>{@code --force-conflicts} + 平台 fieldManager：与其他 SSA 写入同一个身份，
     * managedFields 里能看出是平台写的。
     */
    public HelmResult applyServerSide(String kubeconfig, String manifest) {
        Path manifestFile = null;
        Path kubeconfigFile = null;
        try {
            manifestFile = Files.createTempFile(tempDir, "manifests-", ".yaml");
            Files.writeString(manifestFile, manifest, StandardCharsets.UTF_8);
            kubeconfigFile = writeTemp(kubeconfig);
            List<String> args = List.of(
                    "apply", "--server-side", "--force-conflicts",
                    "--field-manager=" + ServerSideApply.FIELD_MANAGER,
                    "-f", manifestFile.toString(),
                    "--kubeconfig", kubeconfigFile.toString());
            return run(toolCommand(args), args);
        } catch (Exception e) {
            return new HelmResult(false, -1, "kubectl 执行失败: " + e.getMessage());
        } finally {
            deleteQuietly(manifestFile);
            deleteQuietly(kubeconfigFile);
        }
    }

    /**
     * 删除 manifest 里列出的对象；不存在的对象不算失败（与 S3 删除语义一致）。
     *
     * <p>为什么用 kubectl 而不是 generic client：删除需要 kind → plural 的映射，平台没有维护全量 kind 表；
     * 交给 kubectl 就绕开了这件事，且与 {@link #applyServerSide} 走同一把 kubeconfig 与临时文件规矩。
     */
    public HelmResult deleteManifest(String kubeconfig, String manifest) {
        Path manifestFile = null;
        Path kubeconfigFile = null;
        try {
            manifestFile = Files.createTempFile(tempDir, "delete-", ".yaml");
            Files.writeString(manifestFile, manifest, StandardCharsets.UTF_8);
            kubeconfigFile = writeTemp(kubeconfig);
            List<String> args = List.of(
                    "delete", "-f", manifestFile.toString(),
                    "--ignore-not-found",
                    "--kubeconfig", kubeconfigFile.toString());
            return run(toolCommand(args), args);
        } catch (Exception e) {
            return new HelmResult(false, -1, "kubectl 执行失败: " + e.getMessage());
        } finally {
            deleteQuietly(manifestFile);
            deleteQuietly(kubeconfigFile);
        }
    }

    /** 等一个 Job 跑完（初始化动作）；超时或失败返回 ok=false。 */
    public HelmResult waitJob(String kubeconfig, String job, String namespace, Duration timeout) {
        Path kubeconfigFile = null;
        try {
            kubeconfigFile = writeTemp(kubeconfig);
            List<String> args = List.of(
                    "wait", "--for=condition=complete", "job/" + job,
                    "--namespace", namespace,
                    "--timeout=" + timeout.toSeconds() + "s",
                    "--kubeconfig", kubeconfigFile.toString());
            return run(toolCommand(args), args);
        } catch (Exception e) {
            return new HelmResult(false, -1, "kubectl 执行失败: " + e.getMessage());
        } finally {
            deleteQuietly(kubeconfigFile);
        }
    }

    /** 取 Job 日志尾（初始化失败时给人看）；取不到返回空串，不抛。 */
    public String jobLogs(String kubeconfig, String job, String namespace) {
        Path kubeconfigFile = null;
        try {
            kubeconfigFile = writeTemp(kubeconfig);
            List<String> args = List.of(
                    "logs", "job/" + job, "--namespace", namespace, "--tail=100",
                    "--kubeconfig", kubeconfigFile.toString());
            HelmResult r = run(toolCommand(args), args);
            return r.ok() ? r.output() : "";
        } catch (Exception e) {
            return "";
        } finally {
            deleteQuietly(kubeconfigFile);
        }
    }

    /** 等 CRD 真的 Established 再装下一个 release（否则 CR 仍然 {@code no matches for kind}） */
    public HelmResult waitCrdsEstablished(String kubeconfig, List<String> crds) {
        Path kubeconfigFile = null;
        try {
            kubeconfigFile = writeTemp(kubeconfig);
            List<String> args = new ArrayList<>(List.of(
                    "wait", "--for=condition=Established", "--timeout=120s"));
            crds.forEach(name -> args.add("customresourcedefinition/" + name));
            args.add("--kubeconfig");
            args.add(kubeconfigFile.toString());
            return run(toolCommand(args), args);
        } catch (Exception e) {
            return new HelmResult(false, -1, "kubectl 执行失败: " + e.getMessage());
        } finally {
            deleteQuietly(kubeconfigFile);
        }
    }

    /** 直接落盘用 kubectl；测试注入时与 helm 共用假二进制（FakeHelm 只记录 argv） */
    private List<String> toolCommand(List<String> args) {
        List<String> command = new ArrayList<>();
        if (commandPrefix != null) {
            command.addAll(commandPrefix);
        } else {
            command.add(kubectlBinary);
        }
        command.addAll(args);
        return command;
    }

    /** 取 release 实际提交给集群的清单（装后复核用）。 */
    public HelmResult manifest(String kubeconfig, String release, String namespace) {
        return exec(kubeconfig, new ArrayList<>(List.of(
                "get", "manifest", release, "--namespace", namespace)));
    }

    /** 卸载：CRD 与 PVC 不会被删除（Helm 语义），UI 必须明说。 */
    public HelmResult uninstall(String kubeconfig, String release, String namespace) {
        return exec(kubeconfig, new ArrayList<>(List.of(
                "uninstall", release, "--namespace", namespace)));
    }

    /** chart 自带默认 values（上架/表单初始值用）。 */
    public HelmResult showValues(Path chartPath) {
        return pull(new ArrayList<>(List.of("show", "values", chartPath.toString())));
    }

    /** helm pull：不连集群，因此**不附加** {@code --kubeconfig}。 */
    public HelmResult pull(List<String> args) {
        List<String> command = new ArrayList<>();
        if (commandPrefix != null) {
            command.addAll(commandPrefix);
        } else {
            command.add(binary);
        }
        command.addAll(args);
        try {
            return run(command, args);
        } catch (Exception e) {
            return new HelmResult(false, -1, "helm 执行失败: " + e.getMessage());
        }
    }

    // ---------- 内部 ----------

    private HelmResult exec(String kubeconfig, List<String> args) {
        return exec(kubeconfig, args, true);
    }

    /** {@code truncateOutput=false} 只给 server-side apply 用：清单必须完整 */
    private HelmResult exec(String kubeconfig, List<String> args, boolean truncateOutput) {
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
            return run(command, args, truncateOutput);
        } catch (Exception e) {
            return new HelmResult(false, -1, "helm 执行失败: " + e.getMessage());
        } finally {
            deleteQuietly(kubeconfigFile);
        }
    }

    private HelmResult run(List<String> command, List<String> logicalArgs) throws IOException, InterruptedException {
        return run(command, logicalArgs, true);
    }

    private HelmResult run(List<String> command, List<String> logicalArgs, boolean truncateOutput)
            throws IOException, InterruptedException {
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
        return new HelmResult(exit == 0, exit, truncateOutput ? truncate(output) : output);
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
