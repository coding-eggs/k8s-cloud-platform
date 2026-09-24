package com.coding.data.models.auth;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * platform_user_tenant：用户↔租户成员资格（无角色）
 */
@Data
public class PlatformUserTenant implements Serializable {

    private String id;

    private String userId;

    private String tenantId;

    private Date createdAt;

    private static final long serialVersionUID = 1L;
}
