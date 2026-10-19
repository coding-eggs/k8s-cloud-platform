-- ============================================================================================
-- 日志查询中心：API 行 + 页面行 + 角色绑定（admin / tenant-admin / tenant-member）
-- ============================================================================================
--   · LogController（platform-api） —— POST /logs/search
--     查 collector 写进 ES 的应用日志；租户用户由后端按 platform_tenant_namespace 收窄命名空间。
--   · 页面 /observability/logs → tenant:page:observability.logs（租户可达页，平台布局里也显示）
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被启动交叉校验拒启（fail-closed）。
-- 幂等：显式 id + ODKU。
-- ============================================================================================

INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_logs_search','API','/logs/search','POST','tenant:log:query','查询应用日志（按租户收窄命名空间）'),
 ('perm_page_logs','Page','/observability/logs','VIEW','tenant:page:observability.logs','页面·日志查询')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- admin：两行全给（平台视图下可按租户筛选）
INSERT INTO platform_role_permission (id,role_id,permission_id) VALUES
 ('rp_admin_logs_search','builtin_role_admin','perm_logs_search'),
 ('rp_admin_page_logs','builtin_role_admin','perm_page_logs'),
-- tenant-admin：两行
 ('rp_tadmin_logs_search','builtin_role_tenant_admin','perm_logs_search'),
 ('rp_tadmin_page_logs','builtin_role_tenant_admin','perm_page_logs'),
-- tenant-member：只读查询，同样给
 ('rp_tmember_logs_search','builtin_role_tenant_member','perm_logs_search'),
 ('rp_tmember_page_logs','builtin_role_tenant_member','perm_page_logs')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
