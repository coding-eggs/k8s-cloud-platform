package com.coding.gateway.security;

import com.coding.common.components.permission.PermissionRegistry;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

/**
 * 权限表加载与定时重载（spec §8.4）。
 *
 * <p>platform-api 的权限点管理页改完行只热换 api 自己那份表，够不着网关；网关改为定时重读。
 * <ul>
 *   <li>启动时同步加载一次，失败则启动失败：空表等于全站 403，不能带病起；</li>
 *   <li>之后每 {@code interval} 重读，失败<b>保留旧表</b>并 ERROR（清空同样是全站 403）。</li>
 * </ul>
 * 装全部 API 域行（各服务都在），service 由路由区分。角色/用户分配的变化不走这里，那走闭包缓存的 TTL。
 */
public class PermissionRegistryReloader implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PermissionRegistryReloader.class);

    private final PlatformPermissionMapper mapper;
    private final PermissionRegistry registry;
    private final Duration interval;
    private Disposable periodic;

    /** 只加载、不定时 */
    public PermissionRegistryReloader(PlatformPermissionMapper mapper, PermissionRegistry registry) {
        this(mapper, registry, null);
    }

    public PermissionRegistryReloader(PlatformPermissionMapper mapper, PermissionRegistry registry, Duration interval) {
        this.mapper = mapper;
        this.registry = registry;
        this.interval = interval;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            registry.replaceRules(load());
        } catch (RuntimeException e) {
            throw new IllegalStateException("[RBAC] 网关启动时加载权限表失败，拒绝启动（空表 = 全站 403）", e);
        }
        log.info("[RBAC] 网关权限表已加载：{} 条 API 规则", registry.rules().size());
        if (interval != null) {
            periodic = Flux.interval(interval, interval, Schedulers.boundedElastic())
                    .subscribe(tick -> reload());
        }
    }

    /** 重读一次；失败保留旧表。 */
    public void reload() {
        try {
            List<PermissionRegistry.Rule> rules = load();
            registry.replaceRules(rules);
            log.debug("[RBAC] 网关权限表已重载：{} 条", rules.size());
        } catch (RuntimeException e) {
            log.error("[RBAC] 网关权限表重载失败，继续使用上一份（{} 条）", registry.rules().size(), e);
        }
    }

    private List<PermissionRegistry.Rule> load() {
        return mapper.selectAllActive().stream()
                .filter(p -> "API".equals(p.getDomain()))
                .map(PermissionRegistry.Rule::from)
                .toList();
    }

    @Override
    public void destroy() {
        if (periodic != null) {
            periodic.dispose();
        }
    }
}
