package com.coding.k8sserver.components;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.k8sserver.middleware.MiddlewareCrLookup;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 资源保护：判定一个对象是否由平台"托管"，并拒绝普通资源页的写入。
 *
 * <p>判定顺序（spec §7.1）：
 * <ol>
 *   <li>平台标签 {@code platform.coding/managed-by}（平台自己创建的对象，如组件的 Gateway CR）；</li>
 *   <li>Helm 归属注解：release 名以 {@code platform-}（组件/Operator）或 {@code mw-}（中间件实例，含 Nacos）开头；</li>
 *   <li>标签里的实例名能回查到中间件 CR（Operator 给子资源打的标签，见 {@link MiddlewareCrLookup}）。</li>
 * </ol>
 *
 * <p><b>必须后端拦</b>：前端置灰只是提示，直接调 API 一样要拒。
 */
@Component
public class ManagedResourceGuard {

    public static final String COMPONENT = "COMPONENT";
    public static final String MIDDLEWARE = "MIDDLEWARE";

    private static final String MANAGED_BY_LABEL = "platform.coding/managed-by";
    private static final String HELM_RELEASE_ANNOTATION = "meta.helm.sh/release-name";

    private final MiddlewareCrLookup middlewareLookup;

    public ManagedResourceGuard(MiddlewareCrLookup middlewareLookup) {
        this.middlewareLookup = middlewareLookup;
    }

    /** 返回 {@code COMPONENT} / {@code MIDDLEWARE} / null（用户资源）。 */
    public String managedBy(String clusterId, String namespace,
                            Map<String, String> labels, Map<String, ?> annotations) {
        if (labels != null) {
            String own = labels.get(MANAGED_BY_LABEL);
            if (own != null) {
                return "middleware".equals(own) || "middleware-operator".equals(own)
                        ? MIDDLEWARE : COMPONENT;
            }
        }
        if (annotations != null) {
            Object relObj = annotations.get(HELM_RELEASE_ANNOTATION);
            String release = relObj == null ? null : String.valueOf(relObj);
            if (release != null) {
                if (release.startsWith("mw-")) {
                    return MIDDLEWARE;
                }
                if (release.startsWith("platform-")) {
                    return COMPONENT;
                }
            }
        }
        if (middlewareLookup.ownedByMiddleware(clusterId, namespace, labels)) {
            return MIDDLEWARE;
        }
        return null;
    }

    /** 写入前的硬闸：受管资源一律拒。 */
    public void assertMutable(String clusterId, String namespace,
                              Map<String, String> labels, Map<String, ?> annotations) {
        if (managedBy(clusterId, namespace, labels, annotations) != null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_MANAGED_BY_PLATFORM);
        }
    }
}
