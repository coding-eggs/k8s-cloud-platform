-- ============================================================================================
-- S4.3 卷快照：权限行、角色绑定、租户 RBAC 模板规则
-- 设计：docs/superpowers/specs/2026-11-01-volume-snapshot-design.md
-- ============================================================================================
-- 租户侧：/volumesnapshots/**（读 / 建 / 删三个码；没有更新，快照 spec 不可变）。
--   恢复不在这里：恢复就是 POST /pvcs 带 dataSourceRef，沿用 tenant:pvc:create。
-- 平台侧：/volumesnapshotclasses/** 与 /volumesnapshotcontents/** 只读，复用 platform:cluster:manage
--   （与 StorageClass 同理：集群级、无命名空间维度、无法按租户收窄）。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启。
-- ⚠️ 重放后必须点一次「重下发全部模板」（RBAC 模板页，或 POST /rbacTemplate/sync-all），
--    否则各集群上已有的 tn-tpl-* ClusterRole 还是旧 rules，租户调快照接口会被 K8s 403。
-- ⚠️ 重放后需重新登录或等权限缓存 TTL（30s）。
-- ⚠️ 本仓没有 Flyway，本文件是手工重放的；启动日志里永远不会出现 Migrating schema。
-- 幂等：显式 id + ODKU；角色关联按显式 id 写，不用 LIKE。
-- 路径不带 `/resource` 前缀（V2026_10_08_1 已统一去掉）。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符，否则整个文件重放失败。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_res_vs_capability',  'API', '/volumesnapshots/capability',    'POST',   'tenant:volumesnapshot:read',   '卷快照能力探测'),
 ('perm_res_vs_list',        'API', '/volumesnapshots/list',          'POST',   'tenant:volumesnapshot:read',   '列出卷快照'),
 ('perm_res_vs_get',         'API', '/volumesnapshots/{name}',        'GET',    'tenant:volumesnapshot:read',   '查看卷快照'),
 ('perm_res_vs_yaml',        'API', '/volumesnapshots/{name}/yaml',   'GET',    'tenant:volumesnapshot:read',   '查看卷快照 YAML'),
 ('perm_res_vs_create',      'API', '/volumesnapshots',               'POST',   'tenant:volumesnapshot:create', '创建卷快照'),
 ('perm_res_vs_delete',      'API', '/volumesnapshots/{name}',        'DELETE', 'tenant:volumesnapshot:delete', '删除卷快照'),
 ('perm_res_vsclass_list',   'API', '/volumesnapshotclasses/list',    'POST',   'platform:cluster:manage',      '列出快照类（平台只读）'),
 ('perm_res_vsclass_get',    'API', '/volumesnapshotclasses/{name}',  'GET',    'platform:cluster:manage',      '查看快照类（平台只读）'),
 ('perm_res_vscontent_list', 'API', '/volumesnapshotcontents/list',   'POST',   'platform:cluster:manage',      '列出快照内容（平台只读）'),
 ('perm_res_vscontent_get',  'API', '/volumesnapshotcontents/{name}', 'GET',    'platform:cluster:manage',      '查看快照内容（平台只读）'),
 ('perm_page_volume_snapshots',        'Page', '/resources/volumesnapshots', 'VIEW', 'tenant:page:volume-snapshots',          '页面·卷快照'),
 ('perm_page_volume_snapshot_cluster', 'Page', '/volume-snapshot-cluster',   'VIEW', 'platform:page:volume-snapshot-cluster', '页面·快照类与快照内容（平台）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

-- 新权限行不会按 code 自动继承到角色上，必须逐条显式绑定。
-- tenant_member 只拿读（与 PVC 的创建/删除同一档）；平台行只绑内置 admin。
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_tadmin_res_vs_capability', 'builtin_role_tenant_admin', 'perm_res_vs_capability'),
 ('rp_tadmin_res_vs_list',       'builtin_role_tenant_admin', 'perm_res_vs_list'),
 ('rp_tadmin_res_vs_get',        'builtin_role_tenant_admin', 'perm_res_vs_get'),
 ('rp_tadmin_res_vs_yaml',       'builtin_role_tenant_admin', 'perm_res_vs_yaml'),
 ('rp_tadmin_res_vs_create',     'builtin_role_tenant_admin', 'perm_res_vs_create'),
 ('rp_tadmin_res_vs_delete',     'builtin_role_tenant_admin', 'perm_res_vs_delete'),
 ('rp_tadmin_page_volume_snapshots', 'builtin_role_tenant_admin', 'perm_page_volume_snapshots'),

 ('rp_tmember_res_vs_capability', 'builtin_role_tenant_member', 'perm_res_vs_capability'),
 ('rp_tmember_res_vs_list',       'builtin_role_tenant_member', 'perm_res_vs_list'),
 ('rp_tmember_res_vs_get',        'builtin_role_tenant_member', 'perm_res_vs_get'),
 ('rp_tmember_res_vs_yaml',       'builtin_role_tenant_member', 'perm_res_vs_yaml'),
 ('rp_tmember_page_volume_snapshots', 'builtin_role_tenant_member', 'perm_page_volume_snapshots'),

 ('rp_admin_res_vs_capability',  'builtin_role_admin', 'perm_res_vs_capability'),
 ('rp_admin_res_vs_list',        'builtin_role_admin', 'perm_res_vs_list'),
 ('rp_admin_res_vs_get',         'builtin_role_admin', 'perm_res_vs_get'),
 ('rp_admin_res_vs_yaml',        'builtin_role_admin', 'perm_res_vs_yaml'),
 ('rp_admin_res_vs_create',      'builtin_role_admin', 'perm_res_vs_create'),
 ('rp_admin_res_vs_delete',      'builtin_role_admin', 'perm_res_vs_delete'),
 ('rp_admin_res_vsclass_list',   'builtin_role_admin', 'perm_res_vsclass_list'),
 ('rp_admin_res_vsclass_get',    'builtin_role_admin', 'perm_res_vsclass_get'),
 ('rp_admin_res_vscontent_list', 'builtin_role_admin', 'perm_res_vscontent_list'),
 ('rp_admin_res_vscontent_get',  'builtin_role_admin', 'perm_res_vscontent_get'),
 ('rp_admin_page_volume_snapshots',        'builtin_role_admin', 'perm_page_volume_snapshots'),
 ('rp_admin_page_volume_snapshot_cluster', 'builtin_role_admin', 'perm_page_volume_snapshot_cluster')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- 租户 RBAC 模板：只加 volumesnapshots。快照类和快照内容是集群级，租户侧只经 UPSTREAM 窄投影
-- （admin client）读快照类，不该进租户的 ClusterRole。
-- 用 JSON_ARRAY_APPEND 追加整条 rule，不用字符串 REPLACE（原因见 V2026_10_17_1）。
UPDATE platform_rbac_template
SET rules = JSON_ARRAY_APPEND(rules, '$.rules',
        CAST('{"apiGroups":["snapshot.storage.k8s.io"],"resources":["volumesnapshots"],"verbs":["*"]}' AS JSON))
WHERE built_in = 1
  AND deleted_at IS NULL
  AND rules NOT LIKE '%snapshot.storage.k8s.io%';
