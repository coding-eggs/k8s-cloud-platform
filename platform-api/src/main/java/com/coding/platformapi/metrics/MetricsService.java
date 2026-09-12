package com.coding.platformapi.metrics;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.DeploymentDTO;
import com.coding.common.models.k8s.dto.ReplicaSetDTO;
import com.coding.common.models.k8s.dto.WorkloadDTO;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.k8s.K8sResourceClient;
import com.coding.platformapi.metrics.dto.MetricPoint;
import com.coding.platformapi.metrics.dto.MetricSeries;
import com.coding.platformapi.metrics.dto.MetricSeriesResponse;
import com.coding.platformapi.metrics.dto.NodeCurrentMetric;
import com.coding.platformapi.metrics.dto.NodeMetricsRequest;
import com.coding.platformapi.metrics.dto.WorkloadMetricsRequest;
import com.coding.platformapi.metrics.labels.DeviceLabels;
import com.coding.platformapi.metrics.labels.MetricLabels;
import com.coding.platformapi.metrics.model.PromResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 指标业务层：每个方法对应一个接口，**各自拼自己的 sql**（占位符数量/顺序由该条查询自定）、
 * 各自调 {@code client.queryRange}、各自组响应。不抽统一的"拼 SQL + 填参"层——不同查询占位符不同，统一抽象反而写不动。
 * <p>仅保留纯机械转换：{@link #clusterName}（clusterId→名）、{@link #toSeries}（Thanos 行→点序列）。
 */
@Service
@RequiredArgsConstructor
public class MetricsService {

    /** step（秒）：当前默认 30s；要更大时间宽度时调这里 + 前端预设即可，接口不改。 */
    private static final int STEP = 15;

    private final K8sClusterMapper clusterMapper;
    private final ThanosQueryClient client;
    /** 通用 k8s-server 资源 client：Deployment 指标需先列出其名下 ReplicaSet（见 {@link #resolveOwner}）。 */
    private final K8sResourceClient resourceClient;

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

    // ===== 节点底层调用 / 转换 =====

    /** 节点区间查询（固定 step）。 */
    private List<PromResult<MetricLabels>> queryNode(String sql, NodeMetricsRequest req) {
        return client.queryRange(sql, req.getStart(), req.getEnd(), STEP, MetricLabels.class);
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
