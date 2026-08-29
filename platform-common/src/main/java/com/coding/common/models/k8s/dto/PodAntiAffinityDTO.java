package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Pod 反亲和性")
public class PodAntiAffinityDTO {

    @Schema(description = "必须满足的反亲和条件")
    private List<PodAffinityTermDTO> required;

    @Schema(description = "尽量满足的偏好反亲和条件")
    private List<WeightedPodAffinityTermDTO> preferred;

}
