package com.coding.data.models.alert;

import lombok.Data;

import java.util.Date;

/** 通知方式（表 platform_notification_channel）。config 是 JSON 串，敏感字段已加密。 */
@Data
public class NotificationChannel {
    private String id;
    /** 写进 label，K8s label 合法，全局唯一 */
    private String code;
    private String name;
    /** EMAIL（本轮唯一） */
    private String type;
    /** JSON 串 */
    private String config;
    /** null = 全局；非空 = 该租户专用 */
    private String tenantId;
    private Boolean isDefault;
    private Boolean enabled;
    private Date createdAt;
    private Date updatedAt;
}
