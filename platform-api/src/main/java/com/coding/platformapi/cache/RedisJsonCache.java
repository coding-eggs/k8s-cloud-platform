package com.coding.platformapi.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 跨实例共享的 TTL 缓存（Redis）。
 *
 * <p><b>为什么不用进程内缓存</b>：多实例下每个实例各存一份，就变成"各查各的库"，且同一个用户在两个实例上
 * 可能拿到不同的结果 —— 对权限闭包这类数据，那是**授权不一致**，不只是负载问题。共享一份后，
 * 改角色只需等一个 TTL，全集群同时看到新值。
 *
 * <p><b>Redis 不是依赖，只是加速</b>：读失败 → 直接调 loader（本次照样正确，只是少了缓存）；
 * 写失败只记日志。因此 Redis 挂掉时退化成本功能改造前的行为，而不是让接口不可用。
 *
 * <p><b>只缓存非 null 结果</b>（与原先本地 TtlCache 同语义）：失败/降级不上缓存，下一次请求会重试。
 *
 * <p><b>刻意不提供手工失效</b>：本仓用它的地方都允许"旧一个 TTL"，而手工失效要挂到十几个写路径上
 * （角色保存、成员增删、权限点 CRUD、Calico 池变更…），漏一个就从"有上界"变成"永久"。
 * 需要更实时的话，先调 TTL，而不是加失效点。详见
 * {@code docs/superpowers/plans/2026-10-09-permissions-out-of-token.md}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisJsonCache {

    /** 键前缀：与 Spring Session、其它应用的键共存于同一 Redis 时靠它区分 */
    private static final String PREFIX = "platform:cache:";

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;

    /**
     * @param key    逻辑键（本类自动加前缀）；务必把**所有**影响结果的维度编进键（如 tenantId、集群 id）
     * @param ttl    过期时间（陈旧上界）
     * @param type   值的反序列化类型
     * @param loader 未命中时的取数（结果非 null 才写缓存）
     */
    public <T> T get(String key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        String fullKey = PREFIX + key;
        try {
            String cached = redis.opsForValue().get(fullKey);
            if (cached != null) {
                return jsonMapper.readValue(cached, type);
            }
        } catch (Exception e) {
            // 读失败/反序列化失败（如 DTO 改了结构）：当作未命中，直接查源，不当错误放大
            log.warn("Redis 缓存读取失败，直接查源: {} ({})", fullKey, e.getMessage());
            return loader.get();
        }
        T value = loader.get();
        if (value == null) {
            return null; // 失败/降级不缓存
        }
        try {
            redis.opsForValue().set(fullKey, jsonMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.warn("Redis 缓存写入失败（不影响本次结果）: {} ({})", fullKey, e.getMessage());
        }
        return value;
    }

    /** 批量读（MGET，一次往返）。**只回命中项**：未命中由调用方决定怎么补（本仓的补法是走采样器）。 */
    public <T> Map<String, T> getMany(Collection<String> keys, TypeReference<T> type) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        List<String> fullKeys = keys.stream().map(k -> PREFIX + k).toList();
        List<String> raw;
        try {
            raw = redis.opsForValue().multiGet(fullKeys);
        } catch (Exception e) {
            log.warn("Redis 批量读失败，按全未命中处理（{} 个键）：{}", fullKeys.size(), e.getMessage());
            return Map.of();
        }
        if (raw == null) {
            return Map.of();
        }
        Map<String, T> out = new LinkedHashMap<>();
        Iterator<String> ks = keys.iterator();
        for (String value : raw) {
            String key = ks.next();
            if (value == null) {
                continue;
            }
            try {
                out.put(key, jsonMapper.readValue(value, type));
            } catch (Exception e) {
                log.warn("Redis 缓存反序列化失败，按未命中处理: {} ({})", PREFIX + key, e.getMessage());
            }
        }
        return out;
    }

    /**
     * 批量写（pipeline，每键同 TTL）。写失败只记日志 —— 与 get 的降级立场一致。
     * <p><b>null 值不写</b>（与 {@link #get} 的"只缓存非 null"同立场）：值是 null 表示这次没取到
     * （如 {@code NamespaceStatService.rows} 里某个 ns 采样失败）—— 写了"null"下次会被读成"命中却为空"的假象，
     * 而且拖累同批其它键。null 键直接跳过，其余键照常写（一个键没取到不影响其它键）。
     */
    public <T> void setMany(Map<String, T> values, Duration ttl) {
        if (values == null || values.isEmpty()) {
            return;
        }
        try {
            Map<String, String> payload = new LinkedHashMap<>();
            values.forEach((k, v) -> {
                if (v != null) {
                    payload.put(PREFIX + k, jsonMapper.writeValueAsString(v));
                }
            });
            if (payload.isEmpty()) {
                return;
            }
            redis.executePipelined((RedisCallback<Object>) connection -> {
                RedisStringCommands commands = connection.stringCommands();
                payload.forEach((k, v) -> commands.set(k.getBytes(StandardCharsets.UTF_8),
                        v.getBytes(StandardCharsets.UTF_8),
                        Expiration.milliseconds(ttl.toMillis()), RedisStringCommands.SetOption.UPSERT));
                return null;
            });
        } catch (Exception e) {
            log.warn("Redis 批量写失败（不影响本次结果，{} 个键）：{}", values.size(), e.getMessage());
        }
    }
}
