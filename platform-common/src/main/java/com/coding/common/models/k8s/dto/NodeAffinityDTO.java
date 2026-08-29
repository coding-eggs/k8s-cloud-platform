package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "节点亲和性")
public class NodeAffinityDTO {

    @Schema(description = "必须满足的节点选择条件")
    private NodeSelectorDTO required;

    @Schema(description = "尽量满足的偏好条件")
    private List<PreferredSchedulingTermDTO> preferred;

}
