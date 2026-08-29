package com.coding.platformapi.models;

import com.coding.common.models.k8s.dto.PolicyRuleDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "创建 RBAC 模板请求")
public class TemplateCreateRequest {

    @Schema(description = "模板名（K8s ClusterRole 裸名，小写字母/数字/-，≤55 字符）", requiredMode = Schema.RequiredMode.REQUIRED, example = "tenant-readonly")
    private String name;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "权限规则列表", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<PolicyRuleDTO> rules;
}
