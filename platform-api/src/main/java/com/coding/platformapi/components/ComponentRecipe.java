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
 * @param tektonProfile     安装时是否让用户选 Tekton 的 profile（lite / basic / all，默认 all）。
 *                          值通过占位符 {@code ${tekton.profile}} 落进 TektonConfig。
 * @param otel              OTel 采集栈的表单开关；非 OTel 组件为 null。
 * @param seaweedStorage    对象存储（SeaweedFS）的安装参数：StorageClass + 三个组件的容量与副本数。
 *                          这些值落在**嵌套结构**里（{@code volume.dataDirs} 是 list），
 *                          editableValues 的「路径 → 标量」模型表达不了，故按表单参数单独传。
 */
public record ComponentRecipe(String profile, List<ReleaseSpec> releases, List<String> requires,
                              boolean platformAlerting, boolean thanosRemoteWrite, boolean thanosInstall,
                              boolean calicoNetwork, String thanosReceiveDefault, boolean tektonProfile,
                              OtelRecipe otel, boolean seaweedStorage) {

    /** 保留 10 参形态（不含 seaweedStorage）：现有调用点不必改动 */
    public ComponentRecipe(String profile, List<ReleaseSpec> releases, List<String> requires,
                           boolean platformAlerting, boolean thanosRemoteWrite, boolean thanosInstall,
                           boolean calicoNetwork, String thanosReceiveDefault, boolean tektonProfile,
                           OtelRecipe otel) {
        this(profile, releases, requires, platformAlerting, thanosRemoteWrite, thanosInstall,
                calicoNetwork, thanosReceiveDefault, tektonProfile, otel, false);
    }

    /** 默认既不联动告警、也不上报 Thanos 的简写 */
    public ComponentRecipe(String profile, List<ReleaseSpec> releases, List<String> requires) {
        this(profile, releases, requires, false, false, false, false, null, false, null);
    }

    public ComponentRecipe(String profile, List<ReleaseSpec> releases, List<String> requires,
                           boolean platformAlerting) {
        this(profile, releases, requires, platformAlerting, false, false, false, null, false, null);
    }

    public ComponentRecipe(String profile, List<ReleaseSpec> releases, List<String> requires,
                           boolean platformAlerting, boolean thanosRemoteWrite) {
        this(profile, releases, requires, platformAlerting, thanosRemoteWrite, false, false, null, false, null);
    }

    public ComponentRecipe(String profile, List<ReleaseSpec> releases, List<String> requires,
                           boolean platformAlerting, boolean thanosRemoteWrite, boolean thanosInstall) {
        this(profile, releases, requires, platformAlerting, thanosRemoteWrite, thanosInstall, false, null, false,
                null);
    }

    public ComponentRecipe(String profile, List<ReleaseSpec> releases, List<String> requires,
                           boolean platformAlerting, boolean thanosRemoteWrite, boolean thanosInstall,
                           boolean calicoNetwork) {
        this(profile, releases, requires, platformAlerting, thanosRemoteWrite, thanosInstall, calicoNetwork, null,
                false, null);
    }

    public ComponentRecipe(String profile, List<ReleaseSpec> releases, List<String> requires,
                           boolean platformAlerting, boolean thanosRemoteWrite, boolean thanosInstall,
                           boolean calicoNetwork, String thanosReceiveDefault) {
        this(profile, releases, requires, platformAlerting, thanosRemoteWrite, thanosInstall, calicoNetwork,
                thanosReceiveDefault, false, null);
    }

    /** 保留 9 参形态（不含 otel）：现有调用点不必改动 */
    public ComponentRecipe(String profile, List<ReleaseSpec> releases, List<String> requires,
                           boolean platformAlerting, boolean thanosRemoteWrite, boolean thanosInstall,
                           boolean calicoNetwork, String thanosReceiveDefault, boolean tektonProfile) {
        this(profile, releases, requires, platformAlerting, thanosRemoteWrite, thanosInstall, calicoNetwork,
                thanosReceiveDefault, tektonProfile, null);
    }
}
