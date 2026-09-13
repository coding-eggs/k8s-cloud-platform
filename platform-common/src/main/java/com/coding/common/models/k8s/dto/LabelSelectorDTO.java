package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Schema(description = "标签选择器（matchLabels 与 matchExpressions 为 AND 关系）")
public class LabelSelectorDTO {

    @Schema(description = "等值匹配标签")
    private Map<String, String> matchLabels;

    @Schema(description = "集合匹配表达式")
    private List<NodeSelectorRequirementDTO> matchExpressions;

}
