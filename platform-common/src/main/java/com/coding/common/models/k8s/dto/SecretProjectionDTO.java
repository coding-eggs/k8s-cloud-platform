package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "projected 卷的 Secret 投影配置")
public class SecretProjectionDTO {

    @Schema(description = "Secret 名称")
    private String name;

    @Schema(description = "键值映射列表（可选）")
    private List<KeyToPathDTO> items;

    @Schema(description = "是否可选（缺失不报错，可选）")
    private Boolean optional;
}
