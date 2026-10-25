package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/**
 * 应用的一次部署操作（表 platform_application_operation）。只追加与更新状态，不删除。
 *
 * <p>{@code currentStep} 持久化的唯一目的：platform-api 重启后能从断点续跑，
 * 而不是把操作永远留在 RUNNING。
 */
@Data
public class PlatformApplicationOperation {

    private Long id;

    private String appId;

    private Integer revision;

    /** DEPLOY / UPGRADE / DELETE / RETRY */
    private String action;

    /** PENDING / RUNNING / SUCCESS / FAILED */
    private String status;

    /** PRECHECK / RENDER / CONFLICT / APPLY / PRUNE / HEALTH / DONE */
    private String currentStep;

    /** {节点key: 状态} 的 JSON，供画布回显 */
    private String nodeStatesJson;

    private String message;

    private Date startedAt;

    private Date finishedAt;

    private String createdBy;
}
