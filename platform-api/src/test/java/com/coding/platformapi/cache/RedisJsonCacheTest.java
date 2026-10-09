package com.coding.platformapi.cache;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 共享缓存：命中/未命中/降级三条路径。
 *
 * <p>最要紧的是**降级**：Redis 是优化不是依赖，读失败必须退回"直接查源"且不写，
 * 否则 Redis 一挂就变成接口不可用。
 */
class RedisJsonCacheTest {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    RedisJsonCache cache = new RedisJsonCache(redis, JsonMapper.builder().build());

    RedisJsonCacheTest() {
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void hit_returns_cached_value_without_calling_loader() {
        when(valueOps.get("platform:cache:k1")).thenReturn("[\"tenant:workload:list\"]");
        var calls = new AtomicInteger();

        List<String> v = cache.get("k1", Duration.ofSeconds(30), STRING_LIST, () -> {
            calls.incrementAndGet();
            return List.of("ignored");
        });

        assertThat(v).containsExactly("tenant:workload:list");
        assertThat(calls.get()).isZero();
    }

    @Test
    void miss_loads_and_writes_with_ttl() {
        when(valueOps.get("platform:cache:k1")).thenReturn(null);

        List<String> v = cache.get("k1", Duration.ofSeconds(30), STRING_LIST, () -> List.of("a", "b"));

        assertThat(v).containsExactly("a", "b");
        verify(valueOps).set(eq("platform:cache:k1"), eq("[\"a\",\"b\"]"), eq(Duration.ofSeconds(30)));
    }

    /** Redis 读失败 → 直查源，本次结果照样正确（且不该再去写）。 */
    @Test
    void read_failure_degrades_to_loader() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("connection refused"));

        List<String> v = cache.get("k1", Duration.ofSeconds(30), STRING_LIST, () -> List.of("a"));

        assertThat(v).containsExactly("a");
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    /** 脏值（DTO 改结构/手工塞的垃圾）：当未命中处理，不能让反序列化异常掀翻请求。 */
    @Test
    void corrupt_value_degrades_to_loader() {
        when(valueOps.get("platform:cache:k1")).thenReturn("{not json");

        List<String> v = cache.get("k1", Duration.ofSeconds(30), STRING_LIST, () -> List.of("a"));

        assertThat(v).containsExactly("a");
    }

    /** 失败/降级（loader 返回 null）不上缓存：否则一次抖动会被钉死一个 TTL。 */
    @Test
    void null_result_is_not_cached() {
        when(valueOps.get("platform:cache:k1")).thenReturn(null);

        assertThat(cache.get("k1", Duration.ofSeconds(30), STRING_LIST, () -> null)).isNull();
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    /** 写失败不影响本次结果。 */
    @Test
    void write_failure_does_not_break_the_call() {
        when(valueOps.get("platform:cache:k1")).thenReturn(null);
        org.mockito.Mockito.doThrow(new RuntimeException("readonly replica"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        assertThat(cache.get("k1", Duration.ofSeconds(30), STRING_LIST, () -> List.of("a")))
                .containsExactly("a");
    }
}
