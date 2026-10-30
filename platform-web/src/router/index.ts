import { createRouter, createWebHistory } from 'vue-router'
import { getAccessToken } from '@/auth/oauth'
import { usePermission } from '@/stores/permission'
import MainLayout from '@/layouts/MainLayout.vue'
import { pageCodes } from '@/pageCodes'
import type { NavDomainKey } from '@/nav'

declare module 'vue-router' {
  interface RouteMeta {
    /** 免登录页 */
    public?: boolean
    title?: string
    /** 顶栏上下文控件：'full' = 租户→集群→命名空间级联 */
    context?: string
    /** 进入该页所需的权限点 code，ANY-of（命中任一即放行）；缺则守卫跳总览、菜单隐藏。
     *
     *  ⚠️ 只放 **Page 域** 码（pageCodes.ts）。页面可见性与接口授权是两件事：这里的码决定"能不能进这个页面"，
     * 能不能调某个接口由后端权限表按 API 域码判定。历史上这里复用的是 API code，导致收一个 API code
     * 会连带页面消失、也无法单独把页面发出去 —— V2026_10_07_1 起改为 Page 域独立成码。
     *
     *  /overview 有意不设本字段：它是本守卫的回落目标，给它加码会形成重定向死循环。 */
    requiresPerm?: string[]
    /** 所属导航域（租户视图下的归属）。详情/编辑页也要填 —— 侧栏高亮要用。见 nav.ts 的 NavDomainKey。 */
    domain?: NavDomainKey
    /** 平台视图下改归哪个域。只有「两个视图都可见」的页面需要（Git 凭据、链路追踪）。 */
    platformDomain?: NavDomainKey
    /** 菜单图标（Element Plus 图标组件名；main.ts 全局注册，故此处用字符串）。 */
    icon?: string
    /** 'hidden' = 不进菜单（详情/编辑页）。缺省 = 进菜单。 */
    nav?: 'hidden'
    /** 域内排序，小在前；缺省 999。 */
    order?: number
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
        // ============ 总览 / 个人中心 ============
        // 总览：无 requiresPerm（登录即可见）—— 路由守卫的回落目标，加码会死循环
        { path: 'overview', name: 'overview', component: () => import('@/views/OverviewView.vue'), meta: { title: '总览' } },
        // 入口在右上角头像下拉里，侧边菜单不露
        { path: 'profile', name: 'profile', component: () => import('@/views/ProfileView.vue'), meta: { title: '个人中心', nav: 'hidden' } },

        // ============ 平台视图 · 集群 ============
        { path: 'clusters', name: 'clusters', component: () => import('@/views/ClusterView.vue'), meta: { title: '集群管理', domain: 'cluster', icon: 'Monitor', order: 1, requiresPerm: [pageCodes.cluster] } },
        { path: 'clusters/detail', name: 'cluster-detail', component: () => import('@/views/ClusterDetailView.vue'), meta: { title: '集群概览', domain: 'cluster', nav: 'hidden', requiresPerm: [pageCodes.cluster] } },
        { path: 'nodes', name: 'nodes', component: () => import('@/views/NodeView.vue'), meta: { title: '节点管理', domain: 'cluster', icon: 'Cpu', order: 2, requiresPerm: [pageCodes.node.list] } },
        { path: 'nodes/detail', name: 'node-detail', component: () => import('@/views/NodeDetailView.vue'), meta: { title: '节点详情', domain: 'cluster', nav: 'hidden', requiresPerm: [pageCodes.node.detail] } },
        { path: 'namespaces', name: 'namespaces', component: () => import('@/views/NamespaceView.vue'), meta: { title: '命名空间管理', domain: 'cluster', icon: 'FolderOpened', order: 3, requiresPerm: [pageCodes.namespace.list] } },
        { path: 'namespaces/editor', name: 'namespace-editor', component: () => import('@/views/NamespaceEditorView.vue'), meta: { title: '命名空间编辑', domain: 'cluster', nav: 'hidden', requiresPerm: [pageCodes.namespace.edit] } },
        { path: 'namespaces/detail', name: 'namespace-detail', component: () => import('@/views/NamespaceDetailView.vue'), meta: { title: '命名空间概览', domain: 'cluster', nav: 'hidden', requiresPerm: [pageCodes.namespace.detail] } },
        { path: 'resources/storageclasses', name: 'storageclasses', component: () => import('@/views/resource/StorageClassView.vue'), meta: { title: '存储类', domain: 'cluster', icon: 'Files', order: 4, context: 'full', requiresPerm: [pageCodes.storageClass.list] } },

