package com.coding.platformapi.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 中间件商店的固定目录（api 侧的展示元数据）。
 *
 * <p>批①-1 只打通 Redis；其余条目先以"即将支持"占位，批①-2 再补 modes。
 * 版本的 Operator chart 由 k8s-server 的 {@code MiddlewareOperatorCatalog} 持有 —— 两边各管各的：
 * 这边管"卖什么、怎么描述"，那边管"装哪个版本"。
 */
@Component
public class MiddlewareCatalog {

    /**
     * @param fixedReplicas 非空 = 该形态节点数固定，表单只读（单节点/单实例）
     * @param replicasLabel 节点数在该形态下的含义（主节点数 / 数据节点数 / Broker 数 ...）
     */
    public record Mode(String mode, String label, int minReplicas, Integer fixedReplicas,
                       String replicasLabel, String help) {
    }

    /** 指标展示形态：时序趋势 / 当前值 / 状态标签。 */
    public enum MetricKind { CHART, STAT, STATE }

    /**
     * 业务指标声明：前端按它渲染，后端按它拼 PromQL。
     *
     * @param query           PromQL 模板，三个 {@code %s} 依次是 cluster_name / namespace / pod 名正则
     * @param kind            CHART=时序图；STAT=当前值卡片；STATE=状态标签（如存活/集群状态）
     * @param goodWhenNonZero 仅 STATE：value&gt;0 表示正常（存活=1）；false 时 value==0 才是正常（告警数、流控）
     * @param goodLabel       仅 STATE：正常时显示的文字
     * @param badLabel        仅 STATE：异常时显示的文字
     */
    public record MetricDef(String key, String label, String unit, String query, MetricKind kind,
                            boolean goodWhenNonZero, String goodLabel, String badLabel) {

        public static MetricDef chart(String key, String label, String unit, String query) {
            return new MetricDef(key, label, unit, query, MetricKind.CHART, false, null, null);
        }

        public static MetricDef stat(String key, String label, String unit, String query) {
            return new MetricDef(key, label, unit, query, MetricKind.STAT, false, null, null);
        }

        public static MetricDef state(String key, String label, String unit, String query,
                                      boolean goodWhenNonZero, String goodLabel, String badLabel) {
            return new MetricDef(key, label, unit, query, MetricKind.STATE,
                    goodWhenNonZero, goodLabel, badLabel);
        }
    }

    /**
     * @param versions 平台支持的可选版本（第一项为默认）；空 = 版本由实现固定，表单不展示版本字段
     * @param metrics  业务指标声明；空 = 详情页不展示业务指标区
     */
    public record Item(String code, String name, String description, List<Mode> modes, List<String> versions,
                       List<MetricDef> metrics) {
    }

    private static final List<Mode> REDIS_MODES = List.of(
            new Mode("STANDALONE", "单节点", 1, 1, "节点数", "开发/测试，无高可用（固定 1 个节点）"),
            new Mode("SENTINEL", "哨兵主从", 2, null, "数据节点数",
                    "数据节点里 1 个主、其余为从；另外固定起 3 个哨兵做故障切换"),
            new Mode("CLUSTER", "集群分片", 3, null, "主节点数",
                    "数据分成多份存放，每份由一个主节点负责（至少 3 个）；每个主节点可再配副本做备份"));

    private static final List<Mode> MYSQL_MODES = List.of(
            new Mode("SINGLE", "单节点", 1, 1, "节点数", "开发/测试；Group Replication 单成员，无容错（固定 1 个）"),
            new Mode("CLUSTER", "高可用集群", 3, null, "节点数",
                    "组复制：3 或 5 个节点，其中 1 个主、其余为从；写主、读从"));

    private static final List<Mode> KAFKA_MODES = List.of(
            new Mode("CLUSTER", "KRaft 集群", 3, null, "Broker 数", "brokers ≥3，同时承担 controller；2 节点没有容错"));

    private static final List<Mode> ES_MODES = List.of(
            new Mode("CLUSTER", "集群", 1, null, "节点数", "生产建议 ≥3 节点"));

    private static final List<Mode> NACOS_MODES = List.of(
            new Mode("STANDALONE", "单节点", 1, 1, "节点数", "开发/测试（固定 1 个）"),
            new Mode("CLUSTER", "集群", 3, null, "节点数", "内嵌存储（JRaft），≥3 节点"));

    private static final List<Mode> POSTGRESQL_MODES = List.of(
            new Mode("SINGLE", "单实例", 1, 1, "实例数", "开发/测试；无容错（固定 1 个实例）"),
            new Mode("HA", "主备", 3, null, "实例数", "CloudNativePG 一主多备，自动 failover（3/5 实例）"));

