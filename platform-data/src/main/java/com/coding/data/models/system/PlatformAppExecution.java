package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/**
 * 一次发布的执行（表 platform_app_execution）。
 *
 * <p>PENDING / RUNNING / INTERRUPTED 占用「每个应用一条活动执行」的唯一位；FAILED 不占。
 */
@Data
public class PlatformAppExecution {

    private Long id;

    private String appId;

    private Integer releaseNo;

    /** PENDING / RUNNING / INTERRUPTED / FAILED / SUCCESS */
    private String status;

    private String stage;

    private String message;

    /** 执行者租约；为空或已过期才可被接管 */
    private Date leaseUntil;

    private String startedBy;

    private Date startedAt;

    private Date finishedAt;
}
