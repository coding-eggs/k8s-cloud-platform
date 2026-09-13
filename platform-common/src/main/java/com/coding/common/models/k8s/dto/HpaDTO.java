package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * HPA（HorizontalPodAutoscaler）DTO —— 平台侧统一载体，对齐 autoscaling/v2 spec（全保真：五类 metrics + behavior）。
 * <p>发散资源：autoscaling/v1 与 v2 字段几乎不重叠。由 {@code KubernetesOperationsFactory.build()} 按集群 capability
 * 选 V1/V2 converter；v1 仅能表示 CPU 利用率，含内存/其他指标或 behavior 时创建/更新会显式报错（见 HpaV1Converter）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class HpaDTO extends BaseResources {

    /** 最小副本数 */
    private Integer minReplicas;

    /** 最大副本数 */
    private Integer maxReplicas;

    /** 扩缩容目标（kind + name，如 Deployment/app） */
    private HpaCrossVersionObjectReferenceDTO scaleTargetRef;

    /** 指标列表（v2 五类；v1 仅支持其中 Resource/cpu/Utilization） */
    private List<HpaMetricSpecDTO> metrics;

    /** 扩缩容行为（v2.1+；v1 无此字段） */
    private HpaBehaviorDTO behavior;

    /** 当前副本数（仅查询返回，来自 status.currentReplicas） */
    private Integer currentReplicas;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/resources/hpas";
    }

}