    private static final List<Mode> RABBITMQ_MODES = List.of(
            new Mode("SINGLE", "单节点", 1, 1, "节点数", "开发/测试；无容错（固定 1 个）"),
            new Mode("CLUSTER", "集群", 3, null, "节点数", "≥3 节点，Quorum 队列需要多数派"));

    /** Strimzi 1.3.0 的受支持版本清单（顺序即展示顺序，第一项为默认） */
    private static final List<String> KAFKA_VERSIONS =
            List.of("4.3.1", "4.3.0", "4.2.2", "4.2.1", "4.2.0");

    /** elasticsearch-exporter 边车（9114）的指标（ES 9 已无 /_prometheus/metrics，靠外挂导出器） */
    private static final List<MetricDef> ELASTICSEARCH_METRICS = List.of(
            MetricDef.state("health", "集群状态", "1/0",
                    "max(elasticsearch_cluster_health_status{color=\"green\",cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})",
                    true, "green", "非 green"),
            MetricDef.stat("nodes", "节点数", "个",
                    "max(elasticsearch_cluster_health_number_of_nodes{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("active_shards", "活跃分片", "个",
                    "max(elasticsearch_cluster_health_active_shards{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("unassigned", "未分配分片", "个",
                    "max(elasticsearch_cluster_health_unassigned_shards{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("pending_tasks", "待处理任务", "个",
                    "max(elasticsearch_cluster_health_number_of_pending_tasks{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.chart("heap", "JVM 堆使用", "字节",
                    "sum(elasticsearch_jvm_memory_used_bytes{area=\"heap\",cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.chart("search", "查询/秒", "次/秒",
                    "max(rate(elasticsearch_indices_search_query_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.chart("indexing", "索引文档/秒", "次/秒",
                    "max(rate(elasticsearch_indices_indexing_index_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.stat("docs", "文档总数", "个",
                    "max(elasticsearch_indices_docs{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("store", "存储大小", "字节",
                    "max(elasticsearch_indices_store_size_bytes{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"));

    /**
     * mysqld_exporter 边车（9104）的指标：运维最常看的十项
     * （存活 / 连接与使用率 / QPS 与线程 / 慢查询 / 缓冲池 / 锁 / 连接中断 / 组复制流控）。
     */
    private static final List<MetricDef> MYSQL_METRICS = List.of(
            MetricDef.state("up", "实例存活", "1/0",
                    "min(mysql_up{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})", true, "正常", "异常"),
            MetricDef.stat("connections", "连接数", "个",
                    "sum(mysql_global_status_threads_connected{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("conn_usage", "连接使用率", "%",
                    "100 * sum(mysql_global_status_threads_connected{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"
                            + " / clamp_min(sum(mysql_global_variables_max_connections{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}), 1)"),
            MetricDef.chart("qps", "查询/秒", "次/秒",
                    "sum(rate(mysql_global_status_questions{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.stat("running", "活跃线程", "个",
                    "sum(mysql_global_status_threads_running{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.chart("slow", "慢查询/秒", "次/秒",
                    "sum(rate(mysql_global_status_slow_queries{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.stat("buffer_hit", "InnoDB 缓冲池命中率", "%",
                    "100 * (1 - sum(rate(mysql_global_status_innodb_buffer_pool_reads{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"
                            + " / clamp_min(sum(rate(mysql_global_status_innodb_buffer_pool_read_requests{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m])), 0.001))"),
            MetricDef.chart("lock_waits", "行锁等待/秒", "次/秒",
                    "sum(rate(mysql_global_status_innodb_row_lock_waits{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.chart("aborted", "连接中断/秒", "次/秒",
                    "sum(rate(mysql_global_status_aborted_connects{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.state("flow_control", "组复制流控", "1/0",
                    "sum(mysql_global_status_group_replication_flow_control_active{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})",
                    false, "未流控", "流控中"));

    /**
     * Nacos actuator（/nacos/actuator/prometheus）的 Micrometer 指标。
     *
     * <p>全部用 JVM / HTTP / 进程级标准指标：实测 Nacos 的业务 gauge（configCount 等）注册依赖模块初始化，
     * Pod 重启后一段时间内不出现，不适合放进概览。
     */
    private static final List<MetricDef> NACOS_METRICS = List.of(
            MetricDef.stat("heap", "JVM 堆使用", "字节",
                    "sum(jvm_memory_used_bytes{area=\"heap\",cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("nonheap", "JVM 非堆使用", "字节",
                    "sum(jvm_memory_used_bytes{area=\"nonheap\",cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("threads", "活跃线程", "个",
                    "sum(jvm_threads_live_threads{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("classes", "已加载类", "个",
                    "sum(jvm_classes_loaded_classes{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.chart("gc", "GC 次数/秒", "次/秒",
                    "sum(rate(jvm_gc_pause_seconds_count{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.chart("http", "HTTP 请求/秒", "次/秒",
                    "sum(rate(http_server_requests_seconds_count{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.chart("http_rt", "HTTP 平均响应时间", "秒",
                    "sum(rate(http_server_requests_seconds_sum{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"
                            + " / clamp_min(sum(rate(http_server_requests_seconds_count{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m])), 0.001)"),
            MetricDef.stat("cpu", "进程 CPU", "%",
                    "100 * sum(process_cpu_usage{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("load", "系统负载（1m）", "个",
                    "max(system_load_average_1m{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("uptime", "运行时长", "秒",
                    "max(process_uptime_seconds{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"));

