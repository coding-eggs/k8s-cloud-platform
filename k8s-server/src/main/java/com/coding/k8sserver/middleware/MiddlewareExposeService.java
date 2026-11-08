package com.coding.k8sserver.middleware;

import com.coding.common.models.middleware.MiddlewareExposeRequestDTO;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.springframework.stereotype.Service;

/**
 * 中间件对外暴露的集群侧（零业务）：按 platform-api 给的参数建 / 删路由与 NodePort Service。
 * 端口分配、ListenerSet 重算、实例归属校验都在 platform-api。
 *
 * <p>先过访问边界（租户 token 只能动分配给自己的命名空间），再用 admin client 下发：
 * 要读 CRD 是否安装（集群级），租户 client 读不了。
 */
@Service
public class MiddlewareExposeService {

    private final KubernetesClientFactory clients;
    private final ResourceAccessResolver access;

    public MiddlewareExposeService(KubernetesClientFactory clients, ResourceAccessResolver access) {
        this.clients = clients;
        this.access = access;
    }

    public void apply(MiddlewareExposeRequestDTO r) {
        MiddlewareExposeObjects.validate(r);
        access.resolveNamespacedAccessOrPlatform(r.getTenantId(), r.getClusterId(), r.getNamespace());
        KubernetesClient client = clients.getAdminClient(r.getClusterId());
        String ns = r.getNamespace();
        String name = MiddlewareExposeObjects.objectName(r.getInstanceName(), r.getEndpointKey());
        switch (r.getMode()) {
            case "GATEWAY" -> {
                boolean http = "HTTP".equals(r.getProtocol());
                deleteRoute(client, http ? MiddlewareExposeObjects.TCP_ROUTE : MiddlewareExposeObjects.HTTP_ROUTE, ns, name);
                client.services().inNamespace(ns).withName(name).delete();
                client.genericKubernetesResources(http ? MiddlewareExposeObjects.HTTP_ROUTE : MiddlewareExposeObjects.TCP_ROUTE)
                        .inNamespace(ns).resource(MiddlewareExposeObjects.route(r))
                        .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
            }
            case "NODEPORT" -> {
                deleteRoute(client, MiddlewareExposeObjects.HTTP_ROUTE, ns, name);
                deleteRoute(client, MiddlewareExposeObjects.TCP_ROUTE, ns, name);
                var target = client.services().inNamespace(ns).withName(r.getServiceName()).get();
                client.services().inNamespace(ns).resource(MiddlewareExposeObjects.nodePortService(r, target))
                        .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
            }
            default -> {
                deleteRoute(client, MiddlewareExposeObjects.HTTP_ROUTE, ns, name);
                deleteRoute(client, MiddlewareExposeObjects.TCP_ROUTE, ns, name);
                client.services().inNamespace(ns).withName(name).delete();
            }
        }
    }

    /** 删掉某实例的全部暴露对象（按标签） */
    public void deleteAll(MiddlewareExposeRequestDTO r) {
        if (r.getClusterId() == null || r.getNamespace() == null || r.getInstanceName() == null) {
            throw new IllegalArgumentException("clusterId / namespace / instanceName 不能为空");
        }
        access.resolveNamespacedAccessOrPlatform(r.getTenantId(), r.getClusterId(), r.getNamespace());
        KubernetesClient client = clients.getAdminClient(r.getClusterId());
        String ns = r.getNamespace();
        String value = r.getInstanceName();
        for (ResourceDefinitionContext ctx : new ResourceDefinitionContext[]{
                MiddlewareExposeObjects.HTTP_ROUTE, MiddlewareExposeObjects.TCP_ROUTE}) {
            if (crdInstalled(client, ctx)) {
                client.genericKubernetesResources(ctx).inNamespace(ns)
                        .withLabel(MiddlewareExposeObjects.LABEL, value).delete();
            }
        }
        client.services().inNamespace(ns).withLabel(MiddlewareExposeObjects.LABEL, value).delete();
    }

    /** CRD 没装（TCPRoute 在 experimental 通道）时删路由会 404：没装就不可能有对象，跳过 */
    private static void deleteRoute(KubernetesClient client, ResourceDefinitionContext ctx, String ns, String name) {
        if (crdInstalled(client, ctx)) {
            client.genericKubernetesResources(ctx).inNamespace(ns).withName(name).delete();
        }
    }

    private static boolean crdInstalled(KubernetesClient client, ResourceDefinitionContext ctx) {
        return client.apiextensions().v1().customResourceDefinitions()
                .withName(ctx.getPlural() + "." + ctx.getGroup()).get() != null;
    }
}
