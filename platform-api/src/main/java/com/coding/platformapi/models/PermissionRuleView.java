package com.coding.platformapi.models;

/**
 * 权限点目录中的一条规则行（行级投影）。
 *
 * <p>带 {@code id} 是为了让前端在分组视图里仍能编辑/删除具体那一行；
 * {@code description} 是行级字段，同 code 的多行各写各的。
 */
public record PermissionRuleView(String id, String resource, String action, String description) {
}
