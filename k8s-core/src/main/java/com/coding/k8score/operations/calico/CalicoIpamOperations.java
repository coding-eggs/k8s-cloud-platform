package com.coding.k8score.operations.calico;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.IpamBlockStatDTO;
import com.coding.common.models.k8s.dto.IpamIpDetailDTO;
import com.coding.common.models.k8s.dto.IpoolDTO;
import com.coding.common.models.k8s.dto.PoolIpamSummaryDTO;
import com.coding.k8score.converter.impl.calico.IppoolConverter;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Calico IPAM 派生视图（读 ipamblocks / ipreservations / pods → 块级汇总、单块 per-IP、空闲点查、下一批空闲块）。
 * <p><b>效率铁律（spec §6）</b>：唯一真相源 = 已物化/已认领的块集合 M + IPReservation；<b>空闲靠「缺席」判定，
 * 永不枚举补集 T（未创建块）</b>。成本只随真实用量 M 走，与池大小无关——大池/IPv6 不 OOM/超时。
 * <p>K8s/Calico 语义全收在本类（同 B4 {@code CoreV1NodeOperations.listPodStats} 范式）。纯计算方法
 * （{@link #summarize}/{@link #blockStats}/{@link #isFree}/{@link #nextFreeBlocks}/{@link #blockIps}）
 * 直接吃已解析数据，可脱离 client 单测；public fetch 方法只做「读源 → 委托纯计算」。
 * <p><b>部署侧待确认（RBAC）</b>：本类经 admin client（platform-system SA）只读 crd.projectcalico.org/v1
 * 的 ipamblocks + ipreservations，其 ClusterRole 需覆盖该组 get/list/watch（只读）。未覆盖时派生视图整体
 * 降级「—」（见 {@code CalicoService} 降级原则）。请部署侧核对 platform-system ClusterRole 是否已含
 * crd.projectcalico.org 读权限。
 */
@Slf4j
public class CalicoIpamOperations {

    /** IPPool（projectcalico.org/v3，集群级）——取池 CIDR/blockSize。 */
    private static final CustomResourceDefinitionContext IPPOOL_CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup("projectcalico.org").withVersion("v3").withKind("IPPool").withPlural("ippools")
            .withScope("Cluster").build();

    /** IPAMBlock（crd.projectcalico.org/v1，集群级，内部存储组）——只读，永不写。 */
    private static final CustomResourceDefinitionContext IPAM_BLOCK_CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup("crd.projectcalico.org").withVersion("v1").withKind("IPAMBlock").withPlural("ipamblocks")
            .withScope("Cluster").build();

    /** IPReservation（projectcalico.org/v3，集群级）——保留段。spec = reservedCIDRs（CIDR 列表）。group/字段名以 tigera 文档为准（实现时核对）。 */
    private static final CustomResourceDefinitionContext IP_RESERVATION_CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup("projectcalico.org").withVersion("v3").withKind("IPReservation").withPlural("ipreservations")
            .withScope("Cluster").build();

    /** 单块 per-IP 枚举上限（防客户端传入超大 CIDR 触发 OOM；正常块 ≤64）。 */
    private static final long MAX_BLOCK_ENUM = 4096;
    /** nextFreeBlocks walk 迭代安全上限（防病态池死循环）。 */
    private static final int WALK_CAP = 200_000;

    private final KubernetesClient client;
    private final IppoolConverter ippoolConverter;

    public CalicoIpamOperations(KubernetesClient client, IppoolConverter ippoolConverter) {
        this.client = client;
        this.ippoolConverter = ippoolConverter;
    }

    // ==================== public fetch（读源 → 委托纯计算） ====================

    /** 池 IPAM 汇总（含 allocated，供 IPPool 删除守卫）。 */
    public PoolIpamSummaryDTO poolSummary(String poolName) {
        IpoolDTO pool = getPool(poolName);
        if (pool == null || !hasText(pool.getCidr())) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "IPPool「" + poolName + "」不存在");
        }
        return summarize(poolName, pool.getCidr(), pool.getBlockSize(), readBlocks(), readReservations());
    }

    /** 已物化块表（可选按池 / 关键字过滤）。 */
    public List<IpamBlockStatDTO> listBlocks(String poolName, String search) {
        String poolCidr = null;
        if (hasText(poolName)) {
            IpoolDTO p = getPool(poolName);
            if (p != null) {
                poolCidr = p.getCidr();
            }
        }
        return blockStats(readBlocks(), poolCidr, search, readReservations());
    }

    /** 点查某 IP/块当前是否空闲（jump-to 定位）。 */
    public boolean isFree(String cidrOrIp) {
        return isFree(cidrOrIp, readBlocks(), readReservations());
    }

    /** 从池起点 walk、跳过已认领块，凑够一页停 → 「下一批空闲块」分页。 */
    public List<String> nextFreeBlocks(String poolName, int offset, int limit) {
        IpoolDTO p = getPool(poolName);
        if (p == null || !hasText(p.getCidr())) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "IPPool「" + poolName + "」不存在");
        }
        return nextFreeBlocks(p.getCidr(), p.getBlockSize(), readBlocks(), offset, limit);
    }

    /** 单块 per-IP（未物化合成全 free；allocated 用 pod.podIP 反查）。 */
    public List<IpamIpDetailDTO> blockIps(String blockCidr) {
        List<BlockInfo> blocks = readBlocks();
        BlockInfo block = findBlock(blocks, blockCidr);
        List<IpRange> res = readReservations();
        Map<String, PodRef> pods = (block != null) ? readPodIndex() : Map.of();
        return blockIps(blockCidr, block, res, pods);
    }

    // ==================== 纯计算（可脱离 client 单测） ====================

    /**
     * 池汇总。free = capacity − allocated − reserved（含未物化块空间，spec §6.4）。
     * capacity 纯 CIDR 算术（溢出饱和）；allocated 只来自已物化非软删块；reserved 纯区间算术。
     */
    public PoolIpamSummaryDTO summarize(String poolName, String poolCidr, Integer blockSize,
                                        List<BlockInfo> allBlocks, List<IpRange> reservations) {
        IpRange poolAlloc = allocatableRangeOfCidr(poolCidr);
        long capacity = count(poolAlloc);
        List<IpRange> merged = merge(reservations);
        long reservedPool = overlapCount(poolAlloc, merged);

        long allocated = 0;
        int blockCount = 0;
        for (BlockInfo b : allBlocks) {
            if (b.deleted()) {
                continue;
            }
            IpRange blockAlloc = allocatableRangeOfCidr(b.cidr());
            if (!contains(poolAlloc, blockAlloc)) {
                continue; // 块不属于本池（Calico 禁池重叠 → 无歧义）
            }
            long totalIps = count(blockAlloc);
            long freeInBlock = countUnallocated(b.unallocated(), blockAlloc);
            allocated += (totalIps - freeInBlock);
            blockCount++;
        }
        long free = Math.max(0, capacity - allocated - reservedPool);

        PoolIpamSummaryDTO s = new PoolIpamSummaryDTO();
        s.setPoolName(poolName);
        s.setCidr(poolCidr);
        s.setBlockSize(blockSize);
        s.setCapacity(capacity);
        s.setAllocated(allocated);
        s.setFree(free);
        s.setReserved(reservedPool);
        s.setBlockCount(blockCount);
        return s;
    }

    /** 已物化块表（三态划分 free+reserved+allocated=totalIps；可选按池 / 关键字过滤）。 */
    public List<IpamBlockStatDTO> blockStats(List<BlockInfo> allBlocks, String poolCidr, String search,
                                             List<IpRange> reservations) {
        IpRange poolAlloc = hasText(poolCidr) ? allocatableRangeOfCidr(poolCidr) : null;
        List<IpRange> merged = merge(reservations);
        String kw = hasText(search) ? search.trim().toLowerCase() : null;

        List<IpamBlockStatDTO> out = new ArrayList<>();
        for (BlockInfo b : allBlocks) {
            if (b.deleted()) {
                continue;
            }
            IpRange blockAlloc = allocatableRangeOfCidr(b.cidr());
            if (poolAlloc != null && !contains(poolAlloc, blockAlloc)) {
                continue;
            }
            long totalIps = count(blockAlloc);
            long free = 0;
            long reserved = 0;
            Set<BigInteger> unallocSet = b.unallocated();
            if (count(blockAlloc) <= MAX_BLOCK_ENUM) {
                // 逐 IP 三态划分（块小，有界）：free / reserved（空闲且被保留）/ allocated
                for (BigInteger ip = blockAlloc.start(); ip.compareTo(blockAlloc.end()) <= 0; ip = ip.add(ONE)) {
                    if (unallocSet.contains(ip)) {
                        if (inRange(ip, merged)) {
                            reserved++;
                        } else {
                            free++;
                        }
                    } else {
                        // allocated（下面统一算）
                    }
                }
            } else {
                // 防御：异常大块不逐 IP，退化为计数（reserved 走区间算术近似）
                free = countUnallocated(b.unallocated(), blockAlloc);
                reserved = overlapCount(blockAlloc, merged);
            }
            long allocated = totalIps - free - reserved;

            IpamBlockStatDTO stat = new IpamBlockStatDTO();
            stat.setCidr(b.cidr());
            stat.setNode(b.node());
            stat.setTotalIps(totalIps);
            stat.setAllocated(allocated);
            stat.setFree(free);
            stat.setReserved(reserved);

            if (kw != null) {
                boolean hit = b.cidr().toLowerCase().contains(kw)
                        || (b.node() != null && b.node().toLowerCase().contains(kw));
                if (!hit) {
                    continue;
                }
            }
            out.add(stat);
        }
        return out;
    }

    /** 点查：范围内无任何已分配 IP 且无任何保留 IP → true。O(重叠块 × blockSize)。 */
    public boolean isFree(String cidrOrIp, List<BlockInfo> allBlocks, List<IpRange> reservations) {
        IpRange q = parseQuery(cidrOrIp);
        if (q == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "无法解析 IP/CIDR：" + cidrOrIp);
        }
        List<IpRange> merged = merge(reservations);
        if (overlapCount(q, merged) > 0) {
            return false; // 范围内有保留 IP
        }
        for (BlockInfo b : allBlocks) {
            if (b.deleted()) {
                continue;
            }
            IpRange blockAlloc = allocatableRangeOfCidr(b.cidr());
            IpRange sub = intersect(blockAlloc, q);
            if (sub == null) {
                continue; // 不重叠
            }
            long totalInSub = count(sub);
            long freeInSub = countUnallocated(b.unallocated(), sub);
            if ((totalInSub - freeInSub) > 0) {
                return false; // 范围内有已分配 IP
            }
        }
        return true;
    }

    /**
     * 下一批空闲块：从池网络基址按 blockSize walk，跳过已认领块（缺席判定），凑够 limit 停。
     * O(跳过的已认领 + offset + limit) ≤ O(M)，绝不枚举未创建补集之外的空间。
     */
    public List<String> nextFreeBlocks(String poolCidr, Integer blockSize, List<BlockInfo> allBlocks,
                                       int offset, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        boolean v6 = isIpv6(poolCidr);
        int bits = v6 ? 128 : 32;
        int effectiveBlockSize = blockSize != null ? blockSize : (v6 ? 122 : 26);
        long blockAddrCount = 1L << (bits - effectiveBlockSize);

        BigInteger walkBase = networkStart(poolCidr);
        BigInteger poolEndExclusive = networkStart(poolCidr).add(BigInteger.ONE.shiftLeft(bits - prefixOf(poolCidr)));

        Set<BigInteger> claimed = new HashSet<>();
        for (BlockInfo b : allBlocks) {
            if (!b.deleted()) {
                claimed.add(networkStart(b.cidr()));
            }
        }

        List<String> out = new ArrayList<>();
        BigInteger addr = walkBase;
        int freeSeen = 0;
        int iterations = 0;
        while (out.size() < limit && addr.compareTo(poolEndExclusive) < 0 && iterations < WALK_CAP) {
            if (!claimed.contains(addr)) {
                if (freeSeen >= offset) {
                    out.add(toCidr(addr, effectiveBlockSize, v6));
                }
                freeSeen++;
            }
            addr = addr.add(BigInteger.valueOf(blockAddrCount));
            iterations++;
        }
        return out;
    }

    /** 单块 per-IP：已物化读 unallocated；未物化合成全 free；叠保留段标 reserved；allocated 反查 pod。 */
    public List<IpamIpDetailDTO> blockIps(String blockCidr, BlockInfo block, List<IpRange> reservations,
                                          Map<String, PodRef> ipToPod) {
        IpRange r = allocatableRangeOfCidr(blockCidr);
        if (count(r) > MAX_BLOCK_ENUM) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "块过大（可分配地址 > " + MAX_BLOCK_ENUM + "），无法逐 IP 展开：" + blockCidr);
        }
        List<IpRange> merged = merge(reservations);
        Set<BigInteger> unallocSet = (block != null) ? block.unallocated() : Set.of();
        String node = (block != null) ? block.node() : null;

        List<IpamIpDetailDTO> out = new ArrayList<>((int) count(r));
        for (BigInteger ip = r.start(); ip.compareTo(r.end()) <= 0; ip = ip.add(ONE)) {
            IpamIpDetailDTO d = new IpamIpDetailDTO();
            d.setIp(bigIntToIp(ip, isIpv6(blockCidr)));
            d.setNode(node);
            boolean freeSlot = (block == null) || unallocSet.contains(ip);
            if (freeSlot) {
                d.setStatus(inRange(ip, merged) ? "reserved" : "free");
            } else {
                d.setStatus("allocated");
                PodRef pod = ipToPod.get(d.getIp());
                if (pod != null) {
                    d.setPodName(pod.name());
                    d.setPodNamespace(pod.namespace());
                }
            }
            out.add(d);
        }
        return out;
    }

    // ==================== 读源（fabric8） ====================

    private IpoolDTO getPool(String name) {
        if (!hasText(name)) {
            return null;
        }
        GenericKubernetesResource res = client.genericKubernetesResources(IPPOOL_CRD).withName(name).get();
        return ippoolConverter.revert(res);
    }

    private List<BlockInfo> readBlocks() {
        List<GenericKubernetesResource> items = client.genericKubernetesResources(IPAM_BLOCK_CRD)
                .list().getItems();
        List<BlockInfo> out = new ArrayList<>(items.size());
        for (GenericKubernetesResource it : items) {
            BlockInfo b = parseBlock(it);
            if (b != null) {
                out.add(b);
            }
        }
        return out;
    }

    private List<IpRange> readReservations() {
        List<GenericKubernetesResource> items = client.genericKubernetesResources(IP_RESERVATION_CRD)
                .list().getItems();
        List<IpRange> out = new ArrayList<>(items.size());
        for (GenericKubernetesResource it : items) {
            out.addAll(parseReservation(it));
        }
        return out;
    }

    private Map<String, PodRef> readPodIndex() {
        List<Pod> pods = client.pods().inAnyNamespace().list().getItems();
        Map<String, PodRef> map = new HashMap<>();
        for (Pod p : pods) {
            String ip = p.getStatus() != null ? p.getStatus().getPodIP() : null;
            if (!hasText(ip) || p.getMetadata() == null) {
                continue;
            }
            map.put(ip, new PodRef(p.getMetadata().getName(), p.getMetadata().getNamespace(), p.getSpec() != null ? p.getSpec().getNodeName() : null));
        }
        return map;
    }

    /**
     * 解析单个 IPAMBlock CRD → {@link BlockInfo}。
     * <p><b>真实 Calico spec 语义（projectcalico/api IPAMBlockSpec）</b>：
     * <ul>
     *   <li>{@code unallocated}：块内「空闲偏移」的<b>整数列表</b>（0-based ordinal，IP = 块基址 + offset），<b>不是 IP 字符串</b>；</li>
     *   <li>{@code allocations}：位图数组（index = 偏移，null=空闲、非 null=已分配）——unallocated 缺席时兜底；</li>
     *   <li>{@code affinity}：{@code "host:<node>"} / {@code "virtual:<node>"}；{@code deleted}：软删标记。</li>
     * </ul>
     * 空闲集在此统一折算成<b>绝对 IP（BigInteger）</b>，下游纯计算只认绝对值。
     */
    @SuppressWarnings("unchecked")
    BlockInfo parseBlock(GenericKubernetesResource res) {
        Map<String, Object> spec = res.getAdditionalProperties() != null
                ? (Map<String, Object>) res.getAdditionalProperties().get("spec") : null;
        if (spec == null || !(spec.get("cidr") instanceof String cidr)) {
            return null;
        }
        BigInteger base;
        try {
            base = networkStart(cidr);
        } catch (Exception e) {
            return null;
        }
        Set<BigInteger> free = new HashSet<>();
        Object unallocObj = spec.get("unallocated");
        if (unallocObj instanceof List<?> list) {
            // 权威来源：空闲偏移整数列表（全满块时为空 → free 空集）
            for (Object o : list) {
                if (o instanceof Number n) {
                    long off = n.longValue();
                    if (off >= 0) {
                        free.add(base.add(BigInteger.valueOf(off)));
                    }
                }
            }
        } else if (spec.get("allocations") instanceof List<?> allocs) {
            // 兜底：位图（index=偏移，null=空闲）
            for (int i = 0; i < allocs.size(); i++) {
                if (allocs.get(i) == null) {
                    free.add(base.add(BigInteger.valueOf(i)));
                }
            }
        }
        String node = null;
        if (spec.get("affinity") instanceof String aff && aff.startsWith("host:")) {
            node = aff.substring("host:".length());
        }
        boolean deleted = Boolean.TRUE.equals(spec.get("deleted"));
        return new BlockInfo(cidr, free, node, deleted);
    }

    /** 解析单个 IPReservation → 保留区间列表（spec.reservedCIDRs 每个 CIDR 展开成满块闭区间）。 */
    private List<IpRange> parseReservation(GenericKubernetesResource res) {
        Map<String, Object> spec = res.getAdditionalProperties() != null
                ? (Map<String, Object>) res.getAdditionalProperties().get("spec") : null;
        if (spec == null || !(spec.get("reservedCIDRs") instanceof List<?> list)) {
            return List.of();
        }
        List<IpRange> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof String cidr && hasText(cidr)) {
                try {
                    out.add(allocatableRangeOfCidr(cidr));
                } catch (Exception e) {
                    log.warn("解析 IPReservation reservedCIDR 失败（{}）: {}", cidr, e.getMessage());
                }
            }
        }
        return out;
    }

    private BlockInfo findBlock(List<BlockInfo> blocks, String cidr) {
        for (BlockInfo b : blocks) {
            if (!b.deleted() && b.cidr().equalsIgnoreCase(cidr)) {
                return b;
            }
        }
        return null;
    }

    // ==================== IP/CIDR 区间算术（BigInteger，v4/v6 统一） ====================

    private static final BigInteger ONE = BigInteger.ONE;

    /** 已解析块视图：cidr + 空闲集（绝对 IP，由 unallocated 偏移折算）+ 归属节点 + 软删标记。 */
    public record BlockInfo(String cidr, Set<BigInteger> unallocated, String node, boolean deleted) {}

    /** 可分配地址闭区间 [start, end]（BigInteger；Calico 用满整块，含传统 network/broadcast）。 */
    public record IpRange(BigInteger start, BigInteger end) {}

    /** allocated IP 反查到的 Pod 引用。 */
    public record PodRef(String name, String namespace, String node) {}

    private static int prefixOf(String cidr) {
        int slash = cidr.indexOf('/');
        return slash < 0 ? (isIpv6(cidr) ? 128 : 32) : Integer.parseInt(cidr.substring(slash + 1).trim());
    }

    private static boolean isIpv6(String ipOrCidr) {
        String ip = ipOrCidr.contains("/") ? ipOrCidr.substring(0, ipOrCidr.indexOf('/')) : ipOrCidr;
        return ip.contains(":");
    }

    /** 单 IP / CIDR → BigInteger（字节序）。 */
    private static BigInteger ipToBigInt(String ip) throws Exception {
        byte[] bytes = InetAddress.getByName(ip.trim()).getAddress();
        return new BigInteger(1, bytes);
    }

    /** BigInteger → IP 字符串（按族取低 4/16 字节）。 */
    private static String bigIntToIp(BigInteger v, boolean v6) {
        int len = v6 ? 16 : 4;
        byte[] full = new byte[len];
        byte[] raw = v.toByteArray();
        int off = full.length - Math.min(raw.length, full.length);
        System.arraycopy(raw, 0, full, off, Math.min(raw.length, full.length));
        try {
            return InetAddress.getByAddress(full).getHostAddress();
        } catch (Exception e) {
            return v.toString();
        }
    }

    /** CIDR → 网络基址（host 位清零）。 */
    private static BigInteger networkStart(String cidr) throws CloudPlatformException {
        int prefix = prefixOf(cidr);
        boolean v6 = isIpv6(cidr);
        int bits = v6 ? 128 : 32;
        String ipPart = cidr.contains("/") ? cidr.substring(0, cidr.indexOf('/')) : cidr;
        try {
            BigInteger base = ipToBigInt(ipPart);
            int hostBits = bits - prefix;
            return base.shiftRight(hostBits).shiftLeft(hostBits);
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "无法解析 CIDR：" + cidr);
        }
    }

    /** CIDR → 可分配地址闭区间（Calico 用满整块：偏移 0..2^N−1，含传统 network/broadcast；v4/v6 一致）。 */
    private static IpRange allocatableRangeOfCidr(String cidr) {
        int prefix = prefixOf(cidr);
        boolean v6 = isIpv6(cidr);
        int bits = v6 ? 128 : 32;
        BigInteger start = networkStart(cidr);
        BigInteger total = BigInteger.ONE.shiftLeft(bits - prefix);
        BigInteger end = start.add(total).subtract(ONE);
        return new IpRange(start, end);
    }

    /** 单 IP / CIDR → 查询区间（单 IP = [ip,ip]；CIDR = 可分配区间）。 */
    private static IpRange parseQuery(String cidrOrIp) {
        if (!hasText(cidrOrIp)) {
            return null;
        }
        String s = cidrOrIp.trim();
        try {
            if (s.contains("/")) {
                return allocatableRangeOfCidr(s);
            }
            BigInteger ip = ipToBigInt(s);
            return new IpRange(ip, ip);
        } catch (Exception e) {
            return null;
        }
    }

    private static String toCidr(BigInteger addr, int prefix, boolean v6) {
        return bigIntToIp(addr, v6) + "/" + prefix;
    }

    /** 闭区间地址数（溢出饱和到 Long.MAX_VALUE）。 */
    private static long count(IpRange r) {
        if (r == null || r.start().compareTo(r.end()) > 0) {
            return 0;
        }
        BigInteger c = r.end().subtract(r.start()).add(ONE);
        return saturate(c);
    }

    private static long saturate(BigInteger v) {
        if (v.bitLength() >= 63) {
            return Long.MAX_VALUE;
        }
        return v.longValue();
    }

    /** a ⊇ b？（b 完全落在 a 内） */
    private static boolean contains(IpRange a, IpRange b) {
        return a.start().compareTo(b.start()) <= 0 && a.end().compareTo(b.end()) >= 0;
    }

    /** 两闭区间交集；不相交返回 null。 */
    private static IpRange intersect(IpRange a, IpRange b) {
        BigInteger lo = a.start().max(b.start());
        BigInteger hi = a.end().min(b.end());
        return lo.compareTo(hi) > 0 ? null : new IpRange(lo, hi);
    }

    /** 闭区间与「已合并保留段」的交集地址数（saturating）。 */
    private static long overlapCount(IpRange r, List<IpRange> mergedRes) {
        long total = 0;
        for (IpRange m : mergedRes) {
            IpRange inter = intersect(r, m);
            if (inter != null) {
                total += count(inter);
            }
        }
        return total > Long.MAX_VALUE ? Long.MAX_VALUE : total;
    }

    /** ip 是否落在任一保留段。 */
    private static boolean inRange(BigInteger ip, List<IpRange> mergedRes) {
        for (IpRange m : mergedRes) {
            if (ip.compareTo(m.start()) >= 0 && ip.compareTo(m.end()) <= 0) {
                return true;
            }
        }
        return false;
    }

    /** 空闲集（绝对 IP）中落在区间 r 内的地址数（O(空闲数)，有界）。 */
    private static long countUnallocated(Set<BigInteger> free, IpRange r) {
        if (free == null || free.isEmpty()) {
            return 0;
        }
        long n = 0;
        for (BigInteger v : free) {
            if (v.compareTo(r.start()) >= 0 && v.compareTo(r.end()) <= 0) {
                n++;
            }
        }
        return n;
    }

    /** 合并保留段为不相交、升序的区间列表（消除重叠，避免重复计数）。 */
    private static List<IpRange> merge(List<IpRange> ranges) {
        if (ranges == null || ranges.isEmpty()) {
            return List.of();
        }
        List<IpRange> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparing(IpRange::start));
        List<IpRange> out = new ArrayList<>();
        BigInteger curStart = sorted.get(0).start();
        BigInteger curEnd = sorted.get(0).end();
        for (int i = 1; i < sorted.size(); i++) {
            IpRange r = sorted.get(i);
            if (r.start().compareTo(curEnd.add(ONE)) <= 0) {
                // 相邻或重叠 → 扩展
                if (r.end().compareTo(curEnd) > 0) {
                    curEnd = r.end();
                }
            } else {
                out.add(new IpRange(curStart, curEnd));
                curStart = r.start();
                curEnd = r.end();
            }
        }
        out.add(new IpRange(curStart, curEnd));
        return out;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

}
