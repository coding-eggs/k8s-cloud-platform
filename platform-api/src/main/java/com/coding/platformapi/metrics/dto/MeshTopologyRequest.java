package com.coding.platformapi.metrics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 拓扑查询入参：集群必填；命名空间空 = 全集群；时间窗空 = 300s。 */
@Data
@Schema(description = "网格拓扑查询")
public class MeshTopologyRequest {

    @Schema(description = "集群 id")
    private String clusterId;

    @Schema(description = "命名空间（空 = 不限，两端都按它过滤）")
    private String namespace;

    @Schema(description = "时间窗（秒），默认 300")
    private Integer windowSeconds;
}
