package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "emptyDir 卷配置")
public class EmptyDirVolumeDTO {

    @Schema(description = "存储介质（Memory/空）")
    private String medium;

    @Schema(description = "大小上限（quantity）")
    private String sizeLimit;

}
