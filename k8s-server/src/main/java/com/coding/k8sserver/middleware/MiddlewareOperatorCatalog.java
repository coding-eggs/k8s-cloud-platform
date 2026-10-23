package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 中间件 Operator 的固定目录（chart 版本、必需 CRD）。
 *
 * <p>与 S3.6 的组件 recipe 同构：版本钉死在代码里，chart 随镜像内置在
 * {@code charts/middleware/<code>/<chart-version>/chart}。
 *
 * <p>为什么必需 CRD 也写死：Operator 是否装好不能只看 helm release，CRD 才是实例能否创建的前提；
 * 反过来只看 CRD 也不够——CRD 是集群级的，卸载 release 后仍在（Helm 不删 CRD）。
 */
@Component
public class MiddlewareOperatorCatalog {

    /** Operator 统一装在平台命名空间（不属于任何租户）。 */
    public static final String NAMESPACE = "platform-middleware-system";

    /**
     * @param helmValues 安装时必须下发的 values（不能留空：Percona/Strimzi 默认只监听自己命名空间）
     * @param crdsShipInCrdsDir chart 把 CRD 放在 {@code crds/} 目录（Helm 不给它们打 release 注解）。
     *        这类 CRD 装完就是"无人认领"状态：卸载后仍在，重装时按"外部 CRD"拒掉会让平台**再也装不回来**。
     *        true = 允许接管没有 release 注解的 CRD（仍拒绝别的 release 明确拥有的）。
     */
    public record Entry(String code, String chartVersion, List<String> requiredCrds, String helmValues,
                        boolean crdsShipInCrdsDir) {
    }

    private static final Map<String, Entry> ENTRIES;

    static {
        Map<String, Entry> m = new LinkedHashMap<>();
        m.put("redis", new Entry("redis", "0.27.0", List.of(
                "redis.redis.redis.opstreelabs.in",
                "redisreplications.redis.redis.opstreelabs.in",
                "redissentinels.redis.redis.opstreelabs.in",
                "redisclusters.redis.redis.opstreelabs.in"),
                "", false));
        m.put("mysql", new Entry("mysql", "1.2.0",
                List.of("perconaservermysqls.ps.percona.com"),
                // chart 默认只 watch 自身命名空间；不下发这行，租户 ns 的 CR 没人 reconcile
                "watchAllNamespaces: true\n", false));
        m.put("kafka", new Entry("kafka", "1.3.0",
                List.of("kafkas.kafka.strimzi.io", "kafkanodepools.kafka.strimzi.io"),
                // chart 默认 watchNamespaces: [] + watchAnyNamespace: false
                "watchAnyNamespace: true\n", false));
        m.put("elasticsearch", new Entry("elasticsearch", "3.5.0",
                List.of("elasticsearches.elasticsearch.k8s.elastic.co"),
                // ECK 默认全集群监听
                "", false));
        m.put("postgresql", new Entry("postgresql", "0.29.1",
                List.of("clusters.postgresql.cnpg.io"),
                // CNPG 默认 clusterWide（监听全部命名空间）；CRD 在 chart 的 templates/ 里，安装即建
                "", false));
        m.put("rabbitmq", new Entry("rabbitmq", "4.4.34",
                List.of("rabbitmqclusters.rabbitmq.com"),
                // 只要集群 Operator：拓扑 Operator 会多拉两个镜像，平台不提供拓扑类对象。
                // 镜像全部显式覆盖：Bitnami 2025 起把历史 tag 从 bitnami/* 挪到 bitnamilegacy/*，
                // 默认值（bitnami/rabbitmq-cluster-operator:2.16.1-debian-12-r0）已 manifest unknown；
                // broker 用官方 rabbitmq 镜像（library/rabbitmq）。
                "msgTopologyOperator:\n"
                        + "  enabled: false\n"
                        + "global:\n"
                        + "  security:\n"
                        + "    allowInsecureImages: true\n"
                        + "clusterOperator:\n"
                        + "  image:\n"
                        + "    registry: docker.io\n"
                        + "    repository: bitnamilegacy/rabbitmq-cluster-operator\n"
                        + "    tag: 2.16.1-debian-12-r0\n"
                        + "credentialUpdaterImage:\n"
                        + "  registry: docker.io\n"
                        + "  repository: bitnamilegacy/rmq-default-credential-updater\n"
                        + "  tag: 1.0.8-debian-12-r0\n"
                        + "rabbitmqImage:\n"
                        + "  registry: docker.io\n"
                        + "  repository: library/rabbitmq\n"
                        + "  tag: 4.1.3\n", true));
        ENTRIES = Map.copyOf(m);
    }

    public Entry require(String code) {
        Entry e = ENTRIES.get(code);
        if (e == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "不支持的中间件：" + code);
        }
        return e;
    }

    public Set<String> codes() {
        return ENTRIES.keySet();
    }

    public static String releaseName(String code) {
        return "platform-middleware-" + code;
    }
}
