-- ============================================================================================
-- 对象存储：桶用量端点的权限行（2026-10-25）
-- ============================================================================================
-- 背景：列表页要显示每个桶的「当前大小」。S3 没有桶大小 API → k8s-server 递归列举求和
--      （有上限，到顶标 truncated）。该端点是**读**操作，沿用 tenant:object-storage:read。
--
-- 幂等：显式 id + ODKU；⚠️ 本仓没有 Flyway，手工重放；重放后无需重下发租户 RBAC 模板。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_os_buckets_stats', 'API', '/object-storage/buckets/stats', 'POST',
  'tenant:object-storage:read', '桶的当前用量（对象数/字节数，列举有上限）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_os_buckets_stats',   'builtin_role_admin','perm_os_buckets_stats'),
 ('rp_tadmin_os_buckets_stats',  'builtin_role_tenant_admin','perm_os_buckets_stats'),
 ('rp_tmember_os_buckets_stats', 'builtin_role_tenant_member','perm_os_buckets_stats')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
