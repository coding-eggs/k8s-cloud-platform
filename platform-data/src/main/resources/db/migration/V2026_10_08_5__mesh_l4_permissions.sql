-- ============================================================================================
-- B6 服务网格 Phase 2（GRPCRoute + L4 路由 TCP/TLS/UDP）权限行：API 域 24 行 + Page 域 8 行 + 角色关联
-- ============================================================================================
-- 背景：PermissionCrossCheckRunner 启动期交叉校验要求「每个真实端点都有权限行或在豁免清单」，否则
-- platform-api 拒绝启动（fail-closed）。本批新增 4 个 platform-api controller（各 6 端点，共 24）：
--   · GrpcRouteController  —— /grpcroutes
--   · TcpRouteController   —— /tcproutes
--   · TlsRouteController   —— /tlsroutes
--   · UdpRouteController   —— /udproutes
-- 全部租户域（同 Phase 1 的 /gateways、/httproutes），无豁免端点。
--
-- 权限码：新造资源域动词码 tenant:{grpcroute,tcproute,tlsroute,udproute}:*，
-- 照 V2026_09_29_1 / V2026_10_08_4 的既有写法（六动词一资源一码族）。
--
-- ⚠️ 部署顺序：本批与 V2026_10_08_4 都要先重放再起新构建；重放后需重新登录（权限闭包在签发时计算）。
-- 幂等：显式 id + ODKU，同 V2026_09_27_1 范式。id 前缀 perm_route_ / perm_page_route_ 区分。
-- ⚠️ 前缀**刻意不叫 perm_mesh2_**：SQL 的 LIKE 里 `_` 是单字符通配，`perm_page_mesh_%`（V2026_10_08_4 的
--    角色关联用）会连 `perm_page_mesh2_...` 一起匹配，重放 _4 时会静默把本批行也挂给 admin/tenant-admin/member。
-- ============================================================================================

-- ===== 1) API 域：/grpcroutes（6 行，租户域）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_route_grpc_list',   'API','/grpcroutes/list','POST','tenant:grpcroute:list','列出 GRPCRoute'),
 ('perm_route_grpc_get',    'API','/grpcroutes/{name}','GET','tenant:grpcroute:get','查看 GRPCRoute 详情'),
 ('perm_route_grpc_yaml',   'API','/grpcroutes/{name}/yaml','GET','tenant:grpcroute:yaml','查看 GRPCRoute YAML'),
 ('perm_route_grpc_create', 'API','/grpcroutes','POST','tenant:grpcroute:create','创建 GRPCRoute'),
 ('perm_route_grpc_update', 'API','/grpcroutes/{name}','PUT','tenant:grpcroute:update','更新 GRPCRoute'),
 ('perm_route_grpc_delete', 'API','/grpcroutes/{name}','DELETE','tenant:grpcroute:delete','删除 GRPCRoute')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 2) API 域：/tcproutes（6 行，租户域）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_route_tcp_list',   'API','/tcproutes/list','POST','tenant:tcproute:list','列出 TCPRoute'),
 ('perm_route_tcp_get',    'API','/tcproutes/{name}','GET','tenant:tcproute:get','查看 TCPRoute 详情'),
 ('perm_route_tcp_yaml',   'API','/tcproutes/{name}/yaml','GET','tenant:tcproute:yaml','查看 TCPRoute YAML'),
 ('perm_route_tcp_create', 'API','/tcproutes','POST','tenant:tcproute:create','创建 TCPRoute'),
 ('perm_route_tcp_update', 'API','/tcproutes/{name}','PUT','tenant:tcproute:update','更新 TCPRoute'),
 ('perm_route_tcp_delete', 'API','/tcproutes/{name}','DELETE','tenant:tcproute:delete','删除 TCPRoute')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 3) API 域：/tlsroutes（6 行，租户域）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_route_tls_list',   'API','/tlsroutes/list','POST','tenant:tlsroute:list','列出 TLSRoute'),
 ('perm_route_tls_get',    'API','/tlsroutes/{name}','GET','tenant:tlsroute:get','查看 TLSRoute 详情'),
 ('perm_route_tls_yaml',   'API','/tlsroutes/{name}/yaml','GET','tenant:tlsroute:yaml','查看 TLSRoute YAML'),
 ('perm_route_tls_create', 'API','/tlsroutes','POST','tenant:tlsroute:create','创建 TLSRoute'),
 ('perm_route_tls_update', 'API','/tlsroutes/{name}','PUT','tenant:tlsroute:update','更新 TLSRoute'),
 ('perm_route_tls_delete', 'API','/tlsroutes/{name}','DELETE','tenant:tlsroute:delete','删除 TLSRoute')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 4) API 域：/udproutes（6 行，租户域）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_route_udp_list',   'API','/udproutes/list','POST','tenant:udproute:list','列出 UDPRoute'),
 ('perm_route_udp_get',    'API','/udproutes/{name}','GET','tenant:udproute:get','查看 UDPRoute 详情'),
 ('perm_route_udp_yaml',   'API','/udproutes/{name}/yaml','GET','tenant:udproute:yaml','查看 UDPRoute YAML'),
 ('perm_route_udp_create', 'API','/udproutes','POST','tenant:udproute:create','创建 UDPRoute'),
 ('perm_route_udp_update', 'API','/udproutes/{name}','PUT','tenant:udproute:update','更新 UDPRoute'),
 ('perm_route_udp_delete', 'API','/udproutes/{name}','DELETE','tenant:udproute:delete','删除 UDPRoute')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 5) API 域角色关联（新 code 不会自动挂角色，漏挂 = 部署后全 403）=====
