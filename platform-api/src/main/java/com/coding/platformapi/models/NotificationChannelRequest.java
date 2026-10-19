package com.coding.platformapi.models;

import lombok.Data;

import java.util.Map;

/** 通知方式的新建/修改请求。config 里密码是**明文**（service 负责加密）。 */
@Data
public class NotificationChannelRequest {
    private String code;
    private String name;
    /** EMAIL（本轮唯一） */
    private String type;
    private Map<String, String> config;
    /** null = 全局 */
    private String tenantId;
    private Boolean isDefault;
    private Boolean enabled;
}
