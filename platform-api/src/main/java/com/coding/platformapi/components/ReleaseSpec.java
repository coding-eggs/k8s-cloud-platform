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
 */
public record ReleaseSpec(String releaseName, String namespace, String chartCode, String chartVersion,
                          Map<String, Object> contractValues, Map<String, Object> editableValues) {
}
