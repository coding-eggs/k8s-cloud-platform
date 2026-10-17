package com.coding.platformapi.components;

import java.util.List;

/**
 * 某画像下一条组件的安装方式。
 *
 * @param profile          MANAGEMENT / BUSINESS
 * @param releases         该画像要装的 release（通常 1 个）
 * @param requires         前置组件 code（安装前必须已 INSTALLED_HEALTHY）
 * @param platformAlerting 安装/升级成功后是否下发平台告警联动对象
 *                         （Secret {@code platform-alert-token} + AlertmanagerConfig {@code platform-webhook}）
 * @param thanosRemoteWrite 是否引用 {@code ${thanos.receive.url}}（表单里要填 Thanos Receive 地址）
 */
public record ComponentRecipe(String profile, List<ReleaseSpec> releases, List<String> requires,
                              boolean platformAlerting, boolean thanosRemoteWrite) {

    /** 默认既不联动告警、也不上报 Thanos 的简写 */
    public ComponentRecipe(String profile, List<ReleaseSpec> releases, List<String> requires) {
        this(profile, releases, requires, false, false);
    }

    public ComponentRecipe(String profile, List<ReleaseSpec> releases, List<String> requires,
                           boolean platformAlerting) {
        this(profile, releases, requires, platformAlerting, false);
    }
}
