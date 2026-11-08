package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * ListenerSet DTO（{@code gateway.networking.k8s.io/v1}，<b>命名空间级</b>、租户域，<b>只读</b>）。
 * <p>端点走 k8s-server {@code /listener-sets}（{@code AbstractNamespacedResourceController} → 分配表三元组边界）。
 * 写操作在 Operations 层就拒绝：平台自己的 {@code platform-ingress} 由应用发布按端口分配表整份重算，
 * 页面改了也会被下一次同步覆盖；用户自建的 ListenerSet 平台只展示、不管理。
 *
 * <p>{@link #owners} / {@link #platformManaged} 由 platform-api 补全，k8s-server 不填。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ListenerSetDTO extends BaseResources {

    /** spec.parentRef.namespace（缺省 = ListenerSet 自己的命名空间） */
    private String parentNamespace;

    /** spec.parentRef.name：挂到哪个 Gateway */
    private String parentName;

    /** spec.listeners */
    private List<Listener> listeners;

    /** status.conditions（Accepted / Programmed） */
    private List<ConditionDTO> conditions;

    /** status.listeners：各 listener 的 attachedRoutes / conditions */
    private List<ListenerStatus> listenerStatuses;

    /** 创建时间（ISO-8601） */
    private String creationTime;

    /** 是否平台维护（命名空间网关端口的 {@code platform-ingress}）—— platform-api 补全 */
    private Boolean platformManaged;

    /** 平台维护的 ListenerSet：每条 listener 属于哪个应用 / 服务 —— platform-api 补全 */
    private List<ListenerOwner> owners;

    @Data
    public static class Listener {
        private String name;
        private String hostname;
        private Integer port;
        private String protocol;
    }

    @Data
    public static class ListenerStatus {
        private String name;
        /** 已挂载到本 listener 的 Route 数 */
        private Integer attachedRoutes;
        private List<ConditionDTO> conditions;
    }

    /**
     * listener → 端口分配表里的归属。appId 为空 = 分配表里没有这个端口（残留 listener）。
     * appName 为空而 appId 有值 = 应用已不在（只显示端口）。
     */
    @Data
    public static class ListenerOwner {
        private String listenerName;
        private Integer port;
        private String appId;
        private String appName;
        private String serviceKey;
    }

    @Override
    public String getApiPath() {
        return "/listener-sets";
    }
}
