package com.coding.gateway.security;

import com.coding.common.components.permission.PermissionRegistry;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

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
@Slf4j
public class PermissionRegistryReloader implements InitializingBean, DisposableBean {


    private final PlatformPermissionMapper mapper;
    private final PermissionRegistry registry;
    private final Duration interval;
    /** 网关路由覆盖的 service；null = 不校验 */
    private final Set<String> routedServices;
    /** 上次告警过的孤儿行，同一批不在每次定时重载里重复刷屏 */
    private volatile Set<String> lastUnrouted = Set.of();
    private Disposable periodic;

    /** 只加载、不定时 */
    public PermissionRegistryReloader(PlatformPermissionMapper mapper, PermissionRegistry registry) {
        this(mapper, registry, null);
    }

    public PermissionRegistryReloader(PlatformPermissionMapper mapper, PermissionRegistry registry, Duration interval) {
        this(mapper, registry, interval, null);
    }

    public PermissionRegistryReloader(PlatformPermissionMapper mapper, PermissionRegistry registry, Duration interval,
                                      Set<String> routedServices) {
        this.mapper = mapper;
        this.registry = registry;
        this.interval = interval;
        this.routedServices = routedServices == null ? null : Set.copyOf(routedServices);
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

    /**
     * 重读一次；失败保留旧表。LinkageError（如 NoClassDefFoundError）也在这里接住：Reactor 虽不会因它停掉定时器，
     * 但会把它当 jvm fatal 打成 scheduler「uncaught exception」，日志里看不出是权限表重载失败。
     */
    public void reload() {
        try {
            List<PermissionRegistry.Rule> rules = load();
            registry.replaceRules(rules);
            log.debug("[RBAC] 网关权限表已重载：{} 条", rules.size());
        } catch (RuntimeException | LinkageError e) {
            log.error("[RBAC] 网关权限表重载失败，继续使用上一份（{} 条）", registry.rules().size(), e);
        }
    }

    private List<PermissionRegistry.Rule> load() {
        List<PermissionRegistry.Rule> rules = mapper.selectAllActive().stream()
                .filter(p -> "API".equals(p.getDomain()))
                .map(PermissionRegistry.Rule::from)
                .toList();
        warnUnrouted(rules);
        return rules;
    }

    /**
     * service 对不上任何网关路由的行（多半是迁移里拼错）永远匹配不到请求、等于不存在。照装不拦（fail-closed，
     * 不会因此放行），但 WARN 出来，免得只能从「怎么都 403」倒查。spec §8.5。
     */
    private void warnUnrouted(List<PermissionRegistry.Rule> rules) {
        if (routedServices == null) {
            return;
        }
        Set<String> unrouted = rules.stream()
                .filter(r -> !routedServices.contains(r.service()))
                .map(r -> r.service() + " " + r.method() + " " + r.pattern() + " (" + r.code() + ")")
                .collect(Collectors.toCollection(TreeSet::new));
        if (!unrouted.isEmpty() && !unrouted.equals(lastUnrouted)) {
            log.warn("[RBAC] {} 条 API 权限行的 service 不在网关路由 {} 里，永远不会生效：{}",
                    unrouted.size(), new TreeSet<>(routedServices), unrouted);
        }
        lastUnrouted = unrouted;
    }

    @Override
    public void destroy() {
        if (periodic != null) {
            periodic.dispose();
        }
    }
}
