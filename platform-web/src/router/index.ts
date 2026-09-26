import { createRouter, createWebHistory } from 'vue-router'
import { getAccessToken } from '@/auth/oauth'
import { usePermission } from '@/stores/permission'
import MainLayout from '@/layouts/MainLayout.vue'

declare module 'vue-router' {
  interface RouteMeta {
    /** 免登录页 */
    public?: boolean
    title?: string
    group?: string
    /** 顶栏上下文控件：'full' = 租户→集群→命名空间级联 */
    context?: string
    /** 进入该页所需的权限点 code（token data.permissions；缺则守卫跳总览、菜单隐藏） */
    requiresPerm?: string
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

        // 平台管理（requiresPerm 与 platform_permission seed 的 code 对齐；/permission/list 同归 platform:role:read）
        { path: 'clusters', name: 'clusters', component: () => import('@/views/ClusterView.vue'), meta: { title: '集群管理', group: '平台管理', requiresPerm: 'platform:cluster:manage' } },
        { path: 'nodes', name: 'nodes', component: () => import('@/views/NodeView.vue'), meta: { title: '节点管理', group: '平台管理', requiresPerm: 'platform:cluster:manage' } },
        { path: 'nodes/detail', name: 'node-detail', component: () => import('@/views/NodeDetailView.vue'), meta: { title: '节点详情', group: '平台管理', requiresPerm: 'platform:cluster:manage' } },
        { path: 'tenants', name: 'tenants', component: () => import('@/views/TenantView.vue'), meta: { title: '租户管理', group: '平台管理', requiresPerm: 'platform:tenant:read' } },
        { path: 'namespaces', name: 'namespaces', component: () => import('@/views/NamespaceView.vue'), meta: { title: '命名空间管理', group: '平台管理', requiresPerm: 'platform:allocation:list' } },
        { path: 'templates', name: 'templates', component: () => import('@/views/TemplateView.vue'), meta: { title: 'RBAC 模板', group: '平台管理', requiresPerm: 'platform:template:manage' } },
        // Task 20 待建页的权限点预留（路由先不加，模式已就绪）：
        //   /users  → 'platform:user:manage'（用户管理），/roles → 'platform:role:read'（角色与权限）
        // 集群运维（/ops/ippools 前端占位页，后端端点在 k8s-server 无权限行 → 不加门，见 report）

        // 资源管理（context: full = 顶栏展示 租户→集群→命名空间 chip）
        { path: 'resources/workloads', name: 'workloads', component: () => import('@/views/resource/WorkloadView.vue'), meta: { title: '工作负载', group: '资源管理', context: 'full' } },
        { path: 'resources/workloads/detail', name: 'workload-detail', component: () => import('@/views/resource/WorkloadDetailView.vue'), meta: { title: '工作负载详情', group: '资源管理', context: 'full' } },
        { path: 'resources/workloads/editor', name: 'workload-editor', component: () => import('@/views/resource/WorkloadEditorView.vue'), meta: { title: '工作负载编辑', group: '资源管理', context: 'full' } },
        { path: 'resources/pods', name: 'pods', component: () => import('@/views/resource/PodView.vue'), meta: { title: 'Pod', group: '资源管理', context: 'full' } },
        { path: 'resources/pods/detail', name: 'pod-detail', component: () => import('@/views/resource/PodDetailView.vue'), meta: { title: 'Pod 详情', group: '资源管理', context: 'full' } },
        { path: 'resources/pods/container', name: 'container-detail', component: () => import('@/views/resource/ContainerDetailView.vue'), meta: { title: '容器详情', group: '资源管理', context: 'full' } },
        { path: 'resources/configmaps', name: 'configmaps', component: () => import('@/views/resource/ConfigMapView.vue'), meta: { title: 'ConfigMap', group: '资源管理', context: 'full' } },
        { path: 'resources/configmaps/editor', name: 'configmap-editor', component: () => import('@/views/resource/ConfigMapEditorView.vue'), meta: { title: 'ConfigMap 编辑', group: '资源管理', context: 'full' } },
        { path: 'resources/secrets', name: 'secrets', component: () => import('@/views/resource/SecretView.vue'), meta: { title: 'Secret', group: '资源管理', context: 'full' } },
        { path: 'resources/secrets/editor', name: 'secret-editor', component: () => import('@/views/resource/SecretEditorView.vue'), meta: { title: 'Secret 编辑', group: '资源管理', context: 'full' } },
        { path: 'resources/services', name: 'services', component: () => import('@/views/resource/ServiceView.vue'), meta: { title: 'Service', group: '资源管理', context: 'full' } },
        { path: 'resources/services/editor', name: 'service-editor', component: () => import('@/views/resource/ServiceEditorView.vue'), meta: { title: 'Service 编辑', group: '资源管理', context: 'full' } },
        { path: 'resources/pvcs', name: 'pvcs', component: () => import('@/views/resource/PvcView.vue'), meta: { title: 'PVC', group: '资源管理', context: 'full' } },
        { path: 'resources/servicemonitors', name: 'servicemonitors', component: () => import('@/views/resource/ServiceMonitorView.vue'), meta: { title: 'ServiceMonitor', group: '资源管理', context: 'full' } },
        { path: 'resources/servicemonitors/editor', name: 'servicemonitor-editor', component: () => import('@/views/resource/ServiceMonitorEditorView.vue'), meta: { title: 'ServiceMonitor 编辑', group: '资源管理', context: 'full' } },
        { path: 'resources/podmonitors', name: 'podmonitors', component: () => import('@/views/resource/PodMonitorView.vue'), meta: { title: 'PodMonitor', group: '资源管理', context: 'full' } },
        { path: 'resources/podmonitors/editor', name: 'podmonitor-editor', component: () => import('@/views/resource/PodMonitorEditorView.vue'), meta: { title: 'PodMonitor 编辑', group: '资源管理', context: 'full' } },
        { path: 'resources/hpas', name: 'hpas', component: () => import('@/views/resource/HpaView.vue'), meta: { title: 'HPA', group: '资源管理', context: 'full' } },
        { path: 'resources/hpas/editor', name: 'hpa-editor', component: () => import('@/views/resource/HpaEditorView.vue'), meta: { title: 'HPA 编辑', group: '资源管理', context: 'full' } },

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
  if (need && !perm.has(need)) {
    // 无权直达管理页 → 回落总览（菜单本已隐藏，此处防手输 URL / 权限回收后的旧页停留）。
    // to 本身就是 /overview 时放行，避免重定向死循环。
    if (to.name === 'overview') return true
    return { name: 'overview' }
  }
  return true
})

export default router
