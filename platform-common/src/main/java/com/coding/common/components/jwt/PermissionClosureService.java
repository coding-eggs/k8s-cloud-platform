package com.coding.common.components.jwt;

import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformUserRoleMapper;
import com.coding.data.mapper.auth.PlatformUserTenantRoleMapper;
import com.coding.data.models.auth.PlatformUser;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 权限闭包计算：<b>平台族角色的权限 code ∪（tenantId 非空时）该租户内角色的权限 code</b>。
 *
 * <p>这是"某个人此刻有哪些 code"的<b>唯一实现</b>，两个调用场景共用：
 * <ul>
 *   <li>签发期（platform-auth 的 token customizer，仅回滚开关打开时才用）；</li>
 *   <li>请求期（platform-api 的按需解析，见 {@link JwtPermissionResolver}）。</li>
 * </ul>
 * 分两处写必然漂移，而这条语义一旦漂移，后果是<b>静默过度授权</b>（把租户族的码算进无租户上下文的
 * 请求里）—— 所以宁可多一次查库，也不复制这段逻辑。
 *
 * <p>{@code tenantId} 为空是<b>正常态</b>（平台视图的 base token），此时只算平台族：
 * 平台族角色的码无条件生效，租户角色的码只在带租户上下文时并入。这是与
 * {@code TokenExtrasService} 时期逐字一致的语义，2026-10-09 从 token 里搬出来时未做任何改动。
 *
 * <p><b>刻意不打 {@code @Service}</b>：三个应用都扫 {@code com.coding.common}，打个注解就会让
 * k8s-server 凭空多出一个"能查业务权限表"的 bean —— 与它"零业务逻辑、只管边界"的定位相悖，
 * 而且将来有人注入一下就悄悄破了这条线。改由真正需要它的两个应用各自 {@code @Bean} 注册
 * （platform-auth 回滚开关用、platform-api 按需解析用）。
 */
@RequiredArgsConstructor
public class PermissionClosureService {

    private final PlatformUserMapper userMapper;
    private final PlatformUserRoleMapper userRoleMapper;
    private final PlatformUserTenantRoleMapper userTenantRoleMapper;
    private final PlatformPermissionMapper permissionMapper;

    /**
     * @param username token 主体（{@code data.username} / {@code sub}）
     * @param tenantId 当前租户上下文；null/空 = 平台视图，只算平台族
     * @return 权限 code 闭包（去重保序）；用户不存在（已删）→ 空闭包（fail-closed，不抛）
     */
    public List<String> permissions(String username, @Nullable String tenantId) {
        if (!StringUtils.hasText(username)) {
            return List.of();
        }
        PlatformUser user = userMapper.selectByUsername(username);
        if (user == null) {
            return List.of();
        }
        List<String> platformPerms = codesOf(userRoleMapper.selectRoleIdsByUser(user.getId()));
        List<String> tenantPerms = StringUtils.hasText(tenantId)
                ? codesOf(userTenantRoleMapper.selectRoleIdsByUserAndTenant(user.getId(), tenantId))
                : List.of();
        return PermissionClosure.merge(platformPerms, tenantPerms);
    }

    /** {@code selectPermissionCodesByRoleIds} 对空 IN() 非法，故先判空。 */
    private List<String> codesOf(List<String> roleIds) {
        return roleIds == null || roleIds.isEmpty() ? List.of()
                : permissionMapper.selectPermissionCodesByRoleIds(roleIds);
    }
}
