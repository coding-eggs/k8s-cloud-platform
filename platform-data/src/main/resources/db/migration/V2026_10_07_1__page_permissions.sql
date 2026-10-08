-- ============================================================================================
-- 页面权限初始化（Page 域独立成码）
-- ============================================================================================
-- 背景：platform_permission.domain 的 DDL 注释与 PermissionService 的 DOMAINS 一直都允许 Page，
-- 前端 PermissionView 的动作下拉里也有 Page —— 但全库<b>一行 Page 都没有</b>。菜单与路由此前靠
-- meta.requiresPerm 复用 API code，页面可见性与接口授权完全耦合：收一个 API code 会连带页面消失，
-- 也无法单独把某个页面发出去。本迁移把页面权限独立成码。
--
-- 模型：domain='Page'，resource=前端路由 path，action='VIEW'，code=<scope>:page:<pageKey>。
--   · 平台页 → platform:page:*（RoleService.assertScopeMatches 的 PLATFORM 角色族）
--   · 租户可达页 → tenant:page:*（TENANT 角色族）
--   · admin 内置角色因 assertScopeMatches 的 admin 豁免，可同时持有两族。
--   · /overview 有意不建行：它是路由守卫的回落目标（无权时 return {name:'overview'}），
--     给它加码会形成重定向死循环。该例外同时写进 docs/development/frontend-permission-conventions.md。
--
-- Page 行不会进 URL 授权规则表：PermissionRegistryFactory / PermissionService.rulesOf 都按
-- domain='API' 过滤，故不参与 PermissionCrossCheck 的裸端点与幽灵行核账，也不影响任何 endpoint 鉴权。
--
-- ⚠️ 破坏性变更：前端 requiresPerm 与菜单从此只认 page code。未被下面回填规则覆盖的自定义角色会
--    失去对应菜单 —— 升级后须在「角色与权限」页为其补勾页面权限。见同批 runbook。
--
-- 幂等：显式 id 命中 PRIMARY KEY + ON DUPLICATE KEY UPDATE，同 V2026_09_27_1 范式。
-- ============================================================================================