        // ============ 平台视图 · 网络 ============
        { path: 'mesh/gatewayclasses', name: 'gatewayclasses', component: () => import('@/views/mesh/GatewayClassView.vue'), meta: { title: 'GatewayClass', domain: 'network', icon: 'Guide', order: 1, requiresPerm: [pageCodes.mesh.gatewayClass.list] } },
        { path: 'mesh/gatewayclasses/editor', name: 'gatewayclass-editor', component: () => import('@/views/mesh/GatewayClassEditorView.vue'), meta: { title: 'GatewayClass 编辑', domain: 'network', nav: 'hidden', requiresPerm: [pageCodes.mesh.gatewayClass.edit] } },
        { path: 'ops/ippools', name: 'ippools', component: () => import('@/views/ops/IppoolView.vue'), meta: { title: '地址池', domain: 'network', icon: 'Grid', order: 2, requiresPerm: [pageCodes.ops.ippool.list] } },
        { path: 'ops/ippools/detail', name: 'ippool-detail', component: () => import('@/views/ops/IppoolDetailView.vue'), meta: { title: '地址池详情', domain: 'network', nav: 'hidden', requiresPerm: [pageCodes.ops.ippool.detail] } },
        { path: 'ops/ippools/editor', name: 'ippool-editor', component: () => import('@/views/ops/IppoolEditorView.vue'), meta: { title: '地址池编辑', domain: 'network', nav: 'hidden', requiresPerm: [pageCodes.ops.ippool.edit] } },
        { path: 'ops/ipreservations', name: 'ipreservations', component: () => import('@/views/ops/IpReservationView.vue'), meta: { title: '保留 IP', domain: 'network', icon: 'Lock', order: 3, requiresPerm: [pageCodes.ops.ipReservation.list] } },
        { path: 'ops/ipreservations/editor', name: 'ipreservation-editor', component: () => import('@/views/ops/IpReservationEditorView.vue'), meta: { title: '保留 IP 编辑', domain: 'network', nav: 'hidden', requiresPerm: [pageCodes.ops.ipReservation.edit] } },
        { path: 'ops/bgpconfigurations', name: 'bgpconfigurations', component: () => import('@/views/ops/BgpConfigurationView.vue'), meta: { title: 'BGP 配置', domain: 'network', icon: 'Connection', order: 4, requiresPerm: [pageCodes.ops.bgpConfiguration.list] } },
        { path: 'ops/bgpconfigurations/detail', name: 'bgpconfiguration-detail', component: () => import('@/views/ops/BgpConfigurationDetailView.vue'), meta: { title: 'BGP 配置详情', domain: 'network', nav: 'hidden', requiresPerm: [pageCodes.ops.bgpConfiguration.detail] } },
        { path: 'ops/bgpconfigurations/editor', name: 'bgpconfiguration-editor', component: () => import('@/views/ops/BgpConfigurationEditorView.vue'), meta: { title: 'BGP 配置编辑', domain: 'network', nav: 'hidden', requiresPerm: [pageCodes.ops.bgpConfiguration.edit] } },
        { path: 'ops/bgppeers', name: 'bgppeers', component: () => import('@/views/ops/BgpPeerView.vue'), meta: { title: 'BGP 对等体', domain: 'network', icon: 'Share', order: 5, requiresPerm: [pageCodes.ops.bgpPeer.list] } },
        { path: 'ops/bgppeers/detail', name: 'bgppeer-detail', component: () => import('@/views/ops/BgpPeerDetailView.vue'), meta: { title: 'BGP 对等体详情', domain: 'network', nav: 'hidden', requiresPerm: [pageCodes.ops.bgpPeer.detail] } },
        { path: 'ops/bgppeers/editor', name: 'bgppeer-editor', component: () => import('@/views/ops/BgpPeerEditorView.vue'), meta: { title: 'BGP 对等体编辑', domain: 'network', nav: 'hidden', requiresPerm: [pageCodes.ops.bgpPeer.edit] } },
        { path: 'ops/bgpfilters', name: 'bgpfilters', component: () => import('@/views/ops/BgpFilterView.vue'), meta: { title: 'BGP 过滤器', domain: 'network', icon: 'Filter', order: 6, requiresPerm: [pageCodes.ops.bgpFilter.list] } },
        { path: 'ops/bgpfilters/detail', name: 'bgpfilter-detail', component: () => import('@/views/ops/BgpFilterDetailView.vue'), meta: { title: 'BGP 过滤器详情', domain: 'network', nav: 'hidden', requiresPerm: [pageCodes.ops.bgpFilter.detail] } },
        { path: 'ops/bgpfilters/editor', name: 'bgpfilter-editor', component: () => import('@/views/ops/BgpFilterEditorView.vue'), meta: { title: 'BGP 过滤器编辑', domain: 'network', nav: 'hidden', requiresPerm: [pageCodes.ops.bgpFilter.edit] } },

