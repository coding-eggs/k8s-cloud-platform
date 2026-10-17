package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.AbnormalPodDTO;
import com.coding.common.models.k8s.dto.ClusterAggregateDTO;
import com.coding.common.models.k8s.dto.ClusterOverviewDTO;
import com.coding.common.models.k8s.dto.ClusterSnapshotDTO;
import com.coding.common.models.k8s.dto.ClusterTotalDTO;
import com.coding.common.models.k8s.dto.NamespaceSnapshotDTO;
import com.coding.common.models.k8s.dto.NamespaceStatDTO;
import com.coding.common.models.k8s.dto.NamespaceStatEntry;
import com.coding.common.models.k8s.dto.NodeHealthDTO;
import com.coding.common.models.k8s.dto.QuotaState;
import com.coding.common.models.k8s.dto.ResourceCapacityDTO;
import com.coding.common.models.k8s.dto.StorageStatDTO;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.cache.RedisJsonCache;
import com.coding.platformapi.k8s.K8sLifecycleClient;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 集群概览的编排规则（spec §7 的降级承诺）。
 * <p>三件事在这里被钉死：
 * <ol>
 *   <li><b>降级不是 0 而是 null</b>：聚合来源不可用时聚合派生段全为 null，前端才能显示「—」；
 *       但基本信息（读 DB）不受影响 —— 「集群未连接」不等于「什么都不知道」。</li>
 *   <li><b>capability 列只回答 group 存在性与 served versions</b>；未探测（空列）/损坏 JSON
 *       → 空 map，不瞎猜「没装」。组件四态由 {@code ClusterComponentService} 负责（S1.2 收口）。</li>
 *   <li><b>overview 与 resourceBreakdown 共享同一份聚合</b>（TTL 缓存命中）——
 *       两个 tab 的数字不会因为两次查询之间的集群变化而互相矛盾。</li>
 * </ol>
 * 缓存用真 {@link RedisJsonCache} + 内存假 Redis（同 {@code CalicoServiceTest}）：既测缓存命中，
 * 又不依赖真 Redis。
 */
class ClusterServiceTest {

    private final K8sClusterMapper clusterMapper = mock(K8sClusterMapper.class);
    private final K8sLifecycleClient adminClient = mock(K8sLifecycleClient.class);
    /** ns 切片的读写入口（T5）——概览/明细的 per-ns 与合计都由它现算。 */
    private final NamespaceStatService statService = mock(NamespaceStatService.class);
    /** 内存假 Redis 的底层 store：新用例直接塞集群键，模拟"集群键命中、不触聚合"。 */
    private final Map<String, String> cacheStore = new HashMap<>();
    private final ClusterCapabilityService capabilityService =
            new ClusterCapabilityService(clusterMapper, JsonMapper.builder().build());
    private final ClusterService svc = new ClusterService(clusterMapper, adminClient,
            JsonMapper.builder().build(), inMemoryCache(cacheStore),
            capabilityService, statService);

