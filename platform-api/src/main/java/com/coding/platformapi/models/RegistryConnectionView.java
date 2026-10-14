package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 浏览页「连接下拉」的窄投影 —— **只有四个字段**。
 *
 * <p>设计见 spec §5.1。不复用 S0.2 的 {@code /credential/list}：那个要
 * {@code platform:credential:read}（只绑 admin），租户一进页面就 403。
 * 这正是 {@code frontend-permission-conventions.md} §5「页面需要什么数据，就为那份数据开一个
 * 权限码匹配的端点」的教科书情形。
 */
@Schema(description = "可浏览的镜像仓库连接（窄投影）")
public record RegistryConnectionView(String id, String name, String type, String endpoint) {
}
