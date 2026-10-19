package com.coding.platformapi.notify;

import com.coding.data.mapper.alert.AlertDeliveryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Date;

/**
 * 启动兜底：把**中断在 PENDING** 的投递标成 FAILED。
 *
 * <p>场景：发送途中进程重启。投递记录的唯一键同时是去重键，所以那一行永远占着位 ——
 * Alertmanager 之后每一次重推都会被 `insertIfAbsent=0` 挡掉，这条告警的邮件**永久丢失**，
 * 而列表一直显示「发送中」。标成 FAILED 至少让「没发出去」看得见、能查原因。
 *
 * <p>为什么留 5 分钟宽限：多副本部署时另一个实例可能正在发（虽然当前是单副本）。
 * 只清「早于 5 分钟还是 PENDING」的行，不会打断别人的在途投递。
 *
 * <p>完整的「重新入队」需要按 event_id 回查事件并重发，本轮不做（见 plan Task 11 的裁定）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertDeliveryRecovery implements ApplicationRunner {

    static final Duration STALE_AFTER = Duration.ofMinutes(5);
    static final String STALE_ERROR = "应用重启，投递中断";

    private final AlertDeliveryMapper deliveries;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Date before = new Date(System.currentTimeMillis() - STALE_AFTER.toMillis());
            int n = deliveries.failStalePending(before, STALE_ERROR);
            if (n > 0) {
                log.warn("清理了 {} 条中断的告警投递记录（PENDING → FAILED）", n);
            }
        } catch (Exception e) {
            // 兜底失败不能拦住启动
            log.warn("清理中断的告警投递记录失败: {}", e.getMessage());
        }
    }
}
