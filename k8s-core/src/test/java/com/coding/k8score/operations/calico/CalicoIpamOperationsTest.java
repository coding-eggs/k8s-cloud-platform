package com.coding.k8score.operations.calico;

import com.coding.common.models.k8s.dto.IpamBlockStatDTO;
import com.coding.common.models.k8s.dto.IpamIpDetailDTO;
import com.coding.common.models.k8s.dto.PoolIpamSummaryDTO;
import com.coding.k8score.converter.impl.calico.IppoolConverter;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * CalicoIpamOperations 单测。
 * <p>覆盖两层：
 * <ol>
 *   <li><b>parseBlock（真实 CRD 形态）</b>——{@code unallocated} 是块内「空闲偏移」整数列表、
 *       {@code allocations} 位图兜底；这是历史 bug（把偏移当 IP 字符串解析 → 全块误判已分配）的回归防线。</li>
 *   <li><b>纯计算</b>（summarize/blockStats/isFree/nextFreeBlocks/blockIps，脱离 client）——
 *       铁律用例：/8 池 + 3 个已物化块瞬时返回，证明空闲靠「缺席」判定、永不枚举补集 T。</li>
 * </ol>
 * <p>建模口径：<b>Calico 用满整块</b>（偏移 0..2^N−1，含传统 network/broadcast），故 /26 块 totalIps=64。
 */
class CalicoIpamOperationsTest {

    private final CalicoIpamOperations ops = new CalicoIpamOperations(mock(KubernetesClient.class), new IppoolConverter());

    // ---------- 测试辅助 ----------

    private static BigInteger bi(String ip) throws Exception {
        return new BigInteger(1, InetAddress.getByName(ip).getAddress());
    }

    private static CalicoIpamOperations.IpRange range(String start, String end) throws Exception {
        return new CalicoIpamOperations.IpRange(bi(start), bi(end));
    }

    /** 从块网络基址起连续 count 个偏移的空闲集（绝对 IP）。 */
    private static Set<BigInteger> v4FreeRange(String netStartIp, int count) throws Exception {
        BigInteger b = bi(netStartIp);
        Set<BigInteger> s = new HashSet<>(count);
        for (int i = 0; i < count; i++) {
            s.add(b.add(BigInteger.valueOf(i)));
        }
        return s;
    }

    private static CalicoIpamOperations.BlockInfo block(String cidr, Set<BigInteger> free, String node) {
        return new CalicoIpamOperations.BlockInfo(cidr, free, node, false);
    }

