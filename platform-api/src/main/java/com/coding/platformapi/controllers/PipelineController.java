package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.PipelineDTO;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.common.models.pipeline.PipelineCanvasRequest;
import com.coding.common.models.system.ResponseData;
import com.coding.data.models.system.PlatformPipelineRun;
import com.coding.platformapi.audit.AuditAction;
import com.coding.platformapi.audit.Auditable;
import com.coding.platformapi.models.PipelineRunContext;
import com.coding.platformapi.security.AuthContext;
import com.coding.data.models.system.TokenUserInfo;
import com.coding.platformapi.services.CredentialService;
import com.coding.platformapi.services.PipelineRunService;
import com.coding.platformapi.services.PipelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 流水线定义（租户域）。本层只做 HTTP 绑定与 {@link ResponseData} 包装；
 * 业务在 {@link PipelineService}（分层约定见 docs/development/backend-layering.md）。
 *
 * <p>权限行见 {@code V2026_10_15_1__pipeline.sql}：list / get / node-types / preview
 * → {@code tenant:pipeline:read}；create / update / delete → {@code tenant:pipeline:manage}。
 * <b>每个端点的路径必须与那批权限行逐字一致</b>，否则启动期交叉校验拒启（fail-closed）。
 *
 * <p>⚠️ <b>node-types 与 preview 是透传</b>（spec §7 的改定块）：渲染与节点元数据都在 k8s-server，
 * 本服务的这两个端点只是把它转发出去。**前端看到的形状不变** —— 路径、入参、出参都一样，
 * 变的只是"谁算的"。
 *
 * <p><b>审计只标写操作</b>：list / get / node-types / preview 是纯读，不标（spec §8）。
 * 触发与取消（{@code /run}、{@code /run/cancel}）也标 —— 它们是「让集群跑代码」的路径，
 * 由运行服务那一批端点承载。
 */
