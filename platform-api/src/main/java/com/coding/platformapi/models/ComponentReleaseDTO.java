package com.coding.platformapi.models;

import lombok.Data;

/**
 * 组件里一个 Helm release 的版本声明（组件页「版本」列的展开明细）。
 *
 * <p>为什么要有：一个组件可能是多个 chart 的安装单元 —— Istio = 5 个 Istio chart + Gateway API，
 * 管理集群的 Prometheus = kube-prometheus-stack + Thanos，OTel = operator + Tempo。
 * 只报一个版本会让「组件版本」失去意义（Istio 会显示成 Gateway API 的 1.6.2）。
 *
 * <p>chart 版本与应用版本是**两套编号**，两个都要给（kube-prometheus-stack 92.10.1 → v0.94.1）。
 */
@Data
public class ComponentReleaseDTO {
    private String releaseName;
    private String namespace;
    /** 内置 chart 目录名 */
    private String chartCode;
    private String chartVersion;
    /** chart 的应用版本（Chart.yaml 的 appVersion） */
    private String appVersion;
    /** 是否组件本体：{@code availableVersions} 与升级目标只认它 */
    private boolean primary;
}
