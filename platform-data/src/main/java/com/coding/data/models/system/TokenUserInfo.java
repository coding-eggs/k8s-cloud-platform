package com.coding.data.models.system;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class TokenUserInfo {

    private String username;

    private String displayName;

    private String email;
    /**
     * 1正常 0禁用
     */
    private Byte status;

    private UserTenantInfo tenantInfo;

    /**
     * 平台域角色 code 列表（如 admin），与租户域 tenantInfo 并列、互不推导
     */
    private List<String> platformRoles;

    /** 当前上下文权限点 code 闭包（平台族角色 + 若有租户上下文再并租户族角色）。
     *  ⚠️ 2026-10-09 起**不再写进 token**（太长且有保鲜期）—— token 里该字段为空，
     *  由 /user/me 现算填充（唯一算法见 com.coding.common.components.jwt.PermissionClosureService）。
     *  仅当回滚开关 platform.jwt.permissions-in-token=true 时才照旧签发。 */
    private java.util.List<String> permissions;

}
