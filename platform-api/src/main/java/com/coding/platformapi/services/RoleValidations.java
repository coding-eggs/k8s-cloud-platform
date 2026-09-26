package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.auth.PlatformRoleMapper;
import com.coding.data.models.auth.PlatformRole;
import com.coding.data.models.auth.RoleScope;

/**
 * 角色授予前的统一校验（spec §3 scope 不变量）：角色须存在（软删过滤）、族匹配、且启用。
 * <p>
 * {@link TenantMemberService}（授 TENANT 族）与 {@link UserService}（授 PLATFORM 族）共用，
 * 保证两侧口径对称：否则被授错族的行会进入 claim 却不进入闭包（或反之）。
 * {@code selectByPrimaryKey} 已 {@code deleted_at is null} 过滤，返回 null 即视为不存在。
 */
final class RoleValidations {

    private RoleValidations() {
    }

    static void requireRoleOfScope(PlatformRoleMapper roleMapper, String roleId, RoleScope expected) {
        PlatformRole role = roleMapper.selectByPrimaryKey(roleId);
        if (role == null) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "角色不存在：" + roleId);
        }
        if (!expected.name().equals(role.getScope())) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "只能授予" + (expected == RoleScope.TENANT ? "租户" : "平台") + "域（" + expected.name() + "）角色");
        }
        if (role.getStatus() == null || role.getStatus() != 1) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "角色已停用：" + roleId);
        }
    }
}