@Tag(name = "流水线", description = "租户域：流水线定义（画布）、运行历史与日志")
@RestController
@RequestMapping("/pipeline")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineService pipelineService;

    /** 只被 /git-connections 用 —— 那条读的是凭据表，不是流水线表。 */
    private final CredentialService credentialService;

    private final PipelineRunService runService;

    private final AuthContext auth;

    @PostMapping("/list")
    @Operation(summary = "流水线列表", description = "命名空间级；租户帽态下必须持有该命名空间的分配行")
    public ResponseData<List<PipelineDTO>> list(@RequestBody PipelineDTO query) {
        return new ResponseData<>(pipelineService.list(query));
    }

    @PostMapping("/get")
    @Operation(summary = "流水线详情（含画布 JSON）")
    public ResponseData<PipelineDTO> get(@RequestBody PipelineDTO request) {
        return new ResponseData<>(pipelineService.get(request.getId()));
    }

    @PostMapping("/node-types")
    @Operation(summary = "内置节点类型与表单 schema",
            description = "**透传 k8s-server** —— 前端据此渲染属性面板，加字段不用改前端")
    public ResponseData<JsonNode> nodeTypes() {
        return new ResponseData<>(pipelineService.nodeTypes());
    }

    @PostMapping("/preview")
    @Operation(summary = "把画布渲染成 Tekton YAML（预览）",
            description = "**透传 k8s-server**。带环 / 未知节点类型会在这里被拒 —— 与保存时同一个入口")
    public ResponseData<String> preview(@RequestBody PipelineCanvasRequest request) {
        return new ResponseData<>(pipelineService.preview(request.canvasJson()));
    }

    /**
     * 可以在流水线的 git / 推送节点里引用的连接（**窄投影**）。
     *
     * <p>⚠️ 不复用 {@code /credential/list}：那个端点要 {@code platform:credential:read}，
     * 租户一进就 403。这里只回 id/name/endpoint —— 与 S3.1 的 {@code /registry/connections} 同一形状。
     *
     * <p>⚠️ 本端点**直接问 {@link CredentialService}**，不经 {@code PipelineService}：
     * 读的是 {@code platform_credential}，为了一个透传让流水线服务依赖凭据模块不划算。
     */
    @PostMapping("/git-connections")
    @Operation(summary = "可在流水线里使用的 git 连接（窄投影：只回 id/name/endpoint）",
            description = "只含被管理员勾了「允许在流水线里使用」且已启用的连接")
    public ResponseData<List<CredentialService.ConnectionView>> gitConnections() {
        return new ResponseData<>(credentialService.pipelineUsableConnections("GIT"));
    }

    @PostMapping("/create")
    @Operation(summary = "新建流水线", description = "保存前先让 k8s-server 渲染一次：带环的画布当场拒掉")
    @Auditable(action = AuditAction.PIPELINE_CREATE, target = "#request.name")
    public ResponseData<PipelineDTO> create(@RequestBody PipelineDTO request) {
        return new ResponseData<>(pipelineService.create(request));
    }

    @PostMapping("/update")
    @Operation(summary = "更新流水线", description = "归属（cluster/namespace）不可改 —— 取库里存的那一份")
    @Auditable(action = AuditAction.PIPELINE_UPDATE, target = "#request.id")
    public ResponseData<PipelineDTO> update(@RequestBody PipelineDTO request) {
        return new ResponseData<>(pipelineService.update(request));
    }

    @PostMapping("/delete")
    @Operation(summary = "删除流水线", description = "硬删除。运行记录与日志**不跟着删** —— 那是历史")
    @Auditable(action = AuditAction.PIPELINE_DELETE, target = "#request.id")
    public ResponseData<Void> delete(@RequestBody PipelineDTO request) {
        pipelineService.delete(request.getId());
        return new ResponseData<>();
    }

    // ---- 运行 ----

    @PostMapping("/run")
    @Operation(summary = "触发一次运行",
            description = "先生成 run 名并落库、再创建集群对象（幂等键从源头就有）。"
                    + "画布引用的每条连接都必须已授权，否则当场拒（**不静默降级成匿名**）")
    @Auditable(action = AuditAction.PIPELINE_RUN, target = "#request.id")
    public ResponseData<String> run(@RequestBody PipelineDTO request) {
        PipelineDTO pipeline = pipelineService.get(request.getId());   // 顺带校验归属
        return new ResponseData<>(runService.run(pipeline, currentUser(),
                new PipelineRunContext(pipeline.getClusterId(), pipeline.getNamespace(), auth.hatTenantId())));
    }

    @PostMapping("/run/cancel")
    @Operation(summary = "取消一次运行",
            description = "**先抓日志、再取消** —— Tekton 取消时会自己删 Pod，顺序反了那次运行的日志就没了")
    @Auditable(action = AuditAction.PIPELINE_CANCEL, target = "#request.name")
    public ResponseData<Void> cancelRun(@RequestBody PipelineRunDTO request) {
        PlatformPipelineRun run = runService.get(request.getName());
        runService.cancel(request.getName(),
                new PipelineRunContext(run.getClusterId(), run.getNamespace(), auth.hatTenantId()));
        return new ResponseData<>();
    }

    @PostMapping("/run/list")
    @Operation(summary = "运行历史", description = "**只读库** —— 集群里的对象归档后已经删了")
    public ResponseData<List<PlatformPipelineRun>> runList(@RequestBody PipelineDTO request) {
        PipelineDTO pipeline = pipelineService.get(request.getId());
        return new ResponseData<>(runService.list(pipeline.getId(),
                new PipelineRunContext(pipeline.getClusterId(), pipeline.getNamespace(), auth.hatTenantId())));
    }

    @PostMapping("/run/get")
    @Operation(summary = "一次运行的详情", description = "含每个节点的状态与耗时；日志另走 /run/logs")
    public ResponseData<PlatformPipelineRun> runGet(@RequestBody PipelineRunDTO request) {
        return new ResponseData<>(runService.get(request.getName()));
    }

    private String currentUser() {
        TokenUserInfo u = auth.current();
        return u == null ? null : u.getUsername();
    }
}
