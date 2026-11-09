-- ============================================================================================
-- 应用入口灰度：权限行与角色绑定（2026-11-09）
-- ============================================================================================
-- 四个写端点（开始 / 调整 / 全量 / 回退）挂应用发布码 tenant:application:write，
-- 指标端点挂应用查看码 tenant:application:read。
--
-- ⚠️ 部署顺序：先重放本文件，再部署 k8s-server（chart 有 canary values），最后部署 platform-api
--    —— 无行的新端点会被 PermissionCrossCheckRunner 拒启。
-- ⚠️ 重放后需重新登录或等权限缓存 TTL（30s）。
-- ⚠️ 本仓没有 Flyway，本文件是手工重放的。
-- 幂等：显式 id + ODKU。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符，否则整个文件重放失败。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_app_canary_start',   'API', '/application/canary/start',   'POST', 'tenant:application:write', '开始入口灰度'),
 ('perm_app_canary_update',  'API', '/application/canary/update',  'POST', 'tenant:application:write', '调整入口灰度'),
 ('perm_app_canary_promote', 'API', '/application/canary/promote', 'POST', 'tenant:application:write', '灰度全量'),
 ('perm_app_canary_abort',   'API', '/application/canary/abort',   'POST', 'tenant:application:write', '灰度回退'),
 ('perm_app_canary_metrics', 'API', '/application/canary/metrics', 'POST', 'tenant:application:read',  '灰度指标对比')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

-- 新权限行不会按 code 自动继承到角色上：按「已持有同类旧行」回填（内置角色与自定义角色一视同仁）。
-- 写端点：持有「创建发布」(perm_app_release_create) 的角色。id 用 MD5 防超长。
INSERT INTO platform_role_permission (id, role_id, permission_id)
SELECT CONCAT('rp_cny_', SUBSTRING(MD5(CONCAT(rp.role_id, '|', c.id)), 1, 32)), rp.role_id, c.id
FROM platform_role_permission rp
JOIN platform_permission c ON c.id IN ('perm_app_canary_start', 'perm_app_canary_update',
                                       'perm_app_canary_promote', 'perm_app_canary_abort')
WHERE rp.permission_id = 'perm_app_release_create'
GROUP BY rp.role_id, c.id
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);

-- 指标：持有「应用概览」(perm_app_overview) 的角色。
INSERT INTO platform_role_permission (id, role_id, permission_id)
SELECT CONCAT('rp_cny_', SUBSTRING(MD5(CONCAT(rp.role_id, '|', 'perm_app_canary_metrics')), 1, 32)),
       rp.role_id, 'perm_app_canary_metrics'
FROM platform_role_permission rp
WHERE rp.permission_id = 'perm_app_overview'
GROUP BY rp.role_id
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);
