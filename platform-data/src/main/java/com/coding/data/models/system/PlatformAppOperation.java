package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/** 一次应用安装/升级/卸载操作（表 platform_app_operation）。 */
@Data
public class PlatformAppOperation {

    private Long id;

    private String clusterId;

    private String appId;

    /** INSTALL / UPGRADE / UNINSTALL */
    private String action;

    private String fromVersion;

    private String toVersion;

    private String valuesSnapshot;

    /** PENDING / RUNNING / SUCCESS / FAILED / UNKNOWN */
    private String status;

    private String message;

    private String logTail;

    private String operator;

    private Date startedAt;

    private Date finishedAt;
}
