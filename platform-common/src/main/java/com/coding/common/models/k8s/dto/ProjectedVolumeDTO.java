package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "projected 卷配置（把多个来源投影到同一目录）")
public class ProjectedVolumeDTO {

    @Schema(description = "默认权限（八进制，如 644；可选）")
    private Integer defaultMode;

    @Schema(description = "投影来源列表")
    private List<ProjectedSourceDTO> sources;
}
