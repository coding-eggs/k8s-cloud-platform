package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.NamespaceSnapshotDTO;
import com.coding.common.models.k8s.dto.NamespaceStatDTO;
import com.coding.common.models.k8s.dto.TenantClusterSummaryDTO;
import com.coding.common.models.k8s.dto.TenantOverviewDTO;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TenantOverviewService}：租户概览的编排规则（spec §5 / §7 / §10）。
 * <p>四件事在这里钉死：
 * <ol>
 *   <li><b>范围取自分配表</b>：只统计本租户的 (集群, 命名空间)，不读别人的。</li>
 *   <li><b>跨集群求和 / 逐集群小计</b>：合计 = 本页各行相加。</li>
 *   <li><b>逐集群降级</b>：一个集群不可用（网关异常）不拖垮整页，进 {@code unavailableClusters}，其余照常出数。</li>
 *   <li><b>配额口径</b>：三档判定复用 {@link QuotaClassifier}；hard/used 只累加有配额的行。</li>
 * </ol>
 * platform-api <b>不依赖 k8s-core</b>，夹具就地用 {@link NamespaceSnapshotDTO} + {@link NamespaceStatDTO} 手工构造。
 */
class TenantOverviewServiceTest {

    private final PlatformTenantNamespaceMapper allocationMapper = mock(PlatformTenantNamespaceMapper.class);
    private final K8sClusterMapper clusterMapper = mock(K8sClusterMapper.class);
    private final NamespaceStatService statService = mock(NamespaceStatService.class);
    private final TenantOverviewService svc = new TenantOverviewService(allocationMapper, clusterMapper, statService);

    @Test
    void only_the_tenants_namespaces_are_counted() {
        when(allocationMapper.listByTenant("t1")).thenReturn(List.of(alloc("c1", "team-a"), alloc("c1", "team-b")));
        when(statService.rows("t1", "c1", List.of("team-a", "team-b")))
                .thenReturn(List.of(snap("team-a", 1, "2", "4Gi"), snap("team-b", 3, "1", "1Gi")));

        TenantOverviewDTO out = svc.overview("t1");

        assertThat(out.getTotal().getPodCount()).isEqualTo(4);
        assertThat(out.getTotal().getCpuRequest()).isEqualByComparingTo("3");
        assertThat(out.getClusters()).singleElement()
                .satisfies(c -> assertThat(c.getNamespaceCount()).isEqualTo(2));
        assertThat(out.getUnavailableClusters()).isEmpty();
    }

    @Test
    void totals_sum_across_clusters() {
        when(allocationMapper.listByTenant("t1")).thenReturn(List.of(alloc("c1", "team-a"), alloc("c2", "team-c")));
        when(statService.rows("t1", "c1", List.of("team-a"))).thenReturn(List.of(snap("team-a", 1, "2", "4Gi")));
        when(statService.rows("t1", "c2", List.of("team-c"))).thenReturn(List.of(snap("team-c", 3, "1", "1Gi")));

        TenantOverviewDTO out = svc.overview("t1");

        assertThat(out.getTotal().getPodCount()).isEqualTo(4);
        assertThat(out.getTotal().getCpuRequest()).isEqualByComparingTo("3");
        assertThat(out.getTotal().getNamespaceCount()).isEqualTo(2);
        assertThat(out.getClusters()).extracting(TenantClusterSummaryDTO::getClusterId).containsExactly("c1", "c2");
        assertThat(out.getClusters()).allSatisfy(c -> assertThat(c.isAvailable()).isTrue());
    }

    @Test
    void one_cluster_being_down_marks_it_unavailable_without_failing_the_page() {
        when(allocationMapper.listByTenant("t1")).thenReturn(List.of(alloc("c1", "team-a"), alloc("c2", "team-c")));
        when(statService.rows("t1", "c1", List.of("team-a"))).thenReturn(List.of(snap("team-a", 1, "1", "1Gi")));
        when(statService.rows("t1", "c2", List.of("team-c"))).thenThrow(new RuntimeException("cluster down"));

        TenantOverviewDTO out = svc.overview("t1");

        assertThat(out.getUnavailableClusters()).containsExactly("c2");
        assertThat(out.getTotal().getPodCount()).isEqualTo(1);          // 其余集群照常出数
        assertThat(out.getClusters()).filteredOn(c -> "c2".equals(c.getClusterId()))
                .singleElement().satisfies(c -> {
                    assertThat(c.isAvailable()).isFalse();
                    assertThat(c.getTotal()).isNull();                  // 不可用 → 小计 null，前端显示「—」
                    assertThat(c.getNamespaceCount()).isEqualTo(1);     // 范围仍来自分配表
                });
    }

    @Test
    void namespaces_without_quota_are_reported() {
        when(allocationMapper.listByTenant("t1")).thenReturn(List.of(alloc("c1", "team-a"), alloc("c1", "team-b")));
        when(statService.rows("t1", "c1", List.of("team-a", "team-b")))
                .thenReturn(List.of(snapWithQuota("team-a", "2", "4Gi"), snapNoQuota("team-b")));

        TenantOverviewDTO out = svc.overview("t1");

        assertThat(out.getNamespacesWithoutQuota()).containsExactly("team-b");
        assertThat(out.getNamespacesPartialQuota()).isEmpty();
        assertThat(out.getQuotaCpuHard()).isEqualByComparingTo("2");    // 只累加有配额的行
    }

