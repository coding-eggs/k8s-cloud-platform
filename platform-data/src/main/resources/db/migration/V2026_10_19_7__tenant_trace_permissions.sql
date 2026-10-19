-- ============================================================================================
-- 链路追踪：租户可读（按 platform_tenant_namespace 过滤 span）
-- ============================================================================================
--   POST /traces/search    → tenant:trace:search（已有行 foreign code 复用同端点：ANY-of）
--   GET  /traces/{traceId} → tenant:trace:get
--   GET  /traces/services  → tenant:trace:search
--   Page /mesh/traces      → tenant:page:mesh.trace
--
-- 隔离方式：列表/服务候选由后端把分配表拼成 TraceQL
--   (resource.cluster.name && resource.k8s.namespace.name) || ...
--   详情按每条 span 的资源属性过滤（Tempo 的 trace-by-ID 不支持 span 级投影）。
-- 平台管理员继续用 platform:trace:* 三码（V2026_10_19_2/5），行为不变。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被启动交叉校验拒启（fail-closed）。
-- 幂等：显式 id + ODKU。
-- ============================================================================================

INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_tenant_trace_search','API','/traces/search','POST','tenant:trace:search',
  '查询 trace 列表（按分配表收窄命名空间）'),
 ('perm_tenant_trace_get','API','/traces/{traceId}','GET','tenant:trace:get',
  '查看 trace 详情（只返回本租户命名空间内的 span）'),
 ('perm_tenant_trace_services','API','/traces/services','GET','tenant:trace:search',
  'trace 服务名候选（按分配表收窄）'),
 ('perm_tenant_page_trace','Page','/mesh/traces','VIEW','tenant:page:mesh.trace','页面·链路追踪')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- 内置租户角色：管理员与成员都可看（监控/排障是只读能力）
INSERT INTO platform_role_permission (id,role_id,permission_id) VALUES
 ('rp_tadmin_trace_search','builtin_role_tenant_admin','perm_tenant_trace_search'),
 ('rp_tadmin_trace_get','builtin_role_tenant_admin','perm_tenant_trace_get'),
 ('rp_tadmin_trace_services','builtin_role_tenant_admin','perm_tenant_trace_services'),
 ('rp_tadmin_page_trace','builtin_role_tenant_admin','perm_tenant_page_trace'),
 ('rp_tmember_trace_search','builtin_role_tenant_member','perm_tenant_trace_search'),
 ('rp_tmember_trace_get','builtin_role_tenant_member','perm_tenant_trace_get'),
 ('rp_tmember_trace_services','builtin_role_tenant_member','perm_tenant_trace_services'),
 ('rp_tmember_page_trace','builtin_role_tenant_member','perm_tenant_page_trace')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
