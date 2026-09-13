package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * HPA 指标（对应 autoscaling/v2 MetricSpec）。{@code type} 决定哪个子字段有值：
 * Resource / ContainerResource / Pods / Object / External，五者互斥。
 */
@Data
public class HpaMetricSpecDTO {

    /** Resource / ContainerResource / Pods / Object / External */
    private String type;

    /** type=Resource 时有效 */
    private HpaResourceMetricDTO resource;

    /** type=ContainerResource 时有效 */
    private HpaContainerResourceMetricDTO containerResource;

    /** type=Pods 时有效 */
    private HpaPodsMetricDTO pods;

    /** type=Object 时有效 */
    private HpaObjectMetricDTO object;

    /** type=External 时有效 */
    private HpaExternalMetricDTO external;

}
