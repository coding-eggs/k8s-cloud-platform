package com.coding.platformapi.models;

import com.coding.common.models.k8s.dto.LimitRangeDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "命名空间限制范围 upsert：集群 + 命名空间 + 限制范围内容（对象名固定 default）")
public class NamespaceLimitRangeUpsertRequest {

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "命名空间", requiredMode = Schema.RequiredMode.REQUIRED)
    private String namespace;

    @Schema(description = "限制范围内容", requiredMode = Schema.RequiredMode.REQUIRED)
    private LimitRangeDTO limitRange;
}
