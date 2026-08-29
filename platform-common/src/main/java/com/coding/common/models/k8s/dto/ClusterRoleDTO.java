package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "K8s ClusterRole 资源定义，集群级别的 RBAC 角色")
public class ClusterRoleDTO extends BaseResources {

    @Schema(description = "权限规则列表，定义对该角色的访问策略", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<PolicyRuleDTO> rules;

    @Override
    public String getApiPath() {
        return "/admin/clusterroles";
    }

}