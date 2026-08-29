package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 工作负载统一 DTO（Deployment / StatefulSet / DaemonSet 共用基础表单字段）。
 * kind ∈ deployment | statefulset | daemonset（继承自 BaseResources，create/update 按它分发）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WorkloadDTO extends BaseResources {

    /** 期望副本数（DaemonSet 无此概念，为 null） */
    private Integer replicas;

    /** 就绪副本数（仅查询返回） */
    private Integer readyReplicas;

    /** 容器镜像列表（基础表单取全部容器 image） */
    private List<String> images;

    /** 容器端口（创建时可选，仅查询不返回） */
    private List<PortDTO> ports;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    /** 描述 → metadata.annotations["description"] */
    @Schema(description = "描述")
    private String description;

    /** STS 专属 headless service 名，默认 = name */
    @Schema(description = "serviceName（StatefulSet 专属）")
    private String serviceName;

    /** 更新策略（kind 感知） */
    @Schema(description = "更新策略")
    private StrategyDTO strategy;

    /** STS 专属卷声明模板 */
    @Schema(description = "volumeClaimTemplates（StatefulSet 专属）")
    private List<PvcTemplateDTO> volumeClaimTemplates;

    /** 完整 PodSpec（三种 kind 共享）★核心 */
    @Schema(description = "Pod 模板（完整 PodSpec）")
    private PodTemplateDTO podTemplate;

    @Override
    public String getApiPath() {
        return "/resources/workloads";
    }

}
