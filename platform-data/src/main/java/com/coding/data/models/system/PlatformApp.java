package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/** 一个应用实例（表 platform_app）。release 名由平台生成，与 chart 名无关。 */
@Data
public class PlatformApp {

    private String id;

    private String clusterId;

    private String namespace;

    /** 平台生成：app-<8 hex> */
    private String releaseName;

    /** 上架条目 id；空 = 管理员直装 */
    private String catalogId;

    /** HARBOR / REPO */
    private String source;

    private String chartRef;

    private String chartName;

    private String version;

    private String digest;

    private String valuesJson;

    /** APP / OPERATOR */
    private String appKind;

    private String displayName;

    /** INSTALLING / RUNNING / FAILED / UNINSTALLED */
    private String status;

    /** 装后复核发现越界对象时为 true（详情页标红） */
    private Boolean needsReview;

    private String tenantId;

    private String createdBy;

    private Date createdAt;

    private Date updatedAt;

    private Date deletedAt;
}
