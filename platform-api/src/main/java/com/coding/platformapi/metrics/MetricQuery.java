package com.coding.platformapi.metrics;

import com.coding.platformapi.metrics.labels.DeviceLabels;
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
            "sum(rate(container_fs_reads_bytes_total{cluster_name=\"%s\",container!=\"POD\",pod!=\"\" device=~\"/dev/dm-.*\"}[2m]))"),
    CLUSTER_DISK_WRITE(MetricLabels.class,
            "sum(rate(container_fs_writes_bytes_total{cluster_name=\"%s\",container!=\"POD\",pod!=\"\"device=~\"/dev/dm-.*\"}[2m]))"),

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
