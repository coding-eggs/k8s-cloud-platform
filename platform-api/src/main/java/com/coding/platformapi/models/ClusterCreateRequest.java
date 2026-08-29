package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "集群纳管请求")
public class ClusterCreateRequest {

    @Schema(description = "集群名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "prod-cluster-1")
    private String clusterName;

    @Schema(description = "kubeconfig 明文（.kube/config 文件内容）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String kubeconfig;

    @Schema(description = "集群描述")
    private String description;
}
