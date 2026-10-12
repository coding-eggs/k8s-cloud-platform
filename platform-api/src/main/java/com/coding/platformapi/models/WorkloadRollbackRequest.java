package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**回滚请求体（列表页操作列「回滚」→ 选定历史版本）。 */
@Data
@Schema(description = "工作负载回滚请求")
public class WorkloadRollbackRequest {

    @Schema(description = "租户id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String tenantId;

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "命名空间", requiredMode = Schema.RequiredMode.REQUIRED)
    private String namespace;

    @Schema(description = "目标版本号（来自 /revisions）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long revision;
}
