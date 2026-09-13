package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * Pod 容器详情（spec + status 合并视图）。
 * spec 字段与 {@link ContainerDTO} 对齐（供前端按创建表单结构展示），status 字段来自 pod.status。
 * 主/副/初始化容器的区分由 {@link #init} 标记，顺序为「常规容器在前、初始化容器在后」。
 */
@Data
@Schema(description = "Pod 容器详情（spec + status）")
public class PodContainerDTO {

    // ==================== spec ====================

    @Schema(description = "容器名称")
    private String name;

    @Schema(description = "镜像地址")
    private String image;

    @Schema(description = "启动命令")
    private List<String> command;

    @Schema(description = "启动参数")
    private List<String> args;

    @Schema(description = "工作目录")
    private String workingDir;

    @Schema(description = "镜像拉取策略（Always/IfNotPresent/Never）")
    private String imagePullPolicy;

    @Schema(description = "环境变量列表")
    private List<EnvDTO> envs;

    @Schema(description = "环境变量来源列表")
    private List<EnvFromDTO> envFrom;

    @Schema(description = "端口映射列表")
    private List<PortDTO> ports;

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

    // ==================== status ====================

    @Schema(description = "是否初始化容器（true=init，false=常规容器）")
    private Boolean init;

    @Schema(description = "是否就绪")
    private Boolean ready;

    @Schema(description = "该容器重启次数")
    private Integer restartCount;

    @Schema(description = "运行状态：Running / Waiting / Terminated（无状态时为空）")
    private String state;

    @Schema(description = "运行态原因（waiting/terminated 的 reason，如 ImagePullBackOff / CrashLoopBackOff / OOMKilled；Running 时为空）")
    private String reason;

    @Schema(description = "运行态详细信息（waiting/terminated 的 message）")
    private String message;

    @Schema(description = "本次运行开始时间（ISO-8601，存活时长基准；未运行时为空）")
    private String startedAt;

}
