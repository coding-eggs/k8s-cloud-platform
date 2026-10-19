package com.coding.platformapi.notify;

import com.coding.data.models.alert.NotificationChannel;
import com.coding.data.models.k8s.PlatformAlertEvent;

import java.util.List;

/**
 * 一种通知方式的发送实现（本轮只有 {@link EmailSender}）。
 *
 * <p>加新类型（短信 / 企微机器人 / webhook）= 新增一个实现 bean，调度处按 {@code channel.type} 选；
 * 表结构与规则侧契约都不动。
 */
public interface NotificationSender {

    /** 与 {@code platform_notification_channel.type} 对得上（EMAIL / SMS / WECHAT_WORK ...） */
    String type();

    /**
     * 发一批告警给这批收件人。
     *
     * @param recipients 已经过滤掉空值、去重过的地址列表（**可能是空**，此时实现应抛错而不是静默成功）
     * @throws Exception 任何发送失败 —— 调用方按它决定重试
     */
    void send(NotificationChannel channel, List<String> recipients, List<PlatformAlertEvent> events)
            throws Exception;
}
