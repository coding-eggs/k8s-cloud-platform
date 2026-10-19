package com.coding.platformapi.services;

import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformUserTenantMapper;
import com.coding.data.mapper.auth.PlatformUserRoleMapper;
import com.coding.data.mapper.auth.PlatformUserTenantRoleMapper;
import com.coding.data.models.auth.PlatformUser;
import com.coding.data.models.auth.PlatformUserTenant;
import com.coding.platformapi.models.NotificationUserView;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 规则编辑页的收件人候选 + 投递引擎的「id → 邮箱」解析。 */
@Service
public class NotificationUserService {

    /**
     * 内置租户管理员角色的 **code**（不是 id；id 是 {@code builtin_role_tenant_admin}）。
     * ⚠️ 平台管理员角色的 code 是 {@code admin}，别混 —— 用错会让默认收件人恒为空。
     */
    private static final String TENANT_ADMIN_ROLE_CODE = "tenant-admin";

    /** 平台管理员角色 code（在 platform_user_role 里，没有租户维度） */
    private static final String PLATFORM_ADMIN_ROLE_CODE = "admin";

    private final PlatformUserTenantMapper members;
    private final PlatformUserTenantRoleMapper roles;
    private final PlatformUserRoleMapper platformRoles;
    private final PlatformUserMapper users;

    public NotificationUserService(PlatformUserTenantMapper members,
                                   PlatformUserTenantRoleMapper roles,
                                   PlatformUserRoleMapper platformRoles,
                                   PlatformUserMapper users) {
        this.members = members;
        this.roles = roles;
        this.platformRoles = platformRoles;
        this.users = users;
    }

    public List<NotificationUserView> listByTenant(String tenantId) {
        List<String> ids = members.selectByTenantId(tenantId).stream()
                .map(PlatformUserTenant::getUserId).distinct().toList();
        if (ids.isEmpty()) return List.of();
        Map<String, PlatformUser> byId = users.selectByIds(ids).stream()
                .collect(Collectors.toMap(PlatformUser::getId, u -> u, (a, b) -> a));
        return ids.stream().filter(byId::containsKey)
                .map(id -> new NotificationUserView(id,
                        byId.get(id).getDisplayName(), byId.get(id).getEmail()))
                .toList();
    }

    /** 该租户的管理员用户 id（规则没配收件人时的默认收件人）。 */
    public List<String> tenantAdminUserIds(String tenantId) {
        return roles.selectUserIdsByTenantAndRoleCode(tenantId, TENANT_ADMIN_ROLE_CODE);
    }

    /**
     * 平台管理员用户 id —— 告警默认收件人的**最后一级兜底**：
     * 租户管理员不存在、都没填邮箱、或命名空间根本不属于任何租户时，至少让平台管理员收到。
     */
    public List<String> platformAdminUserIds() {
        return platformRoles.selectUserIdsByRoleCode(PLATFORM_ADMIN_ROLE_CODE);
    }

    /** 投递用：id → 邮箱，**过滤空邮箱**并去重保序。 */
    public List<String> emailsOf(Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) return List.of();
        return users.selectByIds(List.copyOf(userIds)).stream()
                .map(PlatformUser::getEmail)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }
}
