package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "downwardAPI 卷文件（fieldRef / resourceFieldRef 二选一）")
public class DownwardAPIVolumeFileDTO {

    @Schema(description = "目录内的文件名")
    private String path;

    @Schema(description = "权限（八进制，可选）")
    private Integer mode;

    @Schema(description = "Pod 字段引用")
    private ObjectFieldSelectorDTO fieldRef;

    @Schema(description = "容器资源引用")
    private ResourceFieldSelectorDTO resourceFieldRef;
}
