package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "绑定主体，定义角色绑定到的对象或用户身份")
public class SubjectDTO {

    @Schema(description = "API 组（可选）", example = "rbac.authorization.k8s.io")
    private String apiGroup;

    @Schema(description = "被引用对象的类型（User、Group 或 ServiceAccount）", example = "ServiceAccount")
    private String kind;

    @Schema(description = "被引用的对象名称", example = "default")
    private String name;

    @Schema(description = "命名空间（仅 ServiceAccount 需要）")
    private String namespace;

}