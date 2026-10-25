package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/** 应用的不可变版本快照（表 platform_application_revision）。 */
@Data
public class PlatformApplicationRevision {

    private Long id;

    private String appId;

    private Integer revision;

    private String specJson;

    /** 解析后的注入上下文（不含任何密码明文） */
    private String resolvedJson;

    /** 渲染出的多文档 YAML；续跑时直接复用，不重复渲染 */
    private String manifest;

    /** RENDERED / DEPLOYED / FAILED */
    private String status;

    private String message;

    private String createdBy;

    private Date createdAt;
}
