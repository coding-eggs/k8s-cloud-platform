package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.Map;

/** 一个 release 的安装规格（由 platform-api 的 recipe 解析后下发）。 */
@Data
public class ReleaseInstallSpecDTO {
    private String releaseName;
    private String namespace;
    /** 内置 chart 的目录名，如 metrics-server */
    private String chartCode;
    /** 目标 chart 版本，如 1.0.0 */
    private String chartVersion;
    /** 平台契约值 + 用户可编辑值合并后的最终 values */
    private Map<String, Object> values;
    /**
     * 安装方式：null = 普通 Helm release；{@code SERVER_SIDE_APPLY} = 渲染后直接 server-side apply，
     * 不建 Helm release。只给超大 chart 用 —— Helm 把整个 chart 存进 release Secret，
     * 超过 1MiB 就装不了（Calico 的 CRD chart 渲染后 3.5MB，2026-10-22 实测）。
     */
    private String applyMode;

    /**
     * 镜像仓库（host[:端口]，不带 scheme）与项目：非空时 k8s-server 会把 **chart 自带 values.yaml** 里的
     * 镜像改写到这个仓库，再与 {@link #values} 合并。
     *
     * <p>为什么改写发生在 k8s-server：镜像绝大多数来自 chart 的默认 values（不在平台 values 里），
     * 而 chart 文件只随 k8s-server 分发 —— platform-api 看不到它们。
     */
    private String imageRegistryHost;
    private String imageRegistryProject;

    /** {@link #applyMode} 的取值：渲染结果直接落盘，不建 Helm release */
    public static final String APPLY_SERVER_SIDE = "SERVER_SIDE_APPLY";
}