    private static RedisJsonCache inMemoryCache(Map<String, String> store) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0, String.class)));
        doAnswer(inv -> store.put(inv.getArgument(0), inv.getArgument(1)))
                .when(ops).set(anyString(), anyString(), any(Duration.class));
        return new RedisJsonCache(redis, JsonMapper.builder().build());
    }

    /** 预置集群键（`cluster:agg:<id>`），使后续调用直接命中、不触聚合 —— 等价于"上次采样刚写过"。 */
    private void seedClusterSnapshot(String clusterId, ClusterSnapshotDTO snap) {
        cacheStore.put("platform:cache:cluster:agg:" + clusterId,
                JsonMapper.builder().build().writeValueAsString(snap));
    }

    // ==================== capability 列（group 存在性 + served versions）====================

    @Test
    void capabilityColumnDerivesGroupPresenceAndServedVersions() {
        stubCluster("{\"gateway.networking.k8s.io\":[\"v1\",\"v1beta1\"],\"networking.istio.io\":[\"v1\"]}");

        assertThat(capabilityService.groups("c1"))
                .containsKeys("gateway.networking.k8s.io", "networking.istio.io");
        assertThat(capabilityService.versionsOf("c1", ClusterCapabilityService.GATEWAY_API_GROUP))
                .containsExactly("v1", "v1beta1");
        assertThat(capabilityService.hasIstio("c1")).isTrue();   // 命中 networking.istio.io
    }

    @Test
    void anEmptyCapabilityColumnMeansNotProbedNotAbsent() {
        stubCluster(null);

        assertThat(capabilityService.groups("c1")).isEmpty();
        assertThat(capabilityService.hasIstio("c1")).isFalse();
    }

    @Test
    void capabilityJsonThatIsBrokenIsTreatedAsNotProbed() {
        stubCluster("{ not json");

        // 解析失败 → 空 map（未探测），不是抛错、也不是"没装"
        assertThat(capabilityService.groups("c1")).isEmpty();
    }

    // ==================== 降级 ====================

    @Test
    void overview_degrades_aggregate_segments_to_null_when_cluster_unreachable() {
        stubCluster("{\"crd.projectcalico.org\":[\"v1\"]}");
        when(adminClient.resourceAggregate("c1"))
                .thenThrow(new CloudPlatformException(500, "集群不可达"));

        ClusterOverviewDTO out = svc.overview("c1");

        // 聚合段全 null —— 不是 0、不是空数组（否则 UI 会把「读不到」显示成「一切正常」）
        assertThat(out.getResourceTotal()).isNull();
        assertThat(out.getResourceCapacity()).isNull();
        assertThat(out.getNodeSummary()).isNull();
        assertThat(out.getUnhealthyNodes()).isNull();
        assertThat(out.getStorage()).isNull();
        assertThat(out.getAbnormalPods()).isNull();
        assertThat(out.getAbnormalPodTotal()).isNull();
    }

    @Test
    void resource_breakdown_returns_null_when_aggregate_unavailable() {
        stubCluster(null);
        when(adminClient.resourceAggregate("c1")).thenThrow(new CloudPlatformException(500, "集群不可达"));

        assertThat(svc.resourceBreakdown("c1")).isNull();
    }

    @Test
    void overview_rejects_unknown_cluster() {
        when(clusterMapper.selectByPrimaryKey("nope")).thenReturn(null);

        assertThatThrownBy(() -> svc.overview("nope")).isInstanceOf(CloudPlatformException.class);
    }

    // ==================== 节点健康 + 缓存共享 ====================

    @Test
    void overview_derives_node_summary_and_lists_unready_or_pressured_nodes() {
        stubCluster(null);
        when(adminClient.resourceAggregate("c1")).thenReturn(aggregateWithNodes());
        // per-ns 与合计改由 ns 切片现算：夹具仍让它等于旧聚合里的数字（合计=Σ明细）
        when(statService.rows(null, "c1", List.of("kube-system"))).thenReturn(List.of(kubeSystemSlice()));

        ClusterOverviewDTO out = svc.overview("c1");

        assertThat(out.getNodeSummary().getTotal()).isEqualTo(3);
        assertThat(out.getNodeSummary().getReady()).isEqualTo(2);
        assertThat(out.getNodeSummary().getNotReady()).isEqualTo(1);
        // 「该看哪里」= 未 Ready 的 + Ready 但带 pressure 的（Ready 不等于没事）
        assertThat(out.getUnhealthyNodes()).extracting(NodeHealthDTO::getName)
                .containsExactlyInAnyOrder("n2", "n3");
        assertThat(out.getAbnormalPodTotal()).isEqualTo(7);
        assertThat(out.getResourceTotal().getPodCount()).isEqualTo(42);
    }

    @Test
    void overview_and_breakdown_share_one_aggregate_call() {
        stubCluster(null);
        when(adminClient.resourceAggregate("c1")).thenReturn(aggregateWithNodes());
        when(statService.rows(null, "c1", List.of("kube-system"))).thenReturn(List.of(kubeSystemSlice()));

        ClusterOverviewDTO overview = svc.overview("c1");
        List<NamespaceStatEntry> rows = svc.resourceBreakdown("c1");

        // 同一份被缓存的聚合：两次调用只打一次 k8s-server
        verify(adminClient, times(1)).resourceAggregate("c1");
        assertThat(overview.getResourceTotal().getPodCount()).isEqualTo(42);
        assertThat(rows).extracting(e -> e.getSnapshot().getStat().getNamespace()).containsExactly("kube-system");
    }

    // ==================== T6：按作用域拆键 ====================

    @Test
    void resource_breakdown_comes_from_namespace_slices() {
        stubCluster(null);
        // 集群键命中（不触聚合）→ 由 ns 切片现算明细，回带 quotaState
        seedClusterSnapshot("c1", snapshot("c1", List.of("team-a"), 1_000L));
        when(statService.rows(null, "c1", List.of("team-a"))).thenReturn(List.of(snap("team-a")));

        assertThat(svc.resourceBreakdown("c1"))
                .extracting(e -> e.getSnapshot().getStat().getNamespace())
                .containsExactly("team-a");
        assertThat(svc.resourceBreakdown("c1")).extracting(NamespaceStatEntry::getQuotaState)
                .containsExactly(QuotaState.MISSING);
    }

    @Test
    void breakdown_is_null_when_cluster_snapshot_unavailable() {
        stubCluster(null);
        when(adminClient.resourceAggregate("c1")).thenThrow(new CloudPlatformException(500, "集群不可达"));

        assertThat(svc.resourceBreakdown("c1")).isNull();   // 前端「—」的既有降级
    }

    // ==================== 防守：切片不可用 / 含 null 不 500 ====================

    /** statService.rows 抛异常（网关异常 / 边界被拒）→ overview 不抛，聚合派生段整体降级为 null。 */
    @Test
    void overview_degrades_when_namespace_slices_throw() {
        stubCluster("{\"crd.projectcalico.org\":[\"v1\"]}");
        seedClusterSnapshot("c1", snapshot("c1", List.of("team-a"), 1_000L));
        when(statService.rows(null, "c1", List.of("team-a")))
                .thenThrow(new CloudPlatformException(500, "切片不可用"));

        ClusterOverviewDTO out = svc.overview("c1");

        assertThat(out.getResourceTotal()).isNull();
        assertThat(out.getResourceCapacity()).isNull();
        assertThat(out.getNodeSummary()).isNull();
        assertThat(out.getUnhealthyNodes()).isNull();
        assertThat(out.getStorage()).isNull();
        assertThat(out.getAbnormalPods()).isNull();
        assertThat(out.getAbnormalPodTotal()).isNull();
        // 能力摘要读 DB，不受切片可用性影响

    }

    /** 同上：resourceBreakdown 遇 rows 抛异常 → 返回 null（前端「—」），不抛。 */
    @Test
    void resource_breakdown_returns_null_when_namespace_slices_throw() {
        stubCluster(null);
        seedClusterSnapshot("c1", snapshot("c1", List.of("team-a"), 1_000L));
        when(statService.rows(null, "c1", List.of("team-a")))
                .thenThrow(new CloudPlatformException(500, "切片不可用"));

        assertThat(svc.resourceBreakdown("c1")).isNull();
    }

    /** rows 返回含 null 的列表（网关异常路径可能 append 进 null）→ 滤掉 null 行、不过 NPE。 */
    @Test
    void breakdown_filters_null_slices_without_npe() {
        stubCluster(null);
        seedClusterSnapshot("c1", snapshot("c1", List.of("team-a"), 1_000L));
        when(statService.rows(null, "c1", List.of("team-a")))
                .thenReturn(java.util.Arrays.asList(null, snap("team-a")));

        assertThat(svc.resourceBreakdown("c1"))
                .extracting(e -> e.getSnapshot().getStat().getNamespace())
                .containsExactly("team-a");
    }

    // ==================== 造数据 ====================

    private void stubCluster(String capabilityJson) {
        K8sCluster c = new K8sCluster();
        c.setClusterId("c1");
        c.setClusterName("prod");
        c.setCapability(capabilityJson);
        when(clusterMapper.selectByPrimaryKey("c1")).thenReturn(c);
    }

    private static ClusterAggregateDTO aggregateWithNodes() {
        ClusterTotalDTO total = new ClusterTotalDTO();
        total.setPodCount(42);
        total.setNamespaceCount(1);

        ResourceCapacityDTO capacity = new ResourceCapacityDTO();
        StorageStatDTO storage = new StorageStatDTO();
        storage.setPvcPending(1);

        AbnormalPodDTO ab = new AbnormalPodDTO();
        ab.setNamespace("kube-system");
        ab.setName("crash");
        ab.setReason("CrashLoopBackOff");

        NamespaceStatDTO ns = new NamespaceStatDTO();
        ns.setNamespace("kube-system");
        ns.setPodCount(42);

        ClusterAggregateDTO agg = new ClusterAggregateDTO();
        agg.setTotal(total);
        agg.setNamespaces(List.of(ns));
        // 集群键的 ns 索引与 ns 键都改由 namespaceSnapshots（per-ns 真值）来
        agg.setNamespaceSnapshots(List.of(kubeSystemSlice()));
        agg.setNodeCapacity(capacity);
        agg.setStorage(storage);
        agg.setAbnormalPods(List.of(ab));
        agg.setAbnormalPodTotal(7);
        agg.setNodeHealth(List.of(health("n1", true), health("n2", false), health("n3", true, "MemoryPressure")));
        return agg;
    }

    private static NodeHealthDTO health(String name, boolean ready) {
        return health(name, ready, null);
    }

    private static NodeHealthDTO health(String name, boolean ready, String pressure) {
        NodeHealthDTO n = new NodeHealthDTO();
        n.setName(name);
        n.setReady(ready);
        n.setPressures(pressure == null ? List.of() : List.of(pressure));
        return n;
    }

    /** 集群键的值（只放集群级事实）：节点/容量/存储 + ns 名索引 + sampledAt。 */
    private static ClusterSnapshotDTO snapshot(String clusterId, List<String> namespaceNames, long sampledAt) {
        ClusterSnapshotDTO s = new ClusterSnapshotDTO();
        s.setNodeHealth(List.of());
        s.setNodeCapacity(new ResourceCapacityDTO());
        s.setStorage(new StorageStatDTO());
        s.setNamespaceNames(namespaceNames);
        s.setSampledAt(sampledAt);
        return s;
    }

    /** ns 切片夹具（无 quota → MISSING）。 */
    private static NamespaceSnapshotDTO snap(String ns) {
        NamespaceSnapshotDTO s = new NamespaceSnapshotDTO();
        NamespaceStatDTO stat = new NamespaceStatDTO();
        stat.setNamespace(ns);
        s.setStat(stat);
        s.setAbnormalPodReasonCounts(new LinkedHashMap<>());
        s.setAbnormalPods(List.of());
        s.setUnhealthyWorkloads(List.of());
        s.setSampledAt(1_000L);
        return s;
    }

    /** kube-system 切片：podCount 42 + 异常数 7（合计=Σ明细；与旧聚合里的数字对齐）。 */
    private static NamespaceSnapshotDTO kubeSystemSlice() {
        NamespaceSnapshotDTO s = new NamespaceSnapshotDTO();
        NamespaceStatDTO stat = new NamespaceStatDTO();
        stat.setNamespace("kube-system");
        stat.setPodCount(42);
        s.setStat(stat);
        AbnormalPodDTO ab = new AbnormalPodDTO();
        ab.setNamespace("kube-system");
        ab.setName("crash");
        ab.setReason("CrashLoopBackOff");
        s.setAbnormalPods(List.of(ab));
        s.setAbnormalPodCount(7);
        Map<String, Integer> reasons = new LinkedHashMap<>();
        reasons.put("CrashLoopBackOff", 7);
        s.setAbnormalPodReasonCounts(reasons);
        s.setUnhealthyWorkloads(List.of());
        s.setSampledAt(2_000L);
        return s;
    }
}
