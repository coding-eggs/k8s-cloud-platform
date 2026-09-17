package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Schema(description = "容器资源定义")
public class ResourcesDTO {

    @Schema(description = "资源上限（基础单位：CPU=核数、内存=字节）")
    private Map<String, BigDecimal> limits;

    @Schema(description = "资源请求（基础单位：CPU=核数、内存=字节）")
    private Map<String, BigDecimal> requests;

}
