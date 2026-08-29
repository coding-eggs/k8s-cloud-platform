package com.coding.k8sserver.controllers.namespace;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.components.ResourceAccessResolver.AccessContext;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 命名空间域 - Pod 查看（双模访问，边界=分配表）。
 * Pod 由工作负载控制器管理：只读 list/get/yaml + delete，无 create/update（基类端点覆写为拒绝）；
 * 日志走 HTTP 流式透传（follow=true 时连接保持到客户端断开/Pod 终止）。
 */
@Slf4j
@Tag(name = "资源管理-Pod", description = "查看/删除命名空间内 Pod（只读，双模访问，边界=分配表）")
@RestController
@RequestMapping("/resources/pods")
public class PodController extends AbstractNamespacedResourceController<PodDTO> {

    private final KubernetesClientFactory clientFactory;

    public PodController(KubernetesOperationsFactory operationsFactory,
                         ResourceAccessResolver accessResolver,
                         KubernetesClientFactory clientFactory) {
        super(operationsFactory, accessResolver);
        this.clientFactory = clientFactory;
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.POD;
    }

    /**Pod 由工作负载控制器管理：不支持直接创建 */
    @Override
    public ResponseData<PodDTO> create(String tenantId, String clusterId, PodDTO body) {
        throw new CloudPlatformException(EnumResponseType.OPERATION_NOT_SUPPORTED);
    }

    /**Pod 由工作负载控制器管理：不支持直接更新 */
    @Override
    public ResponseData<PodDTO> update(String name, String tenantId, String clusterId, PodDTO body) {
        throw new CloudPlatformException(EnumResponseType.OPERATION_NOT_SUPPORTED);
    }

    @GetMapping(value = "/{name}/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "流式获取 Pod 日志（follow=true 保持连接）")
    public void logs(@PathVariable String name,
                     @RequestParam(required = false) String tenantId,
                     @RequestParam String clusterId,
                     @RequestParam String namespace,
                     @RequestParam(required = false) String container,
                     @RequestParam(defaultValue = "500") int tailLines,
                     @RequestParam(defaultValue = "false") boolean follow,
                     HttpServletResponse response) throws IOException {
        AccessContext ctx = accessResolver.resolveNamespacedAccess(tenantId, clusterId, namespace);

        KubernetesClient client = getClient(ctx, clusterId);
        PodResource pod = client.pods().inNamespace(namespace).withName(name);
        if (StringUtils.hasText(container)) {
            pod.inContainer(container);
        }
        if (tailLines > 0) {
            pod.tailingLines(tailLines);
        }

        response.setContentType(MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        try (OutputStream out = response.getOutputStream()) {
            if (follow) {
                //watchLog = follow 语义：流式输出直到 Pod 日志结束或连接关闭
                try (LogWatch watch = pod.watchLog()) {
                    watch.getOutput().transferTo(out);
                }
            } else {
                out.write(pod.getLog().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            out.flush();
        } catch (IOException e) {
            //客户端断开/网络中断：连接已不可用，仅记录
            log.debug("Pod 日志流中断 {}/{}/{}: {}", namespace, name, container, e.getMessage());
        }
    }

    /**租户模式→tenant client（最小权限）；admin 模式→admin client */
    private KubernetesClient getClient(AccessContext ctx, String clusterId) {
        return ctx.adminMode()
                ? clientFactory.getAdminClient(clusterId)
                : clientFactory.getTenantClient(clusterId, ctx.tenantId());
    }

}