    /** 造一个 spec 形态的 GenericKubernetesResource（走 parseBlock 真实入口）。 */
    private static GenericKubernetesResource blockRes(Map<String, Object> spec) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        Map<String, Object> props = new HashMap<>();
        props.put("spec", spec);
        res.setAdditionalProperties(props);
        return res;
    }

    // ---------- parseBlock：真实 Calico CRD 形态（bug 回归防线） ----------

    @Test
    void parseBlock_interprets_unallocated_as_integer_offsets() throws Exception {
        // 真实 IPAMBlock：unallocated = 块内「空闲偏移」整数列表（非 IP 字符串）。offsets 0/1/2 → .0/.1/.2 free。
        GenericKubernetesResource res = blockRes(Map.of(
                "cidr", "10.48.3.0/26",
                "unallocated", List.of(0, 1, 2),
                "affinity", "host:node-7",
                "deleted", false
        ));
        CalicoIpamOperations.BlockInfo b = ops.parseBlock(res);
        assertThat(b).isNotNull();
        assertThat(b.cidr()).isEqualTo("10.48.3.0/26");
        assertThat(b.node()).isEqualTo("node-7");
        assertThat(b.deleted()).isFalse();
        // 空闲集 = 基址 + offset（绝对 IP），而非把 "0"/"1"/"2" 当 IP 解析
        assertThat(b.unallocated()).containsExactlyInAnyOrder(
                bi("10.48.3.0"), bi("10.48.3.1"), bi("10.48.3.2"));
    }

    @Test
    void parseBlock_falls_back_to_allocations_bitmap() throws Exception {
        // unallocated 缺席 → allocations 位图（index=偏移，null=空闲、非 null=已分配）
        List<Object> allocs = new ArrayList<>();
        allocs.add(null); // offset 0 free
        allocs.add(0);    // offset 1 allocated（attr idx 0）
        allocs.add(null); // offset 2 free
        GenericKubernetesResource res = blockRes(Map.of(
                "cidr", "10.48.3.0/26",
                "allocations", allocs,
                "affinity", "host:node-9"
        ));
        CalicoIpamOperations.BlockInfo b = ops.parseBlock(res);
        assertThat(b.unallocated()).containsExactlyInAnyOrder(
                bi("10.48.3.0"), bi("10.48.3.2"));
    }

    @Test
    void parseBlock_fully_allocated_block_has_empty_free_set() {
        // unallocated = []（全满）→ 空闲集为空（正确语义；旧 bug 的空集是「解析失败」，成因不同但形态相同）
        GenericKubernetesResource res = blockRes(Map.of(
                "cidr", "10.48.3.0/26",
                "unallocated", List.of(),
                "deleted", false
        ));
        CalicoIpamOperations.BlockInfo b = ops.parseBlock(res);
        assertThat(b).isNotNull();
        assertThat(b.unallocated()).isEmpty();
    }

    // ---------- 铁律：大池不枚举补集 ----------

    @Test
    void summarize_big_pool_few_blocks_no_complement_enumeration() throws Exception {
        // /8 池 = 2^24 地址（Calico 用满整块）；仅 3 个已物化 /26 块。若枚举补集会迭代 ~1600 万，必然慢/OOM。
        List<CalicoIpamOperations.BlockInfo> blocks = List.of(
                block("10.0.0.0/26", v4FreeRange("10.0.0.0", 64), "n1"),   // 空块：free=64, allocated=0
                block("10.0.0.64/26", v4FreeRange("10.0.0.64", 30), "n2"), // free=30, allocated=34
                block("10.0.1.0/26", Set.of(), "n3")                        // 满块：free=0, allocated=64
        );

        long t0 = System.nanoTime();
        PoolIpamSummaryDTO s = ops.summarize("big-pool", "10.0.0.0/8", 26, blocks, List.of());
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(s.getCapacity()).isEqualTo(16_777_216L);      // 2^24（用满整块）
        assertThat(s.getBlockCount()).isEqualTo(3);
        assertThat(s.getAllocated()).isEqualTo(98L);             // 0 + 34 + 64
        assertThat(s.getReserved()).isZero();
        assertThat(s.getFree()).isEqualTo(16_777_216L - 98L);    // 含全部未物化块空间
        // 不枚举补集 → 毫秒级返回（留足余量，避免 CI 抖动误报）
        assertThat(ms).isLessThan(2000);
    }

    @Test
    void summarize_ipv6_pool_capacity_saturates_no_overflow() {
        // /64 IPv6 池容量 = 2^64 > Long.MAX_VALUE → capacity 饱和封顶，不溢出；free 仍正确（allocated 来自已物化块）
        List<CalicoIpamOperations.BlockInfo> blocks = List.of(
                block("fd00::/122", Set.of(), "n1") // /122 = 64 地址，满块 → allocated=64
        );
        PoolIpamSummaryDTO s = ops.summarize("v6", "fd00::/64", 122, blocks, List.of());
        assertThat(s.getCapacity()).isEqualTo(Long.MAX_VALUE);   // 饱和
        assertThat(s.getBlockCount()).isEqualTo(1);
        assertThat(s.getAllocated()).isEqualTo(64L);
        // free = capacity − allocated（饱和后仍非负）
        assertThat(s.getFree()).isGreaterThanOrEqualTo(0);
    }

    // ---------- blockStats 三态划分 + reserved ----------

    @Test
    void blockStats_partitions_free_reserved_allocated() throws Exception {
        // 块 10.48.0.0/26：满块 totalIps=64，全 free；保留段 .5–.9（5 个）
        List<CalicoIpamOperations.BlockInfo> blocks = List.of(block("10.48.0.0/26", v4FreeRange("10.48.0.0", 64), "n1"));
        List<CalicoIpamOperations.IpRange> res = List.of(range("10.48.0.5", "10.48.0.9"));

        List<IpamBlockStatDTO> stats = ops.blockStats(blocks, "10.48.0.0/16", null, res);
        assertThat(stats).hasSize(1);
        IpamBlockStatDTO st = stats.get(0);
        assertThat(st.getTotalIps()).isEqualTo(64L);
        assertThat(st.getReserved()).isEqualTo(5L);   // .5–.9 空闲且被保留
        assertThat(st.getFree()).isEqualTo(59L);      // 64 − 5
        assertThat(st.getAllocated()).isZero();
        assertThat(st.getNode()).isEqualTo("n1");
    }

    @Test
    void blockStats_pool_filter_excludes_foreign_blocks() throws Exception {
        List<CalicoIpamOperations.BlockInfo> blocks = List.of(
                block("10.48.0.0/26", v4FreeRange("10.48.0.0", 64), null),
                block("192.168.0.0/26", v4FreeRange("192.168.0.0", 64), null) // 不属于 10.48/16
        );
        List<IpamBlockStatDTO> stats = ops.blockStats(blocks, "10.48.0.0/16", null, List.of());
        assertThat(stats).hasSize(1);
        assertThat(stats.get(0).getCidr()).isEqualTo("10.48.0.0/26");
    }

    // ---------- isFree 点查 ----------

    @Test
    void isFree_single_ip_free_allocated_reserved() throws Exception {
        // 块 offsets 0..60 free（.0–.60），.61/.62/.63 allocated；保留段 .5
        List<CalicoIpamOperations.BlockInfo> blocks = List.of(block("10.48.0.0/26", v4FreeRange("10.48.0.0", 61), null));
        List<CalicoIpamOperations.IpRange> res = List.of(range("10.48.0.5", "10.48.0.5"));

        assertThat(ops.isFree("10.48.0.10", blocks, res)).isTrue();   // 空闲
        assertThat(ops.isFree("10.48.0.61", blocks, res)).isFalse();  // 已分配
        assertThat(ops.isFree("10.48.0.5", blocks, res)).isFalse();   // 被保留
    }

    @Test
    void isFree_unmaterialized_block_is_free() throws Exception {
        // 该块未物化（不在 claimed 集）且无保留 → 整块 free（缺席判定）
        List<CalicoIpamOperations.BlockInfo> blocks = List.of(block("10.48.0.0/26", v4FreeRange("10.48.0.0", 64), null));
        assertThat(ops.isFree("10.48.9.0/26", blocks, List.of())).isTrue();
    }

    // ---------- nextFreeBlocks：跳过已认领，分页 ----------

    @Test
    void nextFreeBlocks_skips_claimed_and_paginates() throws Exception {
        // 池 10.48.0.0/16，blockSize 26（块步长 64）。前两个块位（.0/.64）已认领。
        List<CalicoIpamOperations.BlockInfo> blocks = List.of(
                block("10.48.0.0/26", v4FreeRange("10.0.0.0", 64), null),
                block("10.48.0.64/26", v4FreeRange("10.48.0.64", 64), null)
        );

        List<String> page0 = ops.nextFreeBlocks("10.48.0.0/16", 26, blocks, 0, 3);
        assertThat(page0).containsExactly("10.48.0.128/26", "10.48.0.192/26", "10.48.1.0/26");

        List<String> page1 = ops.nextFreeBlocks("10.48.0.0/16", 26, blocks, 1, 2);
        assertThat(page1).containsExactly("10.48.0.192/26", "10.48.1.0/26");
    }

    // ---------- blockIps：已物化 / 未物化合成 free / allocated 反查 pod ----------

    @Test
    void blockIps_materialized_marks_free_reserved_allocated() throws Exception {
        // 块 offsets 0..62 free（.0–.62），.63 allocated；保留段 .5–.7；pod 占用 .63
        List<CalicoIpamOperations.BlockInfo> blocks = List.of(block("10.48.0.0/26", v4FreeRange("10.48.0.0", 63), "n1"));
        CalicoIpamOperations.BlockInfo b = blocks.get(0);
        List<CalicoIpamOperations.IpRange> res = List.of(range("10.48.0.5", "10.48.0.7"));
        Map<String, CalicoIpamOperations.PodRef> pods = new HashMap<>();
        pods.put("10.48.0.63", new CalicoIpamOperations.PodRef("pod-x", "ns-y", "n1"));

        List<IpamIpDetailDTO> ips = ops.blockIps("10.48.0.0/26", b, res, pods);
        assertThat(ips).hasSize(64); // 满块
        Map<String, String> byIp = new HashMap<>();
        for (IpamIpDetailDTO d : ips) {
            byIp.put(d.getIp(), d.getStatus());
        }
        assertThat(byIp.get("10.48.0.5")).isEqualTo("reserved");
        assertThat(byIp.get("10.48.0.7")).isEqualTo("reserved");
        assertThat(byIp.get("10.48.0.8")).isEqualTo("free");
        assertThat(byIp.get("10.48.0.63")).isEqualTo("allocated");

        IpamIpDetailDTO alloc = ips.stream().filter(d -> d.getIp().equals("10.48.0.63")).findFirst().orElseThrow();
        assertThat(alloc.getPodName()).isEqualTo("pod-x");
        assertThat(alloc.getPodNamespace()).isEqualTo("ns-y");
        assertThat(alloc.getNode()).isEqualTo("n1");
    }

    @Test
    void blockIps_unmaterialized_synthesizes_all_free() throws Exception {
        // 未物化块（block=null）→ 合成全 free（无 allocated/pod），保留段仍标 reserved
        List<CalicoIpamOperations.IpRange> res = List.of(range("10.48.9.3", "10.48.9.4"));
        List<IpamIpDetailDTO> ips = ops.blockIps("10.48.9.0/26", null, res, Map.of());
        assertThat(ips).hasSize(64); // 满块
        long free = ips.stream().filter(d -> d.getStatus().equals("free")).count();
        long reserved = ips.stream().filter(d -> d.getStatus().equals("reserved")).count();
        long allocated = ips.stream().filter(d -> d.getStatus().equals("allocated")).count();
        assertThat(free).isEqualTo(62);   // 64 − 2
        assertThat(reserved).isEqualTo(2);
        assertThat(allocated).isZero();
    }

}
