package com.coding.platformapi.services;

import com.coding.platformapi.cache.RedisJsonCache;
import com.coding.platformapi.registry.RegistrySuggestExecutor;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 概览每几秒轮询一次：新版本查询必须有界 —— 同一 (租户, 仓库, 线上 tag) 在 TTL 内最多问一次 Harbor，
 * 失败也记（负缓存），冷启动一次最多等执行器的总预算。
 */
class AppImageUpdatesTest {

    /** 内存版缓存：getMany / setMany 真的存取 */
    static class MemCache extends RedisJsonCache {
        final Map<String, Object> store = new HashMap<>();

        MemCache() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Map<String, T> getMany(Collection<String> keys, TypeReference<T> type) {
            Map<String, T> out = new LinkedHashMap<>();
            for (String k : keys) {
                if (store.containsKey(k)) {
                    out.put(k, (T) store.get(k));
                }
            }
            return out;
        }

        @Override
        public <T> void setMany(Map<String, T> values, Duration ttl) {
            store.putAll(values);
        }
    }

    private final RegistryService registry = mock(RegistryService.class);
    private final MemCache cache = new MemCache();
    private final AppImageUpdates updates = new AppImageUpdates(registry, cache, new RegistrySuggestExecutor());

    private static AppImageUpdates.Probe probe(String repo, String tag) {
        return new AppImageUpdates.Probe(repo, tag);
    }

    @Test
    void returnsNewTagPerServiceKey() {
        when(registry.newerTag("harbor.x/p/api", "1.0", "t1")).thenReturn("1.1");
        when(registry.newerTag("harbor.x/p/job", "2.0", "t1")).thenReturn(null);

        Map<String, String> out = updates.newTags(Map.of(
                "api", probe("harbor.x/p/api", "1.0"), "job", probe("harbor.x/p/job", "2.0")), "t1");

        assertThat(out).containsEntry("api", "1.1").doesNotContainKey("job");
    }

    @Test
    void secondPollIsServedFromCacheWithoutTouchingHarbor() {
        when(registry.newerTag("harbor.x/p/api", "1.0", "t1")).thenReturn("1.1");
        Map<String, AppImageUpdates.Probe> in = Map.of("api", probe("harbor.x/p/api", "1.0"));

        updates.newTags(in, "t1");
        Map<String, String> again = updates.newTags(in, "t1");

        assertThat(again).containsEntry("api", "1.1");
        verify(registry, times(1)).newerTag(anyString(), anyString(), any());
    }

    @Test
    void failuresAreCachedTooSoADownHarborIsNotHammeredEveryPoll() {
        when(registry.newerTag(anyString(), anyString(), any())).thenThrow(new IllegalStateException("down"));
        Map<String, AppImageUpdates.Probe> in = Map.of("api", probe("harbor.x/p/api", "1.0"));

        assertThat(updates.newTags(in, "t1")).isEmpty();
        assertThat(updates.newTags(in, "t1")).isEmpty();
        assertThat(updates.newTags(in, "t1")).isEmpty();

        verify(registry, times(1)).newerTag(anyString(), anyString(), any());
    }

    @Test
    void servicesSharingTheSameImageShareOneLookup() {
        when(registry.newerTag("harbor.x/p/api", "1.0", "t1")).thenReturn("1.1");

        Map<String, String> out = updates.newTags(Map.of(
                "a", probe("harbor.x/p/api", "1.0"), "b", probe("harbor.x/p/api", "1.0")), "t1");

        assertThat(out).containsEntry("a", "1.1").containsEntry("b", "1.1");
        verify(registry, times(1)).newerTag(anyString(), anyString(), any());
    }

    @Test
    void cacheIsPerTenant() {
        when(registry.newerTag("harbor.x/p/api", "1.0", "t1")).thenReturn("1.1");
        when(registry.newerTag("harbor.x/p/api", "1.0", "t2")).thenReturn(null);
        Map<String, AppImageUpdates.Probe> in = Map.of("api", probe("harbor.x/p/api", "1.0"));

        assertThat(updates.newTags(in, "t1")).containsEntry("api", "1.1");
        assertThat(updates.newTags(in, "t2")).isEmpty();
    }
}
