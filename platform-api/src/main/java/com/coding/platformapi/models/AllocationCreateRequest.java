package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "命名空间分配请求")
public class AllocationCreateRequest {

    @Schema(description = "租户id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String tenantId;

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "命名空间名（不存在时自动创建）", requiredMode = Schema.RequiredMode.REQUIRED, example = "milvus")
    private String namespace;

    @Schema(description = "RBAC 模板id，缺省使用内置模板")
    private String roleTemplateId;
}
