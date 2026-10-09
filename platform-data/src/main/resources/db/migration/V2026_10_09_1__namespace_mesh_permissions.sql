-- ============================================================================================
-- B3 §11（命名空间能力开关 + 工作负载 ambient）权限行：2 行 + 角色关联
-- ============================================================================================
-- 背景：PermissionCrossCheckRunner 启动期交叉校验要求「每个真实端点都有权限行或在豁免清单」，否则
-- platform-api 拒绝启动（fail-closed）。本批新增两个端点：
--   · MeshController（platform-api）      —— POST /mesh/gateways          命名空间内 Gateway 列表（平台侧读）
--   · WorkloadController（platform-api）  —— POST /workloads/{name}/mesh-toggle  ambient 开关
--
-- 权限码选择（不造新码）：
--   · /mesh/gateways → platform:cluster:manage。命名空间是平台侧资源，其 istio.io/use-waypoint 标签要在
--     「命名空间编辑」里选候选 waypoint；而 Gateway 是租户域资源，平台管理员没有租户上下文。与 /mesh/**
--     其余端点同族（集群级基础设施）。
--   · /workloads/{name}/mesh-toggle → tenant:workload:update。<b>有意复用既有动词码</b>：它本质就是一次
--     工作负载更新（改 pod template 的两个 label），与同资源的 /workloads/{name}/pause 完全同性质
--     （后者也共享 tenant:workload:update）。不新造 tenant:mesh:* 之类的码，免得"工作负载能不能开 ambient"
--     变成一个需要单独授予的权限 —— 那不是产品语义。
--
-- ⚠️ 命名空间侧的 ambient 两个字段（dataplaneMode / useWaypoint）不需要新行：它们随
--    /namespace/create|update 的 body 携带（已有权限行 perm_ns_b3_create / perm_ns_b3_update）。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被启动交叉校验拒启。
-- ⚠️ 重放后需重新登录：token 的权限闭包在签发时计算。
-- 幂等：显式 id + ODKU。角色关联按 <b>显式 id 列表</b> 关联，不用 LIKE —— id 里的 `_` 是 LIKE 的单字符
-- 通配符，前缀匹配会连带命中同前缀的其它行（V2026_10_08_5 已踩过：perm_page_mesh_% 会匹配 perm_page_mesh2_%）。
-- ============================================================================================

-- ===== 1) API 域：2 行 =====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_nsmesh_wp_candidates','API','/mesh/gateways','POST','platform:cluster:manage',
  '命名空间内 Gateway 列表（平台侧读，供命名空间编辑器的 waypoint 候选）'),
 ('perm_nsmesh_wl_toggle','API','/workloads/{name}/mesh-toggle','POST','tenant:workload:update',
  '切换工作负载 ambient 开关（共享 update 动词）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 2) 角色关联 =====
-- admin → 两行全给。第二行的码是 tenant:*，但 admin 要在租户上下文里代管资源页
--（同 V2026_09_29_2 的结论：admin 只持 platform:% 会失去全部资源页）
INSERT INTO platform_role_permission (id,role_id,permission_id) VALUES
 ('rp_admin_nsmesh_wp','builtin_role_admin','perm_nsmesh_wp_candidates'),
 ('rp_admin_nsmesh_toggle','builtin_role_admin','perm_nsmesh_wl_toggle'),
 ('rp_tadmin_nsmesh_toggle','builtin_role_tenant_admin','perm_nsmesh_wl_toggle')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- tenant-member 有意不给：ambient 开关是写操作（且会触发滚动更新），只读角色不该有。
