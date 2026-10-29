package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 镜像仓库页左栏的一行：一个 {@code host[:port]}（内置档用 {@code builtin:dockerhub} 当 id）。
 *
 * <p>⚠️ 「仓库」不是一张表 —— 它是 Harbor 安装记录 ∪ 凭据行 ∪ 内置档的聚合视角
 * （spec §5）。所以本类没有对应的 mapper。
 */
@Schema(description = "镜像仓库（按 host 聚合的只读视图）")
public record RegistryWarehouseView(
        @Schema(description = "内置档为 builtin:dockerhub，其余为 host[:port]") String id,
        String host,
        @Schema(description = "HARBOR / DOCKER") String kind,
        @Schema(description = "INSTALLED（组件安装记录）/ CREDENTIAL（凭据行）/ BUILTIN（内置档）")
        String origin,
        String displayName,
        @Schema(description = "仅 INSTALLED 有值") String clusterId,
        @Schema(description = "仅 INSTALLED 有值") String exposeMode,
        int connectionCount,
        int projectCount,
        @Schema(description = "OK / ERROR") String health,
        String message) {
}
