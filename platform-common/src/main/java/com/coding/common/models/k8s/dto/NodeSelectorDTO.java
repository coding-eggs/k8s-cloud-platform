package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "节点选择器（requiredDuringScheduling）")
public class NodeSelectorDTO {

    @Schema(description = "节点选择条件项列表")
    private List<NodeSelectorTermDTO> nodeSelectorTerms;

}
