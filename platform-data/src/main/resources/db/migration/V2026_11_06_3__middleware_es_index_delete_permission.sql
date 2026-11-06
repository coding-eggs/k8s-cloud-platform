-- ============================================================================================
-- 中间件实例「删除索引」· 权限行（Elasticsearch，2026-11-06）
-- ============================================================================================
-- POST /middleware/instance/index/delete：删除一个索引，数据不可恢复。
-- 复用 tenant:middleware:write（与删除实例 / 删除实例用户一致）。
-- 角色绑定显式写出（不按 code 继承）：admin、tenant_admin；tenant_member 只读，不绑本行。
-- service 列取默认 platform-api。
--
-- ⚠️ 表驱动授权：没有权限行时 PermissionCrossCheckRunner 启动期 fail-closed，platform-api 起不来
--    —— 先重放本文件再起新构建。本仓没有 Flyway，手工重放。
-- 幂等：显式 id + ON DUPLICATE KEY UPDATE。⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_mw_instance_index_delete', 'API', '/middleware/instance/index/delete', 'POST', 'tenant:middleware:write', '删除索引（ES）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_mw_instance_index_delete',  'builtin_role_admin',        'perm_mw_instance_index_delete'),
 ('rp_tadmin_mw_instance_index_delete', 'builtin_role_tenant_admin', 'perm_mw_instance_index_delete')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
