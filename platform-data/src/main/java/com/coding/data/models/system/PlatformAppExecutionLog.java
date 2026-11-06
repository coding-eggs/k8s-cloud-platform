package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/**
 * 发布执行的过程日志（表 platform_app_execution_log）：每个阶段做了什么、健康检查卡在哪。
 *
 * <p>按 (app_id, release_no) 查：一条发布可能被「继续」多次，但执行行只有一条，日志都挂在它下面。
 */
@Data
public class PlatformAppExecutionLog {

    private Long id;

    private Long executionId;

    private String appId;

    private Integer releaseNo;

    /** 写日志时所处的阶段（PRECHECK / RENDER / … / DONE） */
    private String stage;

    /** INFO / WARN / ERROR */
    private String level;

    private String message;

    private Date createdAt;
}
