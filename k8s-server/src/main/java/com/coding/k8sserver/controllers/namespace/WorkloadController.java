package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.WorkloadDTO;
import com.coding.common.models.k8s.dto.WorkloadRevisionDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.components.ResourceAccessResolver.AccessContext;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 命名空间域 - 工作负载统一管理（Deployment/StatefulSet/DaemonSet，kind 在 DTO 内区分）。
 * list 返回三种 kind 合并结果；get/delete/yaml 按名跨 kind 查找（k8s-core 侧实现）。
 *
 * <h2>本类的三个扩展端点（非六端点形态）</h2>
 * 列表页的行内动作，都在 {@link com.coding.k8score.operations.workload.WorkloadOperations} 里实现：
 * <ul>
 *   <li>{@code POST /{name}/image}     改一个容器的镜像（直接发版）</li>
 *   <li>{@code GET  /{name}/revisions} 历史版本列表（Deployment→ReplicaSet；STS/DS→ControllerRevision）</li>
 *   <li>{@code POST /{name}/rollback}  回滚到指定版本（只写工作负载本身）</li>
 * </ul>
 * 三者都沿用本 controller 的 TENANT 边界与 tenant/admin client 选择（租户 SA 的 apps 组权限覆盖
 * deployments/replicasets/statefulsets/daemonsets；<b>ControllerRevision 需要模板里有 controllerrevisions</b>，
 * 见 V2026_10_11_1 迁移）。业务规则（谁能调、op 管理能否改）在 platform-api，本层只做边界 + 透传。
 */
@Tag(name = "资源管理-工作负载", description = "命名空间内工作负载 Deployment/StatefulSet/DaemonSet（双模访问，边界=分配表）")
@RestController
@RequestMapping("/workloads")
public class WorkloadController extends AbstractNamespacedResourceController<WorkloadDTO> {

    public WorkloadController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.WORKLOAD;
    }

    @PostMapping("/{name}/image")
    @Operation(summary = "改一个容器的镜像（直接发版）",
            description = "只改该容器的 image：读回线上对象 → 改 → SSA 写回，未建模字段不丢；触发一次滚动更新")
    public ResponseData<WorkloadDTO> updateImage(@PathVariable String name,
                                                @RequestParam(required = false) String tenantId,
                                                @RequestParam String clusterId,
                                                @RequestBody ImageRequest body) {
        AccessContext ctx = resolve(tenantId, clusterId, body.getNamespace());
        WorkloadDTO item = operationsFactory.getWorkloadOperation(clusterId, ctx.tenantId(), ctx.adminMode())
                .updateImage(body.getNamespace(), name, body.getContainerName(), body.getImage());
        stamp(item, clusterId, body.getNamespace(), ctx.tenantId());
        return new ResponseData<>(item);
    }

    @GetMapping("/{name}/revisions")
    @Operation(summary = "历史版本列表（新→旧）", description = "Deployment 取 ReplicaSet 的 revision 注解；STS/DS 取 ControllerRevision")
    public ResponseData<List<WorkloadRevisionDTO>> revisions(@PathVariable String name,
                                                             @RequestParam(required = false) String tenantId,
                                                             @RequestParam String clusterId,
                                                             @RequestParam String namespace) {
        AccessContext ctx = resolve(tenantId, clusterId, namespace);
        return new ResponseData<>(operationsFactory.getWorkloadOperation(clusterId, ctx.tenantId(), ctx.adminMode())
                .revisions(namespace, name));
    }

    @PostMapping("/{name}/rollback")
    @Operation(summary = "回滚到指定版本", description = "取该版本的 pod 模板覆盖到工作负载本身（与 kubectl rollout undo 同语义），触发滚动更新")
    public ResponseData<WorkloadDTO> rollback(@PathVariable String name,
                                              @RequestParam(required = false) String tenantId,
                                              @RequestParam String clusterId,
                                              @RequestBody RollbackRequest body) {
        AccessContext ctx = resolve(tenantId, clusterId, body.getNamespace());
        WorkloadDTO item = operationsFactory.getWorkloadOperation(clusterId, ctx.tenantId(), ctx.adminMode())
                .rollback(body.getNamespace(), name, body.getRevision());
        stamp(item, clusterId, body.getNamespace(), ctx.tenantId());
        return new ResponseData<>(item);
    }

    /** 改镜像请求体（局部 DTO，不入库） */
    public static class ImageRequest {
        private String namespace;
        private String containerName;
        private String image;

        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }
        public String getContainerName() { return containerName; }
        public void setContainerName(String containerName) { this.containerName = containerName; }
        public String getImage() { return image; }
        public void setImage(String image) { this.image = image; }
    }

    /** 回滚请求体（局部 DTO，不入库） */
    public static class RollbackRequest {
        private String namespace;
        private Long revision;

        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }
        public Long getRevision() { return revision; }
        public void setRevision(Long revision) { this.revision = revision; }
    }
}
