package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/** 租户在某个 Harbor 上的镜像项目台账（表 platform_registry_project）。 */
@Data
public class PlatformRegistryProject {

    private String id;

    private String clusterId;

    /** null = 平台共享项目（platform） */
    private String tenantId;

    /** host[:port]，不带 scheme */
    private String host;

    private String projectName;

    private String credentialId;

    /** Harbor 机器人账号 id */
    private Long robotId;

    /** READY / ERROR */
    private String status;

    private String message;

    private Date lastSyncedAt;

    private Date createdAt;

    private Date updatedAt;
}
