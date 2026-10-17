package com.coding.platformapi.components;

import java.util.List;

/**
 * 某画像下一条组件的安装方式。
 *
 * @param profile   MANAGEMENT / BUSINESS
 * @param releases  该画像要装的 release（通常 1 个）
 * @param requires  前置组件 code（安装前必须已 INSTALLED_HEALTHY）
 */
public record ComponentRecipe(String profile, List<ReleaseSpec> releases, List<String> requires) {
}
