package com.coding.platformapi.services;

import com.coding.platformapi.cache.RedisJsonCache;
import com.coding.platformapi.registry.RegistrySuggestExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 应用页的「有新版本」提示：对每个已发布服务问一次镜像仓库「线上 tag 之后有没有更晚推送的 tag」。
 *
 * <h3>为什么要有界</h3>
 * 概览接口在发布进行时每几秒轮询一次，而 Harbor 是外部系统（连接 5s + 读 10s 才超时）。
 * 如果每次轮询、每个服务都实打实问一次 Harbor，一个挂掉的 Harbor 会把整个应用页拖死。三层约束：
 * <ol>
 *   <li><b>共享 TTL 缓存</b>（{@link #TTL}）：键 = 租户 + 仓库 + 线上 tag。TTL 内同一键最多问一次 Harbor，
 *       多个服务用同一镜像、多个浏览器同时在看，也只问一次；</li>
 *   <li><b>失败也缓存</b>（存空串）：Harbor 不可达时 TTL 内不再重试，而不是每次轮询重试一遍；</li>
 *   <li><b>冷缓存的总等待有上限</b>：未命中的查询并发跑，共用 {@link RegistrySuggestExecutor} 的 3 秒预算，
 *       超时按「没有新版本」处理。</li>
 * </ol>
 * 代价是新 tag 最多晚一个 TTL 出现 —— 这是提示不是门禁，手填 tag 与发布都不依赖它。
 *
 * <p>租户可见性不在这里实现：{@link RegistryService#newerTag} 只用调用者可见的连接。
 * 缓存键带租户，避免 A 租户缓存下来的结果被 B 租户读到。
 */
@Slf4j
@Component
public class AppImageUpdates {

    /** 缓存存活时间：与镜像补全的缓存同量级（2 分钟） */
    static final Duration TTL = Duration.ofMinutes(2);

    private static final String KEY_PREFIX = "app:newtag:";
    /** 「查过了，没有新版本 / 没查成」的占位值（RedisJsonCache 不缓存 null，也不能让空值与未命中混淆） */
    private static final String NONE = "";

    /** 一个服务线上正在跑的镜像：仓库（不含 tag）+ tag */
    public record Probe(String imageRepo, String liveTag) {
    }

    private final RegistryService registry;
    private final RedisJsonCache cache;
    private final RegistrySuggestExecutor executor;

    public AppImageUpdates(RegistryService registry, RedisJsonCache cache, RegistrySuggestExecutor executor) {
        this.registry = registry;
        this.cache = cache;
        this.executor = executor;
    }

    /**
     * @param probes      服务名 → 线上镜像
     * @param hatTenantId 租户帽（null = 平台管理员）
     * @return 服务名 → 新 tag；没有新版本、不适用或没查成的服务不在结果里。**不抛**。
     */
    public Map<String, String> newTags(Map<String, Probe> probes, String hatTenantId) {
        if (probes == null || probes.isEmpty()) {
            return Map.of();
        }
        // 同一镜像只查一次：缓存键 -> 探针
        Map<String, Probe> byKey = new LinkedHashMap<>();
        Map<String, String> keyOfService = new LinkedHashMap<>();
        for (Map.Entry<String, Probe> e : probes.entrySet()) {
            Probe p = e.getValue();
            if (p == null || p.imageRepo() == null || p.liveTag() == null) {
                continue;
            }
            String key = KEY_PREFIX + (hatTenantId == null ? "-" : hatTenantId) + ":" + p.imageRepo() + ":" + p.liveTag();
            byKey.putIfAbsent(key, p);
            keyOfService.put(e.getKey(), key);
        }

        Map<String, String> resolved = new LinkedHashMap<>(
                cache.getMany(byKey.keySet(), new TypeReference<String>() { }));
        List<String> missKeys = byKey.keySet().stream().filter(k -> !resolved.containsKey(k)).toList();
        if (!missKeys.isEmpty()) {
            List<Callable<List<String>>> tasks = new ArrayList<>();
            for (String k : missKeys) {
                Probe p = byKey.get(k);
                tasks.add(() -> {
                    try {
                        String tag = registry.newerTag(p.imageRepo(), p.liveTag(), hatTenantId);
                        return tag == null ? List.<String>of() : List.of(tag);
                    } catch (RuntimeException e) {
                        // 一行、不带堆栈；结果按「没有新版本」缓存，TTL 内不再重试
                        log.warn("新版本提示：查询镜像仓库失败 repo={}（{} 内不再重试）：{}",
                                p.imageRepo(), TTL, e.getMessage());
                        return List.<String>of();
                    }
                });
            }
            List<List<String>> results = executor.allOf(tasks);
            Map<String, String> toStore = new LinkedHashMap<>();
            for (int i = 0; i < missKeys.size(); i++) {
                List<String> r = results.get(i);
                // 失败 / 超时 / 没有新版本 在执行器里都是空列表 —— 一律存占位，TTL 内不再问
                String value = r.isEmpty() ? NONE : r.get(0);
                resolved.put(missKeys.get(i), value);
                toStore.put(missKeys.get(i), value);
            }
            cache.setMany(toStore, TTL);
        }

        Map<String, String> out = new LinkedHashMap<>();
        keyOfService.forEach((service, key) -> {
            String tag = resolved.get(key);
            if (tag != null && !tag.isEmpty()) {
                out.put(service, tag);
            }
        });
        return out;
    }
}
