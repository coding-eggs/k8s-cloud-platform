package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "环境变量值来源")
public class ValueFromDTO {

    @Schema(description = "ConfigMap 键引用")
    private ConfigMapKeySelectorDTO configMapKeyRef;

    @Schema(description = "Secret 键引用")
    private SecretKeySelectorDTO secretKeyRef;

    @Schema(description = "Pod 字段引用")
    private ObjectFieldSelectorDTO fieldRef;

}
