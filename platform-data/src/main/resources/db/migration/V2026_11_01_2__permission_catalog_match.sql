-- ============================================================================================
-- 权限行：POST /permission/catalog 与 POST /permission/match
--        （权限点页的分组目录 + 按实际 URL 反查）
-- ============================================================================================
-- 背景：权限点页从「平铺 268 行表格」改为「按 code 折叠的目录 + 按 URL 反查」。
--   两个新端点都是只读查询，复用既有权限码 platform:role:read（与 /permission/list 同码）。
--
-- ⚠️ 不需要新码，也不需要新的角色绑定：鉴权按 code（ANY-of）判定，已持有
--    platform:role:read 的角色天然通过；platform_role_permission 绑的是**行 id**，
--    本批没有新增可分配的码。
-- ⚠️ 先重放本文件再起新构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启（fail-closed）。
-- ⚠️ 本仓没有 Flyway，本文件是手工重放的。
-- 幂等：显式 id + ODKU。
-- ============================================================================================

INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_perm_catalog', 'API','/permission/catalog','POST','platform:role:read','权限点目录（按 code 分组）'),
 ('perm_perm_match',   'API','/permission/match',  'POST','platform:role:read','按实际 URL 反查权限覆盖')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action), code=VALUES(code);
