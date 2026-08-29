package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "ConfigMap 引用")
public class ConfigMapRefDTO {

    @Schema(description = "ConfigMap 名称")
    private String name;

    @Schema(description = "是否可选")
    private Boolean optional;

}
