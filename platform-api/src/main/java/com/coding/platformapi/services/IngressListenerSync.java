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

    public IngressListenerSync(IngressPortService ports, K8sAppClient client) {
        this.ports = ports;
        this.client = client;
    }

    public static String sectionName(int port) {
        return "p" + port;
    }

    public void sync(String clusterId, String namespace) {
        List<IngressListenerDTO> listeners = new ArrayList<>();
        for (PlatformIngressPort p : ports.activeInNamespace(clusterId, namespace)) {
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