    /** Strimzi Metrics Reporter（9404）的指标；名字是 reporter 导出的 JMX 原名（含 persec） */
    private static final List<MetricDef> KAFKA_METRICS = List.of(
            MetricDef.chart("messages_in", "消息入站/秒", "条/秒",
                    "sum(rate(kafka_server_brokertopicmetrics_messagesinpersec_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.chart("bytes_in", "字节入站/秒", "字节/秒",
                    "sum(rate(kafka_server_brokertopicmetrics_bytesinpersec_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.chart("bytes_out", "字节出站/秒", "字节/秒",
                    "sum(rate(kafka_server_brokertopicmetrics_bytesoutpersec_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.stat("urp", "未充分复制分区", "个",
                    "sum(kafka_server_replicamanager_underreplicatedpartitions{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("offline", "离线分区", "个",
                    "sum(kafka_controller_kafkacontroller_offlinepartitionscount{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.state("controller", "活跃 Controller", "1/0",
                    "sum(kafka_controller_kafkacontroller_activecontrollercount{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})",
                    true, "正常", "无 Controller"),
            MetricDef.stat("partitions", "分区总数", "个",
                    "sum(kafka_controller_kafkacontroller_globalpartitioncount{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("topics", "主题总数", "个",
                    "sum(kafka_controller_kafkacontroller_globaltopiccount{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.chart("log_size", "日志数据量", "字节",
                    "sum(kafka_log_log_size{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.chart("heap", "Broker JVM 堆使用", "字节",
                    "sum(jvm_memory_used_bytes{area=\"heap\",cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"));

    /**
     * redis-exporter 边车（9121）的指标：运维最常看的十项
     * （存活 / 连接 / 内存 / 命中率 / 命令 / 键与淘汰 / 阻塞 / 持久化）。
     */
    private static final List<MetricDef> REDIS_METRICS = List.of(
            MetricDef.state("up", "实例存活", "1/0",
                    "min(redis_up{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})", true, "正常", "异常"),
            MetricDef.stat("clients", "连接数", "个",
                    "sum(redis_connected_clients{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("memory", "内存使用", "字节",
                    "sum(redis_memory_used_bytes{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.chart("hit_ratio", "命中率", "%",
                    "100 * sum(rate(redis_keyspace_hits_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"
                            + " / clamp_min(sum(rate(redis_keyspace_hits_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"
                            + " + sum(rate(redis_keyspace_misses_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m])), 0.001)"),
            MetricDef.chart("commands", "命令/秒", "个/秒",
                    "sum(rate(redis_commands_processed_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.stat("keys", "键数量", "个",
                    "sum(redis_db_keys{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.chart("expired", "过期键/秒", "个/秒",
                    "sum(rate(redis_expired_keys_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.chart("evicted", "驱逐键/秒", "个/秒",
                    "sum(rate(redis_evicted_keys_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.stat("blocked", "阻塞客户端", "个",
                    "sum(redis_blocked_clients{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.state("rdb", "持久化状态", "1/0",
                    "min(redis_rdb_last_bgsave_status{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})", true, "正常", "异常"));

