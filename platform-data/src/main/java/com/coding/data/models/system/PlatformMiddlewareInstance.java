package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/** 一个中间件实例（表 platform_middleware_instance）：Operator 的 CR 或 Nacos 的 release。 */
@Data
public class PlatformMiddlewareInstance {

    private String id;

    private String clusterId;

    private String namespace;

    /** 中间件 code */
    private String code;

    /** SINGLE / CLUSTER / SENTINEL / STANDALONE / HA */
    private String mode;

    /** K8s 对象名，平台生成：mw-<code>-<8hex> */
    private String name;

    private String displayName;

    private String version;

    /** 表单规格 JSON（不含凭据） */
    private String specJson;

    /** CREATING / READY / DEGRADED / FAILED / DELETING */
    private String status;

    private String message;

    private String tenantId;

    /** 创建它的应用（画布建的实例才有）；空 = 中间件商店直接建的 */
    private String appId;

    /** 应用里的节点 key */
    private String appNodeId;

    private String createdBy;

    private Date createdAt;

    private Date updatedAt;

    private Date deletedAt;
}
