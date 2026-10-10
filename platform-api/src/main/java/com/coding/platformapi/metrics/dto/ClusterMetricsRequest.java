package com.coding.platformapi.metrics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 集群级监控指标查询请求（集群概览「资源用量」tab 的曲线）。
 * <p>与 {@link NamespaceMetricsRequest} / {@link NodeMetricsRequest} 同形，只是维度是<b>整个集群</b>：
 * 查询按 {@code cluster_name="%s"} 过滤、不带 namespace，故多集群共用同一 Thanos 也不会串数。
 */
@Data
@Schema(description = "集群级监控指标查询请求")
public class ClusterMetricsRequest {

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "起始时间（unix 秒）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1756800000")
    private long start;

    @Schema(description = "结束时间（unix 秒）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1756801800")
    private long end;
}
