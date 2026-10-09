package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * GRPCRoute DTO（{@code gateway.networking.k8s.io/v1}，命名空间级、租户域）。自 Gateway API v1.1 GA。
 * <p>骨架同 {@link HttpRouteDTO}，差异都在"匹配什么"和"能挂什么过滤器"上：
 * <ul>
 *   <li>matches 里是 <b>{@code method{type,service,method}} + headers</b> —— <b>没有 path</b>
 *       （gRPC 按服务名/方法名路由，不是 URL 路径），也<b>没有 queryParams</b>
 *       （v1alpha2 时代的 queryParams 在 GA 到 v1 时被移除，spec 里"GRPC 有 queryParams"的说法是错的）。</li>
 *   <li>filters 只有 4 种：{@code RequestHeaderModifier} / {@code ResponseHeaderModifier} /
 *       {@code RequestMirror} / {@code ExtensionRef} —— <b>没有 RequestRedirect / URLRewrite</b>
 *       （gRPC 没有"重定向到另一个 URL"的概念）。</li>
 *   <li>没建模 rule 级的 {@code sessionPersistence}（同 HTTPRoute，靠 fetch-overlay 保留）。</li>
 * </ul>
 *
 * <h2>fetch-overlay</h2>
 * {@code spec.parentRefs} / {@code spec.rules} / {@code rule.matches} / {@code rule.filters} /
 * {@code rule.backendRefs} 都是 atomic list（同 HTTPRoute），故同样必须 overlay —— 见
 * {@code GrpcRouteConverter.convertForUpdate}。
 *
 * <p><b>版本</b>：GRPCRoute 自 v1.1 起 GA。v1 不存在时后端显式报错（不静默回退到 v1alpha2 的旧结构 ——
 * 那个结构与本 DTO 不兼容）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class GrpcRouteDTO extends BaseResources {

    /** spec.parentRefs */
    private List<ParentRefDTO> parentRefs;

    /** spec.hostnames（空 = 所有主机名） */
    private List<String> hostnames;

    /** spec.rules */
    private List<Rule> rules;

    /** status.parents（只读） */
    private List<RouteParentStatusDTO> parentStatuses;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    /** spec.rules[] 项 */
    @Data
    public static class Rule {

        /** rule 名（可选，本 Route 内唯一） */
        private String name;

        /** 匹配条件（空 = 匹配全部） */
        private List<Match> matches;

        /** 过滤器（同一 type 至多一个） */
        private List<Filter> filters;

        /** 后端（空 = 拒绝该规则的请求） */
        private List<BackendRefDTO> backendRefs;

    }

    /** rule.matches[] 项：方法与请求头两个维度 AND */
    @Data
    public static class Match {

        private MethodMatch method;

        private List<HeaderMatch> headers;

    }

    /** gRPC 方法匹配：{@code service} 与 {@code method} 都是"服务名/方法名"，不含斜杠前缀 */
    @Data
    public static class MethodMatch {

        /** {@code Exact}（缺省）/ {@code RegularExpression} */
        private String type;

        /** 服务名，如 {@code helloworld.Greeter}；不填 = 任意服务 */
        private String service;

        /** 方法名，如 {@code SayHello}；不填 = 该服务的任意方法 */
        private String method;

    }

    /** 请求头匹配：{@code type} = Exact（缺省）/ RegularExpression。gRPC 的头是 metadata */
    @Data
    public static class HeaderMatch {

        private String type;

        /** 头名（必填；gRPC metadata key 大小写不敏感） */
        private String name;

        /** 匹配值（必填） */
        private String value;

    }

    /**
     * rule.filters[] 项。已建模 4 种 type；未建模的 type 由后端 overlay 按 type 保住整个元素
     * （本版 GRPCRoute 的 filter 类型就是这 4 种，未建模分支主要是防 CRD 升级新增）。
     */
    @Data
    public static class Filter {

        /** RequestHeaderModifier / ResponseHeaderModifier / RequestMirror / ExtensionRef */
        private String type;

        private HttpRouteDTO.HeaderFilter requestHeaderModifier;

        private HttpRouteDTO.HeaderFilter responseHeaderModifier;

        private HttpRouteDTO.RequestMirror requestMirror;

        private ExtensionRefDTO extensionRef;

    }

    @Override
    public String getApiPath() {
        return "/grpcroutes";
    }

}
