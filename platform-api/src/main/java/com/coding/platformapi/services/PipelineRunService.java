package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PipelineDTO;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.data.models.system.PlatformPipelineRun;
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
    private final K8sPipelineClient k8s;
    private final CredentialService credentials;
    private final PipelineArchiveService archive;

    public PipelineRunService(PlatformPipelineRunMapper runMapper,
                              K8sPipelineClient k8s,
                              CredentialService credentials,
                              PipelineArchiveService archive) {
        this.runMapper = runMapper;
        this.k8s = k8s;
        this.credentials = credentials;
        this.archive = archive;
    }

    /**
     * 触发一次运行。
     *
     * @param pipeline 已经载入并校验过归属的流水线定义
     * @param user     触发人（审计与运行历史都用它）
     * @return 集群里的 PipelineRun 名（= 归档幂等键）
     */
    public String run(PipelineDTO pipeline, String user, PipelineRunContext ctx) {
        // ① 画布里每一条被引用的连接都必须被显式授权 —— 绕过前端直接打接口也一样
        assertConnectionsAuthorized(pipeline.getCanvasJson());

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
                    runName, pipeline.getCanvasJson());
        } catch (RuntimeException e) {
            log.warn("创建 PipelineRun 失败，回滚刚落的库：{}", runName, e);
            runMapper.deleteById(row.getId());
            throw e;
        }
        return runName;
    }

    /**
     * 取消一次运行。**先抓日志、再取消**（spec §4.2）—— 顺序反了那次运行的日志就没了。
     */
    public void cancel(String runName, PipelineRunContext ctx) {
        PlatformPipelineRun row = runMapper.selectByRunName(runName);
        if (row == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST,
                    "运行不存在：" + runName);
        }
        archive.capture(runName, ctx);          // ← 先抓
        k8s.cancelRun(ctx.clusterId(), ctx.tenantId(), ctx.namespace(), runName);   // ← 再取消
    }

    /** 运行历史。**只读库** —— 集群里的对象归档后已经删了（spec §4.3）。 */
    public List<PlatformPipelineRun> list(String pipelineId, PipelineRunContext ctx) {
        return runMapper.listByPipeline(pipelineId, ctx.clusterId(), ctx.namespace());
    }

    public PlatformPipelineRun get(String runName) {
        PlatformPipelineRun row = runMapper.selectByRunName(runName);
        if (row == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST,
                    "运行不存在：" + runName);
        }
        return row;
    }

    // ---- 内部 ----

    /**
     * 逐条问「这条连接被授权了吗」。**明确拒绝，绝不静默降级成"匿名去拉"**（spec §5.3）。
     *
     * <p>画布是平台自己的数据（`{nodes:[{id,type,config}]}`），不是 Tekton 的结构 ——
     * 所以在这里读懂它是合适的，与「不碰 fabric8」那条边界无关。
     */
    private void assertConnectionsAuthorized(String canvasJson) {
        if (canvasJson == null || canvasJson.isBlank()) {
            return;
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
            return;
        }
        for (JsonNode node : nodes) {
            JsonNode config = node.path("config");
            for (String key : CONNECTION_KEYS) {
                String id = config.path(key).asString(null);
                if (id != null && !id.isBlank()) {
                    credentials.assertUsableByPipelines(id.trim(),
                            GIT_KEYS.contains(key) ? "GIT" : null);
                }
            }
        }
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
