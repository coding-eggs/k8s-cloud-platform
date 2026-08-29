package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Pod 字段选择器")
public class ObjectFieldSelectorDTO {

    @Schema(description = "API 版本")
    private String apiVersion;

    @Schema(description = "字段路径")
    private String fieldPath;

}
