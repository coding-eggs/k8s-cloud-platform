package com.coding.platformapi.components;

import java.util.List;
import java.util.Map;

/**
 * 一个 release 的安装规格。
 *
 * @param releaseName    平台固定（用户不能改，否则探测找不到）
 * @param namespace      平台固定（与 S1.2 探测的命名空间一致）
 * @param chartCode      内置 chart 目录名
 * @param chartVersion   chart 版本（升级判定比的就是它）
 * @param contractValues 🔒 平台契约值（不可被用户覆盖）
 * @param editableValues ⚙️ 少量可编辑字段的默认值
 * @param preserveUnknownPlaceholders
 *        未知的 {@code ${...}} 是否原样保留（默认 false = 硬失败）。
 *        只有 Tempo 这种需要把凭证占位符留给运行时 `-config.expand-env` 展开的 release 才打开。
 *        已知占位符解析为空在两种模式下都仍然报错。
 * @param primary        组件本体（一个组件只有一个主 release）。{@code availableVersions} /
 *                       升级目标只看它 —— Istio 的 6 个 release 里主的是 istiod，不是排第一的 Gateway API。
 * @param appVersion     该 chart 的应用版本（Chart.yaml 的 appVersion）。纯展示用；与 chart 版本是两套编号
 *                       （kube-prometheus-stack 92.10.1 → v0.94.1、tempo 3.2.0 → 3.1.0）。
 *                       由 {@code ComponentRecipeCatalogTest} 与内置 chart 的 Chart.yaml 交叉校验。
 * @param applyMode      null = 普通 Helm release；{@link #APPLY_SERVER_SIDE} = 渲染后直接 server-side apply，
 *                       **不建 Helm release**。只给超大 chart 用：Helm 把整个 chart 存进 release Secret，
 *                       超过 1MiB 就装不了（Calico 的 CRD chart 3.5MB，2026-10-22 实测）。
 */
public record ReleaseSpec(String releaseName, String namespace, String chartCode, String chartVersion,
                          Map<String, Object> contractValues, Map<String, Object> editableValues,
                          boolean preserveUnknownPlaceholders, boolean primary, String appVersion,
                          String applyMode) {

    /** 渲染结果直接用 server-side apply 落盘（不建 Helm release）；取值与 DTO 共用一份，避免两端漂移 */
    public static final String APPLY_SERVER_SIDE =
            com.coding.common.models.k8s.dto.ReleaseInstallSpecDTO.APPLY_SERVER_SIDE;

    public ReleaseSpec(String releaseName, String namespace, String chartCode, String chartVersion,
                       Map<String, Object> contractValues, Map<String, Object> editableValues) {
        this(releaseName, namespace, chartCode, chartVersion, contractValues, editableValues,
                false, false, null, null);
    }

    public ReleaseSpec(String releaseName, String namespace, String chartCode, String chartVersion,
                       Map<String, Object> contractValues, Map<String, Object> editableValues,
                       boolean preserveUnknownPlaceholders) {
        this(releaseName, namespace, chartCode, chartVersion, contractValues, editableValues,
                preserveUnknownPlaceholders, false, null, null);
    }

    /** 标记为组件本体（版本列表与升级目标只认它） */
    public ReleaseSpec asPrimary() {
        return new ReleaseSpec(releaseName, namespace, chartCode, chartVersion,
                contractValues, editableValues, preserveUnknownPlaceholders, true, appVersion, applyMode);
    }

    /** 补上 chart 的应用版本（仅展示） */
    public ReleaseSpec withAppVersion(String appVersion) {
        return new ReleaseSpec(releaseName, namespace, chartCode, chartVersion,
                contractValues, editableValues, preserveUnknownPlaceholders, primary, appVersion, applyMode);
    }

    /** 改安装方式（超大 chart 走 {@link #APPLY_SERVER_SIDE}） */
    public ReleaseSpec withApplyMode(String applyMode) {
        return new ReleaseSpec(releaseName, namespace, chartCode, chartVersion,
                contractValues, editableValues, preserveUnknownPlaceholders, primary, appVersion, applyMode);
    }

    /** 主 release 的 chart 版本；没有显式标记（老 recipe / 测试桩）时退回第一个 */
    public static String primaryChartVersion(List<ReleaseSpec> releases) {
        return releases.stream()
                .filter(ReleaseSpec::primary)
                .map(ReleaseSpec::chartVersion)
                .findFirst()
                .orElseGet(() -> releases.get(0).chartVersion());
    }
}
