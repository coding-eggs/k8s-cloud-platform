package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "ServiceAccount Token 投影配置")
public class ServiceAccountTokenProjectionDTO {

    @Schema(description = "audience（可选）")
    private String audience;

    @Schema(description = "过期秒数（可选）")
    private Long expirationSeconds;

    @Schema(description = "投影到目录内的文件名")
    private String path;
}
