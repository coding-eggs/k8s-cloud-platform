package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformPipelineRunLogMapper;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.data.models.system.PlatformPipelineRun;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 保留清理：删掉超过保留期的运行记录与它们的日志行。
 *
 * <p>⚠️ <b>只有这些是平台自己的数据</b> —— 归档之后集群里已经没有对应的对象了
 * （集群对象在归档时就删了），所以「删运行历史」就真的只是删库里的行，不涉及任何集群动作。
 *
 * <p>⚠️ **先删日志行、再删运行行**：本仓不加外键，级联靠代码 —— 顺序反了就会留下一堆
 * 没人认领的日志行（表会一直长，而没有任何入口能看到它们）。
 */
@Slf4j
@Service
public class PipelineRetentionService {

    private final PlatformPipelineRunMapper runMapper;
    private final PlatformPipelineRunLogMapper logMapper;
    private final int retentionDays;

    public PipelineRetentionService(PlatformPipelineRunMapper runMapper,
                                    PlatformPipelineRunLogMapper logMapper,
                                    @Value("${platform.pipeline.log.retention-days:90}") int retentionDays) {
        this.runMapper = runMapper;
        this.logMapper = logMapper;
        this.retentionDays = retentionDays;
    }

    /**
     * @param now 便于单测注入"现在"
     * @return 删掉了多少条运行
     */
    @Transactional
    public int purge(Instant now) {
        // ⚠️ cutoff 用 Instant.toString()：它是 ISO-8601 的 Z 结尾 UTC 形式，与 Tekton 存进
        // completion_time 的 RFC3339 原文同格式 —— 那个列是 varchar，比较是**字符串比较**，
        // 只有格式统一时字典序才等于时间序（见 PlatformPipelineRunMapper 的注释）。
        String cutoff = now.minus(Duration.ofDays(retentionDays)).toString();
        List<PlatformPipelineRun> victims = runMapper.selectCompletedBefore(cutoff);
        if (victims.isEmpty()) {
            return 0;
        }
        List<String> ids = victims.stream().map(PlatformPipelineRun::getId).toList();
        logMapper.deleteByRunIds(ids);          // ← 先日志
        runMapper.deleteByIds(ids);             // ← 再运行
        log.info("流水线运行保留清理：删掉 {} 条早于 {} 的运行及其日志", ids.size(), cutoff);
        return ids.size();
    }

    /**
     * 每日一次。**有意错开整点**（03:17）—— 与其它日任务挤在同一秒没有好处。
     */
    @Scheduled(cron = "${platform.pipeline.log.retention-cron:0 17 3 * * *}")
    public void nightly() {
        try {
            purge(Instant.now());
        } catch (Exception e) {
            // 清理失败不该让调度器带着异常往下跑 —— 明天再来
            log.warn("流水线运行保留清理失败（明天再试）", e);
        }
    }
}
