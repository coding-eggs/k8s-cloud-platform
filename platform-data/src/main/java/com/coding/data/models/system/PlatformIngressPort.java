package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/** 应用网关端口池的一次分配（表 platform_ingress_port）。(clusterId, port) 唯一。 */
@Data
public class PlatformIngressPort {

    public static final String ACTIVE = "ACTIVE";
    public static final String RELEASED = "RELEASED";

    private Long id;

    private String clusterId;

    private Integer port;

    private String namespace;

    private String appId;

    private String serviceKey;

    /** HTTP / TCP */
    private String protocol;

    /** ACTIVE / RELEASED */
    private String status;

    private Date releasedAt;

    private Date createdAt;

    private Date updatedAt;

    /** reactivate 用：只复用释放时间早于它的行（冷却期判定放进 SQL，避免读后改的竞态） */
    private Date cooldownCutoff;
}
