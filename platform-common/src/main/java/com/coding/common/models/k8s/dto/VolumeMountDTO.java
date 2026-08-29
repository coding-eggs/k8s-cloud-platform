package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "卷挂载定义")
public class VolumeMountDTO {

    @Schema(description = "卷名称")
    private String name;

    @Schema(description = "容器内挂载路径")
    private String mountPath;

    @Schema(description = "是否只读")
    private Boolean readOnly;

    @Schema(description = "卷内子路径")
    private String subPath;

}
