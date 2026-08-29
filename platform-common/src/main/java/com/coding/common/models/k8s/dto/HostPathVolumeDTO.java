package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "hostPath 卷配置")
public class HostPathVolumeDTO {

    @Schema(description = "节点上的路径")
    private String path;

    @Schema(description = "路径类型（Directory/DirectoryOrCreate/File 等）")
    private String type;

}
