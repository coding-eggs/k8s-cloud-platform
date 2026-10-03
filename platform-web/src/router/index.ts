import { createRouter, createWebHistory } from 'vue-router'
import { getAccessToken } from '@/auth/oauth'
import { usePermission } from '@/stores/permission'
import MainLayout from '@/layouts/MainLayout.vue'
import { resCodes } from '@/permCodes'

declare module 'vue-router' {
  interface RouteMeta {
    /** 免登录页 */
    public?: boolean
    title?: string
    group?: string
    /** 顶栏上下文控件：'full' = 租户→集群→命名空间级联 */
    context?: string
    /** 进入该页所需的权限点 code，ANY-of（命中任一即放行，镜像后端 seed 端点多行 ANY-of 语义）；
     *  缺则守卫跳总览、菜单隐藏（Task 18 裁定：升级为数组建模） */
    requiresPerm?: string[]
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
    { path: '/callback', name: 'callback', component: () => import('@/views/CallbackView.vue'), meta: { public: true } },
    {
      path: '/',
      component: MainLayout,
      redirect: '/overview',
      children: [
        { path: 'overview', name: 'overview', component: () => import('@/views/OverviewView.vue'), meta: { title: '总览' } },

        // 平台管理（requiresPerm 与 platform_permission seed 的 code 对齐，ANY-of 语义）
        { path: 'clusters', name: 'clusters', component: () => import('@/views/ClusterView.vue'), meta: { title: '集群管理', group: '平台管理', requiresPerm: ['platform:cluster:manage'] } },
        { path: 'nodes', name: 'nodes', component: () => import('@/views/NodeView.vue'), meta: { title: '节点管理', group: '平台管理', requiresPerm: ['platform:cluster:manage'] } },
        { path: 'nodes/detail', name: 'node-detail', component: () => import('@/views/NodeDetailView.vue'), meta: { title: '节点详情', group: '平台管理', requiresPerm: ['platform:cluster:manage'] } },
        { path: 'tenants', name: 'tenants', component: () => import('@/views/TenantView.vue'), meta: { title: '租户管理', group: '用户与权限', requiresPerm: ['platform:tenant:read'] } },
        // Task 18 裁定 #2：租户成员（无 platform:tenant:read）经租户 hat 可达的自管详情；
        // tenantId 不进 path，页面取当前租户上下文（TenantDetailView）
        { path: 'tenants/detail', name: 'tenant-detail', component: () => import('@/views/TenantDetailView.vue'), meta: { title: '我的租户', group: '用户与权限', requiresPerm: ['platform:tenant:read', 'tenant:overview:view', 'tenant:member:manage'] } },
        { path: 'namespaces', name: 'namespaces', component: () => import('@/views/NamespaceView.vue'), meta: { title: '命名空间管理', group: '平台管理', requiresPerm: ['platform:allocation:list'] } },
        { path: 'namespaces/editor', name: 'namespace-editor', component: () => import('@/views/NamespaceEditorView.vue'), meta: { title: '命名空间编辑', group: '平台管理', requiresPerm: ['platform:allocation:manage'] } },
        { path: 'namespaces/detail', name: 'namespace-detail', component: () => import('@/views/NamespaceDetailView.vue'), meta: { title: '命名空间概览', group: '平台管理', requiresPerm: ['platform:allocation:list'] } },
        { path: 'templates', name: 'templates', component: () => import('@/views/TemplateView.vue'), meta: { title: 'RBAC 模板', group: '平台管理', requiresPerm: ['platform:template:manage'] } },
        { path: 'users', name: 'users', component: () => import('@/views/UserView.vue'), meta: { title: '用户管理', group: '用户与权限', requiresPerm: ['platform:user:manage'] } },
        { path: 'roles', name: 'roles', component: () => import('@/views/RoleView.vue'), meta: { title: '角色与权限', group: '用户与权限', requiresPerm: ['platform:role:manage'] } },
        { path: 'permissions', name: 'permissions', component: () => import('@/views/PermissionView.vue'), meta: { title: '权限点', group: '用户与权限', requiresPerm: ['platform:role:manage'] } },
        // 集群运维（/ops/ippools 前端占位页，后端端点在 k8s-server 无权限行 → 不加门，见 report）

        // 资源管理（context: full = 顶栏展示 租户→集群→命名空间 chip；requiresPerm 与 V2026_09_29_1 seed 对齐，ANY-of）
        { path: 'resources/workloads', name: 'workloads', component: () => import('@/views/resource/WorkloadView.vue'), meta: { title: '工作负载', group: '资源管理', context: 'full', requiresPerm: [resCodes.workload.list] } },
        { path: 'resources/workloads/detail', name: 'workload-detail', component: () => import('@/views/resource/WorkloadDetailView.vue'), meta: { title: '工作负载详情', group: '资源管理', context: 'full', requiresPerm: [resCodes.workload.get] } },
        { path: 'resources/workloads/editor', name: 'workload-editor', component: () => import('@/views/resource/WorkloadEditorView.vue'), meta: { title: '工作负载编辑', group: '资源管理', context: 'full', requiresPerm: [resCodes.workload.create, resCodes.workload.update] } },
        { path: 'resources/pods', name: 'pods', component: () => import('@/views/resource/PodView.vue'), meta: { title: 'Pod', group: '资源管理', context: 'full', requiresPerm: [resCodes.pod.list] } },
        { path: 'resources/pods/detail', name: 'pod-detail', component: () => import('@/views/resource/PodDetailView.vue'), meta: { title: 'Pod 详情', group: '资源管理', context: 'full', requiresPerm: [resCodes.pod.get] } },
        { path: 'resources/pods/container', name: 'container-detail', component: () => import('@/views/resource/ContainerDetailView.vue'), meta: { title: '容器详情', group: '资源管理', context: 'full', requiresPerm: [resCodes.pod.get, resCodes.pod.logs] } },
        { path: 'resources/configmaps', name: 'configmaps', component: () => import('@/views/resource/ConfigMapView.vue'), meta: { title: 'ConfigMap', group: '资源管理', context: 'full', requiresPerm: [resCodes.configmap.list] } },
        { path: 'resources/configmaps/editor', name: 'configmap-editor', component: () => import('@/views/resource/ConfigMapEditorView.vue'), meta: { title: 'ConfigMap 编辑', group: '资源管理', context: 'full', requiresPerm: [resCodes.configmap.create, resCodes.configmap.update] } },
        { path: 'resources/secrets', name: 'secrets', component: () => import('@/views/resource/SecretView.vue'), meta: { title: 'Secret', group: '资源管理', context: 'full', requiresPerm: [resCodes.secret.list] } },
        { path: 'resources/secrets/editor', name: 'secret-editor', component: () => import('@/views/resource/SecretEditorView.vue'), meta: { title: 'Secret 编辑', group: '资源管理', context: 'full', requiresPerm: [resCodes.secret.create, resCodes.secret.update] } },
        { path: 'resources/services', name: 'services', component: () => import('@/views/resource/ServiceView.vue'), meta: { title: 'Service', group: '资源管理', context: 'full', requiresPerm: [resCodes.service.list] } },
        { path: 'resources/services/editor', name: 'service-editor', component: () => import('@/views/resource/ServiceEditorView.vue'), meta: { title: 'Service 编辑', group: '资源管理', context: 'full', requiresPerm: [resCodes.service.create, resCodes.service.update] } },
        { path: 'resources/pvcs', name: 'pvcs', component: () => import('@/views/resource/PvcView.vue'), meta: { title: 'PVC', group: '资源管理', context: 'full', requiresPerm: [resCodes.pvc.list] } },
        { path: 'resources/servicemonitors', name: 'servicemonitors', component: () => import('@/views/resource/ServiceMonitorView.vue'), meta: { title: 'ServiceMonitor', group: '资源管理', context: 'full', requiresPerm: [resCodes.servicemonitor.list] } },
        { path: 'resources/servicemonitors/editor', name: 'servicemonitor-editor', component: () => import('@/views/resource/ServiceMonitorEditorView.vue'), meta: { title: 'ServiceMonitor 编辑', group: '资源管理', context: 'full', requiresPerm: [resCodes.servicemonitor.create, resCodes.servicemonitor.update] } },
        { path: 'resources/podmonitors', name: 'podmonitors', component: () => import('@/views/resource/PodMonitorView.vue'), meta: { title: 'PodMonitor', group: '资源管理', context: 'full', requiresPerm: [resCodes.podmonitor.list] } },
        { path: 'resources/podmonitors/editor', name: 'podmonitor-editor', component: () => import('@/views/resource/PodMonitorEditorView.vue'), meta: { title: 'PodMonitor 编辑', group: '资源管理', context: 'full', requiresPerm: [resCodes.podmonitor.create, resCodes.podmonitor.update] } },
        { path: 'resources/hpas', name: 'hpas', component: () => import('@/views/resource/HpaView.vue'), meta: { title: 'HPA', group: '资源管理', context: 'full', requiresPerm: [resCodes.hpa.list] } },
        { path: 'resources/hpas/editor', name: 'hpa-editor', component: () => import('@/views/resource/HpaEditorView.vue'), meta: { title: 'HPA 编辑', group: '资源管理', context: 'full', requiresPerm: [resCodes.hpa.create, resCodes.hpa.update] } },

        // 集群运维
        { path: 'ops/ippools', name: 'ippools', component: () => import('@/views/ops/IppoolView.vue'), meta: { title: '地址池', group: '集群运维' } },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

// 未登录一律去 /login（/callback 由页面自行处理换票）；已登录按 meta.requiresPerm 过滤管理页
router.beforeEach(async (to) => {
  if (to.meta.public) return true
  if (!getAccessToken()) return { name: 'login' }
  const perm = usePermission()
  // 首次进 shell：引导权限 + 租户自动进入（spec §4.5）。await 在页面 mount 之前完成，
  // 自动切换后各页 onMounted 首拉即用新 token；后续导航为 O(1) 空转。
  await perm.bootstrap()
  if (!perm.ready) await perm.load() // JWE/损坏 token：等 /user/me 兜底再判定
  const need = to.meta.requiresPerm
  if (need && !perm.hasAny(need)) {
    // 无权直达管理页 → 回落总览（菜单本已隐藏，此处防手输 URL / 权限回收后的旧页停留）。
    // to 本身就是 /overview 时放行，避免重定向死循环。
    if (to.name === 'overview') return true
    return { name: 'overview' }
  }
  return true
})

export default router
