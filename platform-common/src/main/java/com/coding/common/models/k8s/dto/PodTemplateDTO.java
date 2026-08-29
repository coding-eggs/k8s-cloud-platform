package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "Pod 模板（metadata + spec）")
public class PodTemplateDTO {

    @Schema(description = "标签")
    private Map<String, String> labels;

    @Schema(description = "注解")
    private Map<String, Object> annotations;

    @Schema(description = "Pod 规格（完整 PodSpec）")
    private PodSpecDTO spec;

}
