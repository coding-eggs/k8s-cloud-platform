package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "projected 卷的 DownwardAPI 投影配置")
public class DownwardAPIProjectionDTO {

    @Schema(description = "文件列表")
    private List<DownwardAPIVolumeFileDTO> items;
}
