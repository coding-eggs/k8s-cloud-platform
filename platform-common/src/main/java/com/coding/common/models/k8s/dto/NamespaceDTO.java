package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * 命名空间（集群级资源）常用字段抽象。
 * k8s-server 返回全字段对象，展示/裁剪由 platform-api 侧决定。
 */
@Data
@Schema(description = "命名空间")
public class NamespaceDTO {

    @Schema(description = "命名空间名")
    private String name;

    @Schema(description = "状态（Active/Terminating）")
    private String phase;

    @Schema(description = "创建时间（ISO-8601）")
    private String creationTimestamp;

    @Schema(description = "标签（含 app.kubernetes.io/managed-by 等）")
    private Map<String, String> labels;

}
