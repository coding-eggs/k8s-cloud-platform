package com.coding.common.models.k8s.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "补建单租户 ServiceAccount 请求（幂等）")
public class AdminSaEnsureRequest {

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "租户 SA 裸名（K8s 对象名 = tn- + 该值）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String serviceAccount;

}
