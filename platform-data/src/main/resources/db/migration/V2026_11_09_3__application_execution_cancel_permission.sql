-- ============================================================================================
-- 应用发布：取消进行中的执行（2026-11-09）
-- ============================================================================================
-- 新端点 /application/execution/cancel 挂应用写码 tenant:application:write。
--
-- ⚠️ 部署顺序：**先重放本文件，再部署 platform-api** —— 无权限行的新端点会被
--    PermissionCrossCheckRunner 判为「既无权限行也不在豁免清单」直接拒启。
-- ⚠️ 重放后需重新登录或等权限缓存 TTL（30s），否则新码不生效。
-- ⚠️ 本仓没有 Flyway，本文件是手工重放的。
-- 幂等：显式 id + ODKU；角色绑定从「创建发布」的现有绑定复制（谁能发布，谁就能取消）。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符，否则整个文件重放失败。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_app_execution_cancel', 'API', '/application/execution/cancel', 'POST', 'tenant:application:write', '取消发布')
ON DUPLICATE KEY UPDATE
  domain = VALUES(domain), resource = VALUES(resource), action = VALUES(action),
  code = VALUES(code), description = VALUES(description);

-- 谁能创建发布，谁就能取消发布（含已自定义绑定的角色，不只 builtin）
INSERT INTO platform_role_permission (id, role_id, permission_id)
SELECT CONCAT('rp_cec_', SUBSTRING(MD5(CONCAT(rp.role_id, '|', 'perm_app_execution_cancel')), 1, 32)),
       rp.role_id, 'perm_app_execution_cancel'
FROM platform_role_permission rp
WHERE rp.permission_id = 'perm_app_release_create'
GROUP BY rp.role_id
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);
