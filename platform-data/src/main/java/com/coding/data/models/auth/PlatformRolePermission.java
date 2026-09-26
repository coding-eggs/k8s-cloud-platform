package com.coding.data.models.auth;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * platform_role_permission：角色↔权限点关联
 */
@Data
public class PlatformRolePermission implements Serializable {

    private String id;

    private String roleId;

    private String permissionId;

    private Date createdAt;

    private static final long serialVersionUID = 1L;
}
