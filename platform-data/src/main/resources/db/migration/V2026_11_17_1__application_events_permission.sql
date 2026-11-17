-- ============================================================================================
-- 应用页「事件」页签：Kubernetes 事件只读端点（2026-11-17）
-- ============================================================================================
-- 新端点 /application/events 挂应用读码 tenant:application:read。
--
-- ⚠️ 部署顺序：**先重放本文件，再部署 platform-api** —— 无权限行的新端点会被
--    PermissionCrossCheckRunner 判为「既无权限行也不在豁免清单」直接拒启。
-- ⚠️ 重放后需重新登录或等权限缓存 TTL（30s），否则新码不生效。
-- ⚠️ 本仓没有 Flyway，本文件是手工重放的。
-- 幂等：显式 id + ODKU；角色绑定从「应用概览」复制（能看概览的人就能看事件）。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符，否则整个文件重放失败。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, service, resource, action, code, description) VALUES
 ('perm_app_events', 'API', 'platform-api', '/application/events', 'POST', 'tenant:application:read', '应用事件')
ON DUPLICATE KEY UPDATE
  domain = VALUES(domain), service = VALUES(service), resource = VALUES(resource), action = VALUES(action),
  code = VALUES(code), description = VALUES(description);

-- 谁能看应用概览，谁就能看应用事件（含已自定义绑定的角色，不只 builtin）
INSERT INTO platform_role_permission (id, role_id, permission_id)
SELECT CONCAT('rp_aev_', SUBSTRING(MD5(CONCAT(rp.role_id, '|', 'perm_app_events')), 1, 32)),
       rp.role_id, 'perm_app_events'
FROM platform_role_permission rp
WHERE rp.permission_id = 'perm_app_overview'
GROUP BY rp.role_id
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);
