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
}
