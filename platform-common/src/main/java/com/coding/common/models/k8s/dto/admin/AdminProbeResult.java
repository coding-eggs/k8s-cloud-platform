package com.coding.common.models.k8s.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "集群连通性探测结果")
public class AdminProbeResult {

    @Schema(description = "K8s 版本（gitVersion）")
    private String version;

}
