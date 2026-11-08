package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/**
 * 中间件端点的对外暴露方式（表 platform_middleware_expose）。(instanceId, endpointKey) 唯一；不暴露 = 无行。
 * 网关方式的端口在 platform_ingress_port（owner_kind=MIDDLEWARE），这里只记方式；NodePort 方式的端口记在 nodePort。
 */
@Data
public class PlatformMiddlewareExpose {

    public static final String GATEWAY = "GATEWAY";
    public static final String NODEPORT = "NODEPORT";

    private Long id;

    private String instanceId;

    private String endpointKey;

    /** GATEWAY / NODEPORT */
    private String mode;

    /** NODEPORT 时平台分配的端口，之后不变 */
    private Integer nodePort;

    private Date createdAt;

    private Date updatedAt;
}