-- ===== 1) 权限点（Page 域，47 行）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 -- 平台管理
 ('perm_page_cluster','Page','/clusters','VIEW','platform:page:cluster','页面·集群管理'),
 ('perm_page_node','Page','/nodes','VIEW','platform:page:node','页面·节点管理'),
 ('perm_page_node_detail','Page','/nodes/detail','VIEW','platform:page:node.detail','页面·节点详情'),
 ('perm_page_tenant','Page','/tenants','VIEW','platform:page:tenant','页面·租户管理'),
 ('perm_page_tenant_self','Page','/tenants/detail','VIEW','tenant:page:tenant.self','页面·我的租户（自管轨）'),
 ('perm_page_namespace','Page','/namespaces','VIEW','platform:page:namespace','页面·命名空间管理'),
 ('perm_page_namespace_editor','Page','/namespaces/editor','VIEW','platform:page:namespace.edit','页面·命名空间编辑'),
 ('perm_page_namespace_detail','Page','/namespaces/detail','VIEW','platform:page:namespace.detail','页面·命名空间概览'),
 ('perm_page_template','Page','/templates','VIEW','platform:page:template','页面·RBAC 模板'),
 ('perm_page_user','Page','/users','VIEW','platform:page:user','页面·用户管理'),
 ('perm_page_role','Page','/roles','VIEW','platform:page:role','页面·角色与权限'),
 ('perm_page_permission','Page','/permissions','VIEW','platform:page:permission','页面·权限点'),
 -- 资源管理（租户可达；storageclasses 因无命名空间维度归平台管理员）
 ('perm_page_wl_list','Page','/resources/workloads','VIEW','tenant:page:workload.list','页面·工作负载列表'),
 ('perm_page_wl_detail','Page','/resources/workloads/detail','VIEW','tenant:page:workload.detail','页面·工作负载详情'),
 ('perm_page_wl_edit','Page','/resources/workloads/editor','VIEW','tenant:page:workload.edit','页面·工作负载编辑'),
 ('perm_page_pod_list','Page','/resources/pods','VIEW','tenant:page:pod.list','页面·Pod 列表'),
 ('perm_page_pod_detail','Page','/resources/pods/detail','VIEW','tenant:page:pod.detail','页面·Pod 详情'),
 ('perm_page_pod_container','Page','/resources/pods/container','VIEW','tenant:page:pod.container','页面·容器详情'),
 ('perm_page_cm_list','Page','/resources/configmaps','VIEW','tenant:page:configmap.list','页面·ConfigMap 列表'),
 ('perm_page_cm_edit','Page','/resources/configmaps/editor','VIEW','tenant:page:configmap.edit','页面·ConfigMap 编辑'),
 ('perm_page_sec_list','Page','/resources/secrets','VIEW','tenant:page:secret.list','页面·Secret 列表'),
 ('perm_page_sec_edit','Page','/resources/secrets/editor','VIEW','tenant:page:secret.edit','页面·Secret 编辑'),
 ('perm_page_svc_list','Page','/resources/services','VIEW','tenant:page:service.list','页面·Service 列表'),
 ('perm_page_svc_edit','Page','/resources/services/editor','VIEW','tenant:page:service.edit','页面·Service 编辑'),
 ('perm_page_pvc_list','Page','/resources/pvcs','VIEW','tenant:page:pvc.list','页面·PVC 列表'),
 ('perm_page_pv_list','Page','/resources/persistentvolumes','VIEW','tenant:page:persistentvolume.list','页面·持久卷列表'),
 ('perm_page_sc_list','Page','/resources/storageclasses','VIEW','platform:page:storageclass.list','页面·存储类列表（平台管理员）'),
 ('perm_page_sm_list','Page','/resources/servicemonitors','VIEW','tenant:page:servicemonitor.list','页面·ServiceMonitor 列表'),
 ('perm_page_sm_edit','Page','/resources/servicemonitors/editor','VIEW','tenant:page:servicemonitor.edit','页面·ServiceMonitor 编辑'),
 ('perm_page_pm_list','Page','/resources/podmonitors','VIEW','tenant:page:podmonitor.list','页面·PodMonitor 列表'),
 ('perm_page_pm_edit','Page','/resources/podmonitors/editor','VIEW','tenant:page:podmonitor.edit','页面·PodMonitor 编辑'),
 ('perm_page_hpa_list','Page','/resources/hpas','VIEW','tenant:page:hpa.list','页面·HPA 列表'),
 ('perm_page_hpa_edit','Page','/resources/hpas/editor','VIEW','tenant:page:hpa.edit','页面·HPA 编辑'),
 -- 集群运维
 ('perm_page_ops_ippool','Page','/ops/ippools','VIEW','platform:page:ops.ippool','页面·地址池'),
 ('perm_page_ops_ippool_detail','Page','/ops/ippools/detail','VIEW','platform:page:ops.ippool.detail','页面·地址池详情'),
 ('perm_page_ops_ippool_edit','Page','/ops/ippools/editor','VIEW','platform:page:ops.ippool.edit','页面·地址池编辑'),
 ('perm_page_ops_ipres','Page','/ops/ipreservations','VIEW','platform:page:ops.ipreservation','页面·保留 IP'),
 ('perm_page_ops_ipres_edit','Page','/ops/ipreservations/editor','VIEW','platform:page:ops.ipreservation.edit','页面·保留 IP 编辑'),
 ('perm_page_ops_bgpconf','Page','/ops/bgpconfigurations','VIEW','platform:page:ops.bgpconfiguration','页面·BGP 配置'),
 ('perm_page_ops_bgpconf_detail','Page','/ops/bgpconfigurations/detail','VIEW','platform:page:ops.bgpconfiguration.detail','页面·BGP 配置详情'),
 ('perm_page_ops_bgpconf_edit','Page','/ops/bgpconfigurations/editor','VIEW','platform:page:ops.bgpconfiguration.edit','页面·BGP 配置编辑（需 bgp:config:manage）'),
 ('perm_page_ops_bgppeer','Page','/ops/bgppeers','VIEW','platform:page:ops.bgppeer','页面·BGP 对等体'),
 ('perm_page_ops_bgppeer_detail','Page','/ops/bgppeers/detail','VIEW','platform:page:ops.bgppeer.detail','页面·BGP 对等体详情'),
 ('perm_page_ops_bgppeer_edit','Page','/ops/bgppeers/editor','VIEW','platform:page:ops.bgppeer.edit','页面·BGP 对等体编辑'),
 ('perm_page_ops_bgpfilter','Page','/ops/bgpfilters','VIEW','platform:page:ops.bgpfilter','页面·BGP 过滤器'),
 ('perm_page_ops_bgpfilter_detail','Page','/ops/bgpfilters/detail','VIEW','platform:page:ops.bgpfilter.detail','页面·BGP 过滤器详情'),
 ('perm_page_ops_bgpfilter_edit','Page','/ops/bgpfilters/editor','VIEW','platform:page:ops.bgpfilter.edit','页面·BGP 过滤器编辑')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 2) 自定义角色回填：按「该页主 API code」把 page code 补挂给所有已持有该 code 的角色 =====
