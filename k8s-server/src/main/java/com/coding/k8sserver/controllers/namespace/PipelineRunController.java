package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.components.ResourceAccessResolver.AccessContext;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 —— Tekton {@code PipelineRun}（{@code tekton.dev/v1}，租户域，边界=分配表三元组）。
 *
 * <p>6 个标准端点由 {@link AbstractNamespacedResourceController} 提供；本类只加一个
 * <b>取消</b>（patch {@code spec.status}，不是删对象 —— 删了历史就没了）。
 * update 在 {@code PipelineRunOperations} 里被明确拒掉：运行不可编辑。
 *
 * <p>⚠️ 本资源的每一个端点都**只看得见平台自己创建的** run —— 过滤在
 * {@code PipelineRunOperations} 里（标签选择器 + {@code assertManaged}），本层只做边界与透传。
 */
@Tag(name = "资源管理-流水线运行", description = "命名空间内 PipelineRun（租户域，边界=分配表）")
@RestController
@RequestMapping("/pipelineruns")
public class PipelineRunController extends AbstractNamespacedResourceController<PipelineRunDTO> {

    public PipelineRunController(KubernetesOperationsFactory operationsFactory,
                                 ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.PIPELINE_RUN;
    }

    /**
     * 取消一次运行。
     *
     * <p>⚠️ **平台侧的编排（先抓日志、再取消）在 platform-api 的 {@code PipelineRunService} 里** ——
     * 本端点只做「取消」这一个原子动作。顺序是那条链的全部依据，不该散在两层。
     */
    @PostMapping("/{name}/cancel")
    @Operation(summary = "取消一次流水线运行（patch spec.status，保留对象与历史）")
    public ResponseData<Void> cancel(@PathVariable String name,
                                     @RequestParam(required = false) String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        AccessContext ctx = resolve(tenantId, clusterId, namespace);
        ops(ctx, clusterId).cancel(namespace, name);
        return new ResponseData<>();
    }
}
