package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 资源量对（cpu + memory），基础单位：cpu=核数、memory=字节。
 * 用于 LimitRange 的 max/min/default/defaultRequest/maxLimitRequestRatio。
 * 字段为 null = 该项未设置（converter 不写入该 key；overlay 语义下 null = 删除）。
 */
@Data
@Schema(description = "资源量对（cpu 核 / memory 字节，基础单位）")
public class ResourcePairDTO {

    @Schema(description = "CPU 核数，如 0.5 = 500m")
    private BigDecimal cpu;

    @Schema(description = "内存字节数，如 536870912 = 512Mi")
    private BigDecimal memory;
}
