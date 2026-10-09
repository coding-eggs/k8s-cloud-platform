package com.coding.platformapi.security;

import com.coding.common.components.jwt.PermissionClosureService;
import com.coding.platformapi.cache.RedisJsonCache;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 权限闭包解析（platform-api 侧）：**键必须含租户**（否则切租户串号），失败必须 fail-closed。
 *
 * <p>缓存本身的命中/降级语义在 {@link com.coding.platformapi.cache.RedisJsonCacheTest} 里测，
 * 这里用**真实的** RedisJsonCache（Redis 客户端是 mock）跑穿透，好让 loader 真的被执行到。
 */
class PermissionClosureResolverTest {

    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    PermissionClosureService closureService = mock(PermissionClosureService.class);

    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    RedisJsonCache cache = new RedisJsonCache(redis, JsonMapper.builder().build());
    PermissionClosureResolver resolver = new PermissionClosureResolver(closureService, cache, 30);

    PermissionClosureResolverTest() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null); // 默认未命中 → 走 loader
    }

    /** 平台视图与各租户上下文是不同闭包：键必须区分，别让切租户拿到上一个租户的权限。 */
    @Test
    void cache_key_distinguishes_tenant_and_platform_view() {
        when(closureService.permissions("alice", null)).thenReturn(List.of("platform:role:read"));
        when(closureService.permissions("alice", "t1")).thenReturn(List.of("tenant:workload:list"));

        assertThat(resolver.resolve("alice", null)).containsExactly("platform:role:read");
        assertThat(resolver.resolve("alice", "t1")).containsExactly("tenant:workload:list");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(valueOps, org.mockito.Mockito.times(2)).set(keys.capture(), anyString(), any(Duration.class));
        assertThat(keys.getAllValues()).doesNotHaveDuplicates();
        assertThat(keys.getAllValues().get(1)).contains("alice");
        verify(closureService).permissions("alice", null);
        verify(closureService).permissions("alice", "t1");
    }

    /** 命中缓存：不再查库。 */
    @Test
    void hit_skips_db() {
        when(valueOps.get(anyString())).thenReturn("[\"tenant:workload:list\"]");

        assertThat(resolver.resolve("alice", "t1")).containsExactly("tenant:workload:list");
        verifyNoInteractions(closureService);
    }

    /** 查库抛异常 → 空（fail-closed），不向上抛（否则一次 DB 抖动会让整条认证链失败）。 */
    @Test
    void db_failure_is_fail_closed() {
        when(closureService.permissions(anyString(), any())).thenThrow(new IllegalStateException("db down"));

        assertThat(resolver.resolve("alice", "t1")).isEmpty();
    }

    /** 空白用户名不查库也不碰缓存（脏 token 不该触发任何查询）。 */
    @Test
    void blank_username_short_circuits() {
        assertThat(resolver.resolve("  ", "t1")).isEmpty();
        assertThat(resolver.resolve(null, null)).isEmpty();

        verifyNoInteractions(closureService);
        verify(valueOps, never()).get(anyString());
    }
}
