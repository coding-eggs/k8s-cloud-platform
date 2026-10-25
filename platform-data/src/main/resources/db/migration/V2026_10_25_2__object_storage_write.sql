-- ============================================================================================
-- 对象存储：对象写操作（上传 / 删除）的权限行（2026-10-25）
-- ============================================================================================
-- 口径：**租户可以写自己的桶**（2026-10-25 用户决定）。桶归属校验在 platform-api
--      （requireBucket），这里只负责"能不能调这个端点"。
-- 新码 `tenant:object-storage:write`：不动读码，避免"能看"自动等于"能改"。
--
-- 端点只做单对象：S3 没有目录，按前缀批量删一次可能删掉一整片。
--
-- 幂等：显式 id + ODKU；⚠️ 本仓没有 Flyway，手工重放；重放后无需重下发租户 RBAC 模板。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_os_objects_upload', 'API', '/object-storage/objects/upload', 'POST',
  'tenant:object-storage:write', '上传单个对象（租户可写自己名下的桶）'),
 ('perm_os_objects_delete', 'API', '/object-storage/objects/delete', 'POST',
  'tenant:object-storage:write', '删除单个对象（租户可写自己名下的桶）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_os_objects_upload',   'builtin_role_admin','perm_os_objects_upload'),
 ('rp_tadmin_os_objects_upload',  'builtin_role_tenant_admin','perm_os_objects_upload'),
 ('rp_tmember_os_objects_upload', 'builtin_role_tenant_member','perm_os_objects_upload'),
 ('rp_admin_os_objects_delete',   'builtin_role_admin','perm_os_objects_delete'),
 ('rp_tadmin_os_objects_delete',  'builtin_role_tenant_admin','perm_os_objects_delete'),
 ('rp_tmember_os_objects_delete', 'builtin_role_tenant_member','perm_os_objects_delete')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
