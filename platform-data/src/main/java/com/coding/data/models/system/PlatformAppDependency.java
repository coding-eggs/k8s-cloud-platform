package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/** 应用的一个依赖（表 platform_app_dependency）。 */
@Data
public class PlatformAppDependency {

    private Long id;

    private String appId;

    private String depKey;

    /** MIDDLEWARE / EXTERNAL */
    private String type;

    /** CREATED / BOUND（仅 MIDDLEWARE） */
    private String ownership;

    private String instanceId;

    /** EXTERNAL 的连接信息 JSON，不含密码明文 */
    private String externalJson;

    private String initHash;

    /** PENDING / RUNNING / SUCCESS / FAILED */
    private String initStatus;

    private String initLog;

    private Date createdAt;

    private Date updatedAt;
}
