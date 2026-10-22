package com.coding.k8sserver.helm;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * 测试用的"假 helm"：把 argv 原样写进 FAKE_HELM_LOG，再按 FAKE_HELM_EXIT 退出。
 *
 * <p>为什么不用 shell 脚本：Windows 上 PATH 里的 {@code bash} 可能是 WSL 的，
 * 它不认 {@code D:/...} 这种路径（实测退出码 127）。用同进程 JVM 启动的小程序，
 * 跨平台且不依赖任何 shell。
 *
 * <p>额外的测试钩子：
 * <ul>
 *   <li>{@code FAKE_HELM_SLEEP_MS} —— 睡眠再退出，用来验证超时真的会 kill 进程；</li>
 *   <li>{@code FAKE_HELM_PERM_LOG} —— 把 {@code --kubeconfig} 文件的权限写进去，
 *       用来断言临时文件是 0600（非 POSIX 文件系统写 {@code n/a}）。</li>
 * </ul>
 */
public final class FakeHelm {

    private FakeHelm() {
    }

    public static void main(String[] args) throws Exception {
        String logPath = System.getenv("FAKE_HELM_LOG");
        try (PrintWriter writer = new PrintWriter(logPath, StandardCharsets.UTF_8)) {
            for (String arg : args) {
                writer.println(arg);
            }
        }
        String permLog = System.getenv("FAKE_HELM_PERM_LOG");
        if (permLog != null) {
            String perms = "n/a";
            String kubeconfig = null;
            for (int i = 0; i + 1 < args.length; i++) {
                if ("--kubeconfig".equals(args[i])) {
                    kubeconfig = args[i + 1];
                }
            }
            if (kubeconfig != null) {
                try {
                    perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(Path.of(kubeconfig)));
                } catch (UnsupportedOperationException e) {
                    perms = "n/a"; // Windows 测试机
                }
            }
            Files.writeString(Path.of(permLog), perms, StandardCharsets.UTF_8);
        }
        int sleepMs = Integer.parseInt(System.getenv().getOrDefault("FAKE_HELM_SLEEP_MS", "0"));
        if (sleepMs > 0) {
            Thread.sleep(sleepMs);
        }

        // 模拟 helm template 的 stdout（用来验证 renderFull 不截断、render 截断 64KB）
        String stdoutFile = System.getenv("FAKE_HELM_STDOUT_FILE");
        if (stdoutFile != null) {
            System.out.print(Files.readString(Path.of(stdoutFile), StandardCharsets.UTF_8));
        }

        // 模拟 helm pull：按 --destination 生成一个 .tgz（内容来自环境变量），供 ChartFetcher 校验
        String pullContent = System.getenv("FAKE_HELM_PULL_CONTENT");
        if (pullContent != null && args.length > 0 && "pull".equals(args[0])) {
            String destination = null;
            for (int i = 0; i + 1 < args.length; i++) {
                if ("--destination".equals(args[i])) {
                    destination = args[i + 1];
                }
            }
            Path dir = Path.of(destination);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("fake-chart.tgz"), pullContent, StandardCharsets.UTF_8);
        }

        System.exit(Integer.parseInt(System.getenv().getOrDefault("FAKE_HELM_EXIT", "0")));
    }
}