        // ============ 平台视图 · 组织 ============
        { path: 'tenants', name: 'tenants', component: () => import('@/views/TenantView.vue'), meta: { title: '租户管理', domain: 'org', icon: 'OfficeBuilding', order: 1, requiresPerm: [pageCodes.tenant.list] } },
        { path: 'users', name: 'users', component: () => import('@/views/UserView.vue'), meta: { title: '用户管理', domain: 'org', icon: 'User', order: 2, requiresPerm: [pageCodes.user] } },
        { path: 'roles', name: 'roles', component: () => import('@/views/RoleView.vue'), meta: { title: '角色与权限', domain: 'org', icon: 'Lock', order: 3, requiresPerm: [pageCodes.role] } },
        { path: 'permissions', name: 'permissions', component: () => import('@/views/PermissionView.vue'), meta: { title: '权限点', domain: 'org', icon: 'Key', order: 4, requiresPerm: [pageCodes.permission] } },
        { path: 'templates', name: 'templates', component: () => import('@/views/TemplateView.vue'), meta: { title: 'RBAC 模板', domain: 'org', icon: 'CollectionTag', order: 5, requiresPerm: [pageCodes.template] } },

        // ============ 平台视图 · 运维（也是 both 面页面的平台落点） ============
        { path: 'audit', name: 'audit', component: () => import('@/views/AuditView.vue'), meta: { title: '审计日志', domain: 'ops', icon: 'Document', order: 1, requiresPerm: [pageCodes.audit] } },
        // Git 凭据：两族页面码都能进（平台级凭据 / 租户自建连接），故出现在两个视图里；
        // 租户视图归「交付」，平台视图改归「运维」。
        { path: 'git-credentials', name: 'git-credentials', component: () => import('@/views/GitCredentialView.vue'), meta: { title: 'Git 凭据', domain: 'build', platformDomain: 'ops', icon: 'Tickets', order: 2, requiresPerm: [pageCodes.credential, pageCodes.credentialTenant] } },
        { path: 'notification-channels', name: 'notification-channels', component: () => import('@/views/NotificationChannelView.vue'), meta: { title: '通知方式', domain: 'ops', icon: 'Message', order: 3, context: 'full', requiresPerm: [pageCodes.notifyChannel] } },
        // 链路追踪：平台自研链路（数据源 = 中心 Tempo），平台/租户两套页代码，同上归两个视图
        { path: 'mesh/traces', name: 'mesh-traces', component: () => import('@/views/mesh/TraceView.vue'), meta: { title: '链路追踪', domain: 'observe', platformDomain: 'ops', icon: 'Timer', order: 6, context: 'full', requiresPerm: [pageCodes.mesh.trace, pageCodes.mesh.traceTenant] } },

