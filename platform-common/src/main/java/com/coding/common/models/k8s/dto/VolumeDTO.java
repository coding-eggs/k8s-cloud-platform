package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "卷定义")
public class VolumeDTO {

    @Schema(description = "卷名称")
    private String name;

    @Schema(description = "卷类型（emptyDir/configMap/secret/persistentVolumeClaim/hostPath）")
    private String type;

    @Schema(description = "emptyDir 卷配置")
    private EmptyDirVolumeDTO emptyDir;

    @Schema(description = "configMap 卷配置")
    private ConfigMapVolumeDTO configMap;

    @Schema(description = "secret 卷配置")
    private SecretVolumeDTO secret;

    @Schema(description = "persistentVolumeClaim 卷配置")
    private PvcVolumeDTO persistentVolumeClaim;

    @Schema(description = "hostPath 卷配置")
    private HostPathVolumeDTO hostPath;

}
