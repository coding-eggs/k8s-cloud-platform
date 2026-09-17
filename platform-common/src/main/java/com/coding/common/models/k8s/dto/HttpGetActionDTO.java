package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "HTTP GET 动作")
public class HttpGetActionDTO {

    @Schema(description = "端口（IntOrString，命名端口用 integer）")
    private Integer port;

    @Schema(description = "请求路径")
    private String path;

    @Schema(description = "协议方案（HTTP/HTTPS）")
    private String scheme;

}
