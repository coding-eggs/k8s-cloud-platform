-- ============================================================================================
-- 租户镜像项目列表对租户开放（2026-10-29 评审修复）。
-- 设计：docs/superpowers/specs/2026-10-29-credential-registry-ia-design.md §6.4.1 / §10
--
-- 背景：镜像仓库页对租户开放后，页面无条件调 /registry/projects/list，
--       而该端点只有 platform:registry:project:read（只绑 admin）→ 租户点开自己的
--       平台 Harbor 就 403，并连带把「浏览」一起打死。
--
-- 端点没变，只加一条 tenant: 码（同端点多码，ANY-of）。同步 / 重建**仍只给管理员**。
-- ⚠️ 新权限行必须显式绑角色（不按 code 继承）；三条内置角色都要绑。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_tenant_registry_projects_list', 'API','/registry/projects/list','POST','tenant:registry:project:read',
  '租户自己的 Harbor 镜像项目（按租户帽收窄）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_treg_projects_list',   'builtin_role_admin','perm_tenant_registry_projects_list'),
 ('rp_tadmin_treg_projects_list',  'builtin_role_tenant_admin','perm_tenant_registry_projects_list'),
 ('rp_tmember_treg_projects_list', 'builtin_role_tenant_member','perm_tenant_registry_projects_list')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
