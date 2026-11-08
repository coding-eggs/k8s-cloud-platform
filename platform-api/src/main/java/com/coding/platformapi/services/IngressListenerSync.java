package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.IngressListenerDTO;
import com.coding.common.models.k8s.dto.IngressListenerSetRequestDTO;
import com.coding.data.models.system.PlatformIngressPort;
import com.coding.platformapi.k8s.K8sAppClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 把命名空间里所有 ACTIVE 端口整份同步成一个 ListenerSet（spec §5.3）。
 *
 * <p>一个命名空间一个 {@code platform-ingress}：同命名空间的多个应用共用它，所以每次都按分配表<b>整份</b>重建，
 * 不做增量。分配表为空时也调用 —— k8s-server 删掉 ListenerSet 并摘掉命名空间的放行标签。
 */
@Service
public class IngressListenerSync {

    public static final String LISTENER_SET = "platform-ingress";
    /** Gateway allowedListeners 的放行标签（与 ComponentInstallService.INGRESS_LISTENER_LABELS 同一个键） */
    public static final String NS_LABEL = "ingress.platform.coding/ports";
    public static final String GATEWAY_NS = IngressPortService.GATEWAY_NAMESPACE;
    public static final String GATEWAY = IngressPortService.GATEWAY_SERVICE;

    private final IngressPortService ports;
    private final K8sAppClient client;
    private final IngressNamespaceLock lock;

    public IngressListenerSync(IngressPortService ports, K8sAppClient client, IngressNamespaceLock lock) {
        this.ports = ports;
        this.client = client;
        this.lock = lock;
    }

    public static String sectionName(int port) {
        return "p" + port;
    }

    public void sync(String clusterId, String namespace) {
        syncReleasing(clusterId, namespace, p -> false, () -> { });
    }

    /**
     * 在命名空间锁里：按分配表整份下发，但不含 {@code leaving} 的端口；下发成功后才执行 {@code release}
     * （真正把这些端口标成 RELEASED）。下发失败抛异常、端口保持 ACTIVE —— listener 还在集群上，
     * 不能让它冷却后分给别的命名空间（终审 I4）。
     */
    public void syncReleasing(String clusterId, String namespace, Predicate<PlatformIngressPort> leaving,
                              Runnable release) {
        lock.run(clusterId, namespace, () -> {
            apply(clusterId, namespace, leaving);
            release.run();
        });
    }

    private void apply(String clusterId, String namespace, Predicate<PlatformIngressPort> leaving) {
        List<IngressListenerDTO> listeners = new ArrayList<>();
        for (PlatformIngressPort p : ports.activeInNamespace(clusterId, namespace)) {
            if (leaving.test(p)) {
                continue;
            }
            IngressListenerDTO l = new IngressListenerDTO();
            l.setName(sectionName(p.getPort()));
            l.setPort(p.getPort());
            l.setProtocol(p.getProtocol());
            listeners.add(l);
        }
        IngressListenerSetRequestDTO req = new IngressListenerSetRequestDTO();
        req.setClusterId(clusterId);
        req.setNamespace(namespace);
        req.setName(LISTENER_SET);
        req.setParentNamespace(GATEWAY_NS);
        req.setParentName(GATEWAY);
        req.setNamespaceLabels(Map.of(NS_LABEL, "true"));
        req.setListeners(listeners);
        // 清空时 CRD 不在 = 本来就没有可删的东西
        if (!client.applyIngressListenerSet(req) && !listeners.isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "集群缺少 ListenerSet CRD 或入口 Gateway，不能用网关方式暴露");
        }
    }
}