-- tenant-admin → 本批全部 tenant:* 行（24）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_tadmin_route_', p.id), 'builtin_role_tenant_admin', p.id
FROM platform_permission p
WHERE p.id LIKE 'perm_route_%' AND p.code LIKE 'tenant:%' AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- tenant-member → 仅读动词（list/get/yaml）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_tmember_route_', p.id), 'builtin_role_tenant_member', p.id
FROM platform_permission p
WHERE p.id LIKE 'perm_route_%' AND p.code LIKE 'tenant:%'
  AND (p.code LIKE '%:list' OR p.code LIKE '%:get' OR p.code LIKE '%:yaml')
  AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- admin → 本批全部 API 行（admin 需在租户上下文里代管这些资源，同 V2026_09_29_2 的结论）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_admin_route_', p.id), 'builtin_role_admin', p.id
FROM platform_permission p
WHERE p.id LIKE 'perm_route_%' AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- ===== 6) Page 域：8 个新页面（4 个列表 + 4 个编辑；resource = 前端路由 path）=====
-- 与现有资源页前缀一致：/resources/{grpcroutes,tcproutes,tlsroutes,udproutes}(+/editor)。
-- 租户可达 → tenant:page:*（同 gateway/httproute）。
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_page_route_grpc',      'Page','/resources/grpcroutes','VIEW','tenant:page:grpcroute.list','页面·GRPCRoute 列表'),
 ('perm_page_route_grpc_edit', 'Page','/resources/grpcroutes/editor','VIEW','tenant:page:grpcroute.edit','页面·GRPCRoute 编辑'),
 ('perm_page_route_tcp',       'Page','/resources/tcproutes','VIEW','tenant:page:tcproute.list','页面·TCPRoute 列表'),
 ('perm_page_route_tcp_edit',  'Page','/resources/tcproutes/editor','VIEW','tenant:page:tcproute.edit','页面·TCPRoute 编辑'),
 ('perm_page_route_tls',       'Page','/resources/tlsroutes','VIEW','tenant:page:tlsroute.list','页面·TLSRoute 列表'),
 ('perm_page_route_tls_edit',  'Page','/resources/tlsroutes/editor','VIEW','tenant:page:tlsroute.edit','页面·TLSRoute 编辑'),
 ('perm_page_route_udp',       'Page','/resources/udproutes','VIEW','tenant:page:udproute.list','页面·UDPRoute 列表'),
 ('perm_page_route_udp_edit',  'Page','/resources/udproutes/editor','VIEW','tenant:page:udproute.edit','页面·UDPRoute 编辑')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 7) Page 域回填：按「该页主 API code」补挂给已持有该 code 的角色 =====
-- id 用 MD5：role_id + permission_id 相加可能超 varchar(64)（同 V2026_10_07_1）。
INSERT INTO platform_role_permission (id, role_id, permission_id)
SELECT CONCAT('rp_route_pg_', SUBSTRING(MD5(CONCAT(rp.role_id, '|', pg.id)), 1, 32)), rp.role_id, pg.id
FROM (
  SELECT 'tenant:page:grpcroute.list' AS page_code, 'tenant:grpcroute:list' AS api_code
  UNION ALL SELECT 'tenant:page:grpcroute.edit','tenant:grpcroute:create'
  UNION ALL SELECT 'tenant:page:tcproute.list','tenant:tcproute:list'
  UNION ALL SELECT 'tenant:page:tcproute.edit','tenant:tcproute:create'
  UNION ALL SELECT 'tenant:page:tlsroute.list','tenant:tlsroute:list'
  UNION ALL SELECT 'tenant:page:tlsroute.edit','tenant:tlsroute:create'
  UNION ALL SELECT 'tenant:page:udproute.list','tenant:udproute:list'
  UNION ALL SELECT 'tenant:page:udproute.edit','tenant:udproute:create'
) m
JOIN platform_permission pg ON pg.code = m.page_code AND pg.domain = 'Page' AND pg.deleted_at IS NULL
JOIN platform_permission ap ON ap.code = m.api_code AND ap.deleted_at IS NULL
JOIN platform_role_permission rp ON rp.permission_id = ap.id
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);

-- ===== 8) Page 域内置角色兜底 =====
-- admin → 本批全部 Page 行
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_route_page_admin_', p.id), 'builtin_role_admin', p.id
FROM platform_permission p
WHERE p.id LIKE 'perm_page_route_%' AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);

-- tenant-admin → 本批 tenant:page:*
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_route_page_tadmin_', p.id), 'builtin_role_tenant_admin', p.id
FROM platform_permission p
WHERE p.id LIKE 'perm_page_route_%' AND p.code LIKE 'tenant:page:%' AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);

-- tenant-member → 只读子集（.list，不含 .edit）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_route_page_tmember_', p.id), 'builtin_role_tenant_member', p.id
FROM platform_permission p
WHERE p.id LIKE 'perm_page_route_%' AND p.code LIKE 'tenant:page:%.list' AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);
