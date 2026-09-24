package com.coding.auth.service;

import com.coding.common.components.jwt.PermissionClosure;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformUserTenantRoleMapper;
import com.coding.data.mapper.auth.PlatformUserRoleMapper;
import com.coding.data.models.system.UserTenantInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 签发 JWT 时的附加数据查询（平台角色 code / 租户上下文 / 权限闭包）。
 *
 * <p>集中封装查库逻辑，避免把多个 mapper 塞进 token customizer。
 */
@Service
@RequiredArgsConstructor
public class TokenExtrasService {
    private final PlatformUserRoleMapper userRoleMapper;
    private final PlatformPermissionMapper permissionMapper;
    private final PlatformUserTenantRoleMapper userTenantRoleMapper;
    private final PlatformTenantMapper tenantMapper;

    /** 平台域角色 code（所有 grant 都写入，管理端据此校验 PLATFORM:admin） */
    public List<String> platformRoleCodes(String userId) {
        return userRoleMapper.selectRoleCodesByUser(userId);
    }

    /** 租户上下文：校验成员资格后返回 UserTenantInfo，返回 null 表示用户不属于该租户（调用方处理） */
    @Nullable
    public UserTenantInfo resolveTenant(String username, String tenantId) {
        if (!StringUtils.hasText(tenantId)) return null;
        return tenantMapper.selectTenantByUser(username, tenantId);
    }

    /** 权限闭包 = 平台族角色的权限 ∪（tenantId 非空时）该租户内角色的权限。
     *  注意 selectPermissionCodesByRoleIds 对空 IN() 非法，调用方须先判空。 */
    public List<String> permissions(String userId, @Nullable String tenantId) {
        List<String> platformPerms = codesOf(userRoleMapper.selectRoleIdsByUser(userId));
        List<String> tenantPerms = StringUtils.hasText(tenantId)
                ? codesOf(userTenantRoleMapper.selectRoleIdsByUserAndTenant(userId, tenantId))
                : List.of();
        return PermissionClosure.merge(platformPerms, tenantPerms);
    }

    private List<String> codesOf(List<String> roleIds) {
        return roleIds.isEmpty() ? List.of() : permissionMapper.selectPermissionCodesByRoleIds(roleIds);
    }
}
