package com.coding.auth.service;

import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformUserRoleMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformUser;
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
    private final PlatformUserMapper userMapper;

    /** 平台域角色 code（所有 grant 都写入，管理端据此校验 PLATFORM:admin） */
    public List<String> platformRoleCodes(String userId) {
        return userRoleMapper.selectRoleCodesByUser(userId);
    }

    /**
     * 租户上下文：返回 {@link UserTenantInfo}，{@code null} 表示不能进入该租户（调用方按 invalid_grant 处理）。
     *
     * <p>两条路：
     * <ol>
     *   <li><b>成员</b> —— 原路，查 {@code platform_user_tenant}；</li>
     *   <li><b>平台侧身份</b> —— 不靠成员关系（spec 2026-10-31 §3.2）：管理员能切进任何**启用**租户的视图。
     *       否则"平台代管"就得先把管理员加成该租户的成员，那会把成员表变成切换器开关。</li>
     * </ol>
     *
     * <p>⚠️ 与 platform-api 的 {@code CurrentUserQuery.myTenants()} 是同一套判据的两半：那边列哪些，
     * 这边能不能真切。改一处必须改另一处，否则切换器里点得动、切过去 400。
     */
    @Nullable
    public UserTenantInfo resolveTenant(String username, String tenantId) {
        if (!StringUtils.hasText(tenantId)) return null;

        // 成员优先：绝大多数调用是普通租户用户，走原来那条（一次 join 查询）
        UserTenantInfo byMembership = tenantMapper.selectTenantByUser(username, tenantId);
        if (byMembership != null) return byMembership;

        // 平台侧身份兜底：角色非空 + 租户存在且启用
        PlatformUser user = userMapper.selectByUsername(username);
        if (user == null) return null;
        List<String> roles = platformRoleCodes(user.getId());
        if (roles == null || roles.isEmpty()) return null;

        PlatformTenant tenant = tenantMapper.selectByPrimaryKey(tenantId);
        if (tenant == null || tenant.getDeletedAt() != null) return null;
        if (tenant.getStatus() == null || tenant.getStatus() != 1) return null;

        UserTenantInfo info = new UserTenantInfo();
        info.setUserId(user.getId());
        info.setTenantId(tenant.getId());
        info.setTenantName(tenant.getName());
        info.setServiceAccount(tenant.getServiceAccount());
        return info;
    }
}
