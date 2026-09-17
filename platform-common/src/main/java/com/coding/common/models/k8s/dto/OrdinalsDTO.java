package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** StatefulSet 专属：StatefulSetOrdinals（spec.ordinals） */
@Data
@Schema(description = "StatefulSet ordinals（编号起始值）")
public class OrdinalsDTO {

    /** 编号起始值，默认 0 */
    @Schema(description = "start（默认 0）")
    private Integer start;

}
