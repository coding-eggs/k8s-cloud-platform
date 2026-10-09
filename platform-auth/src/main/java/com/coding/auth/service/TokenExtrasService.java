package com.coding.auth.service;

import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformUserRoleMapper;
import com.coding.data.models.system.UserTenantInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 签发 JWT 时的附加数据查询（平台角色 code / 租户上下文）。
 *
 * <p>集中封装查库逻辑，避免把多个 mapper 塞进 token customizer。
 *
 * <p><b>权限闭包不在此列</b>（2026-10-09 起）：token 不再携带 {@code data.permissions} —— 那份全量码表
 * 与产品权限点总数同阶增长，却要塞进有硬上限的容器（WS 握手 query 4KB / HTTP 头 8KB），还带 1 小时
 * 保鲜期。改为由资源服务器按需解析，唯一实现是
 * {@code com.coding.common.components.jwt.PermissionClosureService}；仅当回滚开关
 * {@code platform.jwt.permissions-in-token=true} 时，token customizer 才会再往 claim 里写一份。
 */
@Service
@RequiredArgsConstructor
public class TokenExtrasService {
    private final PlatformUserRoleMapper userRoleMapper;
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
}
