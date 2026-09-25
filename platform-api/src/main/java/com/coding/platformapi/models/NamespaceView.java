package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "命名空间视图：K8s 命名空间 + 分配信息合并（api 侧业务加工）")
public class NamespaceView {

    @Schema(description = "命名空间名")
    private String name;

    @Schema(description = "状态（Active/Terminating）")
    private String phase;

    @Schema(description = "创建时间（ISO-8601）")
    private String creationTimestamp;

    @Schema(description = "是否平台管理（带 managed-by 标签）")
    private boolean managedBy;

    @Schema(description = "已分配租户名；null = 未分配")
    private String allocatedTenantName;

    @Schema(description = "描述（metadata.annotations[\"description\"]）")
    private String description;

    @Schema(description = "标签（不含 managed-by 等平台保留键由前端按需展示）")
    private Map<String, String> labels;

}
