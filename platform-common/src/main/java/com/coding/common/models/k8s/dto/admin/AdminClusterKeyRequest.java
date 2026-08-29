package com.coding.common.models.k8s.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "集群标识请求：仅需 clusterId 的只读操作（如列集群命名空间）")
public class AdminClusterKeyRequest {

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

}
