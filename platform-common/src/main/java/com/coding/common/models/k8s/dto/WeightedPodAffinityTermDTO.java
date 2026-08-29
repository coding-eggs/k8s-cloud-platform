package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "带权重的 Pod 亲和性条件项")
public class WeightedPodAffinityTermDTO {

    @Schema(description = "权重（1-100）")
    private Integer weight;

    @Schema(description = "Pod 亲和性条件")
    private PodAffinityTermDTO podAffinityTerm;

}
