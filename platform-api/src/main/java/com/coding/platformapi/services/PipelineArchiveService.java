package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.common.models.pipeline.PipelineStepRefDTO;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.system.PlatformPipelineRunLogMapper;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.data.models.system.PlatformPipelineRun;
import com.coding.data.models.system.PlatformPipelineRunLog;
import com.coding.platformapi.k8s.K8sPipelineClient;
import com.coding.platformapi.models.PipelineRunContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 归档：**抓快照 → 落库 → 删对象**（spec §4.5③）。顺序就是这一条链的全部价值。
 *
 * <p>它解决的是用户 2026-10-15 提的那个问题：Tekton 跑完会留一个 {@code Completed} 的 Pod 占资源，
 * 而超时/取消时 Tekton 又会自己把 Pod 删掉、**日志一起没**。所以：
 * 平台在对象还在的时候把状态与日志抓下来落库，然后**自己**把 PipelineRun 删掉
 * （级联带走 TaskRun 与 Pod），集群里不留东西。
 *
 * <h2>两条不显然的规则</h2>
 * <ol>
 *   <li><b>幂等分支仍然要删对象</b>。想象「库写好了，但第 5 步删对象失败」——
 *       重投时若直接 return，那条运行的对象就**永远留在集群里**，正是本批要消掉的东西。
 *       所以：归档幂等（不重写库、不重抓日志），**但删除每次都试**（删本身幂等）。</li>
 *   <li><b>未终结的运行不归档</b> —— 不能把 Running 的当完成的。
 *       例外是 {@code timedOut=true}：平台判超时时 Tekton 那边**还在跑**
 *       （平台是「先抓日志、再取消」），那条回调恰恰是平台自己的终结决定。</li>
 * </ol>
 */
@Slf4j
@Service
public class PipelineArchiveService {

    private final PlatformPipelineRunMapper runMapper;
    private final PlatformPipelineRunLogMapper logMapper;
    private final K8sPipelineClient k8s;
    private final int maxLogBytes;

    public PipelineArchiveService(PlatformPipelineRunMapper runMapper,
                                  PlatformPipelineRunLogMapper logMapper,
                                  K8sPipelineClient k8s,
                                  @Value("${platform.pipeline.log.max-bytes:1048576}") int maxLogBytes) {
        this.runMapper = runMapper;
        this.logMapper = logMapper;
        this.k8s = k8s;
        this.maxLogBytes = maxLogBytes;
    }

    /** 归档（watch 驱动 / 内部回调触发）。走的是**平台身份**（admin）—— 回调里没有用户。 */
    public void archive(String clusterId, String namespace, String runName) {
        archive(clusterId, namespace, runName, false);
    }

    /**
     * @param timedOut 平台判定超时而终结的。⚠️ 这个标志必须跟着请求走：Tekton 侧看到的是平台 patch 的
     *                 {@code spec.status: Cancelled}，光看对象的 condition 分不出「用户取消」与
     *                 「平台判超时」，而 spec §4.2 要求运行历史里两者不一样。
     */
    @Transactional
    public void archive(String clusterId, String namespace, String runName, boolean timedOut) {
        PlatformPipelineRun row = runMapper.selectByRunName(runName);

        // ① 已归档 → **不重写库，但删除照做**（见类注释第 1 条）
        if (row != null && !"RUNNING".equals(row.getStatus())) {
            log.debug("运行已归档，只补一次删除：{}", runName);
            k8s.deleteRun(clusterId, null, namespace, runName);
            return;
        }

        PipelineRunDTO snapshot = k8s.getRun(clusterId, null, namespace, runName);
        if (snapshot == null) {
            // 对象已经没了（重投 / 他方删除）—— 那不是失败
            k8s.deleteRun(clusterId, null, namespace, runName);
            return;
        }

        String status = timedOut ? "TIMEOUT" : snapshot.getStatus();
        if ("RUNNING".equals(status)) {
            // ⚠️ 未终结的不归档，**也不删** —— 它还在跑
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                    "该运行尚未终结，不能归档：" + runName);
        }

