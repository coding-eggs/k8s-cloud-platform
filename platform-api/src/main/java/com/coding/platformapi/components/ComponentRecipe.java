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
 * @param thanosInstall     是否同时安装 Thanos（管理集群，表单里要填 MinIO/凭证 Secret）
 * @param calicoNetwork     安装时是否要填 Pod CIDR 与封装方式（calico）
 * @param thanosReceiveDefault 该画像下 Thanos Receive 地址的**预填默认值**（可空）。
 *                         管理集群用集群内 receive 地址（同集群 Service 名），业务集群为空、
 *                         由平台配置 {@code thanos.receive.url} 预填。表单里始终可改。
 */
public record ComponentRecipe(String profile, List<ReleaseSpec> releases, List<String> requires,
                              boolean platformAlerting, boolean thanosRemoteWrite, boolean thanosInstall,
                              boolean calicoNetwork, String thanosReceiveDefault) {

    /** 默认既不联动告警、也不上报 Thanos 的简写 */
    public ComponentRecipe(String profile, List<ReleaseSpec> releases, List<String> requires) {
        this(profile, releases, requires, false, false, false, false, null);
    }

    public ComponentRecipe(String profile, List<ReleaseSpec> releases, List<String> requires,
                           boolean platformAlerting) {
        this(profile, releases, requires, platformAlerting, false, false, false, null);
    }

    public ComponentRecipe(String profile, List<ReleaseSpec> releases, List<String> requires,
                           boolean platformAlerting, boolean thanosRemoteWrite) {
        this(profile, releases, requires, platformAlerting, thanosRemoteWrite, false, false, null);
    }

    public ComponentRecipe(String profile, List<ReleaseSpec> releases, List<String> requires,
                           boolean platformAlerting, boolean thanosRemoteWrite, boolean thanosInstall) {
        this(profile, releases, requires, platformAlerting, thanosRemoteWrite, thanosInstall, false, null);
    }

    public ComponentRecipe(String profile, List<ReleaseSpec> releases, List<String> requires,
                           boolean platformAlerting, boolean thanosRemoteWrite, boolean thanosInstall,
                           boolean calicoNetwork) {
        this(profile, releases, requires, platformAlerting, thanosRemoteWrite, thanosInstall, calicoNetwork, null);
    }
}
