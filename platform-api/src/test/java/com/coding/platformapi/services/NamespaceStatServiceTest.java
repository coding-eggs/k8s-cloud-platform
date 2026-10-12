package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.NamespaceSnapshotDTO;
import com.coding.common.models.k8s.dto.NamespaceStatDTO;
import com.coding.platformapi.cache.RedisJsonCache;
import com.coding.platformapi.k8s.K8sNamespaceStatClient;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NamespaceStatService}：ns 键的唯一读写入口 —— 缓存优先、未命中采样、过旧补采。
 *
 * <p>用**真实** {@link RedisJsonCache}（配 mock 的 template），而不是 mock 缓存本身：阈值逻辑挂在校验
 * "取回来的 sampledAt 与最新一条差多少"，mock 掉缓存就把被测逻辑架空了。
 */
class NamespaceStatServiceTest {

    private static final TypeReference<NamespaceSnapshotDTO> TYPE = new TypeReference<>() {};

    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    K8sNamespaceStatClient client = mock(K8sNamespaceStatClient.class);
    RedisJsonCache cache;   // 真实实例，配 mock 的 template（与 RedisJsonCacheTest 同法）
    NamespaceStatService svc;

    NamespaceStatServiceTest() {
        when(redis.opsForValue()).thenReturn(valueOps);
        cache = new RedisJsonCache(redis, JsonMapper.builder().build());
        svc = new NamespaceStatService(cache, client);
    }

    /** platform-api **不依赖 k8s-core**，夹具就地构造（不要引 k8s-core 的类） */
    private NamespaceSnapshotDTO snap(String ns, long sampledAt) {
        NamespaceSnapshotDTO s = new NamespaceSnapshotDTO();
        NamespaceStatDTO stat = new NamespaceStatDTO();
        stat.setNamespace(ns);
        s.setStat(stat);
        s.setAbnormalPodReasonCounts(new LinkedHashMap<>());
        s.setAbnormalPods(List.of());
        s.setUnhealthyWorkloads(List.of());
        s.setSampledAt(sampledAt);
        return s;
    }

    /** 把快照序列化成缓存里的值（与 RedisJsonCache 写入同一形态）。 */
    private static String json(NamespaceSnapshotDTO s) {
        return JsonMapper.builder().build().writeValueAsString(s);
    }

    @Test
    void row_fills_from_sampler_on_miss_and_writes_back() {
        when(valueOps.get("platform:cache:ns:c1:team-a")).thenReturn(null);
        when(client.row(null, "c1", "team-a")).thenReturn(snap("team-a", 1_000L));

        assertThat(svc.row(null, "c1", "team-a").getStat().getNamespace()).isEqualTo("team-a");
        verify(valueOps).set(eq("platform:cache:ns:c1:team-a"), anyString(), eq(Duration.ofSeconds(45)));
    }

    @Test
    void rows_reuse_fresh_cached_rows_and_resample_only_stale_ones() {
        long now = System.currentTimeMillis();
        when(valueOps.multiGet(any())).thenReturn(List.of(
                json(snap("a", now)),          // 新鲜
                json(snap("b", now - 60_000))));  // 超过 15s 阈值 → 必须重采
        when(client.row(null, "c1", "b")).thenReturn(snap("b", now));

        List<NamespaceSnapshotDTO> out = svc.rows(null, "c1", List.of("a", "b"));

        assertThat(out).extracting(s -> s.getStat().getNamespace()).containsExactly("a", "b");
        verify(client, never()).row(any(), anyString(), eq("a"));
        verify(client).row(null, "c1", "b");
    }

    @Test
    void rows_with_no_skew_do_not_resample() {
        long now = System.currentTimeMillis();
        when(valueOps.multiGet(any())).thenReturn(List.of(json(snap("a", now)), json(snap("b", now - 5_000))));

        svc.rows(null, "c1", List.of("a", "b"));

        verify(client, never()).row(any(), anyString(), anyString());
    }

    /** 全未命中：每个 ns 都走采样器，输出按 ns 名排序（键批在 service 内排序，与传入顺序无关）。 */
    @Test
    void rows_sample_all_on_full_cache_miss_in_sorted_order() {
        when(valueOps.multiGet(any())).thenReturn(Arrays.asList(null, null));
        when(client.row(null, "c1", "a")).thenReturn(snap("a", 2L));
        when(client.row(null, "c1", "b")).thenReturn(snap("b", 1L));

        List<NamespaceSnapshotDTO> out = svc.rows(null, "c1", List.of("b", "a"));

        assertThat(out).extracting(s -> s.getStat().getNamespace()).containsExactly("a", "b");
        verify(client).row(null, "c1", "a");
        verify(client).row(null, "c1", "b");
    }
}
