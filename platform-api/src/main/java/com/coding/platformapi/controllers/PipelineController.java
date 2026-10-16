package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.PipelineDTO;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.common.models.pipeline.PipelineCanvasRequest;
import com.coding.common.models.pipeline.PipelineStepRefDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.data.models.system.PlatformPipelineRun;
import com.coding.platformapi.audit.AuditAction;
import com.coding.platformapi.audit.Auditable;
import com.coding.platformapi.models.PipelineRunContext;
import com.coding.platformapi.security.AuthContext;
import com.coding.data.models.system.TokenUserInfo;
import com.coding.platformapi.services.CredentialService;
import com.coding.platformapi.services.PipelineCapabilityService;
import com.coding.platformapi.services.PipelineRunService;
import com.coding.platformapi.services.PipelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
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
public class PipelineController {

    private final PipelineService pipelineService;

    /** 只被 /git-connections 用 —— 那条读的是凭据表，不是流水线表。 */
    private final CredentialService credentialService;

    private final PipelineRunService runService;

    private final AuthContext auth;

    /** 单步日志上限（与归档那一侧同一份配置）—— 在线看日志也照它截断，免得一屏拉出几百 MB */
    private final int maxLogBytes;

    public PipelineController(PipelineService pipelineService,
                              CredentialService credentialService,
                              PipelineRunService runService,
                              AuthContext auth,
                              @Value("${platform.pipeline.log.max-bytes:1048576}") int maxLogBytes) {
        this.pipelineService = pipelineService;
        this.credentialService = credentialService;
        this.runService = runService;
        this.auth = auth;
        this.maxLogBytes = maxLogBytes;
    }

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
     * 这个集群能不能跑 CI。**页面级门控的唯一判据**（spec §9）。
     *
     * <p>不可用时回一句原因，前端在页面顶部显示并把「运行」按下去 ——
     * 而不是让用户配完一条流水线、点运行，才收一个 500。
     */
    @PostMapping("/capability")
    @Operation(summary = "这个集群能不能跑 CI（外加构建缓存的状态）",
            description = "Tekton 与可动态供应的 StorageClass 缺一不可；不可用时带原因。"
                    + "带了命名空间时顺带回构建缓存的状态 —— 它是命名空间级的资源状态，"
                    + "边界由 k8s-server 的分配表校验兜住；问不了就不回那一段")
    public ResponseData<PipelineCapabilityService.Capability> capability(@RequestBody PipelineDTO request) {
        return new ResponseData<>(pipelineService.capability(request.getClusterId(),
                auth.effectiveTenantId(request.getTenantId()), request.getNamespace()));
    }

