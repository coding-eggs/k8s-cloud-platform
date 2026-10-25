package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/** 一个应用（表 platform_application）：spec_json 是真相源，画布只是它的编辑器。 */
@Data
public class PlatformApplication {

    private String id;

    private String tenantId;

    private String clusterId;

    private String namespace;

    private String name;

    /** K8s 对象名前缀，由 name 归一化得到（小写、连字符、≤30） */
    private String slug;

    private String displayName;

    private String description;

    /** DRAFT / DEPLOYING / READY / FAILED / DELETING */
    private String status;

    /** 图定义（AppSpecDTO 的 JSON） */
    private String specJson;

    private Integer currentRevision;

    private String createdBy;

    private Date createdAt;

    private Date updatedAt;

    private Date deletedAt;
}
