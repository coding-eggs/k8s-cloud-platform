package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "亲和性定义")
public class AffinityDTO {

    @Schema(description = "节点亲和性")
    private NodeAffinityDTO nodeAffinity;

    @Schema(description = "Pod 反亲和性")
    private PodAntiAffinityDTO podAntiAffinity;

}
