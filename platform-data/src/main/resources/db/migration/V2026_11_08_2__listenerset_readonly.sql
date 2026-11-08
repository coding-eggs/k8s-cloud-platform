-- ============================================================================================
-- ListenerSet 只读页：权限行、角色绑定、租户 RBAC 模板只读规则（2026-11-08）
-- ============================================================================================
-- 租户侧：/listener-sets/list、/{name}、/{name}/yaml → tenant:listenerset:list（只读，没有写端点）。
-- 平台侧：/listener-sets/list-all → platform:listenerset:list-all（跨命名空间全局视图，同 ConfigMap）。
-- 页面：/resources/listenersets 挂两个页面码（同链路追踪的先例，同一路径两行）——
--   tenant:page:listenerset.list（租户视角）+ platform:page:listenerset（平台视角，只绑 admin）。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启。
-- ⚠️ 重放后必须点一次「重下发全部模板」（RBAC 模板页，或 POST /rbacTemplate/sync-all），
--    否则各集群上已有的 tn-tpl-* ClusterRole 没有 listenersets，租户打开页面会被 K8s 403。
-- ⚠️ 重放后需重新登录或等权限缓存 TTL（30s）。
-- ⚠️ 本仓没有 Flyway，本文件是手工重放的。
-- 幂等：显式 id + ODKU；模板规则带 NOT LIKE 守卫。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符，否则整个文件重放失败。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_res_ls_list',       'API',  '/listener-sets/list',        'POST', 'tenant:listenerset:list',       '列出 ListenerSet'),
 ('perm_res_ls_get',        'API',  '/listener-sets/{name}',      'GET',  'tenant:listenerset:list',       '查看 ListenerSet'),
 ('perm_res_ls_yaml',       'API',  '/listener-sets/{name}/yaml', 'GET',  'tenant:listenerset:list',       '查看 ListenerSet YAML'),
 ('perm_res_ls_list_all',   'API',  '/listener-sets/list-all',    'POST', 'platform:listenerset:list-all', '跨全部命名空间列出 ListenerSet（平台侧）'),
 ('perm_page_listenersets', 'Page', '/resources/listenersets',    'VIEW', 'tenant:page:listenerset.list',  '页面·ListenerSet（只读）'),
 ('perm_page_listenersets_platform', 'Page', '/resources/listenersets', 'VIEW', 'platform:page:listenerset', '页面·ListenerSet（平台视角，只读）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

-- 新权限行不会按 code 自动继承到角色上，必须逐条显式绑定。只读：三类内置角色都给读与页面。
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_tadmin_res_ls_list',        'builtin_role_tenant_admin',  'perm_res_ls_list'),
 ('rp_tadmin_res_ls_get',         'builtin_role_tenant_admin',  'perm_res_ls_get'),
 ('rp_tadmin_res_ls_yaml',        'builtin_role_tenant_admin',  'perm_res_ls_yaml'),
 ('rp_tadmin_page_listenersets',  'builtin_role_tenant_admin',  'perm_page_listenersets'),

 ('rp_tmember_res_ls_list',       'builtin_role_tenant_member', 'perm_res_ls_list'),
 ('rp_tmember_res_ls_get',        'builtin_role_tenant_member', 'perm_res_ls_get'),
 ('rp_tmember_res_ls_yaml',       'builtin_role_tenant_member', 'perm_res_ls_yaml'),
 ('rp_tmember_page_listenersets', 'builtin_role_tenant_member', 'perm_page_listenersets'),

 ('rp_admin_res_ls_list',         'builtin_role_admin',         'perm_res_ls_list'),
 ('rp_admin_res_ls_get',          'builtin_role_admin',         'perm_res_ls_get'),
 ('rp_admin_res_ls_yaml',         'builtin_role_admin',         'perm_res_ls_yaml'),
 ('rp_admin_res_ls_list_all',     'builtin_role_admin',         'perm_res_ls_list_all'),
 ('rp_admin_page_listenersets',   'builtin_role_admin',         'perm_page_listenersets'),
 ('rp_admin_page_listenersets_platform', 'builtin_role_admin',   'perm_page_listenersets_platform')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- 自定义角色：已能看 Gateway 列表（tenant:gateway:list）的，一并给 ListenerSet 的读与页面
-- （同属 Gateway API 入口族、同为只读）。id 用 MD5 防超长。
INSERT INTO platform_role_permission (id, role_id, permission_id)
SELECT CONCAT('rp_ls_', SUBSTRING(MD5(CONCAT(rp.role_id, '|', ls.id)), 1, 32)), rp.role_id, ls.id
FROM platform_role_permission rp
JOIN platform_permission gw ON gw.id = rp.permission_id AND gw.code = 'tenant:gateway:list' AND gw.deleted_at IS NULL
JOIN platform_permission ls ON ls.id IN ('perm_res_ls_list', 'perm_res_ls_get', 'perm_res_ls_yaml', 'perm_page_listenersets')
WHERE rp.role_id NOT IN ('builtin_role_admin', 'builtin_role_tenant_admin', 'builtin_role_tenant_member')
GROUP BY rp.role_id, ls.id
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);

-- 租户 RBAC 模板：listenersets 单独一条只读规则，不并进 Gateway 那条 verbs:["*"]
-- —— 否则租户能自己建 ListenerSet 挂到入口网关（allowedListeners 还要命名空间标签，但别只靠一道闸）。
-- 用 JSON_ARRAY_APPEND 追加整条 rule，不用字符串 REPLACE（原因见 V2026_10_17_1）。
UPDATE platform_rbac_template
SET rules = JSON_ARRAY_APPEND(rules, '$.rules',
        CAST('{"apiGroups":["gateway.networking.k8s.io"],"resources":["listenersets"],"verbs":["get","list","watch"]}' AS JSON))
WHERE built_in = 1
  AND deleted_at IS NULL
  AND rules NOT LIKE '%listenersets%';
