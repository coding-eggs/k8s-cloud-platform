package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "NFS 卷配置")
public class NfsVolumeDTO {

    @Schema(description = "NFS 服务器地址（必填）")
    private String server;

    @Schema(description = "挂载路径")
    private String path;

    @Schema(description = "只读挂载（可选）")
    private Boolean readOnly;
}
