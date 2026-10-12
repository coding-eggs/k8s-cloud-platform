-- ============================================================================================
-- 租户概览（Task 9）：4 个租户概览端点 + 命名空间快照端点 —— API 行 9 条 + 角色关联
-- ============================================================================================
-- 背景：PermissionCrossCheckRunner 启动期交叉校验要求「每个真实端点都有权限行或在豁免清单」，否则
-- platform-api 拒绝启动（fail-closed）。本批新增端点：
--   · TenantOverviewController（platform-api）—— POST /tenant/overview                  首屏快照
--                                              POST /tenant/overview/breakdown          资源明细
--                                              POST /tenant/overview/metrics/cpu        租户 CPU 用量曲线
--                                              POST /tenant/overview/metrics/memory     租户内存用量曲线
--   · NamespaceController（platform-api）    —— POST /namespace/stat/get                 命名空间快照（含配额三档）
--
-- 权限码选择（只造 0 个新码，全部复用既有码）：
--   · 4 个租户端点各 2 行 = 同一 (method, path) 两码 ANY-of（既有 /tenant/namespace/list 的范式）：
--       tenant:overview:view     —— 自管轨（租户成员看本租户）
--       platform:allocation:list —— 代管轨（平台管理员代租户看；与分配视图同码）
--   · /namespace/stat/get → platform:allocation:list（与 /namespace/list、/namespace/get 同族；读取面不开放给租户）
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被启动交叉校验拒启。
-- ⚠️ 重放后重新登录或等权限缓存 TTL（30s）：token 里已无权限码，闭包在请求期现算。
-- 幂等：权限行显式 id + ODKU；角色关联的 id 由 permission 表行派生（CONCAT），重放落同 id → PK 冲突 → ODKU。
-- ============================================================================================

-- ===== 1) API 域：9 行（4 个租户端点 × 2 码 + 命名空间快照 1 行）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_tov_overview','API','/tenant/overview','POST','tenant:overview:view','租户概览快照（自管轨）'),
 ('perm_tov_overview_p','API','/tenant/overview','POST','platform:allocation:list','租户概览快照（代管轨）'),
 ('perm_tov_breakdown','API','/tenant/overview/breakdown','POST','tenant:overview:view','租户概览资源明细（自管轨）'),
 ('perm_tov_breakdown_p','API','/tenant/overview/breakdown','POST','platform:allocation:list','租户概览资源明细（代管轨）'),
 ('perm_tov_metrics_cpu','API','/tenant/overview/metrics/cpu','POST','tenant:overview:view','租户 CPU 用量曲线（自管轨）'),
 ('perm_tov_metrics_cpu_p','API','/tenant/overview/metrics/cpu','POST','platform:allocation:list','租户 CPU 用量曲线（代管轨）'),
 ('perm_tov_metrics_mem','API','/tenant/overview/metrics/memory','POST','tenant:overview:view','租户内存用量曲线（自管轨）'),
 ('perm_tov_metrics_mem_p','API','/tenant/overview/metrics/memory','POST','platform:allocation:list','租户内存用量曲线（代管轨）'),
 ('perm_ns_stat_get','API','/namespace/stat/get','POST','platform:allocation:list','命名空间快照（含配额三档状态）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 2) 角色关联：admin 全 9 行；tenant-admin / tenant-member 只绑 tenant:overview:view 那 4 行 =====
-- admin → 全 9 行（含 tenant:* 码：admin 要在租户上下文里代管，同 V2026_09_29_2 / V2026_10_12_1 的结论）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_admin_tov_', p.id), 'builtin_role_admin', p.id FROM platform_permission p
WHERE p.id LIKE 'perm_tov_%' OR p.id = 'perm_ns_stat_get'
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- tenant-admin / tenant-member → 只绑自管轨的 tenant:overview:view 那 4 行
-- （realm 内的租户成员看本租户概览；代管轨的 platform:* 码不发租户角色）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_ttov_', r.role_id, '_', p.id), r.role_id, p.id
FROM platform_permission p
CROSS JOIN (SELECT 'builtin_role_tenant_admin' AS role_id UNION ALL SELECT 'builtin_role_tenant_member') r
WHERE p.id IN ('perm_tov_overview','perm_tov_breakdown','perm_tov_metrics_cpu','perm_tov_metrics_mem')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
