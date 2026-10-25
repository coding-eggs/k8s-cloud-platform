package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/** 应用对平台对象的引用（表 platform_application_reference）：删除守卫的反查表。 */
@Data
public class PlatformApplicationReference {

    private Long id;

    private String appId;

    /** 应用里的节点 key */
    private String nodeId;

    /** MIDDLEWARE_INSTANCE / SECRET */
    private String refType;

    private String refId;

    private Date createdAt;
}
