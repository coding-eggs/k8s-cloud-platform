package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "本地对象引用（按名称，如 Secret / ConfigMap）")
public class LocalObjectReferenceDTO {

    @Schema(description = "对象名称")
    private String name;
}
