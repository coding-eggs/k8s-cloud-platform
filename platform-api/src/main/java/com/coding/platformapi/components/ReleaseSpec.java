package com.coding.platformapi.components;

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
 */
public record ReleaseSpec(String releaseName, String namespace, String chartCode, String chartVersion,
                          Map<String, Object> contractValues, Map<String, Object> editableValues,
                          boolean preserveUnknownPlaceholders) {

    public ReleaseSpec(String releaseName, String namespace, String chartCode, String chartVersion,
                       Map<String, Object> contractValues, Map<String, Object> editableValues) {
        this(releaseName, namespace, chartCode, chartVersion, contractValues, editableValues, false);
    }
}
