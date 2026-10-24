-- ============================================================================================
-- 对象存储：补「凭据列表」端点的权限行（2026-10-24）
-- ============================================================================================
-- 背景：V2026_10_24_1 漏了 POST /object-storage/credentials/list —— platform-api 的
--       PermissionCrossCheckRunner 会在启动时校验「每个端点都有权限行或进豁免清单」，
--       漏了会直接拒绝启动：
--         [RBAC] 以下 endpoint 既无权限行也不在豁免清单，启动失败:
--         [Endpoint[method=POST, pattern=/object-storage/credentials/list]]
--
-- 口径与同批其它读端点一致：租户可读（tenant:object-storage:read），绑
-- admin / tenant_admin / tenant_member。
--
-- 幂等：显式 id + ODKU；⚠️ 本仓没有 Flyway，手工重放；重放后无需重下发租户 RBAC 模板。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_os_credentials_list', 'API', '/object-storage/credentials/list', 'POST',
  'tenant:object-storage:read', '某个桶的有效凭据列表')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_os_credentials_list',   'builtin_role_admin','perm_os_credentials_list'),
 ('rp_tadmin_os_credentials_list',  'builtin_role_tenant_admin','perm_os_credentials_list'),
 ('rp_tmember_os_credentials_list', 'builtin_role_tenant_member','perm_os_credentials_list')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
