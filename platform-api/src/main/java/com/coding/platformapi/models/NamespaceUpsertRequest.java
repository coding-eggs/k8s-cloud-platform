package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "命名空间创建/更新：集群 + 名称 + 描述 + 标签")
public class NamespaceUpsertRequest {

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "命名空间名（创建必填且不可变；更新定位用）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "描述（存 metadata.annotations[\"description\"]；空=清空）")
    private String description;

    @Schema(description = "标签")
    private Map<String, String> labels;
}
