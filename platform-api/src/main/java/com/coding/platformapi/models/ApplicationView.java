package com.coding.platformapi.models;

import lombok.Data;

import java.util.Date;

/** 应用的对外视图。 */
@Data
public class ApplicationView {

    private String id;

    private String clusterId;

    private String namespace;

    private String name;

    private String slug;

    private String displayName;

    private String description;

    private String status;

    /** 旧画布的版本号，已不再使用；新模型看 liveReleaseNo */
    private Integer currentRevision;

    /** 线上发布号；0 或空 = 尚未发布 */
    private Integer liveReleaseNo;

    private Date createdAt;

    private Date updatedAt;
}
