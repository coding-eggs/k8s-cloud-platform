package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "节点选择要求")
public class NodeSelectorRequirementDTO {

    @Schema(description = "键名")
    private String key;

    @Schema(description = "操作符（In/NotIn/Exists/DoesNotExist/Gt/Lt）")
    private String operator;

    @Schema(description = "值列表")
    private List<String> values;

}
