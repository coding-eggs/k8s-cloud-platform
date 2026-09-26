package com.coding.data.models.auth;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * platform_user_tenant_role：用户在某租户内被授予的角色（租户域授权权威表）
 */
@Data
public class PlatformUserTenantRole implements Serializable {

    private String id;

    private String userId;

    private String tenantId;

    private String roleId;

    private Date createdAt;

    private static final long serialVersionUID = 1L;
}
