package com.coding.platformapi.models;

import lombok.Data;

import java.util.Date;

/** 管理员看到的「租户镜像项目」一行（Harbor 组件面板）。 */
@Data
public class RegistryProjectView {
    private String id;
    private String clusterId;
    private String tenantId;
    private String tenantName;
    private String host;
    private String projectName;
    private String credentialId;
    /** READY / ERROR */
    private String status;
    private String message;
    private Date lastSyncedAt;
}
