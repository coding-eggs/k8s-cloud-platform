package com.coding.platformapi.metrics;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.DeploymentDTO;
import com.coding.common.models.k8s.dto.ReplicaSetDTO;
import com.coding.common.models.k8s.dto.WorkloadDTO;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.metrics.dto.ClusterMetricsRequest;
import com.coding.platformapi.metrics.dto.MetricPoint;
import com.coding.platformapi.metrics.dto.MetricSeries;
import com.coding.platformapi.metrics.dto.MetricSeriesResponse;
import com.coding.platformapi.metrics.dto.MeshTopologyResponse;
import com.coding.platformapi.metrics.dto.NodeCurrentMetric;
import com.coding.platformapi.metrics.dto.NodeMetricsRequest;
import com.coding.platformapi.metrics.dto.NamespaceMetricsRequest;
import com.coding.platformapi.metrics.dto.WorkloadMetricsRequest;
import com.coding.platformapi.metrics.labels.DeviceLabels;
import com.coding.platformapi.metrics.labels.MeshEdgeLabels;
import com.coding.platformapi.metrics.labels.MeshErrorLabels;
import com.coding.platformapi.metrics.labels.MeshLatencyLabels;
import com.coding.platformapi.metrics.labels.MetricLabels;
import com.coding.platformapi.metrics.labels.NamespaceLabels;
import com.coding.platformapi.metrics.model.PromResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 指标业务层：每个方法对应一个接口，**各自拼自己的 sql**（占位符数量/顺序由该条查询自定）、
 * 各自调 {@code client.queryRange}、各自组响应。不抽统一的"拼 SQL + 填参"层——不同查询占位符不同，统一抽象反而写不动。
 * <p>仅保留纯机械转换：{@link #clusterName}（clusterId→名）、{@link #toSeries}（Thanos 行→点序列）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsService {

    /** step（秒）：当前默认 30s；要更大时间宽度时调这里 + 前端预设即可，接口不改。 */
    private static final int STEP = 15;

    private final K8sClusterMapper clusterMapper;
    private final ThanosQueryClient client;
    /** 通用 k8s-server 资源 client：Deployment 指标需先列出其名下 ReplicaSet（见 {@link #resolveOwner}）。 */
    private final K8sClient resourceClient;

    // ===== Pod 维度（占位符：cluster, ns, pod[, 方向]）=====

    public MetricSeriesResponse podCpu(WorkloadMetricsRequest req) {
        String sql = String.format(MetricQuery.POD_CPU_USED.sql(), clusterName(req.getClusterId()), req.getNamespace(), req.getName());
        return new MetricSeriesResponse("核", List.of(toSeries("用量", query(sql, req))));
    }

    public MetricSeriesResponse podMemory(WorkloadMetricsRequest req) {
        String sql = String.format(MetricQuery.POD_MEMORY_USED.sql(),
                clusterName(req.getClusterId()), req.getNamespace(), req.getName());
        return new MetricSeriesResponse("字节", List.of(toSeries("用量", query(sql, req))));
    }

    public MetricSeriesResponse podNetwork(WorkloadMetricsRequest req) {
        String c = clusterName(req.getClusterId());
        // 本条占位符顺序：direction, cluster, ns, pod；方向 %s = receive|transmit → RX / TX（netns 在 pod 级，container="POD"）
        return new MetricSeriesResponse("字节/秒", List.of(
                toSeries("RX", query(String.format(MetricQuery.POD_NETWORK_RECEIVE.sql(), c, req.getNamespace(), req.getName()), req)),
                toSeries("TX", query(String.format(MetricQuery.POD_NETWORK_TRANSMIT.sql(), c, req.getNamespace(), req.getName()), req))));
    }

    public MetricSeriesResponse podDisk(WorkloadMetricsRequest req) {
        String c = clusterName(req.getClusterId());
        // 本条占位符顺序：direction, cluster, ns, pod；方向 %s = reads|writes → 读 / 写
        return new MetricSeriesResponse("字节/秒", List.of(
                toSeries("读", query(String.format(MetricQuery.POD_DISK_READ.sql(),  c, req.getNamespace(), req.getName()), req)),
                toSeries("写", query(String.format(MetricQuery.POD_DISK_WRITE.sql(),  c, req.getNamespace(), req.getName()), req))));
    }

    // ===== 工作负载维度（占位符 = owner_kind, owner_name[正则]；网络/磁盘再前置一个方向）=====
    // pod 归并到工作负载靠 kube_pod_owner。Deployment 的 pod 实际归 ReplicaSet 管，不能直接按
    // owner_kind=Deployment 查——resolveOwner 先列出其名下 RS，把 RS 名拼成 owner_name 正则。

    public MetricSeriesResponse workloadCpu(WorkloadMetricsRequest req) {
        Owner o = resolveOwner(req);
        String sql = String.format(MetricQuery.WORKLOAD_CPU_USED.sql(), o.kind(), o.nameRegex());
        return new MetricSeriesResponse("核", List.of(toSeries("用量", query(sql, req))));
    }

    public MetricSeriesResponse workloadMemory(WorkloadMetricsRequest req) {
        Owner o = resolveOwner(req);
        String sql = String.format(MetricQuery.WORKLOAD_MEMORY_USED.sql(), o.kind(), o.nameRegex());
        return new MetricSeriesResponse("字节", List.of(toSeries("用量", query(sql, req))));
    }

    public MetricSeriesResponse workloadNetwork(WorkloadMetricsRequest req) {
        Owner o = resolveOwner(req);
        // 方向 %s = receive|transmit（netns 在 pod 级，container="POD"），后两位 owner_kind/owner_name
        String rx = String.format(MetricQuery.WORKLOAD_NETWORK_RECEIVE.sql(), o.kind(), o.nameRegex());
        String tx = String.format(MetricQuery.WORKLOAD_NETWORK_TRANSMIT.sql(),  o.kind(), o.nameRegex());
        return new MetricSeriesResponse("字节/秒", List.of(
                toSeries("RX", query(rx, req)),
                toSeries("TX", query(tx, req))));
    }

    public MetricSeriesResponse workloadDisk(WorkloadMetricsRequest req) {
        Owner o = resolveOwner(req);
        String read = String.format(MetricQuery.WORKLOAD_DISK_READ.sql(), o.kind(), o.nameRegex());
        String write = String.format(MetricQuery.WORKLOAD_DISK_WRITE.sql(), o.kind(), o.nameRegex());
        return new MetricSeriesResponse("字节/秒", List.of(
                toSeries("读", query(read, req)),
                toSeries("写", query(write, req))));
    }

    // ===== 中间件实例维度（%s = clusterName, namespace, pod 名正则）=====
    // 一个实例可能由多个工作负载组成（ES+Kibana、Redis 哨兵两个 CR、Kafka 的 StrimziPodSet），
    // 按 owner 归并覆盖不全，改由调用方给「该实例全部 Pod 名」拼成的正则。
    public com.coding.platformapi.metrics.dto.MiddlewareMetricsResponse middlewareInstance(
            String clusterId, String namespace, String podRegex, long start, long end) {
        String c = clusterName(clusterId);
        return new com.coding.platformapi.metrics.dto.MiddlewareMetricsResponse(
                new MetricSeriesResponse("核", List.of(toSeries("用量", queryAt(
                        String.format(MetricQuery.INSTANCE_CPU_USED.sql(), c, namespace, podRegex), start, end)))),
                new MetricSeriesResponse("字节", List.of(toSeries("用量", queryAt(
                        String.format(MetricQuery.INSTANCE_MEMORY_USED.sql(), c, namespace, podRegex), start, end)))),
                new MetricSeriesResponse("字节/秒", List.of(
                        toSeries("RX", queryAt(String.format(MetricQuery.INSTANCE_NETWORK_RECEIVE.sql(), c, namespace, podRegex), start, end)),
                        toSeries("TX", queryAt(String.format(MetricQuery.INSTANCE_NETWORK_TRANSMIT.sql(), c, namespace, podRegex), start, end)))),
                new MetricSeriesResponse("字节/秒", List.of(
                        toSeries("读", queryAt(String.format(MetricQuery.INSTANCE_DISK_READ.sql(), c, namespace, podRegex), start, end)),
                        toSeries("写", queryAt(String.format(MetricQuery.INSTANCE_DISK_WRITE.sql(), c, namespace, podRegex), start, end)))),
                List.of());
    }

    /**
     * 一条业务指标：PromQL 由 {@code MiddlewareCatalog} 声明（三个 {@code %s} = cluster / namespace / pod 正则），
     * 这里只负责把实例维度套进去。
     */
    public MetricSeriesResponse businessMetric(String clusterId, String namespace, String podRegex,
                                               String query, String unit, long start, long end) {
        String c = clusterName(clusterId);
        return new MetricSeriesResponse(unit, List.of(toSeries("用量",
                queryAt(String.format(query, c, namespace, podRegex), start, end))));
    }

    // ===== 节点维度（%s = instance，形如 "<internalIp>:9100"）=====

    public MetricSeriesResponse nodeCpu(NodeMetricsRequest req) {
        String sql = String.format(MetricQuery.NODE_CPU_USED.sql(), req.getInstance());
        return new MetricSeriesResponse("%", List.of(toSeries("使用率", queryNode(sql, req))));
    }

    public MetricSeriesResponse nodeMemory(NodeMetricsRequest req) {
        String i = req.getInstance();
        return new MetricSeriesResponse("字节", List.of(
                toSeries("Used", queryNode(String.format(MetricQuery.NODE_MEMORY_USED.sql(), i, i), req)),
                toSeries("Cached", queryNode(String.format(MetricQuery.NODE_MEMORY_CACHED.sql(), i), req)),
                toSeries("Buffers", queryNode(String.format(MetricQuery.NODE_MEMORY_BUFFERS.sql(), i), req)),
                toSeries("Free", queryNode(String.format(MetricQuery.NODE_MEMORY_FREE.sql(), i), req))));
    }

    public MetricSeriesResponse nodeNetwork(NodeMetricsRequest req) {
        String i = req.getInstance();
        return new MetricSeriesResponse("字节/秒", List.of(
                toSeries("RX", queryNode(String.format(MetricQuery.NODE_NETWORK_RECEIVE.sql(), i), req)),
                toSeries("TX", queryNode(String.format(MetricQuery.NODE_NETWORK_TRANSMIT.sql(), i), req))));
    }

    /** 磁盘按设备拆线：读/写各一条，图例 = "<device> 读" / "<device> 写"。 */
    public MetricSeriesResponse nodeDisk(NodeMetricsRequest req) {
        String i = req.getInstance();
        List<PromResult<DeviceLabels>> read = client.queryRange(
                String.format(MetricQuery.NODE_DISK_READ.sql(), i), req.getStart(), req.getEnd(), STEP, DeviceLabels.class);
        List<PromResult<DeviceLabels>> write = client.queryRange(
                String.format(MetricQuery.NODE_DISK_WRITE.sql(), i), req.getStart(), req.getEnd(), STEP, DeviceLabels.class);
        Map<String, List<MetricPoint>> readByDev = groupByDevice(read);
        Map<String, List<MetricPoint>> writeByDev = groupByDevice(write);
        Set<String> devices = new LinkedHashSet<>();
        devices.addAll(readByDev.keySet());
        devices.addAll(writeByDev.keySet());
        List<MetricSeries> series = new ArrayList<>();
        for (String dev : devices) {
            MetricSeries r = new MetricSeries();
            r.setLegend(dev + " 读");
            r.setPoints(readByDev.getOrDefault(dev, List.of()));
            series.add(r);
            MetricSeries w = new MetricSeries();
            w.setLegend(dev + " 写");
            w.setPoints(writeByDev.getOrDefault(dev, List.of()));
            series.add(w);
        }
        return new MetricSeriesResponse("字节/秒", series);
    }

    /** 列表页当前用量：逐 instance 即时查 CPU%/内存%（无数据返回 null，前端显示 "-"）。 */
    public Map<String, NodeCurrentMetric> nodeCurrents(String clusterId, List<String> instances) {
        clusterName(clusterId); // 校验集群存在/未删
        Map<String, NodeCurrentMetric> out = new LinkedHashMap<>();
        if (instances == null) {
            return out;
        }
        for (String inst : instances) {
            if (!StringUtils.hasText(inst)) {
                continue;
            }
            NodeCurrentMetric m = new NodeCurrentMetric();
            m.setCpuPercent(firstInstant(String.format(MetricQuery.NODE_CPU_USED.sql(), inst)));
            m.setMemPercent(firstInstant(String.format(MetricQuery.NODE_MEM_PERCENT.sql(), inst, inst, inst)));
            out.put(inst, m);
        }
        return out;
    }

    // ===== 命名空间维度（占位符 = clusterName, namespace；跨该 ns 全部 pod 聚合）=====

    public MetricSeriesResponse namespaceCpu(NamespaceMetricsRequest req) {
        String sql = String.format(MetricQuery.NAMESPACE_CPU_USED.sql(), clusterName(req.getClusterId()), req.getNamespace());
        return new MetricSeriesResponse("核", List.of(toSeries("用量", queryNamespace(sql, req))));
    }

    public MetricSeriesResponse namespaceMemory(NamespaceMetricsRequest req) {
        String sql = String.format(MetricQuery.NAMESPACE_MEMORY_USED.sql(), clusterName(req.getClusterId()), req.getNamespace());
        return new MetricSeriesResponse("字节", List.of(toSeries("用量", queryNamespace(sql, req))));
    }

    public MetricSeriesResponse namespaceNetwork(NamespaceMetricsRequest req) {
        String c = clusterName(req.getClusterId());
        return new MetricSeriesResponse("字节/秒", List.of(
                toSeries("RX", queryNamespace(String.format(MetricQuery.NAMESPACE_NETWORK_RECEIVE.sql(), c, req.getNamespace()), req)),
                toSeries("TX", queryNamespace(String.format(MetricQuery.NAMESPACE_NETWORK_TRANSMIT.sql(), c, req.getNamespace()), req))));
    }

    public MetricSeriesResponse namespaceDisk(NamespaceMetricsRequest req) {
        String c = clusterName(req.getClusterId());
        return new MetricSeriesResponse("字节/秒", List.of(
                toSeries("读", queryNamespace(String.format(MetricQuery.NAMESPACE_DISK_READ.sql(), c, req.getNamespace()), req)),
                toSeries("写", queryNamespace(String.format(MetricQuery.NAMESPACE_DISK_WRITE.sql(), c, req.getNamespace()), req))));
    }

    // ===== 集群维度（占位符 = clusterName；无 ns 过滤 = 全集群聚合）=====
    // 集群概览的「实时 used」口径。used 不碰 K8s 列表 —— 集群概览的 allocatable/allocated 来自
    // k8s-core 聚合快照，used 来自 Thanos，两个平面在展示层拼成三口径（spec §5.4）。

    public MetricSeriesResponse clusterCpu(ClusterMetricsRequest req) {
        String sql = String.format(MetricQuery.CLUSTER_CPU_USED.sql(), clusterName(req.getClusterId()));
        return new MetricSeriesResponse("核", List.of(toSeries("用量", queryCluster(sql, req))));
    }

    public MetricSeriesResponse clusterMemory(ClusterMetricsRequest req) {
        String sql = String.format(MetricQuery.CLUSTER_MEMORY_USED.sql(), clusterName(req.getClusterId()));
        return new MetricSeriesResponse("字节", List.of(toSeries("用量", queryCluster(sql, req))));
    }

    /** 各命名空间的 CPU 用量：一条序列 = 一个命名空间，图例 = 命名空间名（与明细表行对齐）。 */
    public MetricSeriesResponse clusterCpuByNamespace(ClusterMetricsRequest req) {
        String sql = String.format(MetricQuery.CLUSTER_CPU_USED_BY_NS.sql(), clusterName(req.getClusterId()));
        return new MetricSeriesResponse("核", seriesByNamespace(queryClusterByNs(sql, req)));
    }

    /** 各命名空间的内存用量（字节）：同 {@link #clusterCpuByNamespace}。 */
    public MetricSeriesResponse clusterMemoryByNamespace(ClusterMetricsRequest req) {
        String sql = String.format(MetricQuery.CLUSTER_MEMORY_USED_BY_NS.sql(), clusterName(req.getClusterId()));
        return new MetricSeriesResponse("字节", seriesByNamespace(queryClusterByNs(sql, req)));
    }

    // ===== 租户维度（占位符 = clusterName；复用集群的 by(namespace) 语句，用租户的 ns 集合在内存里过滤）=====
    // R10：曲线**不**放 TenantOverviewService —— 它只编排快照/明细；曲线归指标层（同 ClusterController 对集群指标）。
    // R12：作用域是**成对**的 (clusterId → 该集群下本租户的命名空间)，不是两个扁平列表 —— 否则同名 ns 归属别的租户时
    //      会把别人的序列静默并进来。逐集群各查各的、按**该集群自己的 ns 集合**过滤 legend，再把跨集群**同名** ns 的值相加
    //      （同名且都在本租户名下才是对的相加）。有意**不改** clusterCpuByNamespace：那是集群页的 per-ns 多线图。

    /** 租户 CPU 用量曲线（核）：一条「租户合计」；作用域成对给出（clusterId → 该集群下本租户的命名空间）。 */
    public MetricSeriesResponse tenantCpu(String tenantId, Map<String, List<String>> namespacesByCluster, long start, long end) {
        return new MetricSeriesResponse("核",
                List.of(tenantAggregate(tenantId, namespacesByCluster, start, end, MetricQuery.CLUSTER_CPU_USED_BY_NS)));
    }

    /** 租户内存用量曲线（字节）：同 {@link #tenantCpu}。 */
    public MetricSeriesResponse tenantMemory(String tenantId, Map<String, List<String>> namespacesByCluster, long start, long end) {
        return new MetricSeriesResponse("字节",
                List.of(tenantAggregate(tenantId, namespacesByCluster, start, end, MetricQuery.CLUSTER_MEMORY_USED_BY_NS)));
    }

    /**
     * 逐集群取 by(namespace) 序列 → 只留**该集群**下本租户的 ns（过滤 legend）→ 逐时间点求和成一条「租户合计」。
     * <p>按集群各自的 ns 集合过滤（成对作用域）：同名 ns 只有在**各集群都属于本租户**时才会相加，
     * 别的租户在同一集群里占用的同名 ns 不会被并进来。
     */
    private MetricSeries tenantAggregate(String tenantId, Map<String, List<String>> namespacesByCluster,
                                         long start, long end, MetricQuery query) {
        // TreeMap：以时间戳升序收敛（同一 ts 跨集群 merge 成一个值）
        Map<Long, Double> sums = new TreeMap<>();
        int clusters = 0;
        if (namespacesByCluster != null) {
            for (Map.Entry<String, List<String>> entry : namespacesByCluster.entrySet()) {
                Set<String> wanted = entry.getValue() == null ? Set.of() : new HashSet<>(entry.getValue());
                String sql = String.format(query.sql(), clusterName(entry.getKey()));
                for (PromResult<NamespaceLabels> r : client.queryRange(sql, start, end, STEP, NamespaceLabels.class)) {
                    String ns = (r.getMetric() != null && StringUtils.hasText(r.getMetric().getNamespace()))
                            ? r.getMetric().getNamespace() : "unknown";
                    if (!wanted.contains(ns)) {
                        continue;   // 不属于本集群下本租户的序列（别的同名 ns 即使同集群返回也滤掉）
                    }
                    for (MetricPoint p : pointsOf(r)) {
                        sums.merge(p.getTs(), p.getValue(), Double::sum);
                    }
                }
                clusters++;
            }
        }
        log.debug("租户 {} 用量曲线：聚合 {} 个集群", tenantId, clusters);

        MetricSeries s = new MetricSeries();
        s.setLegend("租户合计");
        List<MetricPoint> points = new ArrayList<>();
        sums.forEach((ts, v) -> {
            MetricPoint p = new MetricPoint();
            p.setTs(ts);
            p.setValue(v);
            points.add(p);
        });
        s.setPoints(points);
        return s;
    }

    /** 集群网络 IO（字节/秒，RX/TX）。与命名空间级同形，只是去掉了 namespace 过滤（netns 在 pod 级）。 */
    public MetricSeriesResponse clusterNetwork(ClusterMetricsRequest req) {
        String c = clusterName(req.getClusterId());
        return new MetricSeriesResponse("字节/秒", List.of(
                toSeries("RX", queryCluster(String.format(MetricQuery.CLUSTER_NETWORK_RECEIVE.sql(), c), req)),
                toSeries("TX", queryCluster(String.format(MetricQuery.CLUSTER_NETWORK_TRANSMIT.sql(), c), req))));
    }

    /** 集群磁盘 IO（字节/秒，读/写）。与命名空间级同形，设备过滤沿用 `device=~"/dev/dm-.*"`。 */
    public MetricSeriesResponse clusterDisk(ClusterMetricsRequest req) {
        String c = clusterName(req.getClusterId());
        return new MetricSeriesResponse("字节/秒", List.of(
                toSeries("读", queryCluster(String.format(MetricQuery.CLUSTER_DISK_READ.sql(), c), req)),
                toSeries("写", queryCluster(String.format(MetricQuery.CLUSTER_DISK_WRITE.sql(), c), req))));
    }

    // ===== 服务网格拓扑（S2.4）=====
    // 三条业务查询 + 一条哨兵并发发出；合并是纯内存活。

    /** 默认时间窗（秒）：与前端默认的 5m 一致。 */
    private static final int DEFAULT_TOPOLOGY_WINDOW = 300;
    /** 外部网关所在的命名空间 —— 那些节点是"外部入口"，前端画得跟业务节点不一样。 */
    private static final String INGRESS_NAMESPACE = "istio-system";
    /** 边的层级：L7 = 有请求数/响应码/延迟；L4 = 只有连接与字节。 */
    private static final String L7 = "L7";
    private static final String L4 = "L4";
    /** Istio 解析不到工作负载时给的占位值；把它当节点名画出来只会让人困惑。 */
    private static final String UNKNOWN_WORKLOAD = "unknown";

    /** 边两端（都已拼成 `命名空间/工作负载`）。 */
    private record Pair(String source, String target) { }

    /**
     * 服务网格拓扑：节点 = 工作负载（由边两端推出），边 = 谁调谁。
     *
     * <p>⚠️ {@code meshMetricsAvailable} 用**哨兵** {@code count(istio_build)} 判，不看边数 ——
     * 「没接采集」和「接了但这段时间没调用」的主查询都是空的，只有哨兵能把它们分开。
     * <p>⚠️ 延迟那条失败不算整体失败（只让 {@code p95Ms = null}）—— 少一个数不该让整张图打不开。
     */
    public MeshTopologyResponse meshTopology(String clusterId, String namespace, int windowSeconds) {
        String cluster = clusterName(clusterId);   // 同时校验集群存在/未删
        int window = windowSeconds > 0 ? windowSeconds : DEFAULT_TOPOLOGY_WINDOW;
        // 过滤片段拼在花括号内部，故有值时以 `,` 开头；空串 = 不过滤。
        // 命名空间**两端都滤**：只滤一端会漏掉"从别处进来"的边。
        String filters = StringUtils.hasText(namespace)
                ? ",source_workload_namespace=\"" + namespace + "\",destination_workload_namespace=\"" + namespace + "\""
                : "";
        String span = window + "s";

        CompletableFuture<List<PromResult<MeshEdgeLabels>>> edgesF = CompletableFuture.supplyAsync(() ->
                client.query(String.format(MetricQuery.MESH_TOPOLOGY_EDGES.sql(), cluster, filters, span),
                        null, MeshEdgeLabels.class));
        CompletableFuture<List<PromResult<MeshErrorLabels>>> errorsF = CompletableFuture.supplyAsync(() ->
                client.query(String.format(MetricQuery.MESH_TOPOLOGY_ERRORS.sql(), cluster, filters, span),
                        null, MeshErrorLabels.class));
        CompletableFuture<List<PromResult<MeshLatencyLabels>>> latencyF = CompletableFuture.supplyAsync(() ->
                        client.query(String.format(MetricQuery.MESH_TOPOLOGY_LATENCY.sql(), cluster, filters, span),
                                null, MeshLatencyLabels.class))
                .exceptionally(e -> {
                    log.warn("拓扑延迟查询失败，p95 一律判为无样本", e);
                    return List.of();
                });
        CompletableFuture<List<PromResult<MetricLabels>>> sentinelF = CompletableFuture.supplyAsync(() ->
                client.query(String.format(MetricQuery.MESH_TOPOLOGY_SCRAPED.sql(), cluster),
                        null, MetricLabels.class));
        // L4 与 L7 同一个 DTO 收（by 出来的四个标签完全一样）；失败也不算整体失败 —— 少一层信息而已
        CompletableFuture<List<PromResult<MeshEdgeLabels>>> l4EdgesF = CompletableFuture.supplyAsync(() ->
                        client.query(String.format(MetricQuery.MESH_TOPOLOGY_L4_EDGES.sql(), cluster, filters, span),
                                null, MeshEdgeLabels.class))
                .exceptionally(e -> {
                    log.warn("拓扑 L4 查询失败，图里只会有 L7 边", e);
                    return List.of();
                });

        CompletableFuture.allOf(edgesF, errorsF, latencyF, sentinelF, l4EdgesF).join();

        Map<Pair, Double> rps = new LinkedHashMap<>();
        for (PromResult<MeshEdgeLabels> r : edgesF.join()) {
            Pair p = pairOf(r.getMetric());
            Double v = instantValue(r);
            if (p != null && v != null) {
                rps.merge(p, v, Double::sum);
            }
        }
        Map<Pair, Double> errors = new LinkedHashMap<>();
        for (PromResult<MeshErrorLabels> r : errorsF.join()) {
            Pair p = pairOf(r.getMetric());
            Double v = instantValue(r);
            if (p != null && v != null) {
                errors.merge(p, v, Double::sum);
            }
        }
        Map<Pair, Double> latency = new LinkedHashMap<>();
        for (PromResult<MeshLatencyLabels> r : latencyF.join()) {
            Pair p = pairOf(r.getMetric());
            Double v = instantValue(r);
            if (p != null && v != null) {
                latency.put(p, v);   // 同一条边只保留一个分位数
            }
        }
        Map<Pair, Double> l4Bytes = new LinkedHashMap<>();
        for (PromResult<MeshEdgeLabels> r : l4EdgesF.join()) {
            Pair p = pairOf(r.getMetric());
            Double v = instantValue(r);
            if (p != null && v != null) {
                l4Bytes.merge(p, v, Double::sum);
            }
        }

        MeshTopologyResponse resp = new MeshTopologyResponse();
        resp.setWindowSeconds(window);
        resp.setMeshMetricsAvailable(hasPositive(sentinelF.join()));

        Set<String> nodeIds = new LinkedHashSet<>();
        // ① L7 边：一对端点同时也有 L4 流量时，把字节数**附在同一条边**上（不重复画）
        for (Map.Entry<Pair, Double> e : rps.entrySet()) {
            Pair p = e.getKey();
            double total = e.getValue();
            double err = errors.getOrDefault(p, 0.0);
            MeshTopologyResponse.MeshEdge edge = new MeshTopologyResponse.MeshEdge();
            edge.setSource(p.source());
            edge.setTarget(p.target());
            edge.setLevel(L7);
            edge.setRps(total);
            edge.setErrorRate(total > 0 ? err / total : 0.0);   // 分母是总量；防除零
            edge.setP95Ms(latency.get(p));
            edge.setBytesPerSec(l4Bytes.get(p));
            resp.getEdges().add(edge);
            nodeIds.add(p.source());
            nodeIds.add(p.target());
        }
        // ② 只有 L4 的边（对端不在网格里、或还没挂 waypoint）：没有错误率、没有请求延迟
        for (Map.Entry<Pair, Double> e : l4Bytes.entrySet()) {
            Pair p = e.getKey();
            if (rps.containsKey(p)) {
                continue;   // 已在 ① 里并进 L7 那条边
            }
            MeshTopologyResponse.MeshEdge edge = new MeshTopologyResponse.MeshEdge();
            edge.setSource(p.source());
            edge.setTarget(p.target());
            edge.setLevel(L4);
            edge.setRps(0);
            edge.setErrorRate(0);
            edge.setP95Ms(null);
            edge.setBytesPerSec(e.getValue());
            resp.getEdges().add(edge);
            nodeIds.add(p.source());
            nodeIds.add(p.target());
        }
        for (String id : nodeIds) {
            int slash = id.indexOf('/');
            MeshTopologyResponse.MeshNode node = new MeshTopologyResponse.MeshNode();
            node.setId(id);
            node.setNamespace(id.substring(0, slash));
            node.setWorkload(id.substring(slash + 1));
            node.setGateway(INGRESS_NAMESPACE.equals(node.getNamespace()));
            resp.getNodes().add(node);
        }
        return resp;
    }

    /** 标签 → 边两端；标签缺失或落在 `unknown` 上时返回 null（该行丢弃，不编造节点）。 */
    private Pair pairOf(MeshEdgeLabels l) {
        if (l == null || !StringUtils.hasText(l.getSourceWorkload()) || !StringUtils.hasText(l.getDestinationWorkload())) {
            return null;
        }
        if (UNKNOWN_WORKLOAD.equals(l.getSourceWorkload()) || UNKNOWN_WORKLOAD.equals(l.getDestinationWorkload())) {
            return null;
        }
        return new Pair(idOf(l.getSourceNamespace(), l.getSourceWorkload()),
                idOf(l.getDestinationNamespace(), l.getDestinationWorkload()));
    }

    private String idOf(String namespace, String workload) {
        return (StringUtils.hasText(namespace) ? namespace : "unknown") + "/" + workload;
    }

    /** 即时查询的 value → 数值；缺失/NaN/Inf 返回 null。 */
    private Double instantValue(PromResult<?> r) {
        if (r == null || r.getValue() == null || r.getValue().size() < 2) {
            return null;
        }
        double v = parseDouble(r.getValue().get(1));
        return (Double.isNaN(v) || Double.isInfinite(v)) ? null : v;
    }

    /** 哨兵：任一序列的值 > 0 即「在采集」。 */
    private boolean hasPositive(List<PromResult<MetricLabels>> rows) {
        if (rows == null) {
            return false;
        }
        for (PromResult<MetricLabels> r : rows) {
            Double v = instantValue(r);
            if (v != null && v > 0) {
                return true;
            }
        }
        return false;
    }

    // ===== 节点底层调用 / 转换 =====

    /** 节点区间查询（固定 step）。 */
    private List<PromResult<MetricLabels>> queryNode(String sql, NodeMetricsRequest req) {
        return client.queryRange(sql, req.getStart(), req.getEnd(), STEP, MetricLabels.class);
    }

    /** 命名空间区间查询（固定 step）。 */
    private List<PromResult<MetricLabels>> queryNamespace(String sql, NamespaceMetricsRequest req) {
        return client.queryRange(sql, req.getStart(), req.getEnd(), STEP, MetricLabels.class);
    }

    /** 集群区间查询（固定 step）。 */
    private List<PromResult<MetricLabels>> queryCluster(String sql, ClusterMetricsRequest req) {
        return client.queryRange(sql, req.getStart(), req.getEnd(), STEP, MetricLabels.class);
    }

    /** 只带 start/end 的区间查询（中间件实例维度：请求体里没有可套用的维度 DTO）。 */
    private List<PromResult<MetricLabels>> queryAt(String sql, long start, long end) {
        return client.queryRange(sql, start, end, STEP, MetricLabels.class);
    }

    /** 集群 by(namespace) 区间查询（读 namespace 标签作图例）。 */
    private List<PromResult<NamespaceLabels>> queryClusterByNs(String sql, ClusterMetricsRequest req) {
        return client.queryRange(sql, req.getStart(), req.getEnd(), STEP, NamespaceLabels.class);
    }

    /** by(namespace) 行 → 一序列一行（图例 = 命名空间名，与明细表行对齐）；缺标签落 "unknown"（同 groupByDevice）。 */
    private List<MetricSeries> seriesByNamespace(List<PromResult<NamespaceLabels>> rows) {
        List<MetricSeries> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (PromResult<NamespaceLabels> r : rows) {
            String ns = (r.getMetric() != null && StringUtils.hasText(r.getMetric().getNamespace()))
                    ? r.getMetric().getNamespace() : "unknown";
            MetricSeries s = new MetricSeries();
            s.setLegend(ns);
            s.setPoints(pointsOf(r));
            out.add(s);
        }
        return out;
    }

    /** 按 device label 归并（disk by(device)）；无 device 的落 "unknown"。 */
    private Map<String, List<MetricPoint>> groupByDevice(List<PromResult<DeviceLabels>> rows) {
        Map<String, List<MetricPoint>> out = new LinkedHashMap<>();
        if (rows == null) {
            return out;
        }
        for (PromResult<DeviceLabels> r : rows) {
            String dev = (r.getMetric() != null && StringUtils.hasText(r.getMetric().getDevice()))
                    ? r.getMetric().getDevice() : "unknown";
            out.computeIfAbsent(dev, k -> new ArrayList<>()).addAll(pointsOf(r));
        }
        return out;
    }

    /** 单条 PromResult 的 values → 点序列（跳过 NaN/Inf）。 */
    private List<MetricPoint> pointsOf(PromResult<?> row) {
        List<MetricPoint> pts = new ArrayList<>();
        if (row == null || row.getValues() == null) {
            return pts;
        }
        for (List<String> sample : row.getValues()) {
            if (sample == null || sample.size() < 2) {
                continue;
            }
            double v = parseDouble(sample.get(1));
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                continue;
            }
            MetricPoint p = new MetricPoint();
            p.setTs((long) parseDouble(sample.get(0)));
            p.setValue(v);
            pts.add(p);
        }
        return pts;
    }

    /** 即时查询取第一个值（vector）；无数据/NaN/Inf 返回 null。 */
    private Double firstInstant(String sql) {
        List<PromResult<MetricLabels>> rows = client.query(sql, null, MetricLabels.class);
        if (rows == null || rows.isEmpty() || rows.get(0).getValue() == null || rows.get(0).getValue().size() < 2) {
            return null;
        }
        double v = parseDouble(rows.get(0).getValue().get(1));
        return (Double.isNaN(v) || Double.isInfinite(v)) ? null : v;
    }

    // ===== 工作负载 pod-owner 解析（CPU/MEMORY/NETWORK 共用：Deployment→ReplicaSet 归并）=====

    /** kube_pod_owner 查询参数：owner_kind + owner_name 正则。 */
    private record Owner(String kind, String nameRegex) { }

    /**
     * 解析工作负载的 pod-owner：
     * <ul>
     *   <li>Deployment：pod 实际归其 ReplicaSet 管（owner_kind=ReplicaSet），先列出该 Deployment
     *       名下所有 RS，把 RS 名用 '|' 拼成 owner_name 正则；</li>
     *   <li>StatefulSet / DaemonSet：pod 直接归其所有，owner_kind=PascalCase(kind)、owner_name=工作负载名。</li>
     * </ul>
     */
    private Owner resolveOwner(WorkloadMetricsRequest req) {
        String kind = pascalKind(req.getKind());
        if ("Deployment".equals(kind)) {
            return new Owner("ReplicaSet", listReplicaSetNames(req));
        }
        return new Owner(kind, req.getName());
    }

    /** deployment→Deployment / statefulset→StatefulSet / daemonset→DaemonSet（owner_kind 用 K8s 原生 PascalCase）。 */
    private String pascalKind(String kind) {
        if (!StringUtils.hasText(kind)) {
            return "";
        }
        return switch (kind.toLowerCase()) {
            case "deployment" -> "Deployment";
            case "statefulset" -> "StatefulSet";
            case "daemonset" -> "DaemonSet";
            default -> Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
        };
    }

    /**
     * 列出该 Deployment 名下所有 ReplicaSet，名字用 '|' 拼成 owner_name 正则。
     * 查不到时退回 deploy 名（正则仍合法，查询返回空序列而非报错）。
     */
    private String listReplicaSetNames(WorkloadMetricsRequest req) {
        WorkloadDTO d = new WorkloadDTO();
        d.setName(req.getName());
        d.setTenantId(req.getTenantId());
        d.setClusterId(req.getClusterId());
        d.setNamespace(req.getNamespace());

        WorkloadDTO workloadDTO = resourceClient.get(d);
        Map<String, String> matchSelector = workloadDTO.getSelector();

        String labelSelector = matchSelector.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));

        ReplicaSetDTO q = new ReplicaSetDTO();
        q.setClusterId(req.getClusterId());
        q.setNamespace(req.getNamespace());
        q.setTenantId(req.getTenantId());
        q.setLabelSelector(labelSelector);
        String regex = resourceClient.list(q).stream()
                .map(ReplicaSetDTO::getName)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("|"));
        return StringUtils.hasText(regex) ? regex : req.getName();
    }

    // ===== 底层机械调用 / 转换（不含"拼哪条 SQL、怎么填参"的业务逻辑）=====

    /** 执行一条已拼好的 sql（固定 step + 空标签类型）。 */
    private List<PromResult<MetricLabels>> query(String sql, WorkloadMetricsRequest req) {
        return client.queryRange(sql, req.getStart(), req.getEnd(), STEP, MetricLabels.class);
    }

    /** clusterId → 人类可读集群名（= cluster_name 标签值）。 */
    private String clusterName(String clusterId) {
        K8sCluster c = clusterMapper.selectByPrimaryKey(clusterId);
        if (c == null || c.getDeletedAt() != null) {
            throw new CloudPlatformException(EnumResponseType.CLUSTER_NOT_EXIST);
        }
        return c.getClusterName();
    }

    /** sum() 无 by → 至多一条序列；取第一条的 values（不读 metric）。 */
    private MetricSeries toSeries(String legend, List<PromResult<MetricLabels>> rows) {
        MetricSeries s = new MetricSeries();
        s.setLegend(legend);
        List<MetricPoint> points = new ArrayList<>();
        if (rows != null && !rows.isEmpty() && rows.get(0).getValues() != null) {
            for (List<String> sample : rows.get(0).getValues()) {
                if (sample == null || sample.size() < 2) {
                    continue;
                }
                double v = parseDouble(sample.get(1));
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    continue; // 无数据点（+Inf / NaN）跳过
                }
                MetricPoint p = new MetricPoint();
                p.setTs((long) parseDouble(sample.get(0)));
                p.setValue(v);
                points.add(p);
            }
        }
        s.setPoints(points);
        return s;
    }

    private double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
