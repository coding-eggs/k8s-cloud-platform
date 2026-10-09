-- ============================================================================================
-- B6 服务网格（Istio / Gateway API）权限行：API 域 19 行 + Page 域 6 行 + 角色关联
-- ============================================================================================
-- 背景：PermissionCrossCheckRunner 启动期交叉校验要求「每个真实端点都有权限行或在豁免清单」，否则
-- platform-api 拒绝启动（fail-closed）。本批新增
--   · MeshController（platform-api）      —— /mesh/** 7 端点（GatewayClass CRUD + status）
--   · GatewayController（platform-api）   —— /gateways 6 端点（租户域）
--   · HttpRouteController（platform-api） —— /httproutes 6 端点（租户域）
-- 其中 /mesh/gatewayclass-refs 有意进 ExemptPaths（纯候选值窄投影，见该文件说明），不建行。
--
-- 权限码选择（不造新平台码）：
--   · /mesh/**   → platform:cluster:manage（集群级基础设施 = PLATFORM:admin 纵深防御，与 /calico/**、
--                  /nodes/** 同族；admin 已持有该 code，PermissionAuthorizationManager 按 code 判权 → 自动生效）
--   · /gateways、/httproutes → 新造资源域动词码 tenant:gateway:* / tenant:httproute:*，
--                  照 V2026_09_29_1 的既有写法（list/get/yaml/create/update/delete 六动词一资源一码族）
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被启动交叉校验拒启。
-- ⚠️ 重放后需重新登录：token 的权限闭包在签发时计算。
-- 幂等：显式 id + ODKU，同 V2026_09_27_1 / V2026_09_29_1 范式。id 前缀 perm_mesh_ / perm_page_mesh_ 区分。
-- ============================================================================================

-- ===== 1) API 域：/mesh/**（7 行，平台管理面）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_mesh_gc_list',   'API','/mesh/gatewayclasses/list','POST','platform:cluster:manage','列出 GatewayClass'),
 ('perm_mesh_gc_get',    'API','/mesh/gatewayclasses/{name}','GET','platform:cluster:manage','查看 GatewayClass 详情'),
 ('perm_mesh_gc_yaml',   'API','/mesh/gatewayclasses/{name}/yaml','GET','platform:cluster:manage','查看 GatewayClass YAML'),
 ('perm_mesh_gc_create', 'API','/mesh/gatewayclasses','POST','platform:cluster:manage','创建 GatewayClass'),
 ('perm_mesh_gc_update', 'API','/mesh/gatewayclasses/{name}','PUT','platform:cluster:manage','更新 GatewayClass'),
 ('perm_mesh_gc_delete', 'API','/mesh/gatewayclasses/{name}','DELETE','platform:cluster:manage','删除 GatewayClass'),
 ('perm_mesh_status',    'API','/mesh/status','POST','platform:cluster:manage','服务网格状态探测（istio/ambient/gateway-api）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 2) API 域：/gateways（6 行，租户域）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_mesh_gw_list',   'API','/gateways/list','POST','tenant:gateway:list','列出 Gateway'),
 ('perm_mesh_gw_get',    'API','/gateways/{name}','GET','tenant:gateway:get','查看 Gateway 详情'),
 ('perm_mesh_gw_yaml',   'API','/gateways/{name}/yaml','GET','tenant:gateway:yaml','查看 Gateway YAML'),
 ('perm_mesh_gw_create', 'API','/gateways','POST','tenant:gateway:create','创建 Gateway'),
 ('perm_mesh_gw_update', 'API','/gateways/{name}','PUT','tenant:gateway:update','更新 Gateway'),
 ('perm_mesh_gw_delete', 'API','/gateways/{name}','DELETE','tenant:gateway:delete','删除 Gateway')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 3) API 域：/httproutes（6 行，租户域）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_mesh_hr_list',   'API','/httproutes/list','POST','tenant:httproute:list','列出 HTTPRoute'),
 ('perm_mesh_hr_get',    'API','/httproutes/{name}','GET','tenant:httproute:get','查看 HTTPRoute 详情'),
 ('perm_mesh_hr_yaml',   'API','/httproutes/{name}/yaml','GET','tenant:httproute:yaml','查看 HTTPRoute YAML'),
 ('perm_mesh_hr_create', 'API','/httproutes','POST','tenant:httproute:create','创建 HTTPRoute'),
 ('perm_mesh_hr_update', 'API','/httproutes/{name}','PUT','tenant:httproute:update','更新 HTTPRoute'),
 ('perm_mesh_hr_delete', 'API','/httproutes/{name}','DELETE','tenant:httproute:delete','删除 HTTPRoute')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 4) API 域角色关联（新 code 不会自动挂角色，漏挂 = 部署后全 403）=====
-- tenant-admin → 本批全部 tenant:* 行（12）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_tadmin_mesh_', p.id), 'builtin_role_tenant_admin', p.id
FROM platform_permission p
WHERE p.id LIKE 'perm_mesh_%' AND p.code LIKE 'tenant:%' AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- tenant-member → 仅读动词（list/get/yaml）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_tmember_mesh_', p.id), 'builtin_role_tenant_member', p.id
FROM platform_permission p
WHERE p.id LIKE 'perm_mesh_%' AND p.code LIKE 'tenant:%'
  AND (p.code LIKE '%:list' OR p.code LIKE '%:get' OR p.code LIKE '%:yaml')
  AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- admin → 本批全部 API 行（含 tenant:* 资源码；admin 需在租户上下文里代管这些资源，
-- 同 V2026_09_29_2 的结论：admin 只持 platform:% 会失去全部资源页）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_admin_mesh_', p.id), 'builtin_role_admin', p.id
FROM platform_permission p
WHERE p.id LIKE 'perm_mesh_%' AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- ===== 5) Page 域：6 个新页面（resource = 前端路由 path，action = VIEW）=====
-- 前端路由前缀与现有资源页一致：平台级 /mesh/gatewayclasses，租户级 /resources/{gateways,httproutes}。
-- ⚠️ 两个页面码域分列：GatewayClass 平台管理 → platform:page:*（storageclass 先例）；
--    Gateway/HTTPRoute 租户可达 → tenant:page:*（servicemonitor 先例）。
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_page_mesh_gc',      'Page','/mesh/gatewayclasses','VIEW','platform:page:mesh.gatewayclass','页面·GatewayClass 列表'),
 ('perm_page_mesh_gc_edit', 'Page','/mesh/gatewayclasses/editor','VIEW','platform:page:mesh.gatewayclass.edit','页面·GatewayClass 编辑'),
 ('perm_page_mesh_gw',      'Page','/resources/gateways','VIEW','tenant:page:gateway.list','页面·Gateway 列表'),
 ('perm_page_mesh_gw_edit', 'Page','/resources/gateways/editor','VIEW','tenant:page:gateway.edit','页面·Gateway 编辑'),
 ('perm_page_mesh_hr',      'Page','/resources/httproutes','VIEW','tenant:page:httproute.list','页面·HTTPRoute 列表'),
 ('perm_page_mesh_hr_edit', 'Page','/resources/httproutes/editor','VIEW','tenant:page:httproute.edit','页面·HTTPRoute 编辑')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 6) Page 域回填：按「该页主 API code」把 page code 补挂给所有已持有该 code 的角色 =====
-- 映射列成显式清单（而非按前缀推导）—— 页与 API code 不是一对一，推导必然过度授予。
-- id 用 MD5：role_id + permission_id 相加可能超 varchar(64)（同 V2026_10_07_1）。
INSERT INTO platform_role_permission (id, role_id, permission_id)
SELECT CONCAT('rp_mesh_pg_', SUBSTRING(MD5(CONCAT(rp.role_id, '|', pg.id)), 1, 32)), rp.role_id, pg.id
FROM (
  SELECT 'platform:page:mesh.gatewayclass' AS page_code, 'platform:cluster:manage' AS api_code
  UNION ALL SELECT 'platform:page:mesh.gatewayclass.edit','platform:cluster:manage'
  UNION ALL SELECT 'tenant:page:gateway.list','tenant:gateway:list'
  UNION ALL SELECT 'tenant:page:gateway.edit','tenant:gateway:create'
  UNION ALL SELECT 'tenant:page:httproute.list','tenant:httproute:list'
  UNION ALL SELECT 'tenant:page:httproute.edit','tenant:httproute:create'
) m
JOIN platform_permission pg ON pg.code = m.page_code AND pg.domain = 'Page' AND pg.deleted_at IS NULL
JOIN platform_permission ap ON ap.code = m.api_code AND ap.deleted_at IS NULL
JOIN platform_role_permission rp ON rp.permission_id = ap.id
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);

-- ===== 7) Page 域内置角色兜底（第 6 段映射写错也不会让内置角色丢菜单）=====
-- admin → 全部 Page 行（含 tenant:page:*：admin 代管态要进租户页面）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_mesh_page_admin_', p.id), 'builtin_role_admin', p.id
FROM platform_permission p
WHERE p.id LIKE 'perm_page_mesh_%' AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);

-- tenant-admin → 本批 tenant:page:*
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_mesh_page_tadmin_', p.id), 'builtin_role_tenant_admin', p.id
FROM platform_permission p
WHERE p.id LIKE 'perm_page_mesh_%' AND p.code LIKE 'tenant:page:%' AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);

-- tenant-member → 只读子集（.list，不含 .edit）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_mesh_page_tmember_', p.id), 'builtin_role_tenant_member', p.id
FROM platform_permission p
WHERE p.id LIKE 'perm_page_mesh_%' AND p.code LIKE 'tenant:page:%.list' AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);
