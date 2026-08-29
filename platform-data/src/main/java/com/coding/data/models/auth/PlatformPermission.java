package com.coding.data.models.auth;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * platform_permission
 */
@Data
public class PlatformPermission implements Serializable {

    private String id;

    /**
     * 权限域：K8S / Page / API 
     */
    private String domain;

    /**
     * 资源：Deployment... / Page / API
     */
    private String resource;

    /**
     * 动作：create/update/delete/view/execute
     */
    private String action;

    /**
     * 唯一标识
     */
    private String code;

    private String description;

    private Date createdAt;

    /**
     * 软删除时间，NULL = 未删除
     */
    private Date deletedAt;

    private static final long serialVersionUID = 1L;
}