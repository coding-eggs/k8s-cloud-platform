package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "容忍度定义")
public class TolerationDTO {

    @Schema(description = "污点键名")
    private String key;

    @Schema(description = "操作符（Equal/Exists）")
    private String operator;

    @Schema(description = "污点值")
    private String value;

    @Schema(description = "效果（NoSchedule/PreferNoSchedule/NoExecute）")
    private String effect;

    @Schema(description = "容忍时长（秒，仅 NoExecute）")
    private Long tolerationSeconds;

}
