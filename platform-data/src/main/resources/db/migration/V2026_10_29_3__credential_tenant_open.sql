-- ============================================================================================
-- 凭据第一次对租户开放（2026-10-29）。
-- 设计：docs/superpowers/specs/2026-10-29-credential-registry-ia-design.md §6.4
--
-- 范围：Git 凭据 + 外部 Harbor / Docker Hub 连接，租户可自建自管；
--       平台 Harbor 内部（source=HARBOR）的连接仍只对管理员。
--
-- ⚠️ 租户角色不能持有 platform: 码（角色不变量）→ 必须新开 tenant: 这一族，
--    与现有 platform:credential:* 同端点多码并存，授权 ANY-of。
-- ⚠️ 新权限行必须显式绑角色（不按 code 继承）。read 绑三条内置角色，write 只绑 admin + tenant_admin。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_tenant_credential_list',   'API','/credential/list',   'POST','tenant:credential:read',
  '连接列表（按租户可见性收窄）'),
 ('perm_tenant_credential_test',   'API','/credential/test',   'POST','tenant:credential:read',
  '测试连接'),
 ('perm_tenant_credential_create', 'API','/credential/create', 'POST','tenant:credential:write',
  '新建连接（归属强制为调用者租户）'),
 ('perm_tenant_credential_update', 'API','/credential/update', 'POST','tenant:credential:write',
  '更新连接（只能改自己租户的）'),
 ('perm_tenant_credential_delete', 'API','/credential/delete', 'POST','tenant:credential:write',
  '删除连接（只能删自己租户的）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_tcred_list',       'builtin_role_admin','perm_tenant_credential_list'),
 ('rp_admin_tcred_test',       'builtin_role_admin','perm_tenant_credential_test'),
 ('rp_admin_tcred_create',     'builtin_role_admin','perm_tenant_credential_create'),
 ('rp_admin_tcred_update',     'builtin_role_admin','perm_tenant_credential_update'),
 ('rp_admin_tcred_delete',     'builtin_role_admin','perm_tenant_credential_delete'),
 ('rp_tadmin_tcred_list',      'builtin_role_tenant_admin','perm_tenant_credential_list'),
 ('rp_tadmin_tcred_test',      'builtin_role_tenant_admin','perm_tenant_credential_test'),
 ('rp_tadmin_tcred_create',    'builtin_role_tenant_admin','perm_tenant_credential_create'),
 ('rp_tadmin_tcred_update',    'builtin_role_tenant_admin','perm_tenant_credential_update'),
 ('rp_tadmin_tcred_delete',    'builtin_role_tenant_admin','perm_tenant_credential_delete'),
 ('rp_tmember_tcred_list',     'builtin_role_tenant_member','perm_tenant_credential_list'),
 ('rp_tmember_tcred_test',     'builtin_role_tenant_member','perm_tenant_credential_test')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- Git 凭据页：路径从 /credentials 改成 /git-credentials。
-- platform:page:credential 保留（管理员兜底，且可能有自定义角色持有它），
-- 另加一个 tenant: 码给租户 —— 同一页两个 Page 码是过渡态，见 spec §6.4。
UPDATE platform_permission SET resource = '/git-credentials' WHERE id = 'perm_page_credential';

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_page_credential_tenant', 'Page','/git-credentials','view','tenant:page:credential',
  'Git 凭据管理页（租户自管）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_tcred_page',   'builtin_role_admin','perm_page_credential_tenant'),
 ('rp_tadmin_tcred_page',  'builtin_role_tenant_admin','perm_page_credential_tenant'),
 ('rp_tmember_tcred_page', 'builtin_role_tenant_member','perm_page_credential_tenant')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
