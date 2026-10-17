package com.coding.platformapi.components;

/**
 * 一条探测判据。
 *
 * <p>{@code kind}：{@code deployment} / {@code statefulset} / {@code daemonset}（按工作负载），
 * 或 {@code crd}（纯 CRD 组件，如 Gateway API —— 集群级资源，版本读 CRD 自己的标签/注解）。
 *
 * <p>工作负载判据里 {@code imageRepo} 与 {@code name} 至少一个（前者跨安装稳定，后者是快路径）；
 * CRD 判据只认 {@code name}（CRD 名），{@code versionLabel} 指定读哪个标签。
 */
public record ComponentProbe(String namespace, String kind, String imageRepo, String name,
                             String versionLabel) {

    /** 工作负载判据的常用形态（无 versionLabel） */
    public ComponentProbe(String namespace, String kind, String imageRepo, String name) {
        this(namespace, kind, imageRepo, name, null);
    }
}