        // ============ 租户视图 · 应用 ============
        { path: 'applications', name: 'applications', component: () => import('@/views/ApplicationListView.vue'), meta: { title: '应用编排', domain: 'app', icon: 'Connection', order: 1, context: 'full', requiresPerm: [pageCodes.application] } },
        { path: 'applications/:id', name: 'application-canvas', component: () => import('@/views/ApplicationCanvasView.vue'), meta: { title: '应用画布', domain: 'app', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.application] } },
        { path: 'apps', name: 'apps', component: () => import('@/views/MiddlewareStoreView.vue'), meta: { title: '中间件', domain: 'app', icon: 'Grid', order: 2, context: 'full', requiresPerm: [pageCodes.app] } },
        // 实例列表与商店共用 pageCodes.app：商店管清单与 Operator，这条管已建实例
        { path: 'apps/instances', name: 'middleware-instances', component: () => import('@/views/MiddlewareInstanceView.vue'), meta: { title: '中间件实例', domain: 'app', icon: 'Files', order: 3, context: 'full', requiresPerm: [pageCodes.app] } },
        { path: 'apps/:id', name: 'middleware-detail', component: () => import('@/views/MiddlewareDetailView.vue'), meta: { title: '中间件详情', domain: 'app', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.app] } },

        // ============ 租户视图 · 交付 ============
        { path: 'pipelines', name: 'pipelines', component: () => import('@/views/PipelineListView.vue'), meta: { title: '流水线', domain: 'build', icon: 'Operation', order: 1, context: 'full', requiresPerm: [pageCodes.pipeline] } },
        { path: 'pipelines/editor', name: 'pipeline-editor', component: () => import('@/views/PipelineEditorView.vue'), meta: { title: '流水线编辑', domain: 'build', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.pipeline] } },
        { path: 'pipelines/runs', name: 'pipeline-runs', component: () => import('@/views/PipelineRunView.vue'), meta: { title: '流水线运行', domain: 'build', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.pipeline] } },
        // 一次运行的详情：单独一页（原先挤在列表右侧 560px 那一栏里，DAG 与日志都没法看）
        { path: 'pipelines/run', name: 'pipeline-run-detail', component: () => import('@/views/PipelineRunDetailView.vue'), meta: { title: '运行详情', domain: 'build', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.pipeline] } },
        { path: 'registries', name: 'registries', component: () => import('@/views/RegistryView.vue'), meta: { title: '镜像仓库', domain: 'build', icon: 'Collection', order: 3, requiresPerm: [pageCodes.registry] } },

        // ============ 租户视图 · 资源 ============
        { path: 'resources/workloads', name: 'workloads', component: () => import('@/views/resource/WorkloadView.vue'), meta: { title: '工作负载', domain: 'resource', icon: 'Box', order: 1, context: 'full', requiresPerm: [pageCodes.workload.list] } },
        { path: 'resources/workloads/detail', name: 'workload-detail', component: () => import('@/views/resource/WorkloadDetailView.vue'), meta: { title: '工作负载详情', domain: 'resource', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.workload.detail] } },
        { path: 'resources/workloads/editor', name: 'workload-editor', component: () => import('@/views/resource/WorkloadEditorView.vue'), meta: { title: '工作负载编辑', domain: 'resource', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.workload.edit] } },
        { path: 'resources/pods', name: 'pods', component: () => import('@/views/resource/PodView.vue'), meta: { title: 'Pod', domain: 'resource', icon: 'Cpu', order: 2, context: 'full', requiresPerm: [pageCodes.pod.list] } },
        { path: 'resources/pods/detail', name: 'pod-detail', component: () => import('@/views/resource/PodDetailView.vue'), meta: { title: 'Pod 详情', domain: 'resource', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.pod.detail] } },
        { path: 'resources/pods/container', name: 'container-detail', component: () => import('@/views/resource/ContainerDetailView.vue'), meta: { title: '容器详情', domain: 'resource', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.pod.container] } },
        { path: 'resources/hpas', name: 'hpas', component: () => import('@/views/resource/HpaView.vue'), meta: { title: 'HPA', domain: 'resource', icon: 'TrendCharts', order: 3, context: 'full', requiresPerm: [pageCodes.hpa.list] } },
        { path: 'resources/hpas/editor', name: 'hpa-editor', component: () => import('@/views/resource/HpaEditorView.vue'), meta: { title: 'HPA 编辑', domain: 'resource', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.hpa.edit] } },
        { path: 'resources/configmaps', name: 'configmaps', component: () => import('@/views/resource/ConfigMapView.vue'), meta: { title: 'ConfigMap', domain: 'resource', icon: 'Document', order: 4, context: 'full', requiresPerm: [pageCodes.configmap.list] } },
        { path: 'resources/configmaps/editor', name: 'configmap-editor', component: () => import('@/views/resource/ConfigMapEditorView.vue'), meta: { title: 'ConfigMap 编辑', domain: 'resource', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.configmap.edit] } },
        { path: 'resources/secrets', name: 'secrets', component: () => import('@/views/resource/SecretView.vue'), meta: { title: 'Secret', domain: 'resource', icon: 'Key', order: 5, context: 'full', requiresPerm: [pageCodes.secret.list] } },
        { path: 'resources/secrets/editor', name: 'secret-editor', component: () => import('@/views/resource/SecretEditorView.vue'), meta: { title: 'Secret 编辑', domain: 'resource', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.secret.edit] } },

        // ============ 租户视图 · 存储 ============
        { path: 'resources/pvcs', name: 'pvcs', component: () => import('@/views/resource/PvcView.vue'), meta: { title: 'PVC', domain: 'storage', icon: 'Coin', order: 1, context: 'full', requiresPerm: [pageCodes.pvc.list] } },
        // 持久卷：租户只见绑定到本租户已分配命名空间的 PV（后端按 claimRef.namespace 收窄）
        { path: 'resources/persistentvolumes', name: 'persistentvolumes', component: () => import('@/views/resource/PersistentVolumeView.vue'), meta: { title: '持久卷', domain: 'storage', icon: 'Box', order: 2, context: 'full', requiresPerm: [pageCodes.persistentVolume.list] } },
        // 对象存储（SeaweedFS 集群组件）：桶 / 凭据 / 对象浏览，集群级、无命名空间维度
        { path: 'object-storage', name: 'object-storage', component: () => import('@/views/ObjectStorageView.vue'), meta: { title: '对象存储', domain: 'storage', icon: 'Cloudy', order: 3, requiresPerm: [pageCodes.objectStorage] } },

        // ============ 租户视图 · 网络 ============
        { path: 'resources/services', name: 'services', component: () => import('@/views/resource/ServiceView.vue'), meta: { title: 'Service', domain: 'network', icon: 'Link', order: 1, context: 'full', requiresPerm: [pageCodes.service.list] } },
        { path: 'resources/services/editor', name: 'service-editor', component: () => import('@/views/resource/ServiceEditorView.vue'), meta: { title: 'Service 编辑', domain: 'network', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.service.edit] } },
        { path: 'resources/gateways', name: 'gateways', component: () => import('@/views/resource/GatewayView.vue'), meta: { title: 'Gateway', domain: 'network', icon: 'Promotion', order: 2, context: 'full', requiresPerm: [pageCodes.mesh.gateway.list] } },
        { path: 'resources/gateways/editor', name: 'gateway-editor', component: () => import('@/views/resource/GatewayEditorView.vue'), meta: { title: 'Gateway 编辑', domain: 'network', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.mesh.gateway.edit] } },
        // 路由策略（Route 五合一）：一个页面管 5 种 route；requiresPerm 传 5 个码 = 任一命中即可进
        { path: 'routes', name: 'routes', component: () => import('@/views/resource/RouteListView.vue'), meta: { title: '路由策略', domain: 'network', icon: 'Share', order: 3, context: 'full', requiresPerm: [pageCodes.mesh.httpRoute.list, pageCodes.mesh.grpcRoute.list, pageCodes.mesh.tcpRoute.list, pageCodes.mesh.tlsRoute.list, pageCodes.mesh.udpRoute.list] } },
        { path: 'routes/editor', name: 'route-editor', component: () => import('@/views/resource/RouteEditorView.vue'), meta: { title: '路由策略编辑', domain: 'network', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.mesh.httpRoute.edit, pageCodes.mesh.grpcRoute.edit, pageCodes.mesh.tcpRoute.edit, pageCodes.mesh.tlsRoute.edit, pageCodes.mesh.udpRoute.edit] } },
        // 网格拓扑（S2.4）：与 Gateway 共用一个码 —— 能看 Gateway 的人就能看拓扑
        { path: 'mesh/topology', name: 'mesh-topology', component: () => import('@/views/mesh/MeshTopologyView.vue'), meta: { title: '网格拓扑', domain: 'network', icon: 'DataLine', order: 4, context: 'full', requiresPerm: [pageCodes.mesh.gateway.list] } },

        // ============ 租户视图 · 观测 ============
        { path: 'alerts', name: 'alerts', component: () => import('@/views/AlertView.vue'), meta: { title: '告警', domain: 'observe', icon: 'Bell', order: 1, context: 'full', requiresPerm: [pageCodes.alert] } },
        { path: 'resources/servicemonitors', name: 'servicemonitors', component: () => import('@/views/resource/ServiceMonitorView.vue'), meta: { title: 'ServiceMonitor', domain: 'observe', icon: 'DataLine', order: 2, context: 'full', requiresPerm: [pageCodes.serviceMonitor.list] } },
        { path: 'resources/servicemonitors/editor', name: 'servicemonitor-editor', component: () => import('@/views/resource/ServiceMonitorEditorView.vue'), meta: { title: 'ServiceMonitor 编辑', domain: 'observe', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.serviceMonitor.edit] } },
        { path: 'resources/podmonitors', name: 'podmonitors', component: () => import('@/views/resource/PodMonitorView.vue'), meta: { title: 'PodMonitor', domain: 'observe', icon: 'DataLine', order: 3, context: 'full', requiresPerm: [pageCodes.podMonitor.list] } },
        { path: 'resources/podmonitors/editor', name: 'podmonitor-editor', component: () => import('@/views/resource/PodMonitorEditorView.vue'), meta: { title: 'PodMonitor 编辑', domain: 'observe', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.podMonitor.edit] } },
        { path: 'resources/prometheusrules', name: 'prometheusrules', component: () => import('@/views/resource/PrometheusRuleView.vue'), meta: { title: 'PrometheusRule', domain: 'observe', icon: 'Tickets', order: 4, context: 'full', requiresPerm: [pageCodes.prometheusRule.list] } },
        // 详情用 list 码：能看列表就能看详情（没有独立的 detail 页面码，也不值得单开一行）
        { path: 'resources/prometheusrules/detail', name: 'prometheusrule-detail', component: () => import('@/views/resource/PrometheusRuleDetailView.vue'), meta: { title: 'PrometheusRule 详情', domain: 'observe', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.prometheusRule.list] } },
        { path: 'resources/prometheusrules/editor', name: 'prometheusrule-editor', component: () => import('@/views/resource/PrometheusRuleEditorView.vue'), meta: { title: 'PrometheusRule 编辑', domain: 'observe', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.prometheusRule.edit] } },
        // 日志查询中心（租户可达；后端按分配表收窄命名空间）
        { path: 'observability/logs', name: 'observability-logs', component: () => import('@/views/observability/LogsView.vue'), meta: { title: '日志查询', domain: 'observe', icon: 'Document', order: 5, context: 'full', requiresPerm: [pageCodes.observability.logs] } },

        // ============ 租户视图 · 组织 ============
        // 自管轨「我的租户」：租户 hat 内、无代管权的成员可达；tenantId 不进 path，取当前租户上下文
        { path: 'tenants/detail', name: 'tenant-detail', component: () => import('@/views/TenantDetailView.vue'), meta: { title: '我的租户', domain: 'org', icon: 'OfficeBuilding', order: 1, requiresPerm: [pageCodes.tenant.self] } },
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
    // to 本身就是 /overview 时放行，避免重定向死循环 —— 这也是 /overview 不设 requiresPerm 的原因。
    if (to.name === 'overview') return true
    return { name: 'overview' }
  }
  return true
})

export default router
