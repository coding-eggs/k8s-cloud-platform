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

    /**
     * 业务指标声明：前端按它渲染，后端按它拼 PromQL。
     *
     * @param query PromQL 模板，三个 {@code %s} 依次是 cluster_name / namespace / pod 名正则
     */
    public record MetricDef(String key, String label, String unit, String query) {
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
            new MetricDef("health", "集群状态（green=1）", "1/0",
                    "sum(elasticsearch_cluster_health_status{color=\"green\",cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            new MetricDef("heap", "JVM 堆使用", "字节",
                    "sum(elasticsearch_jvm_memory_used_bytes{area=\"heap\",cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            new MetricDef("search", "查询/秒", "次/秒",
                    "sum(rate(elasticsearch_indices_search_query_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            new MetricDef("unassigned", "未分配分片", "个",
                    "sum(elasticsearch_cluster_health_unassigned_shards{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"));

    /** mysqld_exporter 边车（9104）的指标 */
    private static final List<MetricDef> MYSQL_METRICS = List.of(
            new MetricDef("connections", "连接数", "个",
                    "sum(mysql_global_status_threads_connected{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            new MetricDef("qps", "查询/秒", "次/秒",
                    "sum(rate(mysql_global_status_questions{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            new MetricDef("running", "活跃线程", "个",
                    "sum(mysql_global_status_threads_running{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            new MetricDef("slow", "慢查询/秒", "次/秒",
                    "sum(rate(mysql_global_status_slow_queries{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"));

    /**
     * Nacos actuator（/nacos/actuator/prometheus）的 Micrometer 指标。
     *
     * <p>前三条是 JVM/HTTP 标准指标，**任何状态下都有**；最后一条是 Nacos 业务指标，但实测
     * 它的 gauge 注册依赖模块初始化（Pod 重启后一段时间内不出现），所以只保留一条并接受可能为空。
     */
    private static final List<MetricDef> NACOS_METRICS = List.of(
            new MetricDef("heap", "JVM 堆使用", "字节",
                    "sum(jvm_memory_used_bytes{area=\"heap\",cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            new MetricDef("threads", "活跃线程", "个",
                    "sum(jvm_threads_live_threads{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            new MetricDef("http", "HTTP 请求/秒", "次/秒",
                    "sum(rate(http_server_requests_seconds_count{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            new MetricDef("config", "配置数", "个",
                    "sum(nacos_monitor{module=\"config\",name=\"configCount\",cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"));

    /** Strimzi Metrics Reporter（9404）的指标；名字是 reporter 导出的 JMX 原名（含 persec） */
    private static final List<MetricDef> KAFKA_METRICS = List.of(
            new MetricDef("messages_in", "消息入站/秒", "条/秒",
                    "sum(rate(kafka_server_brokertopicmetrics_messagesinpersec_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            new MetricDef("bytes_in", "字节入站/秒", "字节/秒",
                    "sum(rate(kafka_server_brokertopicmetrics_bytesinpersec_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            new MetricDef("urp", "未充分复制分区", "个",
                    "sum(kafka_server_replicamanager_underreplicatedpartitions{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            new MetricDef("controller", "活跃 Controller", "个",
                    "sum(kafka_controller_kafkacontroller_activecontrollercount{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"));

    /** redis-exporter 边车（9121）的指标 */
    private static final List<MetricDef> REDIS_METRICS = List.of(
            new MetricDef("clients", "连接数", "个",
                    "sum(redis_connected_clients{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            new MetricDef("memory", "内存使用", "字节",
                    "sum(redis_memory_used_bytes{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            new MetricDef("commands", "命令/秒", "个/秒",
                    "sum(rate(redis_commands_processed_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            new MetricDef("blocked", "阻塞客户端", "个",
                    "sum(redis_blocked_clients{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"));

    /** CloudNativePG 内置 exporter（9187）的指标；promql 里三个 %s = cluster_name / namespace / pod 正则 */
    private static final List<MetricDef> POSTGRESQL_METRICS = List.of(
            new MetricDef("connections", "连接数", "个",
                    "sum(cnpg_backends_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            new MetricDef("xact", "事务提交/秒", "个/秒",
                    "sum(rate(cnpg_pg_stat_database_xact_commit{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            new MetricDef("replag", "复制延迟", "秒",
                    "max(cnpg_pg_replication_lag{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            new MetricDef("dbsize", "数据库大小", "字节",
                    "sum(cnpg_pg_database_size_bytes{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"));

    /** RabbitMQ prometheus 插件（15692）的指标 */
    private static final List<MetricDef> RABBITMQ_METRICS = List.of(
            new MetricDef("ready", "就绪消息", "条",
                    "sum(rabbitmq_queue_messages_ready{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            new MetricDef("unacked", "未确认消息", "条",
                    "sum(rabbitmq_queue_messages_unacked{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            new MetricDef("connections", "连接数", "个",
                    "sum(rabbitmq_connections{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            new MetricDef("publish", "发布/秒", "条/秒",
                    "sum(rate(rabbitmq_queue_messages_published_total{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"));

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
