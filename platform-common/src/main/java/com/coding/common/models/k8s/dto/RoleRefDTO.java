package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "角色引用，指向 Role 或 ClusterRole")
public class RoleRefDTO {

    @Schema(description = "API 组", example = "rbac.authorization.k8s.io")
    private String apiGroup;

    @Schema(description = "被引用的资源类型（Role 或 ClusterRole）", example = "ClusterRole")
    private String kind;

    @Schema(description = "被引用的角色名称", example = "admin")
    private String name;

}