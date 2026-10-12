package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 租户概览请求（首屏快照 / 资源明细 / 两条曲线共用）。
 * <p>{@code tenantId} 为生效租户 id：自管轨可不传（由 token 锁定），代管轨（平台管理员）必须显式传 ——
 * 由 {@code TenantContextResolver.requireContext} 裁决。{@code clusterId} 只对曲线有意义（可选的单集群过滤）；
 * 快照 / 明细恒为租户全部集群。{@code start}/{@code end} 只对曲线有意义（unix 秒）。
 */
@Data
@Schema(description = "租户概览请求")
public class TenantOverviewRequest {

    @Schema(description = "生效租户 id（自管轨可省，代管轨必填）")
    private String tenantId;

    @Schema(description = "集群 id（仅曲线：可选，限定到单个集群；缺省=租户全部集群）")
    private String clusterId;

    @Schema(description = "起始时间（unix 秒；仅曲线）", example = "1756800000")
    private long start;

    @Schema(description = "结束时间（unix 秒；仅曲线）", example = "1756801800")
    private long end;
}
