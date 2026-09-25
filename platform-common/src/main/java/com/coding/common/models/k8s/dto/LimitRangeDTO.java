package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 命名空间限制范围（core/v1 LimitRange）。平台单份约定：对象名固定 default。
 * v1 只建模 Container / Pod / PersistentVolumeClaim 三类；其余（ContainerFixed）由
 * convertForUpdate 的 overlay 按 type 对齐原样保留。spec.limits 是 atomic list，
 * 故必须 overlay-before-SSA（省略即删）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "命名空间限制范围")
public class LimitRangeDTO extends BaseResources {

    @Schema(description = "限制项列表（按 type 唯一）")
    private List<LimitRangeItemDTO> limits;

    @Schema(description = "该命名空间存在多份 LimitRange（只读告警旗标）")
    private Boolean multiple;

    @Schema(description = "资源版本（只读回传）")
    private String resourceVersion;

    @Schema(description = "创建时间（只读）")
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/admin/limitranges";
    }
}
