package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "容器资源定义")
public class ResourcesDTO {

    @Schema(description = "资源上限")
    private Map<String, String> limits;

    @Schema(description = "资源请求")
    private Map<String, String> requests;

}
