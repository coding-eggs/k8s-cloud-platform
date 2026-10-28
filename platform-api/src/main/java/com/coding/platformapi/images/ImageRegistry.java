package com.coding.platformapi.images;

/**
 * 一个集群可用的镜像仓库。
 *
 * @param host    主机名（{@code host[:端口]}，**不带 scheme** —— 镜像引用里没有 scheme）
 * @param project 可选的额外路径前缀；**留空 = 一上游一项目**（项目名就是上游主机，如 {@code quay.io}），
 *                这样 Harbor 的复制规则才能直接落到 {@code <harbor>/<上游主机>/<仓库>}
 */
public record ImageRegistry(String host, String project) {

    /** 未配置/已关闭：不改写，一切照旧 */
    public static final ImageRegistry OFFICIAL = new ImageRegistry("", "");

    public boolean enabled() {
        return host != null && !host.isBlank();
    }
}
