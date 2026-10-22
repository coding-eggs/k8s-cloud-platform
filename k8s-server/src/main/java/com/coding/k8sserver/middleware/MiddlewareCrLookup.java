package com.coding.k8sserver.middleware;

import com.coding.k8score.factory.KubernetesClientFactory;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 判断一组标签是否指向某个已存在的中间件实例 CR。
 *
 * <p>为什么要这一步：Operator 给子资源（Pod/Service/PVC）打的是它自己的标签
 * （Percona {@code app.kubernetes.io/instance}、Strimzi {@code strimzi.io/cluster}、
 * ECK {@code elasticsearch.k8s.elastic.co/cluster-name}、Redis {@code app}），
 * 平台创建 CR 时打的 {@code platform.coding/*} 标签不会传播给子资源。
 * 资源保护要认出"这是中间件实例的下级资源"，只能拿标签里的实例名回查 CR 是否存在。
 *
 * <p>只做事实读取（admin client），带 30s 缓存 —— 列表页会按行调用。
 */
@Component
public class MiddlewareCrLookup {

    private record Kind(CustomResourceDefinitionContext context, String labelKey) {
    }

    private static final List<Kind> KINDS = List.of(
            new Kind(crd("ps.percona.com", "v1", "PerconaServerMySQL", "perconaservermysqls"),
                    "app.kubernetes.io/instance"),
            new Kind(crd("kafka.strimzi.io", "v1", "Kafka", "kafkas"), "strimzi.io/cluster"),
            new Kind(crd("kafka.strimzi.io", "v1", "KafkaNodePool", "kafkanodepools"), "strimzi.io/cluster"),
            new Kind(crd("elasticsearch.k8s.elastic.co", "v1", "Elasticsearch", "elasticsearches"),
                    "elasticsearch.k8s.elastic.co/cluster-name"),
            new Kind(crd("redis.redis.opstreelabs.in", "v1beta2", "Redis", "redis"), "app"),
            new Kind(crd("redis.redis.opstreelabs.in", "v1beta2", "RedisReplication", "redisreplications"), "app"),
            new Kind(crd("redis.redis.opstreelabs.in", "v1beta2", "RedisSentinel", "redissentinels"), "app"),
            new Kind(crd("redis.redis.opstreelabs.in", "v1beta2", "RedisCluster", "redisclusters"), "app"));

    private static final long TTL_MS = 30_000;

    private final KubernetesClientFactory clients;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public MiddlewareCrLookup(KubernetesClientFactory clients) {
        this.clients = clients;
    }

    public boolean ownedByMiddleware(String clusterId, String namespace, Map<String, String> labels) {
        if (labels == null || labels.isEmpty() || namespace == null) {
            return false;
        }
        for (Kind kind : KINDS) {
            String name = labels.get(kind.labelKey());
            if (name == null || name.isBlank()) {
                continue;
            }
            if (exists(clusterId, namespace, kind, name)) {
                return true;
            }
        }
        return false;
    }

    private boolean exists(String clusterId, String namespace, Kind kind, String name) {
        String key = clusterId + "|" + namespace + "|" + kind.context().getPlural() + "|" + name;
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(key);
        if (entry != null && now - entry.at() < TTL_MS) {
            return entry.exists();
        }
        boolean exists;
        try {
            KubernetesClient client = clients.getAdminClient(clusterId);
            exists = client.genericKubernetesResources(kind.context())
                    .inNamespace(namespace).withName(name).get() != null;
        } catch (RuntimeException e) {
            // 读不到（集群不可达 / CRD 未安装）按"不是中间件资源"处理：宁可少拦，不可误拦用户资源
            exists = false;
        }
        cache.put(key, new CacheEntry(exists, now));
        if (cache.size() > 5000) {
            cache.clear();   // 简单上限，避免长期运行堆积
        }
        return exists;
    }

    private static CustomResourceDefinitionContext crd(String group, String version, String kind, String plural) {
        return new CustomResourceDefinitionContext.Builder()
                .withGroup(group).withVersion(version).withKind(kind)
                .withPlural(plural).withScope("Namespaced").build();
    }

    private record CacheEntry(boolean exists, long at) {
    }
}
