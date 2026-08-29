package com.coding.common.models.k8s.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "集群连通性探测请求（纳管前一次性探测，明文 kubeconfig）")
public class AdminProbeRequest {

    @Schema(description = "kubeconfig 内容", requiredMode = Schema.RequiredMode.REQUIRED)
    private String kubeconfig;

}