    /**
     * 清掉这个命名空间的**构建缓存**（删那块长期卷，下次运行自动重建）。
     *
     * <p>放在流水线域：缓存卷是构建用的东西，与流水线定义/运行同属一个功能面。
     * 权限沿用 {@code tenant:pipeline:manage}（能改流水线的人才该能清缓存）。
     */
    @PostMapping("/cache/clear")
    @Operation(summary = "清掉这个命名空间的构建缓存（删卷，下次运行自动重建）",
            description = "删的是那块卷而不是里面的文件；有运行正在用它时删不掉（会让它抛错，不是假装成功）")
    @Auditable(action = AuditAction.PIPELINE_CACHE_CLEAR, target = "#request.namespace")
    public ResponseData<Void> clearCache(@RequestBody PipelineDTO request) {
        pipelineService.clearCache(request.getClusterId(),
                auth.effectiveTenantId(request.getTenantId()), request.getNamespace());
        return new ResponseData<>();
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

    /**
     * 可以在流水线的**镜像节点**里引用的连接（同样的窄投影，只是排除 git）。
     *
     * <p>⚠️ 少了它，租户只能手打连接 id —— 而他们看不到凭据页（那个要 platform:credential:read），
     * 于是「构建并推送镜像」这个节点在界面上根本配不出来。
     */
    @PostMapping("/registry-connections")
    @Operation(summary = "可在流水线里使用的镜像仓库连接（窄投影：只回 id/name/endpoint）")
    public ResponseData<List<CredentialService.ConnectionView>> registryConnections() {
        return new ResponseData<>(credentialService.pipelineUsableRegistryConnections());
    }

    @PostMapping("/run")
    @Operation(summary = "触发一次运行",
            description = "先生成 run 名并落库、再创建集群对象（幂等键从源头就有）。"
                    + "画布引用的每条连接都必须已授权，否则当场拒（**不静默降级成匿名**）")
    @Auditable(action = AuditAction.PIPELINE_RUN, target = "#request.id")
    public ResponseData<String> run(@RequestBody PipelineDTO request) {
        PipelineDTO pipeline = pipelineService.get(request.getId());   // 顺带校验归属
        return new ResponseData<>(runService.run(pipeline, currentUser(),
                new PipelineRunContext(pipeline.getClusterId(), pipeline.getNamespace(),
                        auth.effectiveTenantId(request.getTenantId()))));
    }

    @PostMapping("/run/cancel")
    @Operation(summary = "取消一次运行",
            description = "**先抓日志、再取消** —— Tekton 取消时会自己删 Pod，顺序反了那次运行的日志就没了")
    @Auditable(action = AuditAction.PIPELINE_CANCEL, target = "#request.name")
    public ResponseData<Void> cancelRun(@RequestBody PipelineRunDTO request) {
        PlatformPipelineRun run = runService.get(request.getName());
        runService.cancel(request.getName(), contextOf(run, request.getTenantId()));
        return new ResponseData<>();
    }

    /**
     * 删掉一条运行记录。**删的只是库里的行** —— 归档时集群里那个对象与 Pod 就已经删了，
     * 所以没有任何集群动作；但也正因为如此，库是唯一留存（日志、YAML、节点状态都在上面）。
     * 还在跑的那条拒删（先取消）—— 见 {@code PipelineRunService#delete}。
     */
    @PostMapping("/run/delete")
    @Operation(summary = "删掉一条运行记录",
            description = "只删库里的行（归档时集群对象已删）—— **不可恢复**。"
                    + "还在跑的那条拒删：先取消，等它结束")
    @Auditable(action = AuditAction.PIPELINE_RUN_DELETE, target = "#request.name")
    public ResponseData<Void> deleteRun(@RequestBody PipelineRunDTO request) {
        runService.delete(request.getName());
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

    /**
     * 每个节点**现在**的落点 —— 运行详情的 DAG 着色用。
     *
     * <p>⚠️ 还在跑才有：归档之后 TaskRun 与 Pod 都删了，那时的状态在 `run/get` 的
     * `stepsJson` 里（归档那一刻抓的快照）。前端按 run 的状态选一个来源。
     */
    @PostMapping("/run/steps")
    @Operation(summary = "一次运行里每个节点现在的落点（Pod 与容器）",
            description = "还在跑时现抓 k8s-server；已归档返回空 —— 状态看 run/get 的 stepsJson")
    public ResponseData<List<PipelineStepRefDTO>> runSteps(@RequestBody PipelineRunDTO request) {
        PlatformPipelineRun run = runService.get(request.getName());
        return new ResponseData<>(runService.steps(request.getName(), contextOf(run, request.getTenantId())));
    }

    /**
     * 一次运行**渲染出来的 YAML** —— 排查利器（spec §6）。
     *
     * <p>还在跑 → 集群里那个真实对象；已归档 → 归档那一刻抓下来的那一份。
     */
    @PostMapping("/run/yaml")
    @Operation(summary = "一次运行渲染出来的 YAML",
            description = "还在跑抓集群对象；归档后读归档时抓下来的那一份。老运行没留下则回空")
    public ResponseData<String> runYaml(@RequestBody PipelineRunDTO request) {
        PlatformPipelineRun run = runService.get(request.getName());
        return new ResponseData<>(runService.runYaml(request.getName(), contextOf(run, request.getTenantId())));
    }

    /**
     * 看运行日志。**`tenant:pipeline:logs` 的唯一落点** —— 单独成码是因为
     * 日志是容器输出，可能含敏感内容（spec §5.1）。
     *
     * <p>两种数据源，分界线是「归档了没有」：已归档读库（集群对象与 Pod 早删了），
     * 还在跑则现抓（走既有的 Pod 日志通道，不落库也不落缓存）。
     */
    @PostMapping("/run/logs")
    @Operation(summary = "看运行日志",
            description = "已归档读库、还在跑现抓。nodeName 为空则把所有节点拼起来"
                    + "（每段的段头用**节点显示名**，没起名则用 id；⚠️ 但 nodeName 入参给的是 **id**）")
    public ResponseData<String> runLogs(@RequestBody RunLogsRequest request) {
        PlatformPipelineRun run = runService.get(request.runName());
        return new ResponseData<>(runService.logs(request.runName(), request.nodeName(),
                contextOf(run, request.tenantId()), maxLogBytes));
    }

    /**
     * 日志端点的入参：只看某个节点时给 nodeName。
     *
     * @param tenantId 生效租户 —— **平台管理员（代管轨）必须显式给**，否则 k8s-server 会以
     *                 「管理员代操作需显式指定 tenantId」拒掉（租户用户不用给，token 说了算）
     */
    public record RunLogsRequest(String runName, String nodeName, String tenantId) {
    }

    /**
     * 库里的那行 → 调 k8s-server 用的上下文。
     *
     * <p>**cluster/namespace 取库里存的**（run 名是调用方给的，不带边界）；租户取
     * {@link AuthContext#effectiveTenantId}：自管轨以 token 为准，代管轨（平台管理员）用请求里
     * 显式指定的那个 —— 否则 k8s-server 会以「管理员代操作需显式指定 tenantId」拒掉
     * （2026-10-15 用户在真环境撞到过这条）。
     */
    private PipelineRunContext contextOf(PlatformPipelineRun run, String requestedTenantId) {
        return new PipelineRunContext(run.getClusterId(), run.getNamespace(),
                auth.effectiveTenantId(requestedTenantId));
    }

    private String currentUser() {
        TokenUserInfo u = auth.current();
        return u == null ? null : u.getUsername();
    }
}
