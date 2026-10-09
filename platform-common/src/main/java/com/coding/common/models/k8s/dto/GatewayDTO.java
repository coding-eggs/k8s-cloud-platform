package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * Gateway DTO（{@code gateway.networking.k8s.io/v1}，**命名空间级**、租户域 —— 同 ServiceMonitor）。
 * <p>端点走 k8s-server {@code /gateways}（{@code AbstractNamespacedResourceController} → 分配表三元组边界）。
 *
 * <h2>listeners 的 fetch-overlay</h2>
 * {@code spec.listeners} 在 CRD 里是 <b>{@code listType=map, listMapKey=name}</b>（不是 atomic），
 * 因此 SSA 本身按 name 合并、不会丢弃未建模子字段。本模块仍走 {@code convertForUpdate} 显式 overlay
 * （按 name 对齐、以线上元素为底覆盖建模字段），理由有二：①v1.6 的 listener 新增了
 * {@code tls.frontendValidation}、{@code tls.options}、{@code allowedRoutes.kinds[].group} 之外的
 * 若干扩展位，显式 round-trip 比"依赖 schema 语义推断"更可测；②本模块的 YAML tab 承诺全保真，
 * overlay 保证编辑一次不改变未建模字段的所有权归属。
 * <p>未建模的 spec 字段：{@code allowedListeners}、{@code defaultScope}、{@code tls}（网关级前后端 TLS 配置）、
 * {@code infrastructure.parametersRef}、{@code backendTLS} —— 由 fetch-overlay 原样保留，YAML tab 可见全量。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class GatewayDTO extends BaseResources {

    /** spec.gatewayClassName（必填）：引用的 GatewayClass 名 */
    private String gatewayClassName;

    /** spec.listeners（必填，1–64 条，name 在本 Gateway 内唯一） */
    private List<Listener> listeners;

    /** spec.infrastructure（可选）：控制器为派生资源（Service/Deployment）附加的标签与注解 */
    private Infrastructure infrastructure;

    /** spec.addresses（可选）：请求的入口地址；不填由控制器/云厂商分配 */
    private List<Address> addresses;

    /** status.conditions（只读） */
    private List<ConditionDTO> conditions;

    /** status.listeners（只读）：各 listener 的 supportedKinds / attachedRoutes */
    private List<ListenerStatus> listenerStatuses;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    /**
     * spec.listeners[] 项。
     * <p>CRD 的 CEL 约束（提交前前端也做一次软校验）：HTTP/TCP/UDP 不得带 tls；HTTPS 的 tls.mode 只能 Terminate；
     * TLS 必须带 tls.mode；TCP/UDP 不得带 hostname；name 唯一；port+protocol+hostname 组合唯一。
     */
    @Data
    public static class Listener {

        /** listener 名（必填，RFC-1123，在本 Gateway 内唯一）；Route 的 parentRefs[].sectionName 指向它 */
        private String name;

        /** 主机名（可选，可含通配 {@code *.example.com}）；TCP/UDP 不允许设置 */
        private String hostname;

        /** 端口（必填，1–65535） */
        private Integer port;

        /** 协议（必填）：HTTP / HTTPS / TLS / TCP / UDP */
        private String protocol;

        /** TLS 配置（HTTPS 用 Terminate；TLS 用 Terminate 或 Passthrough） */
        private ListenerTls tls;

        /** 允许挂载到本 listener 的 Route 范围（缺省 = 同命名空间、本 Gateway 支持的 kind） */
        private AllowedRoutes allowedRoutes;

    }

    /** listener.tls：{@code mode} 只有 {@code Terminate} / {@code Passthrough} 两个合法值。 */
    @Data
    public static class ListenerTls {

        /** TLS 模式：Terminate（终止，需证书）/ Passthrough（透传）。协议 HTTPS 固定 Terminate */
        private String mode;

        /** 证书引用（Terminate 时必填）：通常指向 tls 类型 Secret 的 tls.crt/tls.key */
        private List<CertificateRef> certificateRefs;

    }

    /** listener.tls.certificateRefs[]：缺省 group=core、kind=Secret、namespace=Gateway 所在命名空间。 */
    @Data
    public static class CertificateRef {

        private String group;

        private String kind;

        /** Secret 名（必填） */
        private String name;

        private String namespace;

    }

    /** listener.allowedRoutes：限制哪些 Route 可以挂上来。 */
    @Data
    public static class AllowedRoutes {

        /** 命名空间范围（缺省 Same） */
        private RouteNamespaces namespaces;

        /** 允许的 Route kind（缺省 = 本 Gateway 支持的全部 kind） */
        private List<RouteGroupKind> kinds;

    }

    /**
     * allowedRoutes.namespaces。
     * <p>字段名是 {@code from} / {@code selector} —— 不是 {@code namespacesFrom} / {@code namespaceSelector}。
     * {@code from=Selector} 时 {@code selector} 才生效。
     */
    @Data
    public static class RouteNamespaces {

        /** All（全部命名空间，需配合 ReferenceGrant）/ Same（同命名空间，缺省）/ Selector（按标签）/ None（不允许） */
        private String from;

        /** 命名空间标签选择器，{@code from=Selector} 时生效 */
        private LabelSelectorDTO selector;

    }

    /** allowedRoutes.kinds[]：{@code group} 缺省 gateway.networking.k8s.io。 */
    @Data
    public static class RouteGroupKind {

        private String group;

        /** HTTPRoute / GRPCRoute / TCPRoute / TLSRoute / UDPRoute */
        private String kind;

    }

    /** spec.infrastructure：{@code parametersRef} 未建模，由 fetch-overlay 保留。 */
    @Data
    public static class Infrastructure {

        private Map<String, String> labels;

        private Map<String, String> annotations;

    }

    /** spec.addresses[] / status.addresses[]：{@code type} 如 IPAddress / Hostname。 */
    @Data
    public static class Address {

        private String type;

        private String value;

    }

    /** status.listeners[]：单个 listener 的收敛状态。 */
    @Data
    public static class ListenerStatus {

        private String name;

        /** 已挂载到本 listener 的 Route 数 */
        private Integer attachedRoutes;

        private List<RouteGroupKind> supportedKinds;

        private List<ConditionDTO> conditions;

    }

    @Override
    public String getApiPath() {
        return "/gateways";
    }

}
