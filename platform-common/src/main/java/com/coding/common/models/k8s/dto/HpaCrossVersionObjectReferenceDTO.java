package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * HPA 跨版本对象引用（对应 autoscaling/v2 CrossVersionObjectReference，用于 scaleTargetRef / object.describedObject）
 */
@Data
public class HpaCrossVersionObjectReferenceDTO {

    /** API 组/版本，如 apps/v1；可空（默认当前组） */
    private String apiVersion;

    /** 对象类型，如 Deployment */
    private String kind;

    /** 对象名称 */
    private String name;

    /** 对象命名空间（describedObject 用；scaleTargetRef 通常留空 = HPA 所在 ns） */
    private String namespace;

}
