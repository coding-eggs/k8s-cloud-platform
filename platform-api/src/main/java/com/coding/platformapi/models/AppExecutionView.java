package com.coding.platformapi.models;

import java.util.Date;

/** 发布执行的进度。 */
public record AppExecutionView(Long id, String appId, Integer releaseNo, String status, String stage,
                               String message, String startedBy, Date startedAt, Date finishedAt,
                               /** 所属发布的类型：NORMAL / ROLLBACK / OFFLINE */
                               String releaseType) {
}
