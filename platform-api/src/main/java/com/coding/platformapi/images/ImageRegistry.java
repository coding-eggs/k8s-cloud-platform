package com.coding.platformapi.images;

/**
 * 一个集群可用的镜像仓库。
 *
 * @param host    主机名（{@code host[:端口]}，**不带 scheme** —— 镜像引用里没有 scheme）
 * @param project Harbor 项目（默认 platform）
 */
public record ImageRegistry(String host, String project) {

    /** 未配置/已关闭：不改写，一切照旧 */
    public static final ImageRegistry OFFICIAL = new ImageRegistry("", "");

    public boolean enabled() {
        return host != null && !host.isBlank() && project != null && !project.isBlank();
    }
}
