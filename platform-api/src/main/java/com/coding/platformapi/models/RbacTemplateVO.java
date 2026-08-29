package com.coding.platformapi.models;

import com.coding.common.models.k8s.dto.PolicyRuleDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
@Schema(description = "RBAC 模板视图（rules 已解析为结构化规则）")
public class RbacTemplateVO {

    @Schema(description = "模板id")
    private String id;

    @Schema(description = "模板名")
    private String name;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "权限规则列表")
    private List<PolicyRuleDTO> rules;

    @Schema(description = "是否内置：0 否，1 是")
    private Integer builtIn;

    @Schema(description = "创建时间")
    private Date createdAt;

    @Schema(description = "更新时间")
    private Date updatedAt;
}
