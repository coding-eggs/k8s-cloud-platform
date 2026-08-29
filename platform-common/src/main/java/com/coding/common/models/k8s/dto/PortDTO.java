package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "K8s 端口定义")
public class PortDTO {

    @Schema(description = "容器端口号", example = "8080")
    private Integer containerPort;

}
