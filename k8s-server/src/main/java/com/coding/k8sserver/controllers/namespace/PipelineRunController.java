package com.coding.k8sserver.controllers.namespace;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.common.models.pipeline.PipelineCanvasRequest;
import com.coding.common.models.pipeline.PipelineRunSnapshotDTO;
import com.coding.common.models.pipeline.PipelineStepRefDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.pipelinerun.PipelineRunOperations;
import com.coding.k8score.pipeline.PipelineCanvasOperations;
import com.coding.k8score.pipeline.PipelineNodeTypeView;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.components.ResourceAccessResolver.AccessContext;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import com.coding.k8sserver.pipeline.PipelineRunFinisher;
import com.coding.k8sserver.pipeline.PipelineRunWatchManager;
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

    /** 画布相关的两个纯函数动作（节点元数据 / 渲染）。**没有集群上下文**，所以不走 ops 那条路。 */
    private final PipelineCanvasOperations canvasOps;

    /** 按需 watch 台账 —— 创建成功后要在钩子里开一条。 */
    private final PipelineRunWatchManager watch;

    /** 终结/取消时「抓快照 → 落库 → 删对象」的唯一编排点。 */
    private final PipelineRunFinisher finisher;

    @org.springframework.beans.factory.annotation.Value("${platform.pipeline.log.max-bytes:1048576}")
    private int maxLogBytes;

    public PipelineRunController(KubernetesOperationsFactory operationsFactory,
                                 ResourceAccessResolver accessResolver,
                                 PipelineCanvasOperations canvasOps,
                                 PipelineRunWatchManager watch,
                                 PipelineRunFinisher finisher) {
        super(operationsFactory, accessResolver);
        this.canvasOps = canvasOps;
        this.watch = watch;
        this.finisher = finisher;
    }

    /**
     * 创建成功之后**顺手开一条按需 watch**（spec §4.1 指定的时机）。
     *
     * <p>那一刻起集群里有一个在跑的对象，而这个对象结束时必须被归档并删掉；
     * 没人盯着的话它就会一直留着（连同跑完的 Completed Pod）。
     */
    @Override
    protected void afterCreate(PipelineRunDTO item, String clusterId) {
        if (item == null || item.getName() == null) {
            return;
        }
        watch.watch(clusterId, item.getNamespace(), item.getName(), java.time.Instant.now());
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.PIPELINE_RUN;
    }

    /**
     * 内置节点的元数据（表单 schema + 语言预设）。
     *
     * <p>⚠️ 端点在**这里**而不是 platform-api：节点 schema 的真相源是 {@code PipelineNodeRegistry}，
     * 而它住在 k8s-core —— platform-api 不依赖 k8s-core（spec §7 的改定块）。
     * 它不碰集群，只是把编译期就在内存里的东西原样发出去。
     */
    @PostMapping("/node-types")
    @Operation(summary = "内置流水线节点的元数据（表单 schema + 语言预设）")
    public ResponseData<List<PipelineNodeTypeView>> nodeTypes() {
        return new ResponseData<>(canvasOps.nodeTypes());
    }

    /**
     * 画布 → Tekton YAML。两个用途：前端预览，以及 platform-api 保存流水线时的**画布校验**
     * （带环、未知节点类型都会在这里抛业务异常 —— 规则只有一份，不重复实现）。
     */
    @PostMapping("/render")
    @Operation(summary = "把画布渲染成 Tekton YAML（预览；同时是保存时的画布校验入口）",
            description = "可以带上本次可用的连接 —— **带镜像节点的画布离开连接就渲染不出来**"
                    + "（目标地址 = 连接 endpoint + 仓库路径 + tag）。连接以 endpoint 参与，不含凭据内容")
    public ResponseData<String> render(@RequestBody PipelineCanvasRequest request) {
        return new ResponseData<>(
                canvasOps.renderYaml(request.canvasJson(), PipelineCanvasOperations.byId(request.connections())));
    }

    /**
     * 一次运行里每个节点在集群上的落点（Pod + 容器）。
     *
     * <p>⚠️ 端点在**这里**而不是 platform-api：「run → TaskRun → Pod → 容器」是 Tekton 的结构知识，
     * 而 platform-api 不依赖 k8s-core（spec §7 的改定块）。归档与实时看日志都拿它，
     * 然后走**既有的** Pod 日志通道 —— 不新写日志通道（spec §4.4）。
     *
     * <p>⚠️ 用 `instanceof` 而不是裸转型：`NamespacedOperations` 上没有这个方法
     * （它是 Tekton 专有的，加进通用接口更糟），所以这里留一道**响亮**的兜底。
     */
    @GetMapping("/{name}/steps")
    @Operation(summary = "一次运行的每个节点在集群上的落点（Pod 与容器）")
    public ResponseData<List<PipelineStepRefDTO>> steps(@PathVariable String name,
                                                        @RequestParam(required = false) String tenantId,
                                                        @RequestParam String clusterId,
                                                        @RequestParam String namespace) {
        AccessContext ctx = resolve(tenantId, clusterId, namespace);
        NamespacedOperations<PipelineRunDTO> ops = ops(ctx, clusterId);
        if (!(ops instanceof PipelineRunOperations runOps)) {
            throw new CloudPlatformException(EnumResponseType.OPERATION_NOT_SUPPORTED,
                    "本资源不支持按节点列举：" + ops.getClass().getSimpleName());
        }
        return new ResponseData<>(runOps.steps(namespace, name));
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
        // ⚠️ 编排（**先抓日志、再取消**）在 k8s-server 侧 —— Tekton 取消时会自己删 Pod，
        //    顺序反了那次运行的日志就没了。本端点只负责边界解析与转发。
        finisher.cancel(clusterId, namespace, name);
        return new ResponseData<>();
    }
}
