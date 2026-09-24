package com.coding.platformapi.security;

import com.coding.data.mapper.auth.PlatformPermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 启动时从 platform_permission 表加载 API 域权限点，构建 {@link PermissionRegistry}。
 * 表的列映射：resource → URL pattern，action → HTTP method，code → 权限 code。
 */
@Configuration
@RequiredArgsConstructor
public class PermissionRegistryFactory {

    private final PlatformPermissionMapper permissionMapper;

    @Bean
    public PermissionRegistry permissionRegistry() {
        List<PermissionRegistry.Rule> rules = permissionMapper.selectAllActive().stream()
                .filter(p -> "API".equals(p.getDomain()))
                .map(p -> new PermissionRegistry.Rule(p.getAction(), p.getResource(), p.getCode()))
                .toList();
        return new PermissionRegistry(rules);
    }
}
