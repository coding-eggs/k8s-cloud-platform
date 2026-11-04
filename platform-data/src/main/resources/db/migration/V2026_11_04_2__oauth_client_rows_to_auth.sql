-- ============================================================================================
-- 平台网关：OAuth 客户端管理的 8 条接口权限行从 platform-api 挪到 platform-auth
-- ============================================================================================
-- 背景（spec docs/superpowers/specs/2026-11-03-platform-gateway-design.md §7）：浏览器 /api/oauth-clients/**
-- 经网关直达 auth 的 /internal/clients/**，api 的转发层（OAuthClientController + AuthClientAdminClient
-- + 共享内部令牌）删除。网关按 (service, 内部路径) 判权限，所以行要改成 auth 的端点。
--
-- · 只改 service + resource；code / action / 描述不变 → 角色绑定（只绑 admin）原样有效。
-- · 页面行 perm_page_oauth_client 不动（菜单仍是 /oauth-clients 页面）。
-- · ⚠️ 不要再单独重放 V2026_11_03_2：它的 ODKU 会把 resource 改回 /oauth-clients/*（service 不动），
--   网关就会对客户端管理一律拒绝；真重放了，再跑一遍本文件即可。
--
-- ⚠️ 部署顺序：在 V2026_11_04_1（service 列）之后、与新 auth / 网关构建同批 ——
--   新 auth 启动自检要求 /internal/clients/* 都有 service=platform-auth 的行，缺行拒启。
-- 幂等：按 id 定值 UPDATE，可重复执行。
-- ============================================================================================

UPDATE platform_permission SET service = 'platform-auth', resource = '/internal/clients/list' WHERE id = 'perm_oauth_client_list';
UPDATE platform_permission SET service = 'platform-auth', resource = '/internal/clients/get' WHERE id = 'perm_oauth_client_get';
UPDATE platform_permission SET service = 'platform-auth', resource = '/internal/clients/scopes' WHERE id = 'perm_oauth_client_scopes';
UPDATE platform_permission SET service = 'platform-auth', resource = '/internal/clients/create' WHERE id = 'perm_oauth_client_create';
UPDATE platform_permission SET service = 'platform-auth', resource = '/internal/clients/update' WHERE id = 'perm_oauth_client_update';
UPDATE platform_permission SET service = 'platform-auth', resource = '/internal/clients/delete' WHERE id = 'perm_oauth_client_delete';
UPDATE platform_permission SET service = 'platform-auth', resource = '/internal/clients/enable' WHERE id = 'perm_oauth_client_enable';
UPDATE platform_permission SET service = 'platform-auth', resource = '/internal/clients/reset-secret' WHERE id = 'perm_oauth_client_reset_secret';
