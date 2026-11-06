package com.coding.platformapi.services.overview;

import com.coding.common.components.jwt.JwtPermissionResolver;
import com.coding.common.security.AuthContext;
import com.coding.data.models.system.TokenUserInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 概览摘要的逐块权限判定：每个计数块按它<b>原有列表页</b>的读权限码放行（与 {@code CurrentUserQuery#me} 同一闭包），
 * 没权限的块返回 null —— 摘要端点自己的权限码只决定"能不能看这一页"，不放大到各业务数据。
 */
@Component
@RequiredArgsConstructor
public class OverviewPermissions {

    public static final String APPLICATION_READ = "tenant:application:read";
    public static final String MIDDLEWARE_READ = "tenant:middleware:read";
    public static final String PIPELINE_READ = "tenant:pipeline:read";
    public static final String TENANT_ALERT_LIST = "tenant:alert:list";
    public static final String PLATFORM_ALERT_READ = "platform:alert:read";

    private final AuthContext auth;
    private final JwtPermissionResolver resolver;

    public boolean has(String code) {
        TokenUserInfo t = auth.current();
        if (t == null) {
            return false;
        }
        List<String> codes = resolver.resolve(t.getUsername(), auth.hatTenantId());
        return codes != null && codes.contains(code);
    }
}
