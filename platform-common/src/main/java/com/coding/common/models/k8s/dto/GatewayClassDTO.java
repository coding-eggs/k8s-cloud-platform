package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * GatewayClass DTO（{@code gateway.networking.k8s.io/v1}，**集群级**、平台管理面）。
 * <p>spec 只有三个字段（controllerName 必填 / parametersRef / description），故<b>全建模、无需 fetch-overlay</b>。
 * status 只取 conditions（supportedFeatures 是实现细节，要看全量走 YAML tab）。
 * <p>端点走 k8s-server {@code /gatewayclasses}（{@code AbstractClusterResourceController} → PLATFORM 边界，admin client）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class GatewayClassDTO extends BaseResources {

    /** spec.controllerName（必填）：实现本 GatewayClass 的控制器域名，如 istio.io/gateway-controller */
    private String controllerName;

    /** spec.parametersRef（可选）：指向控制器私有配置 CRD 的引用 */
    private ParametersRef parametersRef;

    /** spec.description（可选）：人类可读说明 */
    private String description;

    /** status.conditions（只读）：Accepted 等 */
    private List<ConditionDTO> conditions;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    /**
     * spec.parametersRef：{@code group}/{@code kind}/{@code name} 必填。
     * <p>{@code namespace} 仅在目标为<b>命名空间级</b>资源时出现 —— 本对象自身集群级，
     * 但被引用的配置 CRD 可以是命名空间级的。
     */
    @Data
    public static class ParametersRef {
        private String group;
        private String kind;
        private String name;
        private String namespace;
    }

    @Override
    public String getApiPath() {
        return "/gatewayclasses";
    }

}
