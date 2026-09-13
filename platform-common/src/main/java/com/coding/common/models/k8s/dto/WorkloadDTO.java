package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

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

    /** 状态原因（未完全就绪时的说明，来自 workload.status.conditions；正常时为空） */
    @Schema(description = "状态原因")
    private String statusReason;

    /** 容器镜像列表（基础表单取全部容器 image） */
    private List<String> images;

    /** ：绑定到本工作负载的 Service 及其端口。写路径忽略 */
    private List<ExposedService> exposedServices;

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

    /** Pod 选择器 = spec.selector.matchLabels（仅查询返回；用于反查该工作负载的 Pod，勿硬编码 app=） */
    @Schema(description = "Pod 选择器 matchLabels")
    private Map<String, String> selector;

    /** 属主引用 metadata.ownerReferences（仅查询返回；非空即视为「op 管理」→ 前端禁用编辑并展示管理方） */
    @Schema(description = "属主引用（由 Operator/控制器创建的才有）")
    private List<OwnerReferenceDTO> ownerReferences;

    /** 完整 PodSpec（三种 kind 共享）★核心 */
    @Schema(description = "Pod 模板（完整 PodSpec）")
    private PodTemplateDTO podTemplate;

    /** 对外暴露的 Service：名称 + 类型 + 端口（port:nodePort）。仅 list/get 返回 */
    @Data
    public static class ExposedService {
        /** Service 名称 */
        private String name;
        /** NodePort / LoadBalancer / ClusterIP / ExternalName */
        private String type;

        private List<ExposedPort> ports;
    }

    /** 暴露端口：Service 端口 + 节点端口 */
    @Data
    public static class ExposedPort {
        private Integer port;
        private Integer nodePort;
    }

    @Override
    public String getApiPath() {
        return "/resources/workloads";
    }

}
