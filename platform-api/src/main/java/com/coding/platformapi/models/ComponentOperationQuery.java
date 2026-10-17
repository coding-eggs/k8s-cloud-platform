package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 查询组件操作：按 id，或按 (clusterId, componentCode) 取最近一条。 */
@Data
public class ComponentOperationQuery {
    @Schema(description = "操作 id（优先）")
    private Long operationId;
    private String clusterId;
    private String componentCode;
}
