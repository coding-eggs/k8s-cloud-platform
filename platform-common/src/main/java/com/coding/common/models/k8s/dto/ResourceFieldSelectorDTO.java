package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "容器资源选择器（resourceFieldRef）")
public class ResourceFieldSelectorDTO {

    @Schema(description = "容器名（默认主容器，可选）")
    private String containerName;

    @Schema(description = "除数（基础单位数值，如 0.001；可选）")
    private BigDecimal divisor;

    @Schema(description = "资源类型（limits.cpu / limits.memory / requests.cpu / requests.memory）")
    private String resource;
}
