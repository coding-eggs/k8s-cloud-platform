package com.coding.platformapi.metrics;

import com.coding.platformapi.metrics.labels.DeviceLabels;
import com.coding.platformapi.metrics.labels.MeshEdgeLabels;
import com.coding.platformapi.metrics.labels.MeshErrorLabels;
import com.coding.platformapi.metrics.labels.MeshLatencyLabels;
import com.coding.platformapi.metrics.labels.MetricLabels;
import com.coding.platformapi.metrics.labels.NamespaceLabels;

/**
 * 具名查询目录：每个常量 = 一条查询 =（返回类型 + 带 {@code %s} 的 promql）。纯数据，零逻辑。
 * <p>命名 = {@code {范围}_{指标}_{语义}}；本功能一律纯 {@code sum()}（无 by），网络/磁盘的方向是第一个 {@code %s}。
 * <p>返回类型本功能均为空父类 {@link MetricLabels}（sum() 不产生 label，业务只读数值）；
 * 未来需要 {@code by(xxx)} 拆分的查询再加子类补字段。
 */
public enum MetricQuery {

    // ===== Pod 详情（%s = clusterName, namespace, pod[, 方向]）=====
    POD_CPU_USED(MetricLabels.class,
        "sum(rate(container_cpu_usage_seconds_total{cluster_name=\"%s\",namespace=\"%s\",pod=\"%s\",container!=\"POD\", pod!=\"\"}[2m]))"),
    POD_MEMORY_USED(MetricLabels.class,
        "sum(container_memory_working_set_bytes{cluster_name=\"%s\",namespace=\"%s\",pod=\"%s\",container!=\"POD\", pod!=\"\"})"),
    // 方向  RX / TX（netns 在 pod 级，container="POD"）
    POD_NETWORK_RECEIVE(MetricLabels.class,
        "sum(rate(container_network_receive_bytes_total{cluster_name=\"%s\",namespace=\"%s\",pod=\"%s\"}[2m]))"),

    POD_NETWORK_TRANSMIT(MetricLabels.class,
            "sum(rate(container_network_transmit_bytes_total{cluster_name=\"%s\",namespace=\"%s\",pod=\"%s\", device=~\"/dev/dm-.*\"}[2m]))"),


    POD_DISK_READ(MetricLabels.class,
        "sum(rate(container_fs_reads_bytes_total{cluster_name=\"%s\",namespace=\"%s\",pod=\"%s\",container!=\"POD\", device=~\"/dev/dm-.*\"}[2m]))"),

    POD_DISK_WRITE(MetricLabels.class,
            "sum(rate(container_fs_writes_bytes_total{cluster_name=\"%s\",namespace=\"%s\",pod=\"%s\",container!=\"POD\", device=~\"/dev/dm-.*\"}[2m]))"),



    // ===== 工作负载详情（占位符 = owner_kind, owner_name[正则]；网络/磁盘再前置一个方向 %s）=====
    // pod 归并到工作负载靠 kube_pod_owner：owner_kind 用 K8s 原生 PascalCase（Deployment/StatefulSet/DaemonSet）。
    // 注意 Deployment 的 pod 实际归 ReplicaSet 管，不能直接按 owner_kind=Deployment 查——
    //   由 MetricsService 先列出该 Deployment 名下的 RS，把 RS 名拼成正则填进 owner_name（owner_kind 传 ReplicaSet）。
    WORKLOAD_CPU_USED(MetricLabels.class,
            "sum(rate(container_cpu_usage_seconds_total{container!=\"POD\",pod!=\"\"}[2m]) * on(namespace, pod) group_left(owner_kind, owner_name, owner_is_controller) kube_pod_owner{owner_kind='%s',owner_name=~'%s'})"),
    WORKLOAD_MEMORY_USED(MetricLabels.class,
            "sum(container_memory_working_set_bytes{container!=\"POD\",pod!=\"\"} * on(namespace, pod) group_left(owner_kind, owner_name, owner_is_controller) kube_pod_owner{owner_kind='%s',owner_name=~'%s'})"),

    WORKLOAD_NETWORK_RECEIVE(MetricLabels.class,
            "sum(rate(container_network_receive_bytes_total{container!=\"POD\",pod!=\"\"}[2m]) * on(namespace, pod) group_left(owner_kind, owner_name, owner_is_controller) kube_pod_owner{owner_kind='%s',owner_name=~'%s'})"),
    WORKLOAD_NETWORK_TRANSMIT(MetricLabels.class,
            "sum(rate(container_network_transmit_bytes_total{container!=\"POD\",pod!=\"\"}[2m]) * on(namespace, pod) group_left(owner_kind, owner_name, owner_is_controller) kube_pod_owner{owner_kind='%s',owner_name=~'%s'})"),

