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

    /** 文件日志：应用容器把日志写入的目录（挂载到 sidecar 的 /var/log/app） */
    @Schema(description = "文件日志的容器内日志目录")
    private String logDirectory;

    /** 文件日志：用户确认应用确实把日志写入该目录（sidecar 读不到 stdout） */
    @Schema(description = "确认应用写文件（不勾选不允许提交文件日志）")
    private boolean confirmFileLogs;

    @Schema(description = "OTEL_SERVICE_NAME；空 = 工作负载名")
    private String serviceName;

    @Schema(description = "写 OTEL_SERVICE_NAME 的容器；空 = 第一个容器")
    private String containerName;
}
