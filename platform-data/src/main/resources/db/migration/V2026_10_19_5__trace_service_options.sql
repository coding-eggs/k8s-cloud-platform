-- ============================================================================================
-- 链路追踪页「服务」下拉候选：API 行 1 条 + admin 绑定
-- ============================================================================================
--   · TraceController（platform-api） —— GET /traces/services?clusterName&start&end
--     调 Tempo 的 v2 标签值接口（resource.service.name），供页面把「服务」做成下拉。
--
-- 权限码：复用 platform:trace:search（与 /traces/search 同族）；只绑 admin（页面本身就是 admin-only）。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被启动交叉校验拒启（fail-closed）。
-- 幂等：显式 id + ODKU。
-- ============================================================================================

INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_trace_services','API','/traces/services','GET','platform:trace:search',
  'trace 服务名候选（Tempo 标签值，按集群过滤）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

INSERT INTO platform_role_permission (id,role_id,permission_id) VALUES
 ('rp_admin_trace_services','builtin_role_admin','perm_trace_services')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