    WORKLOAD_DISK_READ(MetricLabels.class,
            "sum(rate(container_fs_reads_bytes_total{container!=\"POD\", device=~\"/dev/dm-.*\",pod!=\"\"}[2m]) * on(namespace, pod) group_left(owner_kind, owner_name, owner_is_controller) kube_pod_owner{owner_kind='%s',owner_name=~'%s'})"),
    WORKLOAD_DISK_WRITE(MetricLabels.class,
            "sum(rate(container_fs_writes_bytes_total{container!=\"POD\", device=~\"/dev/dm-.*\",pod!=\"\"}[2m]) * on(namespace, pod) group_left(owner_kind, owner_name, owner_is_controller) kube_pod_owner{owner_kind='%s',owner_name=~'%s'})"),

    // ===== 中间件实例（%s = clusterName, namespace, pod 正则；跨该实例全部 Pod 聚合）=====
    // Pod 集合由 k8s-server 按与状态判定相同的 selector 读出（中间件实例可能由多个工作负载组成，
    // 工作负载维度按 owner 归并覆盖不到 StrimziPodSet 这类 CRD 工作负载，所以直接按 pod 名正则聚合）。
    // 网络 TX 照集群级口径**不加** device 过滤（container_network_* 只有 interface 标签）。
    INSTANCE_CPU_USED(MetricLabels.class,
            "sum(rate(container_cpu_usage_seconds_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\",container!=\"POD\"}[2m]))"),
    INSTANCE_MEMORY_USED(MetricLabels.class,
            "sum(container_memory_working_set_bytes{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\",container!=\"POD\"})"),
    INSTANCE_NETWORK_RECEIVE(MetricLabels.class,
            "sum(rate(container_network_receive_bytes_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
    INSTANCE_NETWORK_TRANSMIT(MetricLabels.class,
            "sum(rate(container_network_transmit_bytes_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
    INSTANCE_DISK_READ(MetricLabels.class,
            "sum(rate(container_fs_reads_bytes_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\",container!=\"POD\",device=~\"/dev/dm-.*\"}[2m]))"),
    INSTANCE_DISK_WRITE(MetricLabels.class,
            "sum(rate(container_fs_writes_bytes_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\",container!=\"POD\",device=~\"/dev/dm-.*\"}[2m]))"),

    // ===== 节点（%s = instance，形如 "<internalIp>:9100"；node_exporter 指标）=====
    // CPU 用 avg（跨核取平均），仍是标量无 by → MetricLabels。内存为 gauge 原值（字节）。
    NODE_CPU_USED(MetricLabels.class,
            "100 - (avg(rate(node_cpu_seconds_total{instance=\"%s\",mode=\"idle\"}[5m])) * 100)"),
    NODE_MEMORY_USED(MetricLabels.class,
            "node_memory_MemTotal_bytes{instance=\"%s\"} - node_memory_MemAvailable_bytes{instance=\"%s\"}"),
    NODE_MEMORY_CACHED(MetricLabels.class,
            "node_memory_Cached_bytes{instance=\"%s\"}"),
    NODE_MEMORY_BUFFERS(MetricLabels.class,
            "node_memory_Buffers_bytes{instance=\"%s\"}"),
    NODE_MEMORY_FREE(MetricLabels.class,
            "node_memory_MemFree_bytes{instance=\"%s\"}"),
    // 内存使用率 %（列表页当前值）：(total - available) / total * 100
    NODE_MEM_PERCENT(MetricLabels.class,
            "(node_memory_MemTotal_bytes{instance=\"%s\"} - node_memory_MemAvailable_bytes{instance=\"%s\"}) / node_memory_MemTotal_bytes{instance=\"%s\"} * 100"),
    // 网络聚合单线，只算物理网卡 ens.*（排除 lo/veth/bridge）
    NODE_NETWORK_RECEIVE(MetricLabels.class,
            "sum(rate(node_network_receive_bytes_total{instance=\"%s\",device=~\"ens.*\"}[5m]))"),
    NODE_NETWORK_TRANSMIT(MetricLabels.class,
            "sum(rate(node_network_transmit_bytes_total{instance=\"%s\",device=~\"ens.*\"}[5m]))"),
    // 磁盘按设备拆线，排除 sr*/loop*（光驱/回环）
    NODE_DISK_READ(DeviceLabels.class,
            "sum by(device) (rate(node_disk_read_bytes_total{instance=\"%s\",device!~\"sr.*|loop.*\"}[5m]))"),
    NODE_DISK_WRITE(DeviceLabels.class,
            "sum by(device) (rate(node_disk_written_bytes_total{instance=\"%s\",device!~\"sr.*|loop.*\"}[5m]))"),

    // ===== 命名空间（%s = clusterName, namespace；跨该 ns 全部 pod 聚合 = pod 级查询去掉 pod= 过滤）=====
    NAMESPACE_CPU_USED(MetricLabels.class,
            "sum(rate(container_cpu_usage_seconds_total{cluster_name=\"%s\",namespace=\"%s\",container!=\"POD\",pod!=\"\"}[2m]))"),
    NAMESPACE_MEMORY_USED(MetricLabels.class,
            "sum(container_memory_working_set_bytes{cluster_name=\"%s\",namespace=\"%s\",container!=\"POD\",pod!=\"\"})"),
    // 方向 RX / TX（netns 在 pod 级）；与 pod 级查询同口径（transmit 沿用 device=~"/dev/dm-.*"）
    NAMESPACE_NETWORK_RECEIVE(MetricLabels.class,
            "sum(rate(container_network_receive_bytes_total{cluster_name=\"%s\",namespace=\"%s\",pod!=\"\",container!=\"POD\"}[2m]))"),
    NAMESPACE_NETWORK_TRANSMIT(MetricLabels.class,
            "sum(rate(container_network_transmit_bytes_total{cluster_name=\"%s\",namespace=\"%s\", device=~\"/dev/dm-.*\",pod!=\"\",container!=\"POD\"}[2m]))"),
    NAMESPACE_DISK_READ(MetricLabels.class,
            "sum(rate(container_fs_reads_bytes_total{cluster_name=\"%s\",namespace=\"%s\",container!=\"POD\", device=~\"/dev/dm-.*\",pod!=\"\"}[2m]))"),
    NAMESPACE_DISK_WRITE(MetricLabels.class,
            "sum(rate(container_fs_writes_bytes_total{cluster_name=\"%s\",namespace=\"%s\",container!=\"POD\", device=~\"/dev/dm-.*\",pod!=\"\"}[2m]))"),

    // ===== 集群（%s = clusterName；无 namespace 过滤 = 跨全集群聚合）=====
    // 「资源用量」tab 的两条曲线；by-namespace 变体喂「资源明细」表的 used 列（一条序列 = 一个命名空间）。
    // 口径与 ns 级查询一致（container!="POD" 排除 netns 影子容器），只是把 namespace="%s" 拿掉换成 by(namespace)。
    CLUSTER_CPU_USED(MetricLabels.class,
            "sum(rate(container_cpu_usage_seconds_total{cluster_name=\"%s\",container!=\"POD\",pod!=\"\"}[2m]))"),
    CLUSTER_MEMORY_USED(MetricLabels.class,
            "sum(container_memory_working_set_bytes{cluster_name=\"%s\",container!=\"POD\",pod!=\"\"})"),
    CLUSTER_CPU_USED_BY_NS(NamespaceLabels.class,
            "sum by (namespace)(rate(container_cpu_usage_seconds_total{cluster_name=\"%s\",container!=\"POD\",pod!=\"\"}[2m]))"),
    CLUSTER_MEMORY_USED_BY_NS(NamespaceLabels.class,
            "sum by (namespace)(container_memory_working_set_bytes{cluster_name=\"%s\",container!=\"POD\",pod!=\"\"})"),
    // 网络 / 磁盘（照 ns 级那几条去掉 namespace 过滤）
    // ⚠️ Pod / Workload / Namespace 级的 **TRANSMIT** 查询带 `device=~"/dev/dm-.*"`（注释写着"沿用"磁盘查询的形状），
    //    但 container_network_* 只有 `interface` 标签、**没有 `device`** —— 该匹配器匹配不到任何序列，那三条 TX 很可能恒空。
    //    集群级这几条**有意不沿用**它（否则新图一样空）；既有那三条待一起修（改动会动到已上线的图，另议）。
    CLUSTER_NETWORK_RECEIVE(MetricLabels.class,
            "sum(rate(container_network_receive_bytes_total{cluster_name=\"%s\",container!=\"POD\", pod!=\"\"}[2m]))"),
    CLUSTER_NETWORK_TRANSMIT(MetricLabels.class,
            "sum(rate(container_network_transmit_bytes_total{cluster_name=\"%s\",container!=\"POD\", pod!=\"\"}[2m]))"),
    // 磁盘的 device 过滤是**正当的**（container_fs_* 确实带 device），照抄 ns 级
    CLUSTER_DISK_READ(MetricLabels.class,
            "sum(rate(container_fs_reads_bytes_total{cluster_name=\"%s\",container!=\"POD\",pod!=\"\",device=~\"/dev/dm-.*\"}[2m]))"),
    CLUSTER_DISK_WRITE(MetricLabels.class,
            "sum(rate(container_fs_writes_bytes_total{cluster_name=\"%s\",container!=\"POD\",pod!=\"\",device=~\"/dev/dm-.*\"}[2m]))"),

    // ===== 服务网格拓扑（%s = ① 集群名 ② 命名空间过滤片段（可为空串）③ 时间窗；哨兵只要集群名）=====
    // 一条序列 = 一条边「源工作负载 → 目标工作负载」。命名空间一并 by 出来 —— 同名 workload 跨 ns 很常见，
    //   只按 workload 名分组会把两条边粘成一个节点。
    // ⚠️ **必须带 cluster_name**：Thanos 是全局单端点，istio_* 指标都带 cluster_name，
    //    不滤就会把别的集群的边一起画进来（2026-10-17 实测确认该标签存在）。
    // ⚠️ 命名空间过滤片段拼在花括号**内部**（有值时以 `,` 开头，无过滤时为空串）。
    // ⚠️ **不要加 `reporter="destination"`**：2026-10-17 实测本集群 `reporter` 只有 `source`（网关记的）
    //    与 `waypoint`，抄网上那些带 destination 的查询会查出空图。将来上了 sidecar 才需要它去重。
    /** 拓扑的边 + QPS（请求数/秒）。 */
    MESH_TOPOLOGY_EDGES(MeshEdgeLabels.class,
            "sum by (source_workload, destination_workload, source_workload_namespace, destination_workload_namespace) (rate(istio_requests_total{cluster_name=\"%s\"%s}[%s]))"),
    /** 同为边，但只看 5xx —— 与 EDGES 相除得错误率（分母是总量）。 */
    MESH_TOPOLOGY_ERRORS(MeshErrorLabels.class,
            "sum by (source_workload, destination_workload, source_workload_namespace, destination_workload_namespace) (rate(istio_requests_total{cluster_name=\"%s\",response_code=~\"5..\"%s}[%s]))"),
    /** 边的 P95 延迟（毫秒）。⚠️ 实测本集群只有 `..._milliseconds_bucket`，没有 `_seconds` 版、也没有裸名。 */
    MESH_TOPOLOGY_LATENCY(MeshLatencyLabels.class,
            "histogram_quantile(0.95, sum by (le, source_workload, destination_workload, source_workload_namespace, destination_workload_namespace) (rate(istio_request_duration_milliseconds_bucket{cluster_name=\"%s\"%s}[%s])))"),
    /**
     * 只经 ztunnel 的 **L4** 边（ambient 下没有 waypoint / 对端不在网格里的调用）。
     * ⚠️ 用**字节/秒**而不是连接数：`istio_tcp_connections_opened_total` 对长连接（mysql/redis）
     * 几乎恒为 0，画出来全是细线；实测 mysql 7.9KB/s、es 1.5KB/s 才有区分度。
     * L4 只有连接与字节，**没有响应码、没有请求延迟** —— 前端不能给它算错误率。
     */
    MESH_TOPOLOGY_L4_EDGES(MeshEdgeLabels.class,
            "sum by (source_workload, destination_workload, source_workload_namespace, destination_workload_namespace) (rate(istio_tcp_sent_bytes_total{cluster_name=\"%s\"%s}[%s]))"),
    /** 哨兵：**在不在采集网格指标**。代理（istiod / ztunnel / waypoint / 网关）活着就产出，与流量无关，
     *  所以它能区分「没接采集」和「接了但这段时间没调用」—— 这两者的主查询**都是空的**。
     *  ⚠️ 必须按集群过滤：否则「B 集群没采集、A 集群在采集」会被误判成已采集。 */
    MESH_TOPOLOGY_SCRAPED(MetricLabels.class,
            "count(istio_build{cluster_name=\"%s\"})"),

    ;

    private final Class<?> labelType;
    private final String sql;

    MetricQuery(Class<?> labelType, String sql) {
        this.labelType = labelType;
        this.sql = sql;
    }

    public Class<?> labelType() {
        return labelType;
    }

    public String sql() {
        return sql;
    }
}
