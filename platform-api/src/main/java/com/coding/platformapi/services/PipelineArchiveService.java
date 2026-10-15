package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.pipeline.PipelineRunSnapshotDTO;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.system.PlatformPipelineRunLogMapper;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.data.models.system.PlatformPipelineRun;
import com.coding.data.models.system.PlatformPipelineRunLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 归档的**落库那一半**：把 k8s-server 抓好的快照写进平台库。
 *
 * <h2>⚠️ 它不碰集群，也不该碰</h2>
 * 快照由 k8s-server 抓（watch 是它开的，收到终结事件时集群对象、TaskRun、Pod 都还在它手上），
 * 随回调一起送过来；平台落完库回 200，**k8s-server 收到 ack 后自己删对象**。
 * 于是本服务没有任何集群调用 —— 也就不需要为「没有用户 token 的后台调用」造一套机器身份。
 *
 * <p>服务的意义在这里只有两件事：**幂等**（同一条运行重投多少次都只写一遍）与
 * **事务**（日志行与运行状态要么一起进，要么都不进）。
 */
@Slf4j
@Service
public class PipelineArchiveService {

    private final PlatformPipelineRunMapper runMapper;
    private final PlatformPipelineRunLogMapper logMapper;

    public PipelineArchiveService(PlatformPipelineRunMapper runMapper,
                                  PlatformPipelineRunLogMapper logMapper) {
        this.runMapper = runMapper;
        this.logMapper = logMapper;
    }

    /**
     * 落库。**幂等**：重投（k8s-server 重试 / 平台重启后补投）时只写一次。
     *
     * <p>⚠️ 幂等分支**返回正常**（而不是抛）—— 调用方据此回 200，k8s-server 才会去删对象。
     * 这里如果抛，就变成「库早就写好了，但对象永远删不掉」。
     */
    @Transactional
    public void persist(PipelineRunSnapshotDTO snapshot) {
        PlatformPipelineRun row = runMapper.selectByRunName(snapshot.runName());
        if (row == null) {
            // 不是平台发起的运行（或保留清理已经把它删了）—— 落不了库，但对象该由那边删
            log.warn("归档时库里没有这条运行（跳过落库，对象由 k8s-server 删）：{}", snapshot.runName());
            return;
        }
        if (!"RUNNING".equals(row.getStatus())) {
            log.debug("这条运行已经归档过了，跳过落库：{}", snapshot.runName());
            return;
        }
        if ("RUNNING".equals(snapshot.status())) {
            // 防御：k8s-server 只在终结（或平台判超时）时才发快照。真到了这里说明对方判断有误 ——
            // **不 ack**，让它留着对象与 Pod（那份日志还在），比删掉再看要好
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                    "该运行尚未终结，不能归档：" + snapshot.runName());
        }

        List<PipelineRunSnapshotDTO.StepLog> steps =
                snapshot.steps() == null ? List.of() : snapshot.steps();
        for (PipelineRunSnapshotDTO.StepLog step : steps) {
            if (step.log() == null || step.log().isEmpty()) {
                // 「没有日志」与「日志是空的」不是一回事 —— 不写空行
                continue;
            }
            PlatformPipelineRunLog line = new PlatformPipelineRunLog();
            line.setId(ULIDGenerator.generateULID());
            line.setRunId(row.getId());
            line.setNodeName(step.nodeName());
            line.setContent(step.log());
            line.setTruncated(step.truncated() ? 1 : 0);
            line.setSizeBytes(step.sizeBytes());
            logMapper.insert(line);           // 唯一索引 (run_id, node_name) + ON DUPLICATE KEY
        }

        row.setStatus(snapshot.status());
        row.setReason(snapshot.reason());
        row.setStartTime(snapshot.startTime());
        row.setCompletionTime(snapshot.completionTime());
        row.setStepsJson(stepsJson(steps));
        runMapper.update(row);
    }

    private static String stepsJson(List<PipelineRunSnapshotDTO.StepLog> steps) {
        return steps.stream()
                .map(s -> s.nodeName() + "=" + s.status())
                .reduce((a, b) -> a + "," + b)
                .orElse(null);
    }
}
