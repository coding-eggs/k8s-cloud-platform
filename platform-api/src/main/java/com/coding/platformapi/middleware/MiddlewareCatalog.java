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

    public record Item(String code, String name, String description, List<Mode> modes) {
    }

    private static final List<Mode> REDIS_MODES = List.of(
            new Mode("STANDALONE", "单节点", 1, "开发/测试，无高可用"),
            new Mode("SENTINEL", "哨兵主从", 2, "数据节点数；哨兵固定 3 个"),
            new Mode("CLUSTER", "集群分片", 3, "≥3 个主分片，数据分片存储"));

    private static final List<Item> ITEMS = List.of(
            new Item("redis", "Redis", "内存键值存储：缓存、会话、分布式锁", REDIS_MODES),
            new Item("mysql", "MySQL", "关系型数据库（Percona Operator）", List.of()),
            new Item("kafka", "Kafka", "事件流平台（Strimzi，KRaft）", List.of()),
            new Item("elasticsearch", "Elasticsearch", "搜索与日志存储（ECK）", List.of()),
            new Item("postgresql", "PostgreSQL", "关系型数据库（CloudNativePG）", List.of()),
            new Item("rabbitmq", "RabbitMQ", "消息队列（官方 Cluster Operator）", List.of()),
            new Item("nacos", "Nacos", "注册中心与配置中心（chart）", List.of()));

    public List<Item> items() {
        return ITEMS;
    }

    public Item require(String code) {
        return ITEMS.stream().filter(i -> i.code().equals(code)).findFirst()
                .orElseThrow(() -> new CloudPlatformException(EnumResponseType.ERROR,
                        "不支持的中间件：" + code));
    }

    public Mode requireMode(Item item, String mode) {
        return item.modes().stream().filter(m -> m.mode().equals(mode)).findFirst()
                .orElseThrow(() -> new CloudPlatformException(EnumResponseType.ERROR,
                        item.name() + " 不支持的形态：" + mode));
    }
}
