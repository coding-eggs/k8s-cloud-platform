package com.coding.platformapi.models;

import com.coding.common.models.k8s.dto.ResourceQuotaDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "命名空间配额 upsert：集群 + 命名空间 + 配额内容（对象名固定 default）")
public class NamespaceQuotaUpsertRequest {

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "命名空间", requiredMode = Schema.RequiredMode.REQUIRED)
    private String namespace;

    @Schema(description = "配额内容", requiredMode = Schema.RequiredMode.REQUIRED)
    private ResourceQuotaDTO quota;
}
