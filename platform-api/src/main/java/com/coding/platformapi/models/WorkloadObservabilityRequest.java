package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 工作负载「可观测接入」开关（OTel 批次一）。
 *
 * <p>只改 PodTemplate 的 OTel 注入注解与主容器的 {@code OTEL_SERVICE_NAME}，不碰规格。
 * {@code tracing} / {@code logs} 都为 false 时移除注入注解（关闭接入）。
 *
 * <p>语言只支持 java / python（Node 第一批不做）；注解值按组合选三个 Instrumentation 之一：
 * 只追踪 → {@code monitor/platform-instrumentation}、只日志 → {@code platform-instrumentation-logs}、
 * 两者都要 → {@code platform-instrumentation-all}。
 */
@Data
@Schema(description = "工作负载可观测接入（语言 + 追踪/日志开关）")
public class WorkloadObservabilityRequest {

    @Schema(description = "工作负载名（路径变量回填；跨 kind 查找用）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "租户id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String tenantId;

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "命名空间", requiredMode = Schema.RequiredMode.REQUIRED)
    private String namespace;

    @Schema(description = "工作负载类型（deployment/statefulset/daemonset）；不传则按 name 跨 kind 查找")
    private String kind;

    @Schema(description = "应用运行时：java / python（Node 第一批不支持）")
    private String language;

    @Schema(description = "是否开启追踪")
    private boolean tracing;

    @Schema(description = "是否开启应用日志（OTel logs → ES）")
    private boolean logs;

    /** app = Java/Python 日志框架（默认）；file = sidecar 读日志文件（Nginx 等） */
    @Schema(description = "日志采集方式：app / file")
    private String logMode;

    /**
     * 文件日志：应用容器写入的**具体日志文件**（容器内绝对路径，如 {@code /var/log/nginx/access.log}）。
     * 平台把它的父目录挂成 emptyDir，sidecar 只采这个文件（支持 glob，如 {@code access.log*}）。
     */
    @Schema(description = "文件日志的容器内文件路径（不是目录）")
    private String logFile;

    @Schema(description = "OTEL_SERVICE_NAME；空 = 工作负载名")
    private String serviceName;

    /**
     * 工作负载级采样率（百分比 1–100）；{@code null} = 跟随 OTel 组件的全局采样率。
     *
     * <p>非空时平台在容器上写 {@code OTEL_TRACES_SAMPLER=parentbased_traceidratio} +
     * {@code OTEL_TRACES_SAMPLER_ARG=<比例>} —— operator 只在容器**没有**同名 env 时才注入
     * 组件级的 sampler（v0.161.0 `internal/instrumentation/sdk.go`），所以这两个 env 会覆盖全局值。
     * 为空时移除这两个 env，回到组件默认。
     */
    @Schema(description = "工作负载级采样率百分比（1–100）；空 = 跟随组件默认")
    private Integer sampling;

    @Schema(description = "写 OTEL_SERVICE_NAME 的容器；空 = 第一个容器")
    private String containerName;
}
