package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "ClusterRole 列表查询请求参数")
public class ListClusterRoleQueryDTO {

    @Schema(description = "集群 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "cluster-001")
    private String clusterId;

}