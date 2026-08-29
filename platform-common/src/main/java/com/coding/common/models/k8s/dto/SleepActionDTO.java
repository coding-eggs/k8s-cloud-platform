package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Sleep 动作")
public class SleepActionDTO {

    @Schema(description = "休眠时长（秒）")
    private Long seconds;

}
