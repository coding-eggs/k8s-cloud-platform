package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** {@code POST /cluster/components/apply} 的请求体。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ComponentApplyRequest {
    private String clusterId;
    private String componentCode;
    /** INSTALL / UPGRADE */
    private String action;
    /** 目标 recipe 版本（空 = 用 recipe 的当前版本） */
    private String targetVersion;
    /** 用户可编辑的 values（只允许 recipe 声明的字段） */
    private Map<String, Object> editableValues;
    /**
     * 平台告警回调地址（仅 platformAlerting 的组件用，如 Prometheus）。
     * 用户在建/升级时填写；clusterId 由后端拼接。空 = 用平台配置的默认值。
     */
    private String alertWebhookUrl;

    /** 兼容旧调用（不含告警地址） */
    public ComponentApplyRequest(String clusterId, String componentCode, String action,
                                 String targetVersion, Map<String, Object> editableValues) {
        this(clusterId, componentCode, action, targetVersion, editableValues, null);
    }
}
