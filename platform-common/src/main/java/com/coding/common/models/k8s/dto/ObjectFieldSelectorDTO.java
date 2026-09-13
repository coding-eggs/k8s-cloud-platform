package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Pod 字段选择器（fieldRef）")
public class ObjectFieldSelectorDTO {

    @Schema(description = "API 版本（默认 v1，可选）")
    private String apiVersion;

    @Schema(description = "字段路径（如 metadata.name、status.podIP）")
    private String fieldPath;
}