-- 目的：升级不出现"菜单全隐身"的悬崖。映射列成显式清单（而非按前缀推导）——页与 API code 不是
-- 一对一，推导必然过度授予；显式清单可审计，且只在本次一次性使用（后续新页在角色管理页勾选即可）。
-- 内置 admin/tenant-* 角色也走这条路径（它们持有这些 API code），第 3 段再兜一层保证 admin 不漏。
--
-- id 用 MD5 而非拼 role_id+permission_id：两者相加最长可达 73 字符，会超出 platform_role_permission.id
-- 的 varchar(64)；MD5 定长且确定，重放同 id → ON DUPLICATE KEY 幂等（另有 UNIQUE(role_id,permission_id) 兜底）。
INSERT INTO platform_role_permission (id, role_id, permission_id)
SELECT CONCAT('rp_pg_', SUBSTRING(MD5(CONCAT(rp.role_id, '|', pg.id)), 1, 32)), rp.role_id, pg.id
FROM (
  SELECT 'platform:page:cluster' AS page_code, 'platform:cluster:manage' AS api_code
  UNION ALL SELECT 'platform:page:node','platform:cluster:manage'
  UNION ALL SELECT 'platform:page:node.detail','platform:cluster:manage'
  UNION ALL SELECT 'platform:page:tenant','platform:tenant:read'
  UNION ALL SELECT 'tenant:page:tenant.self','tenant:member:manage'
  UNION ALL SELECT 'platform:page:namespace','platform:allocation:list'
  UNION ALL SELECT 'platform:page:namespace.detail','platform:allocation:list'
  UNION ALL SELECT 'platform:page:namespace.edit','platform:allocation:manage'
  UNION ALL SELECT 'platform:page:template','platform:template:manage'
  UNION ALL SELECT 'platform:page:user','platform:user:manage'
  UNION ALL SELECT 'platform:page:role','platform:role:manage'
  UNION ALL SELECT 'platform:page:permission','platform:role:manage'
  UNION ALL SELECT 'platform:page:storageclass.list','platform:cluster:manage'
  UNION ALL SELECT 'tenant:page:workload.list','tenant:workload:list'
  UNION ALL SELECT 'tenant:page:workload.detail','tenant:workload:get'
  UNION ALL SELECT 'tenant:page:workload.edit','tenant:workload:create'
  UNION ALL SELECT 'tenant:page:pod.list','tenant:pod:list'
  UNION ALL SELECT 'tenant:page:pod.detail','tenant:pod:get'
  UNION ALL SELECT 'tenant:page:pod.container','tenant:pod:logs'
  UNION ALL SELECT 'tenant:page:configmap.list','tenant:configmap:list'
  UNION ALL SELECT 'tenant:page:configmap.edit','tenant:configmap:create'
  UNION ALL SELECT 'tenant:page:secret.list','tenant:secret:list'
  UNION ALL SELECT 'tenant:page:secret.edit','tenant:secret:create'
  UNION ALL SELECT 'tenant:page:service.list','tenant:service:list'
  UNION ALL SELECT 'tenant:page:service.edit','tenant:service:create'
  UNION ALL SELECT 'tenant:page:pvc.list','tenant:pvc:list'
  UNION ALL SELECT 'tenant:page:persistentvolume.list','tenant:persistentvolume:list'
  UNION ALL SELECT 'tenant:page:servicemonitor.list','tenant:servicemonitor:list'
  UNION ALL SELECT 'tenant:page:servicemonitor.edit','tenant:servicemonitor:create'
  UNION ALL SELECT 'tenant:page:podmonitor.list','tenant:podmonitor:list'
  UNION ALL SELECT 'tenant:page:podmonitor.edit','tenant:podmonitor:create'
  UNION ALL SELECT 'tenant:page:hpa.list','tenant:hpa:list'
  UNION ALL SELECT 'tenant:page:hpa.edit','tenant:hpa:create'
  UNION ALL SELECT 'platform:page:ops.ippool','platform:cluster:manage'
  UNION ALL SELECT 'platform:page:ops.ippool.detail','platform:cluster:manage'
  UNION ALL SELECT 'platform:page:ops.ippool.edit','platform:cluster:manage'
  UNION ALL SELECT 'platform:page:ops.ipreservation','platform:cluster:manage'
  UNION ALL SELECT 'platform:page:ops.ipreservation.edit','platform:cluster:manage'
  UNION ALL SELECT 'platform:page:ops.bgpconfiguration','platform:cluster:manage'
  UNION ALL SELECT 'platform:page:ops.bgpconfiguration.detail','platform:cluster:manage'
  UNION ALL SELECT 'platform:page:ops.bgpconfiguration.edit','platform:bgp:config:manage'
  UNION ALL SELECT 'platform:page:ops.bgppeer','platform:cluster:manage'
  UNION ALL SELECT 'platform:page:ops.bgppeer.detail','platform:cluster:manage'
  UNION ALL SELECT 'platform:page:ops.bgppeer.edit','platform:cluster:manage'
  UNION ALL SELECT 'platform:page:ops.bgpfilter','platform:cluster:manage'
  UNION ALL SELECT 'platform:page:ops.bgpfilter.detail','platform:cluster:manage'
  UNION ALL SELECT 'platform:page:ops.bgpfilter.edit','platform:cluster:manage'
) m
JOIN platform_permission pg ON pg.code = m.page_code AND pg.domain = 'Page' AND pg.deleted_at IS NULL
JOIN platform_permission ap ON ap.code = m.api_code AND ap.deleted_at IS NULL
JOIN platform_role_permission rp ON rp.permission_id = ap.id
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);

