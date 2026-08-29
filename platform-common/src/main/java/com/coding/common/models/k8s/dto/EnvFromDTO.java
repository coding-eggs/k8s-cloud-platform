package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "环境变量来源定义")
public class EnvFromDTO {

    @Schema(description = "变量名前缀")
    private String prefix;

    @Schema(description = "ConfigMap 引用")
    private ConfigMapRefDTO configMapRef;

    @Schema(description = "Secret 引用")
    private SecretRefDTO secretRef;

}
