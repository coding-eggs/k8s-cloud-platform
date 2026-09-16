package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * PodMonitor DTO（monitoring.coreos.com/v1 CRD）。
 * spec 顶层字段：selector（必填）+ podMetricsEndpoints（必填）+ namespaceSelector / jobLabel /
 * podTargetLabels / sampleLimit / targetLimit / labelLimit / bodySizeLimit / attachMetadata。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PodMonitorDTO extends BaseResources {

    /** 目标 Pod 选择器 matchLabels（spec.selector，必填）：由后端据 podRef 解析填充；查询时原样返回 */
    private Map<String, String> matchLabels;

    /** 目标 Pod 选择器 matchExpressions（spec.selector.matchExpressions）：与 matchLabels 并存，ANDed；支持按表达式选 Pod */
    private List<MatchExpression> matchExpressions;

    /** 绑定的目标 Pod（平台侧）：前端选择后据此反查其 labels 生成 matchLabels；非 K8s 字段，转发 k8s-server 前剥离、不落库 */
    private PodRef podRef;

    /** 命名空间选择（缺省=仅本命名空间） */
    private NamespaceSelector namespaceSelector;

    /** Prometheus job 名（缺省 = <namespace>-<name>） */
    private String jobLabel;

    /** 从目标 Pod 的标签透传到抓取目标的 label 列表 */
    private List<String> podTargetLabels;

    /** 每个 target 最大样本数 */
    private Integer sampleLimit;

    /** 每 scrape 最大 target 数 */
    private Integer targetLimit;

    /** 每个 target 最大 label 数 */
    private Integer labelLimit;

    /** 响应体大小上限，如 50MB（string） */
    private String bodySizeLimit;

    /** 附加元数据（node=true 时把 node 名加到目标标签） */
    private AttachMetadata attachMetadata;

    /** 抓取端点列表（spec.podMetricsEndpoints，必填；注意非 ServiceMonitor 的 endpoints） */
    private List<PodMonitorEndpointDTO> podMetricsEndpoints;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    /** spec.namespaceSelector：any=跨所有命名空间；matchNames=指定列表 */
    @Data
    public static class NamespaceSelector {
        private Boolean any;
        private List<String> matchNames;
    }

    /** 绑定的目标 Pod（name + namespace）：namespace 缺省取本资源所在命名空间 */
    @Data
    public static class PodRef {
        private String name;
        private String namespace;
    }

    /** spec.attachMetadata：node=true 附加 node 名标签 */
    @Data
    public static class AttachMetadata {
        private Boolean node;
    }

    /** spec.selector.matchExpressions[] 项：key + operator(In/NotIn/Exists/DoesNotExist) + values */
    @Data
    public static class MatchExpression {
        private String key;
        private String operator;
        private List<String> values;
    }

    @Override
    public String getApiPath() {
        return "/resources/podmonitors";
    }

}
