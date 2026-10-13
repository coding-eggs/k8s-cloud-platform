package com.coding.platformapi.cache;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.types.Expiration;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** 批量读（MGET）：只回命中项，未命中的键不出现在结果里。 */
    @Test
    void getMany_returns_only_hits_in_one_round_trip() {
        when(valueOps.multiGet(List.of("platform:cache:a", "platform:cache:b")))
                .thenReturn(Arrays.asList("[\"x\"]", null));

        Map<String, List<String>> got = cache.getMany(List.of("a", "b"), STRING_LIST);

        assertThat(got).containsOnlyKeys("a");
        assertThat(got.get("a")).containsExactly("x");
    }

    /** Redis 批量读失败 → 不抛，当作全未命中（调用方自行走采样器）。 */
    @Test
    void getMany_degrades_on_redis_failure() {
        when(valueOps.multiGet(any())).thenThrow(new RuntimeException("redis down"));

        assertThat(cache.getMany(List.of("a"), STRING_LIST)).isEmpty();   // 不抛：调用方当作全未命中
    }

    /**
     * setMany：<b>null 值不写</b>（一个键没取到不该拖累同批其它键、也绝不写 "null"）、且整批不抛。
     * 场景即 {@code NamespaceStatService.rows}：某个 ns 采样失败 → 值为 null，其余 ns 照常写。
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setMany_skips_null_values_and_writes_the_rest() {
        RedisConnection connection = mock(RedisConnection.class);
        RedisStringCommands commands = mock(RedisStringCommands.class);
        when(connection.stringCommands()).thenReturn(commands);
        when(redis.executePipelined(any(RedisCallback.class))).thenAnswer(inv -> {
            ((RedisCallback) inv.getArgument(0)).doInRedis(connection);
            return List.of();
        });

        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("a", List.of("x"));
        values.put("b", null);            // 该键没取到 → 不写
        values.put("c", List.of("y"));
        cache.setMany(values, Duration.ofSeconds(30));   // 不抛

        byte[] keyA = "platform:cache:a".getBytes(StandardCharsets.UTF_8);
        byte[] keyB = "platform:cache:b".getBytes(StandardCharsets.UTF_8);
        byte[] keyC = "platform:cache:c".getBytes(StandardCharsets.UTF_8);
        //set 有两个重载（Expiration+SetOption / SetCondition+Expiration），匹配器须带类型消歧
        verify(commands).set(eq(keyA), eq("[\"x\"]".getBytes(StandardCharsets.UTF_8)),
                any(Expiration.class), any(RedisStringCommands.SetOption.class));
        verify(commands, never()).set(eq(keyB), any(), any(Expiration.class), any(RedisStringCommands.SetOption.class));
        verify(commands).set(eq(keyC), eq("[\"y\"]".getBytes(StandardCharsets.UTF_8)),
                any(Expiration.class), any(RedisStringCommands.SetOption.class));
    }
}
