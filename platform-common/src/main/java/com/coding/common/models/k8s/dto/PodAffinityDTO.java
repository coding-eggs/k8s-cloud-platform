package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Pod 亲和性")
public class PodAffinityDTO {

    @Schema(description = "必须满足的亲和条件")
    private List<PodAffinityTermDTO> required;

    @Schema(description = "尽量满足的偏好亲和条件")
    private List<WeightedPodAffinityTermDTO> preferred;

}
