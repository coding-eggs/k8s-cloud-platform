package com.coding.k8sserver.components;

import org.springframework.web.method.HandlerMethod;

/**
 * 由 k8s-server 的 controller / WebSocket handler 实现，声明自己的访问边界。
 *
 * <p>两个资源基类已给出默认值（{@code AbstractClusterResourceController → PLATFORM}、
 * {@code AbstractNamespacedResourceController → TENANT}），无基类的 controller 直接实现本接口。
 * 消费方是 {@code BoundaryAuthorizationManager}（SecurityFilterChain 里统一判定），
 * 它靠 {@code HandlerMappingIntrospector} 拿到目标 handler，读本接口的返回值。
 *
 * <h2>为什么边界是端点属性，而不是 token 属性</h2>
 * 直觉上"JWT 带 tenantInfo 走租户逻辑、不带就要求平台侧"就够了，但那只覆盖命名空间级资源：
 * 集群级 controller（nodes / storageclasses / clusterroles 等）<b>不调用</b> {@link ResourceAccessResolver}，
 * 而租户成员的 token <b>是带</b> tenantInfo 的 —— 按 token 属性判，它们会落进"走原逻辑"，
 * 而原逻辑对它们等于没有校验。所以"这个端点是不是平台侧"只能由端点自己声明。
 *
 * <p>读不到声明时判定的处理见 {@code BoundaryAuthorizationManager}：<b>拒绝</b>（fail-closed，
 * 不静默放行）—— 未分类的 handler 会对所有人 403，包括平台管理员，因此不会被忽略。
 */
public interface AccessBoundaryAware {

    /**本 handler 适用的访问边界 */
    AccessBoundary accessBoundary();

    /**
     * 从任意 handler 对象读边界声明；未实现本接口返回 {@code null}（调用方据此 fail-closed）。
     * <p>
     * {@link HandlerMethod} 传它的 {@code getBean()}（而不是 {@code getBeanType()}）—— bean 是实例，
     * 基类上的接口实现天然被继承，CGLIB/JDK 代理也不影响 {@code instanceof}。
     */
    static AccessBoundary boundaryOf(Object handler) {
        return handler instanceof AccessBoundaryAware aware ? aware.accessBoundary() : null;
    }
}
