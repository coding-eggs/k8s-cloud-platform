package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PipelineDTO;
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
import java.util.Set;
import java.util.List;

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

    /**
     * 画布里「引用了一条平台连接」的字段名。
     *
     * <p>⚠️ **这是跨模块契约**：定义在 k8s-core 的节点类里（{@code GitCloneNode} /
     * {@code ImagePushNode}），而本模块读不到那些类型。改名必须同时改两边 ——
     * `BuiltinNodesTest.theConnectionReferenceFieldsPlatformApiScansForAreStillNamedThis`
     * 是唯一会提醒你的地方。
     */
    static final List<String> CONNECTION_KEYS = List.of("connectionId", "registryConnectionId");

    /** git 节点引用的必须是 git 连接；镜像节点只要求「是一条被授权的 registry 连接」（expectedType=null） */
    private static final List<String> GIT_KEYS = List.of("connectionId");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

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

    /**
     * 触发一次运行。
     *
     * @param pipeline 已经载入并校验过归属的流水线定义
     * @param user     触发人（审计与运行历史都用它）
     * @return 集群里的 PipelineRun 名（= 归档幂等键）
     */
    public String run(PipelineDTO pipeline, String user, PipelineRunContext ctx) {
        // ① 解析画布里引用到的连接：**逐条校授权 + 解密**（绕过前端直接打接口也一样）。
        //    解密出来的东西只交给 k8s-server 建 Secret，不进任何能被用户看到的地方。
        List<com.coding.common.models.pipeline.PipelineConnectionRef> connections =
                resolveConnections(pipeline.getCanvasJson());

        // ② 先生成名字：**幂等键从源头就有**
        String runName = generateRunName(pipeline.getName());

        // ③ 先落库
        PlatformPipelineRun row = new PlatformPipelineRun();
        row.setId(ULIDGenerator.generateULID());
        row.setPipelineId(pipeline.getId());
        row.setClusterId(ctx.clusterId());
        row.setNamespace(ctx.namespace());
        row.setRunName(runName);
        row.setStatus("RUNNING");
        row.setTriggeredBy(user);
        runMapper.insert(row);

        // ④ 再创建。失败就把刚写的那行撤掉 —— 库里不能留一条 RUNNING 的孤儿
        try {
            k8s.createRun(ctx.clusterId(), ctx.tenantId(), ctx.namespace(),
                    runName, pipeline.getCanvasJson(), connections);
        } catch (RuntimeException e) {
            log.warn("创建 PipelineRun 失败，回滚刚落的库：{}", runName, e);
            runMapper.deleteById(row.getId());
            throw e;
        }
        return runName;
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
        PlatformPipelineRun row = runMapper.selectByRunName(runName);
        if (row == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST,
                    "运行不存在：" + runName);
        }
        access.assertAccessible(row.getClusterId(), row.getNamespace());
        // ⚠️ 编排（**先抓日志、再取消**）在 k8s-server 侧：Tekton 取消时会自己删 Pod，
        //    顺序反了那次运行的日志就没了。本方法只做归属校验与转发。
        k8s.cancelRun(ctx.clusterId(), ctx.tenantId(), ctx.namespace(), runName);
    }

    /** 运行历史。**只读库** —— 集群里的对象归档后已经删了（spec §4.3）。 */
    public List<PlatformPipelineRun> list(String pipelineId, PipelineRunContext ctx) {
        return runMapper.listByPipeline(pipelineId, ctx.clusterId(), ctx.namespace());
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
     * @param nodeName 只看某个节点；为空则把所有节点拼起来（每段前面标节点名）
     */
    public String logs(String runName, String nodeName, PipelineRunContext ctx, int maxBytes) {
        PlatformPipelineRun row = runMapper.selectByRunName(runName);
        if (row == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "运行不存在：" + runName);
        }
        access.assertAccessible(row.getClusterId(), row.getNamespace());
        if (!"RUNNING".equals(row.getStatus())) {
            return archivedLogs(row.getId(), nodeName);
        }
        return liveLogs(runName, nodeName, ctx, maxBytes);
    }

    private String archivedLogs(String runId, String nodeName) {
        List<PlatformPipelineRunLog> lines = logMapper.listByRunId(runId);
        if (nodeName != null && !nodeName.isBlank()) {
            lines = lines.stream().filter(l -> nodeName.equals(l.getNodeName())).toList();
        }
        StringBuilder out = new StringBuilder();
        for (PlatformPipelineRunLog l : lines) {
            out.append(header(l.getNodeName(), lines.size() > 1));
            out.append(l.getContent() == null ? "" : l.getContent());
            if (l.getTruncated() != null && l.getTruncated() == 1) {
                out.append("\n\n[日志超过单步上限，已截断]\n");
            }
        }
        return out.toString();
    }

    private String liveLogs(String runName, String nodeName, PipelineRunContext ctx, int maxBytes) {
        List<PipelineStepRefDTO> steps = k8s.steps(ctx.clusterId(), ctx.tenantId(), ctx.namespace(), runName);
        if (nodeName != null && !nodeName.isBlank()) {
            steps = steps.stream().filter(s -> nodeName.equals(s.nodeName())).toList();
        }
        StringBuilder out = new StringBuilder();
        for (PipelineStepRefDTO step : steps) {
            if (step.podName() == null || step.podName().isBlank()) {
                continue;                                  // 还没起来，没日志可看
            }
            out.append(header(step.nodeName(), steps.size() > 1));
            for (String container : step.containers()) {
                out.append(k8s.podLog(ctx.clusterId(), ctx.tenantId(), ctx.namespace(),
                        step.podName(), container, maxBytes).content());
            }
        }
        return out.toString();
    }

    private static String header(String nodeName, boolean multi) {
        return multi ? "===== " + nodeName + " =====\n" : "";
    }

    public PlatformPipelineRun get(String runName) {
        PlatformPipelineRun row = runMapper.selectByRunName(runName);
        if (row == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST,
                    "运行不存在：" + runName);
        }
        // ⚠️ 归属校验用**库里那行**的 cluster/namespace，不是请求里的 —— run 名是调用方给的，
        // 不带边界；拿它直接查库就等于「知道名字就能读别人的运行」。
        access.assertAccessible(row.getClusterId(), row.getNamespace());
        return row;
    }

    // ---- 内部 ----

    /**
     * 解析画布引用到的每一条连接。**明确拒绝，绝不静默降级成"匿名去拉"**（spec §5.3）。
     *
     * <p>画布是平台自己的数据（`{nodes:[{id,type,config}]}`），不是 Tekton 的结构 ——
     * 所以在这里读懂它是合适的，与「不碰 fabric8」那条边界无关。
     */
    private List<com.coding.common.models.pipeline.PipelineConnectionRef> resolveConnections(
            String canvasJson) {
        if (canvasJson == null || canvasJson.isBlank()) {
            return List.of();
        }
        JsonNode canvas;
        try {
            canvas = JSON.readTree(canvasJson);
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_CANVAS_INVALID,
                    "画布内容无法解析");
        }
        JsonNode nodes = canvas.path("nodes");
        if (!nodes.isArray()) {
            return List.of();
        }
        List<com.coding.common.models.pipeline.PipelineConnectionRef> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode node : nodes) {
            JsonNode config = node.path("config");
            for (String key : CONNECTION_KEYS) {
                String id = config.path(key).asString(null);
                if (id == null || id.isBlank() || !seen.add(id.trim())) {
                    continue;
                }
                out.add(credentials.resolveForPipeline(id.trim(),
                        GIT_KEYS.contains(key) ? "GIT" : null));
            }
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

    /** 只为测试/排查暴露：画布引用扫描用到的 key（与 k8s-core 的护栏测试对齐）。 */
    static List<String> connectionKeys() {
        return new ArrayList<>(CONNECTION_KEYS);
    }
}
