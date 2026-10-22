-- ============================================================================================
-- 中间件商店：把页面码 tenant:page:app 补绑租户角色（2026-10-22）
-- ============================================================================================
-- 背景：perm_page_app 由 V2026_10_21_1 创建时只绑了 builtin_role_admin；
--       但路由 /apps（中间件商店）对租户可达，租户管理员/成员没有这个页面码时菜单不显示。
--       spec §3.4 明确：tenant:page:app 绑 admin + tenant_admin + tenant_member。
--
-- 幂等：显式 id + ODKU。
-- ⚠️ 本仓没有 Flyway，手工重放。
-- ============================================================================================

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_page_tadmin_app',  'builtin_role_tenant_admin','perm_page_app'),
 ('rp_page_tmember_app', 'builtin_role_tenant_member','perm_page_app')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
