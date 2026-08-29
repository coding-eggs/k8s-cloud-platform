package com.coding.common.models.k8s.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "租户 K8s 侧批量清理请求（best-effort）：删 RoleBinding + 各集群 SA")
public class AdminCleanupRequest {

    @Schema(description = "租户id（用于清 k8s-server 侧租户 client 缓存；调用方可能已软删租户行，故显式传递）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String tenantId;

    @Schema(description = "租户 SA 裸名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String serviceAccount;

    @Schema(description = "需删除的 RoleBinding 位置列表")
    private List<AdminAllocationRef> roleBindings;

    @Schema(description = "需删除 SA 的集群id列表")
    private List<String> saClusterIds;

}
