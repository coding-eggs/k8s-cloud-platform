-- ============================================================================================
-- 工作负载 OTel 可观测接入（OTel 批次一）：API 行 1 条 + 角色关联
-- ============================================================================================
-- 背景：PermissionCrossCheckRunner 启动期交叉校验要求「每个真实端点都有权限行或在豁免清单」，
-- 否则 platform-api 拒绝启动（fail-closed）。本批新增端点漏了权限行，启动直接报：
--   [RBAC] 以下 endpoint 既无权限行也不在豁免清单，启动失败:
--          [Endpoint[method=POST, pattern=/workloads/{name}/observability]]
--
--   · WorkloadController（platform-api） —— POST /workloads/{name}/observability
--       开/关工作负载的 OTel 接入：把 instrumentation.opentelemetry.io/inject-* 注解与
--       主容器的 OTEL_SERVICE_NAME 写进 pod template（文件日志模式还会挂 sidecar 与日志目录卷）。
-- k8s-server 侧同名端点不参与本校验：那边的授权是 AccessBoundary，不是权限表。
--
-- 权限码选择（只造 0 个新码）：
--   · → tenant:workload:update。<b>有意复用既有动词码</b>：本质就是"改这个工作负载"（改 pod
--     template → 触发滚动更新），与 /workloads/{name}/pause、/mesh-toggle、/image、/rollback
--     完全同性质，它们也共享 tenant:workload:update。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被启动交叉校验拒启。
-- ⚠️ 权限闭包在请求期现算（platform-api 侧 30s 缓存），重放后最多等 30 秒即可；
--    若打开了 platform.jwt.permissions-in-token 回滚开关（闭包签进 token），则需重新登录。
-- 幂等：显式 id + ODKU。角色关联用显式 id（不用 LIKE —— id 里的 `_` 是 LIKE 单字符通配符）。
-- ============================================================================================

-- ===== 1) API 域：1 行 =====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_wl_observability','API','/workloads/{name}/observability','POST','tenant:workload:update',
  '开/关工作负载的 OTel 可观测接入（共享 update 动词，触发滚动更新）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 2) 角色关联：admin / tenant-admin =====
-- admin → 绑定（admin 要在租户上下文里代管资源页，同 V2026_09_29_2 的结论）
INSERT INTO platform_role_permission (id,role_id,permission_id) VALUES
 ('rp_admin_wl_observability','builtin_role_admin','perm_wl_observability'),
-- tenant-admin → 绑定
 ('rp_tadmin_wl_observability','builtin_role_tenant_admin','perm_wl_observability')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- tenant-member 有意不给：可观测接入是写操作（且会触发滚动更新），只读角色不该有。
-- 其它角色（自定义）不给：新行的码不会自动挂到它们身上，需要管理员在权限页按需授予。
