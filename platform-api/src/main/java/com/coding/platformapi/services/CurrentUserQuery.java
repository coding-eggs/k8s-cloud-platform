package com.coding.platformapi.services;

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
 */
@Service
@RequiredArgsConstructor
public class CurrentUserQuery {

    private final AuthContext auth;
    private final PlatformUserMapper userMapper;
    private final PlatformTenantMapper tenantMapper;

    /**
     * 当前 token 的用户信息（username/displayName/email/status/tenantInfo/platformRoles/permissions）。
     * 返回给自己的属主，permissions 即服务端可信解出的权限闭包；TokenUserInfo 本身无密码等敏感字段。
     */
    public TokenUserInfo me() {
        TokenUserInfo t = auth.current();
        if (t == null) {
            throw new CloudPlatformException(EnumResponseType.USER_UN_LOGIN);
        }
        return t;
    }

    /** 当前用户所属的未删除租户列表（前端租户切换器）。 */
    public List<PlatformTenant> myTenants() {
        TokenUserInfo t = me();
        PlatformUser u = userMapper.selectByUsername(t.getUsername());
        if (u == null) {
            throw new CloudPlatformException(EnumResponseType.USER_UN_LOGIN);
        }
        return tenantMapper.listTenantsByUser(u.getId());
    }
}
