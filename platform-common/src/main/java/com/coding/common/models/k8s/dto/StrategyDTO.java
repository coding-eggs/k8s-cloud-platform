package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "更新策略（kind 感知）")
public class StrategyDTO {

    @Schema(description = "策略类型（Dep: RollingUpdate/Recreate；STS/DS: RollingUpdate/OnDelete）")
    private String type;

    @Schema(description = "滚动更新参数")
    private RollingUpdateDTO rollingUpdate;

}
