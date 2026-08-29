package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "K8s 环境变量定义")
public class EnvDTO {

    @Schema(description = "环境变量名称", example = "SPRING_PROFILES_ACTIVE")
    private String name;

    @Schema(description = "环境变量值", example = "prod")
    private String value;

}
