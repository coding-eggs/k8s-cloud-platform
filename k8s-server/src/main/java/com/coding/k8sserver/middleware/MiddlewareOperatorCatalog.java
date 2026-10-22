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

    public record Entry(String code, String chartVersion, List<String> requiredCrds) {
    }

    private static final Map<String, Entry> ENTRIES;

    static {
        Map<String, Entry> m = new LinkedHashMap<>();
        m.put("redis", new Entry("redis", "0.27.0", List.of(
                "redis.redis.redis.opstreelabs.in",
                "redisreplications.redis.redis.opstreelabs.in",
                "redissentinels.redis.redis.opstreelabs.in",
                "redisclusters.redis.redis.opstreelabs.in")));
        m.put("mysql", new Entry("mysql", "1.2.0",
                List.of("perconaservermysqls.ps.percona.com")));
        m.put("kafka", new Entry("kafka", "1.3.0",
                List.of("kafkas.kafka.strimzi.io", "kafkanodepools.kafka.strimzi.io")));
        m.put("elasticsearch", new Entry("elasticsearch", "3.5.0",
                List.of("elasticsearches.elasticsearch.k8s.elastic.co")));
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
