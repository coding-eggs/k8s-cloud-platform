package com.coding.common.models.k8s.dto.admin;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 资源管理上下文级联数据（platform-web 顶栏 chip：租户 → 集群 → 命名空间）
 * 全部来自平台 DB（分配表），不查 K8s
 */
@Data
public class ResourceContextDTO {

    private List<TenantNode> tenants = new ArrayList<>();

    @Data
    public static class TenantNode {
        private String tenantId;
        private String name;
        /** 1=启用 0=停用（停用的租户不展示其集群） */
        private Integer status;
        private List<ClusterNode> clusters = new ArrayList<>();
    }

    @Data
    public static class ClusterNode {
        private String clusterId;
        private String clusterName;
        private List<String> namespaces = new ArrayList<>();
    }

}
