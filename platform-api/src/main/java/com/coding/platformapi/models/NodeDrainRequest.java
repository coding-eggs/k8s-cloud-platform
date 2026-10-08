package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**节点驱逐请求体（平台侧请求模型）。 */
@Data
@Schema(description = "节点 drain 请求")
public class NodeDrainRequest {

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "节点名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "强制驱逐（忽略不受控制器管理的 Pod）")
    private Boolean force;

    @Schema(description = "同时删除 emptyDir 数据")
    private Boolean deleteEmptyDir;
}
