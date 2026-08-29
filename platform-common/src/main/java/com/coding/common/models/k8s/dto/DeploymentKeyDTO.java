package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Deployment 按名称查询/删除请求参数")
public class DeploymentKeyDTO {

    @Schema(description = "集群 ID", required = true, example = "cluster-001")
    private String clusterId;

    @Schema(description = "租户 ID", required = true, example = "tenant-001")
    private String tenantId;

    @Schema(description = "命名空间", required = true, example = "default")
    private String namespace;

    @Schema(description = "Deployment 名称", required = true, example = "my-deployment")
    private String name;

}
