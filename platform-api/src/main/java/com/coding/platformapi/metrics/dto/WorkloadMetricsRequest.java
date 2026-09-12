package com.coding.platformapi.metrics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 工作负载 / Pod 指标查询请求。name 由 path 注入（工作负载名或 Pod 名）。 */
@Data
@Schema(description = "工作负载/Pod 指标查询请求")
public class WorkloadMetricsRequest {

    @Schema(description = "租户id（admin token 代操作必须显式传；k8s-server 列 pod 做命名空间域边界校验）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String tenantId;

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "命名空间", requiredMode = Schema.RequiredMode.REQUIRED)
    private String namespace;

    @Schema(description = "工作负载名或 Pod 名（由 path 注入）")
    private String name;

    @Schema(description = "工作负载类型（deployment/statefulset/daemonset）；仅工作负载维度用（如 kube_pod_owner 的 owner_kind），Pod 维度可空")
    private String kind;

    @Schema(description = "起始时间（unix 秒）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1756800000")
    private long start;

    @Schema(description = "结束时间（unix 秒）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1756801800")
    private long end;
}
