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
import io.fabric8.kubernetes.client.dsl.*;
import io.fabric8.kubernetes.client.dsl.internal.core.v1.PodOperationsImpl;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

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

    @GetMapping(value = "/{name}/logs")
    @Operation(summary = "获取 Pod 日志（增量轮询 sinceTime；初始化回看 tailLines/sinceSeconds + timestamps）")
    public StreamingResponseBody logs(@PathVariable String name,
                                      @RequestParam(required = false) String tenantId,
                                      @RequestParam String clusterId,
                                      @RequestParam String namespace,
                                      @RequestParam(required = false) String container,
                                      @RequestParam(required = false) Integer sinceSeconds,
                                      @RequestParam(required = false) String sinceTime,
                                      @RequestParam(required = false) Integer tailLines) {
        AccessContext ctx = accessResolver.resolveNamespacedAccess(tenantId, clusterId, namespace);

        KubernetesClient client = getClient(ctx, clusterId);
        // 转具体类才能链式调用 usingTimestamps()/sinceTime()/tailingLines()（公开接口类型够不到这些方法）
        BytesLimitTerminateTimeTailPrettyLoggable prettyLoggable = client.pods()
                .inNamespace(namespace)
                .withName(name)
                .inContainer(container)
                .usingTimestamps();

        // 恒带 timestamps：每行加 RFC3339Nano 前缀，前端据此做增量游标、展示时剥掉前缀还原原始日志。
        // sinceTime=增量轮询（取上次之后所有新行，不丢行）；tailLines=初始化回看最近 N 行（首选，安静容器也不空）；
        // sinceSeconds=按时间窗回看（旧方式，保留兼容）；都不给=全量。

        return outputStream -> {

            if (StringUtils.hasText(sinceTime)) {
                prettyLoggable.sinceTime(sinceTime)
                        .withPrettyOutput()
                        .getLogInputStream()
                        .transferTo(outputStream);
            } else if (tailLines != null) {
                prettyLoggable.tailingLines(tailLines)
                        .withPrettyOutput()
                        .getLogInputStream()
                        .transferTo(outputStream);
            } else if (sinceSeconds != null) {
                prettyLoggable.sinceSeconds(sinceSeconds)
                        .withPrettyOutput()
                        .getLogInputStream()
                        .transferTo(outputStream);
            } else {
                prettyLoggable.withPrettyOutput()
                        .getLogInputStream()
                        .transferTo(outputStream);
            }
        };
    }

    /**租户模式→tenant client（最小权限）；admin 模式→admin client */
    private KubernetesClient getClient(AccessContext ctx, String clusterId) {
        return ctx.adminMode()
                ? clientFactory.getAdminClient(clusterId)
                : clientFactory.getTenantClient(clusterId, ctx.tenantId());
    }

}
