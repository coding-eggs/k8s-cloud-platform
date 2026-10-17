package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * 一条工作负载探测候选（platform-api 的组件目录决定探什么，适配器只负责读）。
 */
@Data
public class WorkloadProbeTargetDTO {
    private String namespace;
    /** 小写：deployment / statefulset / daemonset（与 K8sWorkload DTO 的 kind 口径一致） */
    private String kind;
    /** 镜像仓库片段 —— **主判据**，跨安装稳定（Helm release 名可变，仓库路径不可变） */
    private String imageRepo;
    /** 工作负载名（kind=crd 时是 CRD 名）—— 快路径，只给名字稳定的组件写（istiod / ztunnel / calico-node） */
    private String name;
    /**
     * kind=crd 时的版本标签名（如 {@code gateway.networking.k8s.io/bundle-version}）。
     * 为 null 时按 {@code app.kubernetes.io/version} → 任意 {@code *bundle-version} 兜底。
     */
    private String versionLabel;
}
