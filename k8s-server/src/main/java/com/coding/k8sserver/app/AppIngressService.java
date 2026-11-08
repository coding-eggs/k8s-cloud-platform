package com.coding.k8sserver.app;

import com.coding.common.models.k8s.dto.IngressListenerSetRequestDTO;
import com.coding.common.models.k8s.dto.IngressListenerSetStatusDTO;
import com.coding.k8sserver.components.ExternalGatewayObjects;
import com.coding.k8sserver.components.IngressListenerSetObjects;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 应用端口池的集群侧（零业务）：命名空间 ListenerSet、命名空间放行标签、网关 Service 声明的端口。
 *
 * <p>挂在应用编排入口（UPSTREAM 边界）而不是组件入口：调用方是租户的保存 / 发布，平台组件端点要求平台侧身份，
 * 租户 token 会被 403。归属校验在 platform-api（应用属于该租户、命名空间是应用自己的）。
 */
@org.springframework.stereotype.Service
public class AppIngressService {

    /** 命名空间放行标签专用的 field manager：只拥有这几个键，给空 Map 就是全部摘掉 */
    static final String INGRESS_LABEL_FIELD_MANAGER = "platform-ingress-label";

    private final KubernetesClientFactory clients;

    public AppIngressService(KubernetesClientFactory clients) {
        this.clients = clients;
    }

    /** listeners 非空：先打放行标签，再 SSA ListenerSet；为空：删 ListenerSet、摘标签。CRD 或父 Gateway 缺失返回 false */
    public boolean apply(IngressListenerSetRequestDTO request) {
        IngressListenerSetObjects.validate(request);
        KubernetesClient client = clients.getAdminClient(request.getClusterId());
        if (client.apiextensions().v1().customResourceDefinitions()
                .withName(IngressListenerSetObjects.CRD_NAME).get() == null) {
            return false;
        }
        boolean empty = request.getListeners() == null || request.getListeners().isEmpty();
        if (empty) {
            client.genericKubernetesResources(IngressListenerSetObjects.CRD)
                    .inNamespace(request.getNamespace()).withName(request.getName()).delete();
            applyNamespaceLabels(client, request.getNamespace(), Map.of());
            return true;
        }
        // 父 Gateway 不存在时 SSA 也会成功，但 ListenerSet 永远不会被接受、路由挂不上：当作「不支持」返回
        if (client.genericKubernetesResources(ExternalGatewayObjects.CRD)
                .inNamespace(request.getParentNamespace()).withName(request.getParentName()).get() == null) {
            return false;
        }
        Map<String, String> labels = request.getNamespaceLabels() == null ? Map.of() : request.getNamespaceLabels();
        applyNamespaceLabels(client, request.getNamespace(), labels);
        client.genericKubernetesResources(IngressListenerSetObjects.CRD)
                .inNamespace(request.getNamespace())
                .resource(IngressListenerSetObjects.build(request))
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return true;
    }

    /** 每个 listener 是否生效 / 冲突；CRD 或对象不存在时 exists=false */
    public IngressListenerSetStatusDTO status(String clusterId, String namespace, String name) {
        KubernetesClient client = clients.getAdminClient(clusterId);
        if (client.apiextensions().v1().customResourceDefinitions()
                .withName(IngressListenerSetObjects.CRD_NAME).get() == null) {
            return new IngressListenerSetStatusDTO();
        }
        var resource = client.genericKubernetesResources(IngressListenerSetObjects.CRD)
                .inNamespace(namespace).withName(name).get();
        return IngressListenerSetObjects.status(resource);
    }

    /** Service 声明的端口；Service 不存在返回空 */
    public List<Integer> servicePorts(String clusterId, String namespace, String name) {
        Service svc = clients.getAdminClient(clusterId).services().inNamespace(namespace).withName(name).get();
        if (svc == null || svc.getSpec() == null || svc.getSpec().getPorts() == null) {
            return List.of();
        }
        return svc.getSpec().getPorts().stream().map(ServicePort::getPort).filter(Objects::nonNull).toList();
    }

    /** 集群里所有 Service 已占用的 nodePort（全部命名空间、每个端口一条）；给平台挑 NodePort 时避让用 */
    public List<com.coding.common.models.app.NodePortUseDTO> usedNodePorts(String clusterId) {
        List<com.coding.common.models.app.NodePortUseDTO> out = new java.util.ArrayList<>();
        for (Service svc : clients.getAdminClient(clusterId).services().inAnyNamespace().list().getItems()) {
            if (svc.getSpec() == null || svc.getSpec().getPorts() == null) {
                continue;
            }
            for (ServicePort p : svc.getSpec().getPorts()) {
                if (p.getNodePort() != null && p.getNodePort() > 0) {
                    com.coding.common.models.app.NodePortUseDTO u = new com.coding.common.models.app.NodePortUseDTO();
                    u.setNamespace(svc.getMetadata().getNamespace());
                    u.setName(svc.getMetadata().getName());
                    u.setNodePort(p.getNodePort());
                    out.add(u);
                }
            }
        }
        return out;
    }

    private static void applyNamespaceLabels(KubernetesClient client, String namespace, Map<String, String> labels) {
        Namespace ns = new NamespaceBuilder()
                .withNewMetadata().withName(namespace).withLabels(new LinkedHashMap<>(labels)).endMetadata()
                .build();
        client.namespaces().resource(ns).fieldManager(INGRESS_LABEL_FIELD_MANAGER).forceConflicts().serverSideApply();
    }
}
