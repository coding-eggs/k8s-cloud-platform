package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**改镜像请求体（列表页「镜像」列的行内编辑）。 */
@Data
@Schema(description = "工作负载改镜像请求")
public class WorkloadImageRequest {

    @Schema(description = "租户id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String tenantId;

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "命名空间", requiredMode = Schema.RequiredMode.REQUIRED)
    private String namespace;

    @Schema(description = "容器名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String containerName;

    @Schema(description = "新镜像（含 tag/digest）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String image;
}
