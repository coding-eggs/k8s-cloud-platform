package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "StatefulSet 卷声明模板")
public class PvcTemplateDTO {

    @Schema(description = "PVC 名称")
    private String name;

    @Schema(description = "访问模式（ReadWriteOnce/ReadOnlyMany/ReadWriteMany/ReadWriteOncePod）")
    private List<String> accessModes;

    @Schema(description = "存储容量（quantity）")
    private String storage;

    @Schema(description = "存储类名称")
    private String storageClassName;

    @Schema(description = "卷模式（Filesystem/Block）")
    private String volumeMode;

}
