package com.coding.data.models.k8s;

import java.sql.Date;

import lombok.Data;

/**
 * RBAC 模板表
 * platform_rbac_template
 */
@Data
public class PlatformRbacTemplate {
    /**
     * 主键id
     */
    private String id;

    /**
     * 模板名（裸名，K8s ClusterRole 名 = tn-tpl- + name）
     */
    private String name;

    /**
     * 描述
     */
    private String description;

    /**
     * 权限规则列表，JSON 文本（List&lt;PolicyRuleDTO&gt; 结构）
     */
    private String rules;

    /**
     * 是否内置：0 否，1 是（内置模板不可修改/删除）
     */
    private int builtIn;

    /**
     * 创建时间
     */
    private Date createdAt;

    /**
     * 更新时间
     */
    private Date updatedAt;

    /**
     * 软删除时间，NULL = 未删除
     */
    private Date deletedAt;
}
