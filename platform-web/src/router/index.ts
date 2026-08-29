import { createRouter, createWebHistory } from 'vue-router'
import { getAccessToken } from '@/auth/oauth'
import MainLayout from '@/layouts/MainLayout.vue'

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

        // 平台管理
        { path: 'clusters', name: 'clusters', component: () => import('@/views/ClusterView.vue'), meta: { title: '集群管理', group: '平台管理' } },
        { path: 'tenants', name: 'tenants', component: () => import('@/views/TenantView.vue'), meta: { title: '租户管理', group: '平台管理' } },
        { path: 'namespaces', name: 'namespaces', component: () => import('@/views/NamespaceView.vue'), meta: { title: '命名空间管理', group: '平台管理' } },
        { path: 'templates', name: 'templates', component: () => import('@/views/TemplateView.vue'), meta: { title: 'RBAC 模板', group: '平台管理' } },

        // 资源管理（context: full = 顶栏展示 租户→集群→命名空间 chip）
        { path: 'resources/workloads', name: 'workloads', component: () => import('@/views/resource/WorkloadView.vue'), meta: { title: '工作负载', group: '资源管理', context: 'full' } },
        { path: 'resources/pods', name: 'pods', component: () => import('@/views/resource/PodView.vue'), meta: { title: 'Pod', group: '资源管理', context: 'full' } },
        { path: 'resources/configmaps', name: 'configmaps', component: () => import('@/views/resource/ConfigMapView.vue'), meta: { title: 'ConfigMap', group: '资源管理', context: 'full' } },
        { path: 'resources/secrets', name: 'secrets', component: () => import('@/views/resource/SecretView.vue'), meta: { title: 'Secret', group: '资源管理', context: 'full' } },
        { path: 'resources/services', name: 'services', component: () => import('@/views/resource/ServiceView.vue'), meta: { title: 'Service', group: '资源管理', context: 'full' } },
        { path: 'resources/pvcs', name: 'pvcs', component: () => import('@/views/resource/PvcView.vue'), meta: { title: 'PVC', group: '资源管理', context: 'full' } },
        { path: 'resources/servicemonitors', name: 'servicemonitors', component: () => import('@/views/resource/ServiceMonitorView.vue'), meta: { title: 'ServiceMonitor', group: '资源管理', context: 'full' } },

        // 集群运维
        { path: 'ops/ippools', name: 'ippools', component: () => import('@/views/ops/IppoolView.vue'), meta: { title: '地址池', group: '集群运维' } },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

// 未登录一律去 /login（/callback 由页面自行处理换票）
router.beforeEach((to) => {
  if (to.meta.public) return true
  if (!getAccessToken()) return { name: 'login' }
  return true
})

export default router
