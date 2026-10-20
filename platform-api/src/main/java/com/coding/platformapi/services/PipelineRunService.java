package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import org.springframework.transaction.annotation.Transactional;
import com.coding.common.models.k8s.dto.PipelineDTO;
import com.coding.common.models.pipeline.PipelineRunLogDTO;
import com.coding.common.models.pipeline.PipelineRetryPlanDTO;
import com.coding.common.models.pipeline.PipelineStepRefDTO;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.system.PlatformPipelineRunLogMapper;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.data.models.system.PlatformPipelineRun;
import com.coding.data.models.system.PlatformPipelineRunLog;
import com.coding.platformapi.k8s.K8sPipelineClient;
import com.coding.platformapi.models.PipelineRunContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 触发、取消与运行历史。
 *
 * <h2>⚠️ 顺序是这两条链的全部价值</h2>
 * <ol>
 *   <li><b>触发：先生成 run 名 → 先落库 → 再创建</b>（spec §4.5①）。
 *       反过来做的话，创建成功而落库失败的那个窗口里，**那个运行就没人知道了** ——
 *       集群里有对象、库里没记录，归档的回调带着一个平台不认识的 run 名回来。
 *       run 名由平台先生成，幂等键就从源头有了。</li>
 *   <li><b>取消：先抓日志 → 再取消</b>（spec §4.2）。Tekton 取消时会自己把 Pod 删掉，
 *       那之后日志就没了 —— 这条顺序是「取消之后日志仍然看得到」的全部依据。</li>
 * </ol>
 *
 * <h2>⚠️ 本类不渲染</h2>
 * 它把**画布 JSON** 原样发给 k8s-server，渲染在那边（spec §7 的改定块）。
 * 它唯一要读懂画布的地方是**连接引用**：那些 id 必须逐条问过「被授权了吗」（spec §5.3）。
 */
