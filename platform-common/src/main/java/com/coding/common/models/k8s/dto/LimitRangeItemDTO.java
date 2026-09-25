package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * LimitRange 单条限制项（core/v1 LimitRangeItem）。
 * 字段为 null / 内部 cpu、memory 为 null = 该项不约束（overlay 时删除对应 key）。
 * 注：K8s 侧字段名为 default（Java 关键字），故 DTO 用 defaultValue —— 由 converter 显式映射，
 * 无需 JSON 注解（DTO 不直接序列化给 K8s）。
 */
@Data
@Schema(description = "LimitRange 限制项")
public class LimitRangeItemDTO {

    @Schema(description = "作用类型：Container / Pod / PersistentVolumeClaim")
    private String type;

    @Schema(description = "上限 → max")
    private ResourcePairDTO max;

    @Schema(description = "下限 → min")
    private ResourcePairDTO min;

    @Schema(description = "默认值 → default")
    private ResourcePairDTO defaultValue;

    @Schema(description = "默认请求量 → defaultRequest")
    private ResourcePairDTO defaultRequest;

    @Schema(description = "limit/request 最大比值 → maxLimitRequestRatio")
    private ResourcePairDTO maxLimitRequestRatio;
}
