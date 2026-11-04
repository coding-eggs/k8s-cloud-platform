package com.coding.gateway.security;

import com.coding.common.components.permission.PermissionRegistry;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.gateway.route.PlatformRouteProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@MapperScan({"com.coding.data.mapper.auth", "com.coding.data.mapper.system"})
public class PermissionTableConfig {

    @Bean
    public PermissionRegistry permissionRegistry() {
        return new PermissionRegistry(List.of()); // 由 PermissionRegistryReloader 启动时填满
    }

    @Bean
    public PermissionRegistryReloader permissionRegistryReloader(PlatformPermissionMapper mapper,
                                                                 PermissionRegistry registry,
                                                                 PlatformRouteProperties props) {
        return new PermissionRegistryReloader(mapper, registry, props.permissionReloadInterval());
    }
}
