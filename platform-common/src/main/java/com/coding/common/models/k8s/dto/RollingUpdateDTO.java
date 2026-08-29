package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "滚动更新参数")
public class RollingUpdateDTO {

    @Schema(description = "最大超出副本数（quantity，仅 Deployment）")
    private String maxSurge;

    @Schema(description = "最大不可用副本数（quantity）")
    private String maxUnavailable;

    @Schema(description = "分区值（仅 StatefulSet）")
    private Integer partition;

}
