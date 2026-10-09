package com.coding.platformapi.services;

import com.coding.common.components.jwt.JwtPermissionResolver;
import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformUser;
import com.coding.data.models.system.TokenUserInfo;
import com.coding.platformapi.security.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 当前登录用户上下文查询（/user/me、/user/my-tenants 的数据源）。
 * 两接口在 ExemptPaths（登录即、无权限点要求），故授权已由 PermissionAuthorizationManager
 * 保证 authenticated；此处仅把「token 无有效 data claim」防御性映射为未登录。
 *
 * <p><b>权限闭包现算</b>（2026-10-09）：token 自本批起不再携带 {@code data.permissions}
 * （全量码表塞进 token 的代价见 PermissionClosureResolver 的注释），所以 /user/me 不能再"回显 claim"，
 * 必须按当前角色实时算出来。用的是**同一个**带缓存的解析器（与授权路径共用一份缓存），
 * 否则会出现"菜单里有、点进去 403"的窗口。
 */
@Service
@RequiredArgsConstructor
public class CurrentUserQuery {

    private final AuthContext auth;
    private final PlatformUserMapper userMapper;
    private final PlatformTenantMapper tenantMapper;
    private final JwtPermissionResolver permissionResolver;

    /**
     * 当前用户信息（username/displayName/email/status/tenantInfo/platformRoles/permissions）。
     * 返回给自己的属主；TokenUserInfo 本身无密码等敏感字段。
     * permissions 是**此刻**的权限闭包（含缓存），可能比上一次请求新 —— 这正是本次改动的目的。
     */
    public TokenUserInfo me() {
        TokenUserInfo t = currentOrThrow();
        t.setPermissions(permissionResolver.resolve(t.getUsername(), hatTenantId(t)));
        return t;
    }

    /** 当前用户所属的未删除租户列表（前端租户切换器）。 */
    public List<PlatformTenant> myTenants() {
        TokenUserInfo t = currentOrThrow();
        PlatformUser u = userMapper.selectByUsername(t.getUsername());
        if (u == null) {
            throw new CloudPlatformException(EnumResponseType.USER_UN_LOGIN);
        }
        return tenantMapper.listTenantsByUser(u.getId());
    }

    /** 只做"有没有 claim"的判定，不触发权限解析（my-tenants 不需要闭包）。 */
    private TokenUserInfo currentOrThrow() {
        TokenUserInfo t = auth.current();
        if (t == null) {
            throw new CloudPlatformException(EnumResponseType.USER_UN_LOGIN);
        }
        return t;
    }

    /** token 帽（tenantInfo.tenantId）；平台视图的 base token → null（= 只算平台族） */
    private static String hatTenantId(TokenUserInfo t) {
        return t.getTenantInfo() != null ? t.getTenantInfo().getTenantId() : null;
    }
}
