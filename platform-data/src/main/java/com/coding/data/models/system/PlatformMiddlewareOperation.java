package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/** 中间件实例的一次操作（表 platform_middleware_operation）。只追加与更新状态，不删除。 */
@Data
public class PlatformMiddlewareOperation {

    private Long id;

    private String clusterId;

    private String instanceId;

    /** CREATE / DELETE / SCALE / UPGRADE */
    private String action;

    /** PENDING / RUNNING / SUCCESS / FAILED / UNKNOWN */
    private String status;

    private String message;

    private String logTail;

    private String operator;

    private Date startedAt;

    private Date finishedAt;
}
