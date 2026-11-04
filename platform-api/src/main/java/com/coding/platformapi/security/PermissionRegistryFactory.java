package com.coding.platformapi.security;

import com.coding.common.components.permission.PermissionRegistry;
import com.coding.common.components.permission.PlatformServices;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 启动时从 platform_permission 表加载 API 域、**service=platform-api** 的权限点，构建 {@link PermissionRegistry}。
 * 只装本服务的行：别的服务（auth 的 /internal/**）的行若混进来，交叉校验会把它们当幽灵行。
 * 表的列映射：resource → URL pattern，action → HTTP method，code → 权限 code。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class PermissionRegistryFactory {

    private final PlatformPermissionMapper permissionMapper;

    @Bean
    public PermissionRegistry permissionRegistry() {
        List<PermissionRegistry.Rule> rules = permissionMapper.selectAllActive().stream()
                .filter(p -> "API".equals(p.getDomain()))
                .map(PermissionRegistry.Rule::from)
                .filter(r -> PlatformServices.API.equals(r.service()))
                .toList();
        log.info("PermissionRegistry loaded {} API rules", rules.size());
        return new PermissionRegistry(rules);
    }
}
