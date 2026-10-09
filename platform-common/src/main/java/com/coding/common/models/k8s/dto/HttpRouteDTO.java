package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * HTTPRoute DTO（{@code gateway.networking.k8s.io/v1}，**命名空间级**、租户域）。7 类里结构最复杂的一个。
 * <p>端点走 k8s-server {@code /httproutes}（分配表三元组边界）。
 *
 * <h2>为什么必须 fetch-overlay</h2>
 * CRD 里 {@code spec.rules}（无 listType 标记 → 默认 atomic）、{@code rule.matches}、
 * {@code rule.filters}、{@code rule.backendRefs}、{@code spec.parentRefs} <b>都是 atomic list</b>。
 * 对 atomic list，SSA 把整个列表当一个值拥有 —— 平台 apply 一次不带某个子字段，该子字段就被抹掉
 * （memory: fabric8-ssa-generic-crd 记的同一个坑）。因此本 DTO 建模的是<b>核心集</b>，
 * 其余一律靠 {@code HttpRouteConverter.convertForUpdate} 的 overlay 原样带回。未建模项：
 * <ul>
 *   <li>rule 级：{@code timeouts}、{@code retry}、{@code sessionPersistence}（{@code name} 已建模）</li>
 *   <li>filter 级：{@code CORS}、{@code ExternalAuth} 两种 type（overlay 按 type 保住整个元素）</li>
 *   <li>match 级：未建模的兄弟键（overlay 逐元素覆盖建模键，其余保留）</li>
 *   <li>{@code requestMirror.percent} 已建模；{@code requestMirror.fraction} 未建模</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class HttpRouteDTO extends BaseResources {

    /** spec.parentRefs（可空；空 = 不挂任何父资源，仅"MESH"消费者路由会这样用） */
    private List<ParentRefDTO> parentRefs;

    /** spec.hostnames（空 = 所有主机名） */
    private List<String> hostnames;

    /** spec.rules */
    private List<Rule> rules;

    /** status.parents（只读）：各父资源对本 Route 的接受情况。与其余 4 类 Route 共用 {@link RouteParentStatusDTO} */
    private List<RouteParentStatusDTO> parentStatuses;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    /** spec.rules[] 项 */
    @Data
    public static class Rule {

        /** rule 名（可选，本 Route 内唯一）。已建模：v1 起 rule 可命名，UI 直接可编辑 */
        private String name;

        /** 匹配条件（空 = 匹配全部请求；CRD 默认 {@code path: {type: PathPrefix, value: /}}） */
        private List<Match> matches;

        /** 过滤器（同一 type 至多一个；RequestRedirect 与 URLRewrite 互斥） */
        private List<Filter> filters;

        /** 后端（空 = 400；Service 引用必须给 port） */
        private List<BackendRefDTO> backendRefs;

    }

    /** rule.matches[] 项：四个维度 AND。 */
    @Data
    public static class Match {

        private PathMatch path;

        /** HTTP 方法（GET/POST/…） */
        private String method;

        private List<HeaderMatch> headers;

        private List<QueryParamMatch> queryParams;

    }

    /** 路径匹配：{@code type} = Exact / PathPrefix / RegularExpression。 */
    @Data
    public static class PathMatch {

        private String type;

        private String value;

    }

    /** 请求头匹配：{@code type} = Exact（缺省）/ RegularExpression。 */
    @Data
    public static class HeaderMatch {

        private String type;

        /** 头名（必填；大小写不敏感） */
        private String name;

        /** 匹配值（必填） */
        private String value;

    }

    /** 查询参数匹配：{@code type} = Exact（缺省）/ RegularExpression。 */
    @Data
    public static class QueryParamMatch {

        private String type;

        /** 参数名（必填） */
        private String name;

        /** 匹配值（必填） */
        private String value;

    }

    /**
     * rule.filters[] 项：{@code type} 决定哪个体生效。
     * <p>已建模 5 种 type；{@code CORS} / {@code ExternalAuth} 未建模 —— overlay 按 type 保留整个元素，
     * 因此存量对象里的 CORS filter 不会因为平台编辑而消失（前端只在 YAML tab 里看得到它）。
     */
    @Data
    public static class Filter {

        /** RequestHeaderModifier / ResponseHeaderModifier / RequestRedirect / URLRewrite / RequestMirror / ExtensionRef */
        private String type;

        private HeaderFilter requestHeaderModifier;

        private HeaderFilter responseHeaderModifier;

        private RequestRedirect requestRedirect;

        private UrlRewrite urlRewrite;

        private RequestMirror requestMirror;

        private ExtensionRefDTO extensionRef;

    }

    /** 头操作的三种动作：set / add / remove（增减列表均可为空）。 */
    @Data
    public static class HeaderFilter {

        private List<HeaderValue> set;

        private List<HeaderValue> add;

        private List<String> remove;

    }

    /** 头名 + 值。 */
    @Data
    public static class HeaderValue {

        private String name;

        private String value;

    }

    /** RequestRedirect：{@code statusCode} 仅 301/302（缺省 302）。 */
    @Data
    public static class RequestRedirect {

        /** http / https；缺省不变 */
        private String scheme;

        /** 重定向目标主机名；缺省不变 */
        private String hostname;

        private PathModifier path;

        /** 目标端口；缺省不变 */
        private Integer port;

        /** 301 或 302（缺省 302） */
        private Integer statusCode;

    }

    /** URLRewrite：hostname 与 path 至少给一个。 */
    @Data
    public static class UrlRewrite {

        private String hostname;

        private PathModifier path;

    }

    /** 路径重写：{@code type} = ReplaceFullPath（配 replaceFullPath）/ ReplacePrefixMatch（配 replacePrefixMatch）。 */
    @Data
    public static class PathModifier {

        private String type;

        private String replaceFullPath;

        private String replacePrefixMatch;

    }

    /**
     * RequestMirror：把匹配请求的副本转发到另一个后端（原请求照常走 backendRefs）。
     * <p>{@code backendRef} 用 {@link BackendRefDTO} 复用类型，但该位置的 CRD 语义是
     * BackendObjectReference —— <b>没有 weight</b>，converter 只输出 group/kind/name/namespace/port。
     */
    @Data
    public static class RequestMirror {

        private BackendRefDTO backendRef;

        /** 镜像比例 0–100（缺省全部镜像）；与未建模的 fraction 二选一 */
        private Integer percent;

    }

    @Override
    public String getApiPath() {
        return "/httproutes";
    }

}
