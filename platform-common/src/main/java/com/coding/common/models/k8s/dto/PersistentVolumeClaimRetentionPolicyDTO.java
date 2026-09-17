package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** StatefulSet 专属：PersistentVolumeClaimRetentionPolicy（spec.persistentVolumeClaimRetentionPolicy） */
@Data
@Schema(description = "StatefulSet PVC 保留策略")
public class PersistentVolumeClaimRetentionPolicyDTO {

    /** StatefulSet 被删除时 PVC 的处理：Retain / Delete */
    @Schema(description = "whenDeleted（Retain/Delete）")
    private String whenDeleted;

    /** StatefulSet 缩容时释放的 PVC 的处理：Retain / Delete */
    @Schema(description = "whenScaled（Retain/Delete）")
    private String whenScaled;

}
