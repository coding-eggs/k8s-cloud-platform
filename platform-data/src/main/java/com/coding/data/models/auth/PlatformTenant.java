package com.coding.data.models.auth;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * platform_tenant
 */
@Data
public class PlatformTenant implements Serializable {
    private String id;

    private String name;

    /**
     * 租户标识
     */
    private String serviceAccount;

    private Byte status;

    private Date createdAt;

    private Date updatedAt;

    /**
     * 软删除时间，NULL = 未删除
     */
    private Date deletedAt;

    private static final long serialVersionUID = 1L;
}