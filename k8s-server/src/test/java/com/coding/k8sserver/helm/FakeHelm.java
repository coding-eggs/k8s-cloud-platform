package com.coding.k8sserver.helm;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * 测试用的"假 helm"：把 argv 原样写进 FAKE_HELM_LOG，再按 FAKE_HELM_EXIT 退出。
 *
 * <p>为什么不用 shell 脚本：Windows 上 PATH 里的 {@code bash} 可能是 WSL 的，
 * 它不认 {@code D:/...} 这种路径（实测退出码 127）。用同进程 JVM 启动的小程序，
 * 跨平台且不依赖任何 shell。
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
        System.exit(Integer.parseInt(System.getenv().getOrDefault("FAKE_HELM_EXIT", "0")));
    }
}
