package com.coding.data.models.auth;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 租户命名空间分配表
 * platform_tenant_namespace
 */
@Data
public class PlatformTenantNamespace implements Serializable {
    private String id;

    private String tenantId;

    private String clusterId;

    private String namespace;

    /**
     * 该分配使用的 RBAC 模板id（决定 RoleBinding 引用的 ClusterRole）
     */
    private String roleTemplateId;

    private Date createTime;

    private static final long serialVersionUID = 1L;
}
