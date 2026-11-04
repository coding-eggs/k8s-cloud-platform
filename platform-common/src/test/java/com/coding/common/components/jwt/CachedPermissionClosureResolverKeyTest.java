package com.coding.common.components.jwt;

import com.coding.common.components.cache.RedisJsonCache;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.when;

/**
 * 缓存键是 platform-api 与 platform-gateway 之间的**契约**：两边各自注册一份解析器、共用同一个 Redis，
 * 键形状一变，两边就各算各的（改角色后网关与 api 看到的权限不一致）。故这里钉死完整字面量。
 */
class CachedPermissionClosureResolverKeyTest {

    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    PermissionClosureService closureService = mock(PermissionClosureService.class);
    CachedPermissionClosureResolver resolver = new CachedPermissionClosureResolver(
            closureService, new RedisJsonCache(redis, JsonMapper.builder().build()), Duration.ofSeconds(30));

    CachedPermissionClosureResolverKeyTest() {
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void key_literal_is_shared_contract() {
        when(closureService.permissions(anyString(), any())).thenReturn(List.of("a:b"));

        resolver.resolve("alice", "t1");
        resolver.resolve("alice", null);

        verify(valueOps).get("platform:cache:perm:closure:alice\u0000t1");
        verify(valueOps).get("platform:cache:perm:closure:alice\u0000");
        verify(valueOps).set("platform:cache:perm:closure:alice\u0000t1", "[\"a:b\"]", Duration.ofSeconds(30));
    }

    @Test
    void cached_value_is_json_string_array() {
        when(valueOps.get("platform:cache:perm:closure:alice\u0000t1")).thenReturn("[\"a:b\"]");

        assertThat(resolver.resolve("alice", "t1")).containsExactly("a:b");
    }

    @Test
    void loader_failure_is_not_cached() {
        when(closureService.permissions(anyString(), any())).thenThrow(new IllegalStateException("db down"));

        assertThat(resolver.resolve("alice", "t1")).isEmpty();
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }
}