        if (row == null) {
            // 库里没有这条运行（保留清理删过、或创建时落库失败后集群对象没清理干净）。
            // 落不了库就别留对象 —— 日志抓不到 run_id 也就存不了。
            log.warn("归档时库里没有这条运行，只删对象：{}", runName);
            k8s.deleteRun(clusterId, null, namespace, runName);
            return;
        }

        // ② 抓快照：先拿落点，再逐个容器取日志（走既有的 Pod 日志通道，spec §4.4）
        List<PipelineStepRefDTO> steps = k8s.steps(clusterId, null, namespace, runName);
        List<PlatformPipelineRunLog> logs = new ArrayList<>();
        List<String> stepsSummary = new ArrayList<>();
        for (PipelineStepRefDTO step : steps) {
            stepsSummary.add(step.nodeName() + "=" + step.status());
            readStepLog(clusterId, namespace, row.getId(), step, logs);
        }

        // ③ 一个事务里写：日志行 + 运行的状态与耗时
        for (PlatformPipelineRunLog line : logs) {
            logMapper.insert(line);
        }
        row.setStatus(status);
        row.setReason(snapshot.getReason());
        row.setStartTime(snapshot.getStartTime());
        row.setCompletionTime(snapshot.getCompletionTime());
        row.setStepsJson(String.join(",", stepsSummary));
        runMapper.update(row);

        // ④ 删对象 —— 级联带走 TaskRun 与 **Pod**，这就是「k8s 不留 Completed Pod」那一步
        k8s.deleteRun(clusterId, null, namespace, runName);
    }

    /**
     * 取消前**抢抓**一次日志（spec §4.2）。
     *
     * <p>⚠️ 它**不碰集群对象** —— 取消由调用方紧接着做。顺序（先抓后取消）是
     * 「取消之后日志仍然看得到」的全部依据：Tekton 取消时会自己把 Pod 删掉。
     */
    public void capture(String runName, PipelineRunContext ctx) {
        PlatformPipelineRun row = runMapper.selectByRunName(runName);
        if (row == null) {
            return;
        }
        List<PipelineStepRefDTO> steps = k8s.steps(ctx.clusterId(), ctx.tenantId(), ctx.namespace(), runName);
        for (PipelineStepRefDTO step : steps) {
            List<PlatformPipelineRunLog> out = new ArrayList<>();
            readStepLog(ctx.clusterId(), ctx.namespace(), row.getId(), step, out, ctx.tenantId());
            out.forEach(logMapper::insert);
        }
    }

    private void readStepLog(String clusterId, String namespace, String runId,
                             PipelineStepRefDTO step, List<PlatformPipelineRunLog> into) {
        readStepLog(clusterId, namespace, runId, step, into, null);
    }

    /**
     * 抓一个节点的日志。**每个容器一段、拼起来** —— Tekton 的每个 step 就是 Pod 里的一个容器。
     *
     * <p>Pod 还没起来（{@code podName == null}）或日志抓不到时**什么都不写**，
     * 不写一条空日志行 —— 「没有日志」与「日志是空的」不是一回事。
     */
    private void readStepLog(String clusterId, String namespace, String runId,
                             PipelineStepRefDTO step, List<PlatformPipelineRunLog> into, String tenantId) {
        if (step.podName() == null || step.podName().isBlank() || step.containers().isEmpty()) {
            return;
        }
        StringBuilder content = new StringBuilder();
        boolean truncated = false;
        int size = 0;
        for (String container : step.containers()) {
            K8sPipelineClient.CappedLog part = k8s.podLog(clusterId, tenantId, namespace,
                    step.podName(), container, maxLogBytes);
            content.append(part.content());
            truncated = truncated || part.truncated();
            size += part.sizeBytes();
        }
        if (content.isEmpty()) {
            return;
        }
        PlatformPipelineRunLog line = new PlatformPipelineRunLog();
        line.setId(ULIDGenerator.generateULID());
        line.setRunId(runId);
        line.setNodeName(step.nodeName());
        line.setContent(content.toString());
        line.setTruncated(truncated ? 1 : 0);
        line.setSizeBytes(size);
        into.add(line);
    }
}
