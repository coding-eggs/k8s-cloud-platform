package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformAppExecutionLogMapper;
import com.coding.data.models.system.PlatformAppExecution;
import com.coding.data.models.system.PlatformAppExecutionLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 发布执行的过程日志：每个阶段做了什么、健康检查卡在哪（表 platform_app_execution_log）。
 *
 * <p><b>写失败只记 warn</b>：日志是给人看的旁路，不能因为它把发布判失败（例如还没重放建表迁移）。
 */
@Slf4j
@Component
public class AppExecutionJournal {

    static final String INFO = "INFO";
    static final String WARN = "WARN";
    static final String ERROR = "ERROR";

    /** 列宽 2000 */
    private static final int MESSAGE_MAX = 2000;

    private final PlatformAppExecutionLogMapper logs;

    public AppExecutionJournal(PlatformAppExecutionLogMapper logs) {
        this.logs = logs;
    }

    public void info(PlatformAppExecution e, String stage, String message) {
        append(e, stage, INFO, message);
    }

    public void warn(PlatformAppExecution e, String stage, String message) {
        append(e, stage, WARN, message);
    }

    public void error(PlatformAppExecution e, String stage, String message) {
        append(e, stage, ERROR, message);
    }

    /**
     * 与该执行上一条日志内容相同则不写：健康检查每 3 秒一拍，状态没变时不该刷屏。
     */
    public void infoIfChanged(PlatformAppExecution e, String stage, String message) {
        try {
            PlatformAppExecutionLog last = e.getId() == null ? null : logs.selectLastByExecution(e.getId());
            if (last != null && Objects.equals(last.getMessage(), clip(message)) && Objects.equals(last.getStage(), stage)) {
                return;
            }
        } catch (RuntimeException ex) {
            log.warn("读发布日志失败（执行 {}）：{}", e.getId(), ex.getMessage());
            return;
        }
        append(e, stage, INFO, message);
    }

    public List<PlatformAppExecutionLog> list(String appId, Integer releaseNo) {
        return logs.selectByRelease(appId, releaseNo);
    }

    private void append(PlatformAppExecution e, String stage, String level, String message) {
        if (e == null || e.getId() == null || message == null || message.isBlank()) {
            return;
        }
        try {
            PlatformAppExecutionLog row = new PlatformAppExecutionLog();
            row.setExecutionId(e.getId());
            row.setAppId(e.getAppId());
            row.setReleaseNo(e.getReleaseNo());
            row.setStage(stage);
            row.setLevel(level);
            row.setMessage(clip(message));
            row.setCreatedAt(new Date());
            logs.insert(row);
        } catch (RuntimeException ex) {
            log.warn("写发布日志失败（执行 {}）：{}", e.getId(), ex.getMessage());
        }
    }

    static String clip(String s) {
        return s.length() <= MESSAGE_MAX ? s : s.substring(0, MESSAGE_MAX - 1) + "…";
    }
}
