package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/**
 * 一个应用（表 platform_application）。
 *
 * <p>服务、依赖、发布分别在 platform_app_service / _dependency / _release。
 */
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

    /** 线上发布号；0 = 尚未发布（发布模型，见 platform_app_release） */
    private Integer liveReleaseNo;

    private String createdBy;

    private Date createdAt;

    private Date updatedAt;

    private Date deletedAt;
}
