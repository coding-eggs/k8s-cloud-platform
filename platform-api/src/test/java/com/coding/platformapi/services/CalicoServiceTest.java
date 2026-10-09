package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.IpoolDTO;
import com.coding.common.models.k8s.dto.PoolIpamSummaryDTO;
import com.coding.platformapi.cache.RedisJsonCache;
import com.coding.platformapi.k8s.K8sCalicoClient;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CalicoService 删除守卫 + IPAM 降级单测（mock K8sCalicoClient，不触网）。
 * <p>守卫铁律（spec §5.3）：allocated&gt;0 拒删；汇总不可用（null/异常）→ 无法确认占用 → 保守拒删。
 * <p>缓存用**真** {@link RedisJsonCache} + mock 的 Redis 客户端（恒未命中）——本测试关心的是派生逻辑与降级，
 * 缓存语义本身在 RedisJsonCacheTest 里测。
 */
class CalicoServiceTest {

    private final K8sCalicoClient k8s = mock(K8sCalicoClient.class);
    private final CalicoService svc = new CalicoService(k8s, inMemoryCache());

    /** 真 {@link RedisJsonCache} + 内存假 Redis（get/set 落在一个 map 上）：既保留
     *  "命中缓存就不再打 k8s" 的断言（见 {@code ipamSummary_caches_success}），又不依赖真 Redis。
     *  缓存本身的降级语义在 RedisJsonCacheTest 里测。 */
    private static RedisJsonCache inMemoryCache() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        Map<String, String> store = new HashMap<>();
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0, String.class)));
        doAnswer(inv -> store.put(inv.getArgument(0), inv.getArgument(1)))
                .when(ops).set(anyString(), anyString(), any(Duration.class));
        return new RedisJsonCache(redis, JsonMapper.builder().build());
    }

    private static PoolIpamSummaryDTO summary(long allocated) {
        PoolIpamSummaryDTO s = new PoolIpamSummaryDTO();
        s.setPoolName("p");
        s.setAllocated(allocated);
        return s;
    }

    @Test
    void delete_blocked_when_pool_has_allocated_ips() {
        when(k8s.ipamSummary(anyString(), anyString())).thenReturn(summary(42));
        assertThatThrownBy(() -> svc.deleteIppool("c1", "p"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("42");
        verify(k8s, never()).deleteIppool(anyString(), anyString());
    }

    @Test
    void delete_allowed_when_pool_empty() {
        when(k8s.ipamSummary(anyString(), anyString())).thenReturn(summary(0));
        svc.deleteIppool("c1", "p"); // 不抛错
        verify(k8s).deleteIppool("c1", "p");
    }

    @Test
    void delete_blocked_when_summary_unavailable_null() {
        // 汇总返回 null（源不可用）→ 无法确认占用 → 保守拒删，绝不放行
        when(k8s.ipamSummary(anyString(), anyString())).thenReturn(null);
        assertThatThrownBy(() -> svc.deleteIppool("c1", "p"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("无法确认");
        verify(k8s, never()).deleteIppool(anyString(), anyString());
    }

    @Test
    void delete_blocked_when_summary_throws() {
        // 汇总查询抛异常（集群断开 / capability 缺失）→ safe() 降级 null → 保守拒删
        when(k8s.ipamSummary(anyString(), anyString())).thenThrow(new RuntimeException("connection refused"));
        assertThatThrownBy(() -> svc.deleteIppool("c1", "p"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("无法确认");
        verify(k8s, never()).deleteIppool(anyString(), anyString());
    }

    @Test
    void ipamSummary_degrades_to_null_on_source_error() {
        // 派生查询降级（spec §9）：源不可用 → null（前端「—」），不抛错、不缓存失败
        when(k8s.ipamSummary(anyString(), anyString())).thenThrow(new RuntimeException("boom"));
        assertThat(svc.ipamSummary("c1", "p")).isNull();
    }

    @Test
    void ipamSummary_caches_success() {
        // 成功结果进 TTL 缓存：第二次调用不再触网
        when(k8s.ipamSummary(anyString(), anyString())).thenReturn(summary(3));
        assertThat(svc.ipamSummary("c1", "p").getAllocated()).isEqualTo(3L);
        assertThat(svc.ipamSummary("c1", "p").getAllocated()).isEqualTo(3L);
        verify(k8s, org.mockito.Mockito.times(1)).ipamSummary(anyString(), anyString());
    }

    @Test
    void createIppool_passthrough_to_client() {
        IpoolDTO dto = new IpoolDTO();
        dto.setName("p");
        when(k8s.createIppool(dto)).thenReturn(dto);
        assertThat(svc.createIppool(dto).getName()).isEqualTo("p");
        verify(k8s).createIppool(dto);
    }

}
