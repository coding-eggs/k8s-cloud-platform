package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "K8s 容器定义")
public class ContainerDTO {

    @Schema(description = "容器名称", example = "app-container")
    private String name;

    @Schema(description = "镜像地址", example = "nginx:1.21")
    private String image;

    @Schema(description = "环境变量列表")
    private List<EnvDTO> envs;

    @Schema(description = "端口映射列表")
    private List<PortDTO> ports;

}
