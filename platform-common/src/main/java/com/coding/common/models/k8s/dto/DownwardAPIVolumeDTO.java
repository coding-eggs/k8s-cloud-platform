package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "downwardAPI 卷配置（把 Pod 字段 / 容器资源暴露为文件）")
public class DownwardAPIVolumeDTO {

    @Schema(description = "默认权限（八进制，如 644；可选）")
    private Integer defaultMode;

    @Schema(description = "文件列表")
    private List<DownwardAPIVolumeFileDTO> items;
}
