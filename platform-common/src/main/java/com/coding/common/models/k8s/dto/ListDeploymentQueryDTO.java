package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Deployment 列表查询请求参数")
public class ListDeploymentQueryDTO {

    @Schema(description = "集群 ID", required = true, example = "cluster-001")
    private String clusterId;

    @Schema(description = "租户 ID", required = true, example = "tenant-001")
    private String tenantId;

    @Schema(description = "命名空间（可选，不传则查询所有）", example = "default")
    private String namespace;

}