    @Test
    void partial_quota_namespaces_are_reported_and_totals_skip_missing_keys() {
        when(allocationMapper.listByTenant("t1")).thenReturn(List.of(alloc("c1", "team-p")));
        when(statService.rows("t1", "c1", List.of("team-p"))).thenReturn(List.of(snapPartialQuota("team-p", "4")));

        TenantOverviewDTO out = svc.overview("t1");

        assertThat(out.getNamespacesPartialQuota()).containsExactly("team-p");
        assertThat(out.getNamespacesWithoutQuota()).isEmpty();
        assertThat(out.getQuotaCpuHard()).isEqualByComparingTo("4");
        assertThat(out.getQuotaMemHard()).isNull();                     // 该行没有 memory 配额 → 合计保持 null
    }

    @Test
    void sampled_at_is_the_oldest_row_on_the_page() {
        when(allocationMapper.listByTenant("t1")).thenReturn(List.of(alloc("c1", "team-a"), alloc("c1", "team-b")));
        when(statService.rows("t1", "c1", List.of("team-a", "team-b")))
                .thenReturn(List.of(snapAt("team-a", 5_000L), snapAt("team-b", 1_000L)));

        assertThat(svc.overview("t1").getSampledAt()).isEqualTo(1_000L);
    }

    @Test
    void a_null_slice_from_the_gateway_does_not_blow_up_the_page() {
        // 网关异常路径可能 append 进 null（NamespaceStatMerge.* 会直接解引用）→ 合并前须滤掉
        when(allocationMapper.listByTenant("t1")).thenReturn(List.of(alloc("c1", "team-a")));
        when(statService.rows("t1", "c1", List.of("team-a")))
                .thenReturn(Arrays.asList(snap("team-a", 1, "1", "1Gi"), null));

        TenantOverviewDTO out = svc.overview("t1");

        assertThat(out.getTotal().getPodCount()).isEqualTo(1);          // 只算非 null 的那条
        assertThat(out.getUnavailableClusters()).isEmpty();             // null 不是"集群不可用"
    }

    // ---- 夹具（platform-api 不依赖 k8s-core，就地构造） ----

    private static PlatformTenantNamespace alloc(String clusterId, String namespace) {
        PlatformTenantNamespace a = new PlatformTenantNamespace();
        a.setTenantId("t1");
        a.setClusterId(clusterId);
        a.setNamespace(namespace);
        return a;
    }

    private static NamespaceSnapshotDTO base(String ns) {
        NamespaceSnapshotDTO s = new NamespaceSnapshotDTO();
        NamespaceStatDTO stat = new NamespaceStatDTO();
        stat.setNamespace(ns);
        stat.setCpuRequest(BigDecimal.ZERO);
        stat.setMemRequest(BigDecimal.ZERO);
        stat.setCpuLimit(BigDecimal.ZERO);
        stat.setMemLimit(BigDecimal.ZERO);
        s.setStat(stat);
        s.setAbnormalPodReasonCounts(new LinkedHashMap<>());
        s.setAbnormalPods(List.of());
        s.setUnhealthyWorkloads(List.of());
        return s;
    }

    private static NamespaceSnapshotDTO snap(String ns, int pods, String cpu, String mem) {
        NamespaceSnapshotDTO s = base(ns);
        s.getStat().setPodCount(pods);
        s.getStat().setCpuRequest(new BigDecimal(cpu));
        s.getStat().setMemRequest(quantity(mem));
        return s;
    }

    private static NamespaceSnapshotDTO snapWithQuota(String ns, String cpuHard, String memHard) {
        NamespaceSnapshotDTO s = base(ns);
        s.getStat().setQuotaCpuHard(new BigDecimal(cpuHard));
        s.getStat().setQuotaMemHard(quantity(memHard));
        s.getStat().setQuotaCpuUsed(new BigDecimal(cpuHard));
        s.getStat().setQuotaMemUsed(quantity(memHard));
        return s;
    }

    private static NamespaceSnapshotDTO snapNoQuota(String ns) {
        return base(ns);   // 配额列全 null → QuotaClassifier → MISSING
    }

    private static NamespaceSnapshotDTO snapPartialQuota(String ns, String cpuHard) {
        NamespaceSnapshotDTO s = base(ns);
        s.getStat().setQuotaCpuHard(new BigDecimal(cpuHard));   // 只有 cpu，缺 memory
        return s;
    }

    private static NamespaceSnapshotDTO snapAt(String ns, long sampledAt) {
        NamespaceSnapshotDTO s = base(ns);
        s.setSampledAt(sampledAt);
        return s;
    }

    /** 允许 "4Gi" 这类式子（Gi → 字节），其余按普通十进制解析。 */
    private static BigDecimal quantity(String q) {
        if (q == null) {
            return null;
        }
        if (q.endsWith("Gi")) {
            return new BigDecimal(q.substring(0, q.length() - 2))
                    .multiply(BigDecimal.valueOf(1024L * 1024 * 1024));
        }
        return new BigDecimal(q);
    }
}
