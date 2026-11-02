package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/** 应用的一次发布（表 platform_app_release）。内容不可变，只有 status / note 可更新。 */
@Data
public class PlatformAppRelease {

    private Long id;

    private String appId;

    private Integer releaseNo;

    private String snapshotJson;

    private String objectsJson;

    private String manifest;

    /** NORMAL / ROLLBACK */
    private String type;

    /** ROLLBACK 时的来源发布号 */
    private Integer sourceReleaseNo;

    private String note;

    /** RENDERED / DEPLOYED / FAILED */
    private String status;

    private String createdBy;

    private Date createdAt;
}