@Slf4j
@Service
public class PipelineRunService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * k8s 注入的日志时间戳前缀：**行首**的 RFC3339Nano（UTC {@code Z}）+ 空白。
     *
     * <p>⚠️ 只认 {@code Z}：认 {@code +08:00} 的话，应用自己打在行首的时间戳会被当成 k8s 的剥掉。
     * 与前端 {@code PodLogDialog} 那条正则同一套口径（两边都改才不算走偏）。
     */
    private static final Pattern TS_PREFIX =
            Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z)[ \t]+");

    /** 「整串就是个时间戳」的拆解式：{@code 前面 + .小数 + 时区} —— 给 {@link #normTs} 补零用。 */
    private static final Pattern TS_PREFIX_FULL =
            Pattern.compile("^(.*?)(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?$");

    private final PlatformPipelineRunMapper runMapper;
    private final PlatformPipelineRunLogMapper logMapper;
    private final K8sPipelineClient k8s;
    private final CredentialService credentials;
    private final NamespaceAccessGuard access;

    public PipelineRunService(PlatformPipelineRunMapper runMapper,
                              PlatformPipelineRunLogMapper logMapper,
                              K8sPipelineClient k8s,
                              CredentialService credentials,
                              NamespaceAccessGuard access) {
        this.runMapper = runMapper;
        this.logMapper = logMapper;
        this.k8s = k8s;
        this.credentials = credentials;
        this.access = access;
    }

    /** 兼容旧调用点：不屏蔽任何节点。 */
    public String run(PipelineDTO pipeline, String user, PipelineRunContext ctx) {
        return run(pipeline, user, ctx, List.of());
    }

    /**
     * 触发一次运行。
     *
     * @param pipeline       已经载入并校验过归属的流水线定义
     * @param user           触发人（审计与运行历史都用它）
     * @param maskedNodeIds  本次要屏蔽的节点（**连同全部下游**）；空 = 全跑
     * @return 集群里的 PipelineRun 名（= 归档幂等键）
     */
    public String run(PipelineDTO pipeline, String user, PipelineRunContext ctx,
                      List<String> maskedNodeIds) {
        String canvas = pipeline.getCanvasJson();
        Set<String> masked = PipelineCanvasMask.maskedClosure(canvas, maskedNodeIds);
        // ⚠️ 落库的是**完整画布**（详情页要画全图）；发给 k8s-server 的是**裁剪后的子画布**。
        //    这两者从本批起有意不同，别以为写错了。
        String toRun = PipelineCanvasMask.trim(canvas, allBut(canvas, masked), null);

        // ① 解析连接：**逐条校授权 + 解密**（绕过前端直接打接口也一样）。
        //    只解析**本次真要跑**的子画布 —— 被屏蔽节点的连接不要求授权（它这次不跑）。
        List<com.coding.common.models.pipeline.PipelineConnectionRef> connections = resolveConnections(toRun);

        // ② 先生成名字：**幂等键从源头就有**
        String runName = generateRunName(pipeline.getName());

        // ③ 先落库。画布一并留在这行上：集群对象归档后就被删，而流水线**之后还会被改** ——
        //    没有这一份，历史运行详情画的是今天的流水线
        PlatformPipelineRun row = new PlatformPipelineRun();
        row.setId(ULIDGenerator.generateULID());
        row.setPipelineId(pipeline.getId());
        row.setClusterId(ctx.clusterId());
        row.setNamespace(ctx.namespace());
        row.setCanvasJson(canvas);
        row.setRunName(runName);
        row.setWorkspacePvc(workspacePvcName(runName));
        row.setMaskedNodesJson(masked.isEmpty() ? null : jsonArray(masked));
        row.setAttemptNo(1);
        row.setStatus("RUNNING");
        row.setTriggeredBy(user);
        runMapper.insert(row);

        // ④ 再创建。失败就把刚写的那行撤掉 —— 库里不能留一条 RUNNING 的孤儿
        try {
            k8s.createRun(ctx.clusterId(), ctx.tenantId(), ctx.namespace(),
                    runName, toRun, connections, workspacePvcName(runName), false);
        } catch (RuntimeException e) {
            log.warn("创建 PipelineRun 失败，回滚刚落的库：{}", runName, e);
            runMapper.deleteById(row.getId());
            throw e;
        }
        return runName;
    }

    /** 平台给这条运行（链）定的工作区 PVC 名。 */
    public static String workspacePvcName(String runName) {
        return "platform-pipeline-ws-" + runName;
    }

    /** 画布全部节点去掉屏蔽闭包 —— 本次要跑的节点集合。 */
    private static Set<String> allBut(String canvasJson, Set<String> masked) {
        Set<String> keep = new LinkedHashSet<>(PipelineCanvasMask.idsOf(PipelineCanvasMask.parse(canvasJson)));
        keep.removeAll(masked);
        return keep;
    }

    /** 节点 id 列表 → JSON 数组（库里的两列都是这个形状）。 */
    static String jsonArray(java.util.Collection<String> ids) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String id : ids) {
            if (!first) {
                sb.append(',');
            }
            sb.append('"').append(id).append('"');
            first = false;
        }
        return sb.append(']').toString();
    }

    /**
     * 重试计划（只读）。**前端据此决定按钮与对话框内容** —— 断点规则不在这里重算。
     */
    public PipelineRetryPlanDTO retryPlan(String runName) {
        PlatformPipelineRun row = requireRun(runName);
        String blockedReason = retryBlockedReason(row);
        if (blockedReason != null) {
            return new PipelineRetryPlanDTO(false, blockedReason, List.of(), List.of(), List.of(), Map.of());
        }
        Map<String, String> status = effectiveStatus(row);
        Set<String> masked = parseIds(row.getMaskedNodesJson());
        PipelineCanvasMask.Plan plan = PipelineCanvasMask.plan(row.getCanvasJson(), status, masked);

        Set<String> reused = new LinkedHashSet<>();
        status.forEach((id, st) -> {
            if ("SUCCEEDED".equals(st) && !plan.runNodeIds().contains(id) && !masked.contains(id)) {
                reused.add(id);
            }
        });
        return new PipelineRetryPlanDTO(true, null,
                List.copyOf(plan.retryable()), List.copyOf(plan.runNodeIds()),
                List.copyOf(reused), plan.blocked());
    }

    /**
     * 从断点继续：新建一条只跑「所有断点子树」的运行，沿用父运行的工作区。
     *
     * <p>⚠️ 范围**由服务端算**；客户端只给「从哪个断点开始」，而且它必须在可点集合里。
     */
    public String retry(String runName, String nodeId, String user, PipelineRunContext ctx) {
        PlatformPipelineRun parent = requireRun(runName);
        String blockedReason = retryBlockedReason(parent);
        if (blockedReason != null) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID, blockedReason);
        }
        Map<String, String> status = effectiveStatus(parent);
        Set<String> masked = parseIds(parent.getMaskedNodesJson());
        PipelineCanvasMask.Plan plan = PipelineCanvasMask.plan(parent.getCanvasJson(), status, masked);
        if (!plan.retryable().contains(nodeId)) {
            String why = plan.blocked().get(nodeId);
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                    why == null ? "这个节点不能作为重试起点：" + nodeId : why);
        }

        boolean finallyTarget = PipelineCanvasMask.isFinally(parent.getCanvasJson(), nodeId);
        Set<String> toRun = finallyTarget ? Set.of(nodeId) : plan.runNodeIds();
        String trimmed = PipelineCanvasMask.trim(parent.getCanvasJson(), toRun,
                finallyTarget ? nodeId : null);
        List<com.coding.common.models.pipeline.PipelineConnectionRef> connections = resolveConnections(trimmed);

        Set<String> reused = new LinkedHashSet<>();
        status.forEach((id, st) -> {
            if ("SUCCEEDED".equals(st) && !toRun.contains(id) && !masked.contains(id)) {
                reused.add(id);
            }
        });

        String newRunName = generateRunName(baseNameOfRun(parent.getRunName()));
        PlatformPipelineRun row = new PlatformPipelineRun();
        row.setId(ULIDGenerator.generateULID());
        row.setPipelineId(parent.getPipelineId());
        row.setClusterId(parent.getClusterId());
        row.setNamespace(parent.getNamespace());
        row.setCanvasJson(parent.getCanvasJson());
        row.setRunName(newRunName);
        row.setParentRunId(parent.getId());
        row.setAttemptNo((parent.getAttemptNo() == null ? 1 : parent.getAttemptNo()) + 1);
        row.setRetryFromNode(nodeId);
        row.setMaskedNodesJson(masked.isEmpty() ? null : jsonArray(masked));
        row.setReusedNodesJson(reused.isEmpty() ? null : jsonArray(reused));
        row.setWorkspacePvc(parent.getWorkspacePvc());
        row.setStatus("RUNNING");
        row.setTriggeredBy(user);
        runMapper.insert(row);

        try {
            k8s.createRun(ctx.clusterId(), ctx.tenantId(), ctx.namespace(),
                    newRunName, trimmed, connections, parent.getWorkspacePvc(), true);
        } catch (RuntimeException e) {
            log.warn("创建重试运行失败，回滚刚落的库：{}", newRunName, e);
            runMapper.deleteById(row.getId());
            throw e;
        }
        return newRunName;
    }

    /** 整条运行能不能重试；能 → null，不能 → 原因。 */
    private static String retryBlockedReason(PlatformPipelineRun row) {
        if ("RUNNING".equals(row.getStatus())) {
            return "这条运行还在跑，等它结束之后再重试";
        }
        if ("SUCCEEDED".equals(row.getStatus())) {
            return "这次运行成功了 —— 工作区已释放，不能再从中间重试；要重跑就整条重来";
        }
        if (row.getWorkspacePvc() == null || row.getWorkspacePvc().isBlank()) {
            return "这条运行产生于工作区保留功能上线之前，没有可续跑的工作区；要重跑就整条重来";
        }
        return null;
    }

    /** 有效状态 = 本运行自己的结果 ∪ 沿用集合（沿用的一定是 SUCCEEDED）。 */
    static Map<String, String> effectiveStatus(PlatformPipelineRun row) {
        Map<String, String> out = new LinkedHashMap<>();
        String steps = row.getStepsJson();
        if (steps != null && !steps.isBlank()) {
            for (String part : steps.split(",")) {
                int at = part.indexOf('=');
                if (at <= 0) {
                    continue;
                }
                String id = nodeIdOf(part.substring(0, at).trim(), row.getRunName());
                String st = part.substring(at + 1).trim();
                if (id != null && !id.isBlank() && !st.isBlank()) {
                    out.put(id, st);
                }
            }
        }
        for (String id : parseIds(row.getReusedNodesJson())) {
            out.put(id, "SUCCEEDED");
        }
        return out;
    }

    /** 运行名的「流水线名部分」：`<name>-yyyyMMddHHmmss-xxxx` → `<name>`。 */
    static String baseNameOfRun(String runName) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.*)-\\d{14}-[a-z0-9]{4}$").matcher(runName == null ? "" : runName);
        return m.matches() ? m.group(1) : "pipeline";
    }

    /** `["a","b"]` → {"a","b"}；空/坏内容 → 空集合（库里的旧行可能没有这一列）。 */
    static Set<String> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            Set<String> out = new LinkedHashSet<>();
            for (JsonNode n : JSON.readTree(json)) {
                String id = n.asString("");
                if (id != null && !id.isBlank()) {
                    out.add(id);
                }
            }
            return out;
        } catch (Exception e) {
            return Set.of();
        }
    }

    /** 本命名空间里保留中的工作区（给列表页那一行）。 */
    public com.coding.common.models.pipeline.PipelineRetryWorkspacesDTO retryWorkspaces(
            String clusterId, String namespace, String tenantId) {
        access.assertAccessible(clusterId, namespace);
        List<com.coding.common.models.pipeline.PipelineRetryWorkspaceDTO> free =
                orphanWorkspaces(clusterId, namespace, tenantId);
        long total = free.stream()
                .mapToLong(com.coding.common.models.pipeline.PipelineRetryWorkspaceDTO::sizeBytes).sum();
        return new com.coding.common.models.pipeline.PipelineRetryWorkspacesDTO(free.size(), total, free);
    }

    /** 清掉全部待重试工作区。⚠️ 清掉之后那些运行就**不能**再从断点继续了。 */
    public void clearRetryWorkspaces(String clusterId, String namespace, String tenantId) {
        access.assertAccessible(clusterId, namespace);
        for (com.coding.common.models.pipeline.PipelineRetryWorkspaceDTO item
                : orphanWorkspaces(clusterId, namespace, tenantId)) {
            k8s.deleteRetryWorkspace(clusterId, tenantId, namespace, item.name());
        }
    }

    /**
     * 命名空间里**没有运行正在用**的工作区卷。
     *
     * <p>⚠️ 工作区在运行**创建时**就存在并带平台标签 —— 不排除在途运行的话，
     * 列表会显示「待重试工作区：1 块」，而「全部清理」会把正在跑的那条运行的工作区删掉。
     * 唯一可靠的判据在库里（PVC 的 phase 看不出来）。
     */
    private List<com.coding.common.models.pipeline.PipelineRetryWorkspaceDTO> orphanWorkspaces(
            String clusterId, String namespace, String tenantId) {
        Set<String> inUse = new LinkedHashSet<>(runMapper.selectRunningWorkspacePvcs(clusterId, namespace));
        return k8s.listRetryWorkspaces(clusterId, tenantId, namespace).stream()
                .filter(item -> !inUse.contains(item.name()))
                .toList();
    }

    /**
     * 取消一次运行。**先抓日志、再取消**（spec §4.2）—— 顺序反了那次运行的日志就没了
     * （Tekton 取消时会自己把 Pod 删掉）。
     *
     * <p>⚠️ 这条路径**有用户身份**（发起取消的那个人），所以抓快照走的是带 token 的
     * `/pipelineruns/{name}/snapshot`；而 watch 驱动的归档是 k8s-server 侧自己抓的
     * （它开 watch，收到终结事件时快照就在手上），平台不需要为后台调用另造身份。
     */
    public void cancel(String runName, PipelineRunContext ctx) {
        requireRun(runName);
        // ⚠️ 编排（**先抓日志、再取消**）在 k8s-server 侧：Tekton 取消时会自己删 Pod，
        //    顺序反了那次运行的日志就没了。本方法只做归属校验与转发。
        k8s.cancelRun(ctx.clusterId(), ctx.tenantId(), ctx.namespace(), runName);
    }

    /** 运行历史。**只读库** —— 集群里的对象归档后已经删了（spec §4.3）。 */
    public List<PlatformPipelineRun> list(String pipelineId, PipelineRunContext ctx) {
        return runMapper.listByPipeline(pipelineId, ctx.clusterId(), ctx.namespace());
    }

    /**
     * 删掉一条运行记录（用户 2026-10-16 要的「运行历史删除」）。
     *
     * <p>⚠️ 删的**只是库里的行**：归档那一刻集群里的对象与 Pod 就已经删了，所以这里没有任何
     * 集群动作 —— 但也正因为如此，**库是唯一留存**（日志、YAML、节点状态都在上面），删了不可恢复。
     * 前端的确认框必须把这句话说清楚。
     *
     * <p>⚠️ <b>还在跑的那条拒删</b>：它的归档回写还要落在这行上（删了就是「归档落在不存在的行」，
     * 静默丢掉一次运行的结论），而集群里那次运行还在跑 —— 用户想停的是运行本身，不是它的记录。
     * 先取消，等它终结即可。
     *
     * <p>⚠️ **先删日志行、再删运行行**（同 {@code PipelineRetentionService}）：本仓不加外键、
     * 级联靠代码，顺序反了会留下一堆没人认领的日志行。
     */
    @Transactional
    public void delete(String runName) {
        PlatformPipelineRun row = requireRun(runName);       // 存在性 + 命名空间边界都在这条里
        if ("RUNNING".equals(row.getStatus())) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                    "这条运行还在跑，先取消它，等结束之后再删：" + runName);
        }
        logMapper.deleteByRunId(row.getId());
        runMapper.deleteById(row.getId());
    }

    /**
     * 看一次运行的日志。**两种数据源，分界线是「归档了没有」**（spec §4.3）。
     *
     * <ul>
     *   <li>已归档 → 读库。集群里的对象与 Pod 早就删了，库是唯一存续形式。</li>
     *   <li>还在跑 → 现抓。走既有的 Pod 日志通道（spec §4.4：不用新写日志通道），
     *       **不落库也不落缓存** —— 落库只发生在归档那一刻。</li>
     * </ul>
     *
     * <p>⭐ 2026-10-16 起「还在跑」那一路支持**增量**：{@code sinceTime} 是上一批的游标，
     * 带上它就只回新行 —— 页面于是能自己往下滚，而不是让人反复点节点。
     * 集群侧本来就有这个能力（k8s-server 的 Pod 日志端点 {@code sinceTime} + {@code usingTimestamps}），
     * 只是这条路以前每次都在全量重抓（上限 {@code maxBytes}）。
     *
     * <p>⚠️ 时间戳前缀**在服务端剥掉**，游标也只在这里算 —— 见 {@link PipelineRunLogDTO} 的说明：
     * 那是实现细节，漏到前端就等于让每个调用方各自重写一遍正则与去重。
     *
     * @param nodeName  只看某个节点；为空则把所有节点拼起来（每段前面标节点名）
     * @param sinceTime 增量游标（上一批的 {@code cursor}）；为空 = 从头全量
     */
    public PipelineRunLogDTO logs(String runName, String nodeName, PipelineRunContext ctx,
                                  int maxBytes, String sinceTime) {
        PlatformPipelineRun row = requireRun(runName);
        // 段头是**给人看**的那一行 —— 用用户起的名字（见 displayNames）。⚠️ 过滤键仍是 id
        Map<String, String> names = displayNames(row.getCanvasJson());
        if (!"RUNNING".equals(row.getStatus())) {
            // 老运行的日志行键里带着运行名前缀 —— 归一化要用**库里那行**的运行名（不是调用方给的）
            return PipelineRunLogDTO.whole(archivedLogs(row.getId(), nodeName, names, row.getRunName()));
        }
        return liveLogs(runName, nodeName, ctx, maxBytes, names, sinceTime);
    }

    /**
     * 一次运行里每个节点**现在**的落点（Pod 与容器）—— 运行详情的 DAG 着色用它。
     *
     * <p>⚠️ 它只回答「现在」：归档之后 TaskRun 与 Pod 早被删了，那时的节点状态在 run 行的
     * {@code stepsJson} 里（归档那一刻的快照）。**不把两种来源拼成同一个形状** ——
     * 前端按 run 的状态选一个（这正是 spec §4.3 那条分界线，混起来只会让人分不清看到的是什么时候的事）。
     */
    public List<PipelineStepRefDTO> steps(String runName, PipelineRunContext ctx) {
        PlatformPipelineRun row = requireRun(runName);
        if (!"RUNNING".equals(row.getStatus())) {
            return List.of();
        }
        return k8s.steps(ctx.clusterId(), ctx.tenantId(), ctx.namespace(), runName);
    }

    /**
     * 一次运行**渲染出来的 YAML**（spec §6 的排查利器）。
     *
     * <p>两种来源，还是那条「归档了没有」的分界线：
     * <ul>
     *   <li>还在跑 → 抓集群里那个**真实对象**（含 workspace 绑定与凭据 Secret 名）；</li>
     *   <li>已归档 → 对象已被删，读**归档那一刻抓下来的那一份**（{@code run_yaml}）。
     *       ⚠️ 刻意**不拿今天的画布重渲染**：那会画出一份当年没跑过的东西，
     *       而且对引用了连接的流水线根本渲染不出来（节点拒绝在没有凭据的情况下渲染）。</li>
     * </ul>
     *
     * @return 没有留下时返回 {@code null}（早于本功能上线的老运行）—— 由前端说「没有留下」，
     *         而不是编一份出来冒充
     */
    public String runYaml(String runName, PipelineRunContext ctx) {
        PlatformPipelineRun row = requireRun(runName);
        if ("RUNNING".equals(row.getStatus())) {
            return k8s.runYaml(ctx.clusterId(), ctx.tenantId(), ctx.namespace(), runName);
        }
        return row.getRunYaml();
    }

    private String archivedLogs(String runId, String nodeName, Map<String, String> names, String runName) {
        List<PlatformPipelineRunLog> lines = logMapper.listByRunId(runId);
        if (nodeName != null && !nodeName.isBlank()) {
            lines = lines.stream()
                    .filter(l -> nodeName.equals(nodeIdOf(l.getNodeName(), runName))).toList();
        }
        StringBuilder out = new StringBuilder();
        for (PlatformPipelineRunLog l : lines) {
            out.append(header(names, nodeIdOf(l.getNodeName(), runName), lines.size() > 1));
            out.append(l.getContent() == null ? "" : l.getContent());
            if (l.getTruncated() != null && l.getTruncated() == 1) {
                out.append("\n\n[日志超过单步上限，已截断]\n");
            }
        }
        return out.toString();
    }

    /**
     * 日志行里那个键 → **画布节点 id**。
     *
     * <p>⚠️ 两种键都认：新的是画布节点 id；2026-10-16 之前归档的是 **TaskRun 名**
     * （{@code <运行名>-<节点 id>}）—— 那时节点名取的是 TaskRun 名（根因见
     * {@code PipelineRunOperations.nodeNameOf}），日志行与 {@code stepsJson} 都记成了它。
     * 不认老键的话，点老运行的节点日志会一条都取不到 —— 用户看到的还是「只能在『全部』里看到」，
     * 等于没修。顺手把老运行的段头也换回用户起的名字（那个长名不是给人看的）。
     */
    private static String nodeIdOf(String stored, String runName) {
        String prefix = runName == null ? null : runName + "-";
        return prefix != null && stored != null && stored.startsWith(prefix)
                ? stored.substring(prefix.length())
                : stored;
    }

    /**
     * 现抓一次运行在集群上的日志。
     *
     * <p>⭐ 增量（{@code sinceTime}）的**去重口径**：只收**严格晚于游标**的行。
     * k8s 的 {@code sinceTime} 语义是「该时刻之后」且边界那行会**重复来一次**，
     * 所以不滤的话每轮都会多出一行"上一轮的尾巴"。
     *
     * <p>⚠️ 批内每个容器都用**同一个** {@code floor}（本轮的入参游标）去比，而不是用"已经涨上去的"
     * 那个最大值 —— 否则容器 A 抢先把游标推高了，容器 B 在中间时刻产生的行就会被静默丢掉。
     * 返回的游标才是本轮见过的**最大**时间戳。
     *
     * <p>⚠️ 已知的理论损失：容器是**串行**抓的，A 抓完到 B 抓完之间 A 新产生的行，时间戳落在
     * 「本轮 max 之内」→ 下一轮会被当成旧的滤掉。窗口是毫秒级，且只在一个 TaskRun 有**多个
     * step 容器**时才存在（平台渲染出来的节点每个都是单 step，所以现实中不会走到）；
     * 兜底是运行结束那一刻的**整段重取**（归档内容才是权威，见前端）。
     */
    private PipelineRunLogDTO liveLogs(String runName, String nodeName, PipelineRunContext ctx,
                                       int maxBytes, Map<String, String> names, String sinceTime) {
        String floor = sinceTime == null ? "" : sinceTime;
        List<PipelineStepRefDTO> steps = k8s.steps(ctx.clusterId(), ctx.tenantId(), ctx.namespace(), runName);
        if (nodeName != null && !nodeName.isBlank()) {
            steps = steps.stream().filter(s -> nodeName.equals(s.nodeName())).toList();
        }
        StringBuilder out = new StringBuilder();
        boolean sawTimestamp = false;
        String cursor = floor;
        for (PipelineStepRefDTO step : steps) {
            if (step.podName() == null || step.podName().isBlank()) {
                continue;                                  // 还没起来，没日志可看
            }
            StringBuilder section = new StringBuilder();
            for (String container : step.containers()) {
                String raw = k8s.podLog(ctx.clusterId(), ctx.tenantId(), ctx.namespace(),
                        step.podName(), container, maxBytes, floor.isEmpty() ? null : floor).content();
                for (String line : raw.split("\n", -1)) {
                    String[] parts = splitLogLine(line);
                    if (parts[0].isEmpty()) {
                        // 没有 k8s 前缀。空串是 split 留下的尾巴（内容本来就以换行结尾）→ 丢掉；
                        // 其余原样给（没法比大小也没法去重，下面会退化成整段模式）
                        if (!line.isEmpty()) {
                            section.append(parts[1]).append('\n');
                        }
                        continue;
                    }
                    sawTimestamp = true;
                    if (!floor.isEmpty() && normTs(parts[0]).compareTo(normTs(floor)) <= 0) {
                        continue;                          // 边界重复的那一行
                    }
                    section.append(parts[1]).append('\n');
                    if (cursor.isEmpty() || normTs(parts[0]).compareTo(normTs(cursor)) > 0) {
                        cursor = parts[0];
                    }
                }
            }
            // 段头只在**这一段真有新内容**时才加 —— 否则每轮都会把一排空段头刷一遍
            if (section.length() > 0) {
                out.append(header(names, step.nodeName(), steps.size() > 1)).append(section);
            }
        }
        if (sawTimestamp) {
            return new PipelineRunLogDTO(out.toString(), cursor.isEmpty() ? floor : cursor, true);
        }
        // 集群侧没给时间戳（k8s-server 没重建 / 老版本）→ 增量没法成立。
        // 这时**必须退回整段**，否则调用方按增量追加会把同一份日志一遍遍接上去。
        if (floor.isEmpty()) {
            return PipelineRunLogDTO.whole(out.toString());   // 本轮本来就是全量
        }
        return PipelineRunLogDTO.whole(
                liveLogs(runName, nodeName, ctx, maxBytes, names, null).text());
    }

    /**
     * 一行 {@code <RFC3339Nano>\t<内容>} → {@code [时间戳, 内容]}；没有前缀则时间戳为空串。
     *
     * <p>kubelet 用的分隔符是**制表符**；但中间隔着 k8s-server 与网关的转发，可能被规整成空格，
     * 所以两种空白都认。**只认 UTC 的 {@code Z}** —— 认 {@code +08:00} 的话，应用自己打在行首的
     * 时间戳会被当成 k8s 的剥掉。
     *
     * <p>⚠️ 刻意**不**用「找第一个制表符」那种写法：内容里本来就有制表符的行（不只是日志，
     * 比如 {@code ls -l} 的输出）会被从中间劈开，前半个当时间戳、后半个当内容。
     */
    static String[] splitLogLine(String line) {
        Matcher m = TS_PREFIX.matcher(line);
        if (m.find()) {
            return new String[]{m.group(1), line.substring(m.end())};
        }
        return new String[]{"", line};
    }

    /**
     * 小数秒补到固定 9 位，让同一来源的时间戳能**按字典序**比较。
     *
     * <p>⚠️ 直接比字符串是不对的：{@code …:00.5Z} 与 {@code …:00.45Z} 按字符串比会得出前者更小，
     * 而按时间比它更大。补零之后就一致了。
     */
    static String normTs(String ts) {
        Matcher m = TS_PREFIX_FULL.matcher(ts);
        if (!m.matches()) {
            return ts;
        }
        String frac = ((m.group(2) == null ? "" : m.group(2).substring(1)) + "000000000");
        return m.group(1) + "." + frac.substring(0, 9) + (m.group(3) == null ? "" : m.group(3));
    }

    /**
     * 「全部」视图里每一段的段头。**只在拼多个节点时才加**（只看一个节点时加段头是噪声）。
     *
     * <p>⚠️ 段头用**名字**（用户起的那个），过滤与归档键仍然全按 id。
     */
    private static String header(Map<String, String> names, String nodeName, boolean multi) {
        if (!multi) {
            return "";
        }
        String name = names.get(nodeName);
        return "===== " + (name == null || name.isBlank() ? nodeName : name.trim()) + " =====\n";
    }

    /**
     * 画布上的 {@code 节点 id → 显示名}（用户起的那个名字）。
     *
     * <p>只给**段头**这类给人看的地方用：id 是 Tekton 的 task 名，读它的人得先知道
     * 那条流水线长什么样；名字是用户自己起的，一眼就知道这段在跑什么。
     *
     * <p>没起名的节点不在 map 里（调用方退回显示 id）；老运行没留画布 → 空 map → 段头全是 id。
     */
    private static Map<String, String> displayNames(String canvasJson) {
        if (canvasJson == null || canvasJson.isBlank()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        try {
            JsonNode nodes = JSON.readTree(canvasJson).path("nodes");
            if (!nodes.isArray()) {
                return Map.of();
            }
            for (JsonNode node : nodes) {
                String id = node.path("id").asString(null);
                String name = node.path("name").asString(null);
                if (id != null && !id.isBlank() && name != null && !name.isBlank()) {
                    out.put(id, name.trim());
                }
            }
        } catch (Exception e) {
            // 画布解析不了不该让日志看不成 —— 段头退回 id
            log.debug("解析画布取显示名失败（段头退回 id）: {}", e.getMessage());
        }
        return out;
    }

    public PlatformPipelineRun get(String runName) {
        return requireRun(runName);
    }

    // ---- 内部 ----

    /**
     * 取一行运行 + 校验归属。**本类里每个按 run 名进来的口子都必须过这里。**
     *
     * <p>⚠️ 边界用**库里那行**的 cluster/namespace，不是请求里的 —— run 名是调用方给的，
     * 不带边界；拿它直接查库就等于「知道名字就能读（甚至取消）别人的运行」。
     */
    private PlatformPipelineRun requireRun(String runName) {
        PlatformPipelineRun row = runMapper.selectByRunName(runName);
        if (row == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST,
                    "运行不存在：" + runName);
        }
        access.assertAccessible(row.getClusterId(), row.getNamespace());
        return row;
    }


    /**
     * 解析画布引用到的每一条连接。**明确拒绝，绝不静默降级成"匿名去拉"**（spec §5.3）。
     *
     * <p>画布是平台自己的数据（`{nodes:[{id,type,config}]}`），不是 Tekton 的结构 ——
     * 所以在这里读懂它是合适的，与「不碰 fabric8」那条边界无关。
     */
    private List<com.coding.common.models.pipeline.PipelineConnectionRef> resolveConnections(
            String canvasJson) {
        List<com.coding.common.models.pipeline.PipelineConnectionRef> out = new ArrayList<>();
        for (PipelineCanvasRefs.Ref ref : PipelineCanvasRefs.scan(canvasJson)) {
            out.add(credentials.resolveForPipeline(ref.id(), ref.expectedType()));
        }
        return out;
    }

    /**
     * {@code 流水线名-yyyyMMddHHmmss-4位随机}。
     *
     * <p>⚠️ 时间戳 + 随机后缀是**必须的**：`platform_pipeline_run.run_name` 的唯一索引是
     * **全局**的（不按 cluster/namespace 分组），只用流水线名会在第二次运行时就撞上。
     * 同时它还得是合法的 K8s 对象名（小写字母/数字/连字符）。
     */
    private static String generateRunName(String pipelineName) {
        StringBuilder suffix = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            suffix.append(ALNUM.charAt(RANDOM.nextInt(ALNUM.length())));
        }
        return pipelineName + "-" + LocalDateTime.now().format(STAMP) + "-" + suffix;
    }

    /** 只为测试/排查暴露：画布引用扫描用到的 key（真正的定义在 {@link PipelineCanvasRefs}）。 */
    static List<String> connectionKeys() {
        return new ArrayList<>(PipelineCanvasRefs.CONNECTION_KEYS);
    }
}
