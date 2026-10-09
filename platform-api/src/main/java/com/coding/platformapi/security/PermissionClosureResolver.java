package com.coding.platformapi.security;

import com.coding.common.components.jwt.JwtPermissionResolver;
import com.coding.common.components.jwt.PermissionClosureService;
import com.coding.platformapi.cache.RedisJsonCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.List;

/**
 * platform-api 的权限闭包取数：语义照抄 {@link PermissionClosureService}（与签发期同一实现，
 * 不复制一行判断），外面套一层 <b>Redis 共享</b>的 TTL 缓存。
 *
 * <p><b>为什么是 Redis 而不是进程内缓存</b>：多实例时本地缓存会让同一用户在两个实例上拿到不同的权限集
 * （各实例 TTL 独立过期），那是**授权不一致**；共享一份后改角色只需等一个 TTL，全集群同时看到新值。
 *
 * <p><b>为什么不做手工失效</b>：失效点至少 10 个（RoleService 保存/删角色、UserService 授/收平台角色、
 * TenantMemberService 授/收/移除成员、PermissionService 增删改/重载），漏挂任何一个，该角色下所有人就
 * **永久**保留已撤销的权限。TTL 把这个上界钉死，而且比原先 token 里那份副本的 1 小时紧得多。
 *
 * <p>失败语义：Cache 读不到 Redis 就直查库（见 {@link RedisJsonCache}）；查库异常 → 返回空
 * （fail-closed）+ ERROR 日志，且**不缓存失败**，因此一次 DB 抖动不会把这个用户钉死一个 TTL。
 */
@Slf4j
@Component
public class PermissionClosureResolver implements JwtPermissionResolver {

    /** 逻辑键前缀（RedisJsonCache 还会再加全局前缀） */
    private static final String KEY_PREFIX = "perm:closure:";

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final PermissionClosureService closureService;
    private final RedisJsonCache cache;
    private final Duration ttl;

    public PermissionClosureResolver(PermissionClosureService closureService,
                                     RedisJsonCache cache,
                                     @Value("${platform.permissions.cache-ttl-seconds:30}") long ttlSeconds) {
        this.closureService = closureService;
        this.cache = cache;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    @Override
    public List<String> resolve(String username, String tenantId) {
        if (!StringUtils.hasText(username)) {
            return List.of();
        }
        // 平台视图（tenantId 空）与各租户上下文是**不同的闭包**，键必须含租户，否则切租户会串号
        String key = KEY_PREFIX + username + '\u0000' + (tenantId == null ? "" : tenantId);
        try {
            List<String> codes = cache.get(key, ttl, STRING_LIST,
                    () -> closureService.permissions(username, tenantId));
            return codes == null ? List.of() : codes;
        } catch (Exception e) {
            log.error("权限闭包解析失败，按无权限码处理（fail-closed）: user={}, tenant={}", username, tenantId, e);
            return List.of();
        }
    }
}
