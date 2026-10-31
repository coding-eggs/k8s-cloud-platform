-- ============================================================================================
-- 权限行：POST /registry/projects/pull-secret/sync（重下发命名空间默认拉取凭据）
-- ============================================================================================
-- 背景（spec 2026-10-31-platform-tenant-boundary-tightening-design.md §6.6）：
--   存量命名空间不会因为升级就拥有 platform-registry-pull —— 命名空间是集群里的对象，SQL 碰不到它们。
--   第一次上线、robot 轮换后自动重下发没成功、Secret 被误删，都靠这个手动入口补。
--
-- 权限码：platform:registry:project:manage（与 /registry/projects/sync、/rebuild 同一族）。
-- ⚠️ 新权限行必须显式绑角色：绑给"已经持有 /registry/projects/sync 那一行"的角色。
-- ⚠️ 先重放本文件再起新构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启（fail-closed）。
-- ⚠️ 重放后需重新登录：token 的权限闭包在签发时计算。
-- ⚠️ 本仓没有 Flyway，本文件是手工重放的。
-- 幂等：显式 id + ODKU。
-- ============================================================================================

INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_registry_pull_sync', 'API','/registry/projects/pull-secret/sync','POST',
  'platform:registry:project:manage','重下发全部命名空间的默认拉取凭据')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

INSERT INTO platform_role_permission (id, role_id, permission_id)
SELECT CONCAT('rp_reg_pull_', SUBSTRING(MD5(CONCAT(rp.role_id, '|', p.id)), 1, 32)), rp.role_id, p.id
FROM platform_permission p
JOIN platform_permission base ON base.id = 'perm_registry_projects_sync'
JOIN platform_role_permission rp ON rp.permission_id = base.id
WHERE p.id = 'perm_registry_pull_sync'
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);
