-- ============================================================================================
-- 对象存储：「修改桶容量上限」端点的权限行（2026-10-25）
-- ============================================================================================
-- 建桶是管理员专属，改容量上限同样归管理员（platform:object-storage:manage）。
-- 租户侧不开放：配额是平台的资源治理手段，不能让租户自己调大。
--
-- 幂等：显式 id + ODKU；⚠️ 本仓没有 Flyway，手工重放；重放后无需重下发租户 RBAC 模板。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_os_buckets_quota', 'API', '/object-storage/buckets/quota', 'POST',
  'platform:object-storage:manage', '修改桶的容量上限（null = 不限）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_os_buckets_quota', 'builtin_role_admin', 'perm_os_buckets_quota')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
