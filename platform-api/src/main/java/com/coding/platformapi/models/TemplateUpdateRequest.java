package com.coding.platformapi.models;

import com.coding.common.models.k8s.dto.PolicyRuleDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "更新 RBAC 模板请求（内置模板不可修改；名称创建后不可改）")
public class TemplateUpdateRequest {

    @Schema(description = "模板id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String id;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "权限规则列表（传则整体替换，并同步各集群 ClusterRole）")
    private List<PolicyRuleDTO> rules;
}
