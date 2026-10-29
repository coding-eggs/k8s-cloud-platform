-- ============================================================================================
-- 镜像仓库页的仓库清单（按 host 聚合的只读视图）。
-- 设计：docs/superpowers/specs/2026-10-29-credential-registry-ia-design.md §6.1
--
-- ⚠️ 表驱动授权：新端点没有权限行时 PermissionCrossCheckRunner 会在启动期 fail-closed。
-- ⚠️ 新权限行必须显式绑角色（不按 code 继承）；三条内置角色都要绑。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_registry_warehouses', 'API','/registry/warehouses','POST','tenant:registry:catalog',
  '镜像仓库清单（安装记录 ∪ 凭据行 ∪ 内置档，按 host 聚合）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_reg_warehouses',   'builtin_role_admin','perm_registry_warehouses'),
 ('rp_tadmin_reg_warehouses',  'builtin_role_tenant_admin','perm_registry_warehouses'),
 ('rp_tmember_reg_warehouses', 'builtin_role_tenant_member','perm_registry_warehouses')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
