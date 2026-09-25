package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 命名空间（core/v1，集群级资源）。
 * name/labels 继承自 {@link BaseResources}；namespace 恒为 null（集群级）。
 * 描述存于 metadata.annotations["description"]（同 WorkloadDTO 约定）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "命名空间")
public class NamespaceDTO extends BaseResources {

    @Schema(description = "状态（Active/Terminating）")
    private String phase;

    @Schema(description = "创建时间（ISO-8601）")
    private String creationTimestamp;

    @Schema(description = "描述 → metadata.annotations[\"description\"]")
    private String description;

    @Schema(description = "资源版本（只读回传）")
    private String resourceVersion;

    /** 平台级命名空间管理，走 admin 边界（无租户上下文） */
    @Override
    public String getApiPath() {
        return "/admin/namespaces";
    }
}
