package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "节点调度偏好项")
public class PreferredSchedulingTermDTO {

    @Schema(description = "权重（1-100）")
    private Integer weight;

    @Schema(description = "偏好条件")
    private NodeSelectorTermDTO preference;

}
