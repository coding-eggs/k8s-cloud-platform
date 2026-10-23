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

    public record Mode(String mode, String label, int minReplicas, String help) {
    }

    /**
     * @param versions 平台支持的可选版本（第一项为默认）；空 = 版本由实现固定，表单不展示版本字段
     */
    public record Item(String code, String name, String description, List<Mode> modes, List<String> versions) {
    }

    private static final List<Mode> REDIS_MODES = List.of(
            new Mode("STANDALONE", "单节点", 1, "开发/测试，无高可用"),
            new Mode("SENTINEL", "哨兵主从", 2, "数据节点数；哨兵固定 3 个"),
            new Mode("CLUSTER", "集群分片", 3, "≥3 个主分片，数据分片存储"));

    private static final List<Mode> MYSQL_MODES = List.of(
            new Mode("SINGLE", "单节点", 1, "开发/测试；Group Replication 单成员，无容错"),
            new Mode("CLUSTER", "高可用集群", 3, "Group Replication，单主强一致，3/5 节点"));

    private static final List<Mode> KAFKA_MODES = List.of(
            new Mode("CLUSTER", "KRaft 集群", 3, "brokers ≥3，同时承担 controller；2 节点没有容错"));

    private static final List<Mode> ES_MODES = List.of(
            new Mode("CLUSTER", "集群", 1, "生产建议 ≥3 节点"));

    private static final List<Mode> NACOS_MODES = List.of(
            new Mode("STANDALONE", "单节点", 1, "开发/测试"),
            new Mode("CLUSTER", "集群", 3, "内嵌存储（JRaft），≥3 节点"));

    private static final List<Mode> POSTGRESQL_MODES = List.of(
            new Mode("SINGLE", "单实例", 1, "开发/测试；无容错"),
            new Mode("HA", "主备", 3, "CloudNativePG 一主多备，自动 failover（3/5 实例）"));

    private static final List<Mode> RABBITMQ_MODES = List.of(
            new Mode("SINGLE", "单节点", 1, "开发/测试；无容错"),
            new Mode("CLUSTER", "集群", 3, "≥3 节点，Quorum 队列需要多数派"));

    /** Strimzi 1.3.0 的受支持版本清单（顺序即展示顺序，第一项为默认） */
    private static final List<String> KAFKA_VERSIONS =
            List.of("4.3.1", "4.3.0", "4.2.2", "4.2.1", "4.2.0");

    private static final List<Item> ITEMS = List.of(
            new Item("redis", "Redis", "内存键值存储：缓存、会话、分布式锁", REDIS_MODES, List.of()),
            new Item("mysql", "MySQL", "关系型数据库（Percona Operator）", MYSQL_MODES, List.of()),
            // Strimzi 1.3.0 支持的版本（见其 UnsupportedKafkaVersionException 提示），默认 4.3.1
            new Item("kafka", "Kafka", "事件流平台（Strimzi，KRaft）", KAFKA_MODES,
                    KAFKA_VERSIONS),
            // ECK 3.5.0 实测可用的版本，默认 9.5.0
            new Item("elasticsearch", "Elasticsearch", "搜索与日志存储（ECK）", ES_MODES,
                    List.of("9.5.0")),
            new Item("postgresql", "PostgreSQL", "关系型数据库（CloudNativePG）", POSTGRESQL_MODES, List.of()),
            new Item("rabbitmq", "RabbitMQ", "消息队列（官方 Cluster Operator）", RABBITMQ_MODES, List.of()),
            new Item("nacos", "Nacos", "注册中心与配置中心（chart）", NACOS_MODES, List.of()));

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
