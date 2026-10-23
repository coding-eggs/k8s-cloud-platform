-- ============================================================================================
-- 中间件创建表单的可选项端点（命名空间下拉 / StorageClass 下拉）
-- ============================================================================================
-- 背景：表单原来让用户手输命名空间和 StorageClass。命名空间必须按租户分配表收窄，
--       StorageClass 的读端点只对管理员开放（V2026_10_07_3，租户 403），
--       所以两者都收口到后端算：新增 /middleware/form-options。
--
-- ⚠️ 新权限行必须显式绑角色（不按 code 继承）：本文件同时给 admin / tenant_admin / tenant_member 绑定。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_mw_form_options', 'API','/middleware/form-options', 'POST','tenant:middleware:read', '中间件表单可选项（命名空间/StorageClass）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_mw_form_options',        'builtin_role_admin','perm_mw_form_options'),
 ('rp_tadmin_mw_form_options',       'builtin_role_tenant_admin','perm_mw_form_options'),
 ('rp_tmember_mw_form_options',      'builtin_role_tenant_member','perm_mw_form_options')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
