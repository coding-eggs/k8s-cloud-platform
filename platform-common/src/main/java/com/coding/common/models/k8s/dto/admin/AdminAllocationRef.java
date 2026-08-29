package com.coding.common.models.k8s.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "租户命名空间分配引用（集群 + 命名空间）")
public class AdminAllocationRef {

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "命名空间名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String namespace;

}
