package com.coding.k8sserver.pipeline;

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
 * 归档的**落库那一半**：把快照写进平台库。
 *
 * <h2>⚠️ 它为什么在 k8s-server 而不是 platform-api</h2>
 * 因为抓快照的就是 k8s-server（watch 是它开的，收到终结事件时集群对象、TaskRun、Pod 都还在它手上），
 * 而它**本来就有 platform-data**（{@code K8sClusterMapper} 早就在用）、{@code @MapperScan}
 * 与事务也都已启用。于是「抓完直接写」不需要任何跨进程调用 ——
 * 也就**不需要**那条免认证的入站端点、那个共享密钥、以及配套的 ack / 待补 / 重投语义。
 *
 * <p>代价如实记着：运行表从此有**两个写入方**（这里是归档，platform-api 是触发与保留清理），
 * 且「k8s-server 零业务逻辑」在这条链上让了步。换来的是**入站攻击面归零**。
 *
 * <p>本服务只管两件事：**幂等**（同一条运行重投多少次都只写一遍）与
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
     * 落库。**幂等**：重投时只写一次。
     *
     * <p>⚠️ 已归档时**返回正常**（而不是抛）—— 调用方据此才去删对象。
     * 这里如果抛，就变成「库早就写好了，但对象永远删不掉」。
     */
    @Transactional
    public void persist(PipelineRunSnapshotDTO snapshot) {
        PlatformPipelineRun row = runMapper.selectByRunName(snapshot.runName());
        if (row == null) {
            // 不是平台发起的运行（或保留清理已经把它删了）—— 落不了库，但对象该由调用方删
            log.warn("归档时库里没有这条运行（跳过落库，对象照删）：{}", snapshot.runName());
            return;
        }
        if (!"RUNNING".equals(row.getStatus())) {
            log.debug("这条运行已经归档过了，跳过落库：{}", snapshot.runName());
            return;
        }
        if ("RUNNING".equals(snapshot.status())) {
            // 防御：只在终结（或平台判超时）时才抓快照。真到了这里说明判断有误 ——
            // **不写也不删**，留着对象与 Pod（那份日志还在），比删掉再想找回要好
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
        // YAML 是**归档这一刻**才拿得到的东西（对象马上就被删了），所以它跟着这次回写一起进去
        row.setRunYaml(snapshot.runYaml());
        runMapper.update(row);
    }

    private static String stepsJson(List<PipelineRunSnapshotDTO.StepLog> steps) {
        return steps.stream()
                .map(s -> s.nodeName() + "=" + s.status())
                .reduce((a, b) -> a + "," + b)
                .orElse(null);
    }
}
