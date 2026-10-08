package com.coding.k8sserver.components;

/**
 * k8s-server 侧 controller 的访问边界声明（由 {@link AccessBoundaryAware} 暴露，见该接口的说明）。
 *
 * <p><b>为什么用声明而不是路径前缀</b>：历史上本层用 {@code /admin/**} 前缀当作"平台侧"的判据，
 * 那是把<b>传输寻址</b>当成了<b>授权概念</b> —— platform-api 侧对应的权限行根本不用这个前缀
 * （是 {@code /calico/**}），且同一前缀族上还挂着两个不同的权限码（{@code platform:cluster:manage}
 * 与 {@code platform:bgp:config:manage}）。任何"单一前缀判据"因此必然至少错一个方向。
 * 边界是资源自身的属性（它有没有租户维度），而这件事基类已经表达了，不需要再抄一份路径清单。
 */
public enum AccessBoundary {

    /**
     * 只允许平台侧调用：要求 token 持有 {@code PLATFORM_SCOPE} authority（= {@code data.platformRoles} 非空，
     * 即持有任意 PLATFORM-scope 角色）。<b>与 token 有无 {@code tenantInfo} 无关</b> —— 详见
     * {@link AccessBoundaryAware} 里"为什么不能只看 tenantInfo"。
     */
    PLATFORM,

    /**
     * 租户边界：本层不额外要求平台身份，边界由 handler 内的
     * {@link ResourceAccessResolver#resolveNamespacedAccess} 做
     * （JWT 的 {@code tenantInfo} 为唯一身份源 + 分配表三元组 + 决定 tenant/admin client）。
     */
    TENANT,

    /**
     * 本层<b>不做</b>边界判定，收窄责任在上游服务层（platform-api）。
     * <p>
     * 当前<b>仅</b>集群级但租户可见的资源使用这一档：PersistentVolume —— 它集群级（本层没有 namespace
     * 可校验），但租户必须能列出属于自己的 PV，跨租户收窄由 platform-api 的 {@code PersistentVolumeService}
     * 按 {@code claimRef.namespace} 完成。选它等于显式承认："这一族对象的隔离不归本层"，
     * 便于一眼搜出全部例外，而不是让它静默绕过。
     */
    UPSTREAM
}
