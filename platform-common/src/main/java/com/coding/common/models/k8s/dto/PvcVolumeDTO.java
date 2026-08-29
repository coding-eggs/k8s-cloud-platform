package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "persistentVolumeClaim 卷配置")
public class PvcVolumeDTO {

    @Schema(description = "PVC 名称")
    private String claimName;

}
