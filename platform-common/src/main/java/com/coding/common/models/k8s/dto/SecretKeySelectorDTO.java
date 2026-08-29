package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Secret 键选择器")
public class SecretKeySelectorDTO {

    @Schema(description = "Secret 名称")
    private String name;

    @Schema(description = "键名")
    private String key;

    @Schema(description = "是否可选")
    private Boolean optional;

}
