package com.coding.k8sserver.components;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.OwnerReferenceDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 资源保护：判定一个对象是否由平台"托管"，并拒绝普通资源页的写入。
 *
 * <p><b>纯元数据判定，不做任何集群读取</b> —— 列表页会按行调用，任何 IO 都会变成 N+1。
 * 判定顺序（命中即停）：
 * <ol>
 *   <li>标签 {@code platform.coding/managed-by}：{@code middleware*} → MIDDLEWARE，其它 → COMPONENT；</li>
 *   <li>标签 {@code platform.coding/managed=true} → COMPONENT（平台组件对象一直用的是这个标签）；</li>
 *   <li>Helm 注解 {@code meta.helm.sh/release-name}：{@code mw-} → MIDDLEWARE，{@code platform-} → COMPONENT；
 *       否则若 {@code meta.helm.sh/release-namespace} 是平台命名空间（istio-system/monitor/...）→ COMPONENT；</li>
 *   <li>ownerReferences 的属主名：{@code mw-} → MIDDLEWARE，{@code platform-} → COMPONENT
 *       （PVC/Pod 这类由 StatefulSet/Deployment 派生的对象靠这一条）；</li>
 *   <li>实例名标签（{@code app.kubernetes.io/instance} / {@code strimzi.io/cluster} /
 *       {@code elasticsearch.k8s.elastic.co/cluster-name} / {@code app}）的值以 {@code mw-} 开头 → MIDDLEWARE。</li>
 * </ol>
 *
 * <p>第 5 条只认平台自己的命名约定（实例名 {@code mw-<code>-<8hex>}），不做"标签名→CR 回查"：
 * 那样既会把用户 `app: redis` 这类通用标签误判成受管，又会在每个列表行产生多次集群请求。
 *
 * <p><b>必须后端拦</b>：前端置灰只是提示，直接调 API 一样要拒。
 */
@Component
public class ManagedResourceGuard {

    public static final String COMPONENT = "COMPONENT";
    public static final String MIDDLEWARE = "MIDDLEWARE";

    private static final String MANAGED_BY_LABEL = "platform.coding/managed-by";
    private static final String MANAGED_LABEL = "platform.coding/managed";
    private static final String RELEASE_ANNOTATION = "meta.helm.sh/release-name";
    private static final String RELEASE_NS_ANNOTATION = "meta.helm.sh/release-namespace";

    /** 属主名/实例名以它开头 = 中间件实例（见 MiddlewareService 的名字生成） */
    static final String MIDDLEWARE_PREFIX = "mw-";
    /** 组件 release / 对象名以它开头 */
    static final String COMPONENT_PREFIX = "platform-";

    private static final List<String> INSTANCE_LABEL_KEYS = List.of(
            "app.kubernetes.io/instance",
            "strimzi.io/cluster",
            "elasticsearch.k8s.elastic.co/cluster-name",
            "app");

    private final Set<String> platformNamespaces;

    public ManagedResourceGuard(
            @Value("${platform.managed-namespaces:platform-system,platform-middleware-system,monitor,"
                    + "istio-system,tekton-pipelines,cert-manager}") String namespaces) {
        this.platformNamespaces = Arrays.stream(namespaces.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    /** 返回 {@code COMPONENT} / {@code MIDDLEWARE} / null（用户资源）。 */
    public String managedBy(Map<String, String> labels, Map<String, ?> annotations,
                            List<OwnerReferenceDTO> ownerReferences) {
        // 1) 平台标签
        if (labels != null) {
            String own = labels.get(MANAGED_BY_LABEL);
            if (own != null) {
                return own.startsWith("middleware") ? MIDDLEWARE : COMPONENT;
            }
            if ("true".equals(labels.get(MANAGED_LABEL))) {
                return COMPONENT;
            }
        }

        // 2) Helm 归属
        if (annotations != null) {
            String release = stringOf(annotations.get(RELEASE_ANNOTATION));
            if (release != null) {
                if (release.startsWith(MIDDLEWARE_PREFIX)) {
                    return MIDDLEWARE;
                }
                if (release.startsWith(COMPONENT_PREFIX)) {
                    return COMPONENT;
                }
                String relNs = stringOf(annotations.get(RELEASE_NS_ANNOTATION));
                if (relNs != null && platformNamespaces.contains(relNs)) {
                    return COMPONENT;
                }
            }
        }

        // 3) 属主链（PVC/Pod 等派生对象）
        if (ownerReferences != null) {
            for (OwnerReferenceDTO owner : ownerReferences) {
                String name = owner == null ? null : owner.getName();
                if (name == null) {
                    continue;
                }
                if (name.startsWith(MIDDLEWARE_PREFIX)) {
                    return MIDDLEWARE;
                }
                if (name.startsWith(COMPONENT_PREFIX)) {
                    return COMPONENT;
                }
            }
        }

        // 4) Operator 给子资源打的实例名标签
        if (labels != null) {
            for (String key : INSTANCE_LABEL_KEYS) {
                String value = labels.get(key);
                if (value != null && value.startsWith(MIDDLEWARE_PREFIX)) {
                    return MIDDLEWARE;
                }
            }
        }
        return null;
    }

    /** 写入前的硬闸：受管资源一律拒。 */
    public void assertMutable(Map<String, String> labels, Map<String, ?> annotations,
                              List<OwnerReferenceDTO> ownerReferences) {
        if (managedBy(labels, annotations, ownerReferences) != null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_MANAGED_BY_PLATFORM);
        }
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
