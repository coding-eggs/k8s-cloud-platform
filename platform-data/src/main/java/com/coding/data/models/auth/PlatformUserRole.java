package com.coding.data.models.auth;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * platform_user_role：用户↔平台域角色授予（无租户维度）
 */
@Data
public class PlatformUserRole implements Serializable {

    private String id;

    private String userId;

    private String roleId;

    private Date createdAt;

    private static final long serialVersionUID = 1L;
}