-- ===== 3) 内置角色兜底（正确的冗余：第 2 段任一映射写错也不会让内置角色丢菜单）=====
-- admin 平台角色 → 全部 Page 行（含 tenant:page:*：admin 代管态需要进租户页面）
INSERT INTO platform_role_permission (id, role_id, permission_id)
SELECT CONCAT('rp_page_admin_', p.id), 'builtin_role_admin', p.id
FROM platform_permission p
WHERE p.domain = 'Page' AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);

-- tenant-admin → 全部 tenant:page:*（租户内管理，编辑器页也要）
INSERT INTO platform_role_permission (id, role_id, permission_id)
SELECT CONCAT('rp_page_tadmin_', p.id), 'builtin_role_tenant_admin', p.id
FROM platform_permission p
WHERE p.domain = 'Page' AND p.code LIKE 'tenant:page:%' AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);

-- tenant-member → 只读子集（list/detail/container + 自管租户页；不含任何 editor）
INSERT INTO platform_role_permission (id, role_id, permission_id)
SELECT CONCAT('rp_page_tmember_', p.id), 'builtin_role_tenant_member', p.id
FROM platform_permission p
WHERE p.domain = 'Page' AND p.deleted_at IS NULL
  AND (p.code LIKE 'tenant:page:%.list' OR p.code LIKE 'tenant:page:%.detail'
       OR p.code LIKE 'tenant:page:%.container' OR p.code = 'tenant:page:tenant.self')
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);
