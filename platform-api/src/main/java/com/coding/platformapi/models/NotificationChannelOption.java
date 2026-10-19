package com.coding.platformapi.models;

/**
 * 规则编辑页的通知方式候选（**窄投影**）。
 *
 * <p>刻意不带 {@code config}：SMTP 的地址/端口/发件账号是平台运维信息，
 * {@code /notification-channels/options} 是租户管理员也能调的端点。
 */
public record NotificationChannelOption(String code, String name, Boolean isDefault, String tenantId) {
}
