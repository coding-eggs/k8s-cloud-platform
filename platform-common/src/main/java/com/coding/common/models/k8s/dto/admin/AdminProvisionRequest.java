package com.coding.common.models.k8s.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "集群开通请求：platform-system + 全部启用租户 SA + 全部模板 ClusterRole（幂等）")
public class AdminProvisionRequest {

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

}
