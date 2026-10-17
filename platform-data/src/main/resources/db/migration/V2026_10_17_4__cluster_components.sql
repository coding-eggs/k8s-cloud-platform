-- ============================================================================================
-- S1.2：集群组件展示的三条端点权限行
-- ============================================================================================
-- 端点：
--   POST /cluster/components          → 平台侧全量（状态 / 版本 / 健康 / 命名空间 / 功能映射）
--   POST /cluster/component-flags     → 租户可读的功能旗标（CI / 网格 / 网络 / HPA 的门控来源）
--   POST /cluster/components/refresh  → 重新探测（探完覆写缓存）
--
-- ⚠️ component-flags **两行同一路径**（platform:cluster:manage 与 tenant:cluster:capability:view）——
--    这不是笔误，是既有做法：/cluster/capability/get 就是这么写的（V2026_10_07_2）。
--    少写租户那一行，租户进 CI 页就吃 403，而"能不能跑 CI"正是他要的答案。
-- ⚠️ 新 tab **不需要新页面码** —— 它长在集群详情页里，那个页面已由 platform:page:cluster 门禁
--    （platform-web/src/router/index.ts 的 cluster-detail 路由）。
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启。
-- ⚠️ 本仓没有 Flyway，手工重放；启动日志里永远没有 Migrating schema。
-- 幂等：显式 id + ODKU；角色关联按**显式 id**（不用 LIKE，`_` 是通配符）。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_res_cluster_components', 'API','/cluster/components', 'POST','platform:cluster:manage',
  '集群组件清单（全量：状态/版本/健康/命名空间/影响的功能）'),
 ('perm_res_cluster_component_flags_admin', 'API','/cluster/component-flags', 'POST','platform:cluster:manage',
  '组件功能旗标（管理员身份）'),
 ('perm_res_cluster_component_flags_tenant', 'API','/cluster/component-flags', 'POST','tenant:cluster:capability:view',
  '组件功能旗标（租户可读的集群只读快照，门控用）'),
 ('perm_res_cluster_components_refresh', 'API','/cluster/components/refresh', 'POST','platform:cluster:manage',
  '重新探测集群组件')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

-- ⚠️ 新权限行必须**显式绑角色**（不按 code 前缀继承）。
--    tenant:cluster:capability:view 那行按 V2026_10_12_1 的先例绑**三个**内置角色 ——
--    少绑 tenant_member，成员在 CI 页/网格页就拿不到旗标（他本来就持这个码的其它读行）。
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_cluster_components',        'builtin_role_admin', 'perm_res_cluster_components'),
 ('rp_admin_cluster_comp_flags',        'builtin_role_admin', 'perm_res_cluster_component_flags_admin'),
 ('rp_admin_cluster_comp_flags_tenant', 'builtin_role_admin', 'perm_res_cluster_component_flags_tenant'),
 ('rp_tadmin_cluster_comp_flags_tenant','builtin_role_tenant_admin', 'perm_res_cluster_component_flags_tenant'),
 ('rp_tmember_cluster_comp_flags_tenant','builtin_role_tenant_member', 'perm_res_cluster_component_flags_tenant'),
 ('rp_admin_cluster_comp_refresh',      'builtin_role_admin', 'perm_res_cluster_components_refresh')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
