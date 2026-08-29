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

    @Schema(description = "启动命令")
    private List<String> command;

    @Schema(description = "启动参数")
    private List<String> args;

    @Schema(description = "工作目录")
    private String workingDir;

    @Schema(description = "镜像拉取策略（Always/IfNotPresent/Never）")
    private String imagePullPolicy;

    @Schema(description = "环境变量来源列表")
    private List<EnvFromDTO> envFrom;

    @Schema(description = "资源定义")
    private ResourcesDTO resources;

    @Schema(description = "生命周期钩子")
    private LifecycleDTO lifecycle;

    @Schema(description = "存活探针")
    private ProbeDTO livenessProbe;

    @Schema(description = "就绪探针")
    private ProbeDTO readinessProbe;

    @Schema(description = "启动探针")
    private ProbeDTO startupProbe;

    @Schema(description = "卷挂载列表")
    private List<VolumeMountDTO> volumeMounts;

}
