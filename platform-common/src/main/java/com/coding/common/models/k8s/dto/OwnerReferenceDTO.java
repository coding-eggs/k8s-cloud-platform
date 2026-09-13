package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 属主引用（metadata.ownerReferences 单项）。工作负载由 Operator/控制器创建时才有，
 * 非空即视为「op 管理」→ 前端禁用编辑并展示管理方。
 */
@Data
@Schema(description = "属主引用（OwnerReference）")
public class OwnerReferenceDTO {

    @Schema(description = "属主 API 组/版本", example = "argoproj.io/v1alpha1")
    private String apiVersion;

    @Schema(description = "属主 Kind", example = "Application")
    private String kind;

    @Schema(description = "属主名称")
    private String name;

    @Schema(description = "是否为控制器（controller: true 表示该属主负责垃圾回收）")
    private Boolean controller;

}
