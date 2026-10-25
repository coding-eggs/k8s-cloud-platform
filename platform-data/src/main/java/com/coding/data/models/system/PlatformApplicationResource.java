package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/** 应用创建的资源台账（表 platform_application_resource）：prune 的差集与资源页反查都靠它。 */
@Data
public class PlatformApplicationResource {

    private Long id;

    private String appId;

    private Integer revision;

    private String apiVersion;

    private String kind;

    private String namespace;

    private String name;

    private String nodeKey;

    private String objectUid;

    private Date createdAt;
}
