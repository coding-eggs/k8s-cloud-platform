package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "节点选择条件项")
public class NodeSelectorTermDTO {

    @Schema(description = "标签匹配表达式列表")
    private List<NodeSelectorRequirementDTO> matchExpressions;

    @Schema(description = "字段匹配表达式列表")
    private List<NodeSelectorRequirementDTO> matchFields;

}
