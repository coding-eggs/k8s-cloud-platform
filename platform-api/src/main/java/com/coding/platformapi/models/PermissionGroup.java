package com.coding.platformapi.models;

import java.util.List;

/**
 * 权限点目录中的一组：同一个权限码（code）覆盖的全部规则行。
 *
 * <p>勾选与授权都按 code 展开（ANY-of），所以「组」是人对权限的正确心智单位；
 * 规则行（{@link PermissionRuleView}）保留 id，编辑/删除仍落在行上。
 */
public record PermissionGroup(String code, String domain, List<PermissionRuleView> rules) {
}
