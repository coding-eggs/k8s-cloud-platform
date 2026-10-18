-- 链路追踪（OTel 批次三）
--
-- 平台自研 trace 查看：查询管理集群的 Tempo。
--   POST /traces/search    → platform:trace:search
--   GET  /traces/{traceId} → platform:trace:get
--   Page /mesh/traces      → platform:page:mesh.trace
--
-- ⚠️ trace 可能跨租户（中心 Tempo 汇总所有集群），本批**只绑 admin**；
--    租户按命名空间过滤留作后续。新权限行必须显式绑角色（不按 code 继承）。
-- 幂等：显式 id + ODKU。

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_trace_search', 'API',  '/traces/search',    'POST', 'platform:trace:search', '查询 trace 列表'),
 ('perm_trace_get',    'API',  '/traces/{traceId}', 'GET',  'platform:trace:get',    '查看 trace 详情'),
 ('perm_page_trace',   'Page', '/mesh/traces',      'VIEW', 'platform:page:mesh.trace', '页面·链路追踪')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- 三条都只绑 admin
INSERT INTO platform_role_permission (id, role_id, permission_id)
SELECT CONCAT('rp_admin_trace_', p.id), 'builtin_role_admin', p.id
FROM platform_permission p
WHERE p.id IN ('perm_trace_search', 'perm_trace_get', 'perm_page_trace')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