    /**
     * CloudNativePG 内置 exporter（9187）的指标：存活 / 连接与等待 / 事务与回滚 / 死锁 / 缓存命中 /
     * 最长事务 / 复制延迟 / 数据库大小。
     */
    private static final List<MetricDef> POSTGRESQL_METRICS = List.of(
            MetricDef.state("up", "实例存活", "1/0",
                    "min(cnpg_collector_up{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})", true, "正常", "异常"),
            MetricDef.stat("connections", "连接数", "个",
                    "sum(cnpg_backends_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("waiting", "等待中的后端", "个",
                    "sum(cnpg_backends_waiting_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.chart("xact", "事务提交/秒", "个/秒",
                    "sum(rate(cnpg_pg_stat_database_xact_commit{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.chart("rollback", "事务回滚/秒", "个/秒",
                    "sum(rate(cnpg_pg_stat_database_xact_rollback{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.chart("deadlocks", "死锁/秒", "个/秒",
                    "sum(rate(cnpg_pg_stat_database_deadlocks{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.chart("cache_hit", "缓存命中率", "%",
                    "100 * sum(rate(cnpg_pg_stat_database_blks_hit{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"
                            + " / clamp_min(sum(rate(cnpg_pg_stat_database_blks_hit{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"
                            + " + sum(rate(cnpg_pg_stat_database_blks_read{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m])), 0.001)"),
            MetricDef.stat("max_tx", "最长事务时长", "秒",
                    "max(cnpg_backends_max_tx_duration_seconds{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("replag", "复制延迟", "秒",
                    "max(cnpg_pg_replication_lag{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("dbsize", "数据库大小", "字节",
                    "sum(cnpg_pg_database_size_bytes{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"));

    /** RabbitMQ prometheus 插件（15692）的指标：积压 / 连接与信道 / 队列与消费者 / 吞吐 / 重投 / 资源告警 */
    private static final List<MetricDef> RABBITMQ_METRICS = List.of(
            MetricDef.chart("ready", "就绪消息", "条",
                    "sum(rabbitmq_queue_messages_ready{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.chart("unacked", "未确认消息", "条",
                    "sum(rabbitmq_queue_messages_unacked{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("connections", "连接数", "个",
                    "sum(rabbitmq_connections{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("channels", "信道数", "个",
                    "sum(rabbitmq_channels{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("queues", "队列数", "个",
                    "sum(rabbitmq_queues{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("consumers", "消费者数", "个",
                    "sum(rabbitmq_consumers{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.chart("publish", "发布/秒", "条/秒",
                    "sum(rate(rabbitmq_queue_messages_published_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.chart("deliver", "投递/秒", "条/秒",
                    "sum(rate(rabbitmq_global_messages_delivered_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.chart("redeliver", "重投/秒", "条/秒",
                    "sum(rate(rabbitmq_global_messages_redelivered_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.state("alarms", "资源告警", "个",
                    "sum(rabbitmq_alarms_memory_used_watermark{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"
                            + " + sum(rabbitmq_alarms_free_disk_space_watermark{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})",
                    false, "正常", "有告警"));

    private static final List<Item> ITEMS = List.of(
            new Item("redis", "Redis", "内存键值存储：缓存、会话、分布式锁", REDIS_MODES, List.of(), REDIS_METRICS),
            new Item("mysql", "MySQL", "关系型数据库（Percona Operator）", MYSQL_MODES, List.of(), MYSQL_METRICS),
            // Strimzi 1.3.0 支持的版本（见其 UnsupportedKafkaVersionException 提示），默认 4.3.1
            new Item("kafka", "Kafka", "事件流平台（Strimzi，KRaft）", KAFKA_MODES,
                    KAFKA_VERSIONS, KAFKA_METRICS),
            // ECK 3.5.0 实测可用的版本，默认 9.5.0
            new Item("elasticsearch", "Elasticsearch", "搜索与日志存储（ECK）", ES_MODES,
                    List.of("9.5.0"), ELASTICSEARCH_METRICS),
            new Item("postgresql", "PostgreSQL", "关系型数据库（CloudNativePG）", POSTGRESQL_MODES, List.of(),
                    POSTGRESQL_METRICS),
            new Item("rabbitmq", "RabbitMQ", "消息队列（官方 Cluster Operator）", RABBITMQ_MODES, List.of(),
                    RABBITMQ_METRICS),
            new Item("nacos", "Nacos", "注册中心与配置中心（chart）", NACOS_MODES, List.of(), NACOS_METRICS));

    /** chart 安装的条目（不走 Operator） */
    public boolean chartBacked(String code) {
        return "nacos".equals(code);
    }

    public List<Item> items() {
        return ITEMS;
    }

    public Item require(String code) {
        return ITEMS.stream().filter(i -> i.code().equals(code)).findFirst()
                .orElseThrow(() -> new CloudPlatformException(EnumResponseType.ERROR,
                        "不支持的中间件：" + code));
    }

    /** 版本是否是平台声明支持的（空清单 = 该中间件版本由实现固定，忽略入参） */
    public boolean supportsVersion(Item item, String version) {
        return item.versions().isEmpty() || item.versions().contains(version);
    }

    public Mode requireMode(Item item, String mode) {
        return item.modes().stream().filter(m -> m.mode().equals(mode)).findFirst()
                .orElseThrow(() -> new CloudPlatformException(EnumResponseType.ERROR,
                        item.name() + " 不支持的形态：" + mode));
    }
}
