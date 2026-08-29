package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "secret 卷配置")
public class SecretVolumeDTO {

    @Schema(description = "Secret 名称")
    private String secretName;

    @Schema(description = "键值映射列表")
    private List<KeyToPathDTO> items;

}
