package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "TCP Socket 动作")
public class TCPSocketActionDTO {

    @Schema(description = "端口（IntOrString，命名端口用 Integer）")
    private Integer port;

}
