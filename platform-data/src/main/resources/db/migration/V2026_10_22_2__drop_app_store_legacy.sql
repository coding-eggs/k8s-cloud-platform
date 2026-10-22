-- ============================================================================================
-- 清理：被否决的通用 chart 商店遗留（2026-10-22）
-- ============================================================================================
-- 背景：V2026_10_21_1 建的 platform_app / platform_app_operation / platform_app_catalog
--       三张表，以及 7 条 /app/** 权限行，对应功能已下线（通用 chart 商店被否决，
--       改成固定清单 + Operator 实例，见 V2026_10_22_1）。
--
-- 保留：perm_page_app（tenant:page:app）。路由 /apps 现在是「中间件商店」，
--       页面码沿用同一个 —— 删了它菜单与页面权限就没了。
--
-- ⚠️ 本仓没有 Flyway，手工重放。
-- ⚠️ 第 3 步 DROP 会丢表里的数据。该功能从未上线，表里只可能有开发/测试数据。
-- ⚠️ 老迁移 V2026_10_21_1 保持原样不动（已执行过的迁移不回头改），全新环境会先建后删，幂等无害。
-- ============================================================================================

-- ===== 1) 角色绑定（先删绑定，再删权限行）=====
DELETE FROM platform_role_permission
WHERE permission_id IN (
  'perm_app_list', 'perm_app_get', 'perm_app_preview', 'perm_app_op_get',
  'perm_app_install', 'perm_app_upgrade', 'perm_app_uninstall');

-- ===== 2) 权限行（这 7 个端点已不存在）=====
DELETE FROM platform_permission
WHERE id IN (
  'perm_app_list', 'perm_app_get', 'perm_app_preview', 'perm_app_op_get',
  'perm_app_install', 'perm_app_upgrade', 'perm_app_uninstall');

-- ===== 3) 三张表 =====
DROP TABLE IF EXISTS platform_app_catalog;
DROP TABLE IF EXISTS platform_app_operation;
DROP TABLE IF EXISTS platform_app;
