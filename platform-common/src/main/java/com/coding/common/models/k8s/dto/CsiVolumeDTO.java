package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "CSI 卷配置")
public class CsiVolumeDTO {

    @Schema(description = "CSI 驱动名（必填）")
    private String driver;

    @Schema(description = "只读挂载（可选）")
    private Boolean readOnly;

    @Schema(description = "文件系统类型（如 ext4，可选）")
    private String fsType;

    @Schema(description = "卷属性键值对（可选）")
    private Map<String, String> volumeAttributes;

    @Schema(description = "节点发布 Secret 引用（可选）")
    private LocalObjectReferenceDTO nodePublishSecretRef;
}
