-- ============================================================================================
-- OAuth 客户端管理迁入平台：内置客户端打标 + 权限行（8 API + 1 Page）+ admin 角色关联
-- ============================================================================================
-- 背景：platform-auth 原有的 client-manage.html + /clients/** 没有任何权限校验，且与平台共用用户体系 ——
-- 任何登录用户都能改 platform-web-client 的回调地址（偷管理员 token）、删客户端、读密钥密文。
-- 本批把管理入口搬到 platform-api 的 /oauth-clients/**（权限表 + 审计），auth 只留内部接口。
-- 设计全文见 docs/superpowers/specs/2026-11-03-oauth-client-management-in-platform-design.md。
--
-- 权限码：
--   · platform:oauth-client:view    —— 列表 / 详情 / scope 目录（不含任何密钥材料）
--   · platform:oauth-client:manage  —— 新建 / 编辑 / 删除 / 启停 / 重置密钥
--   · platform:page:oauth-client    —— 前端页面可见性（Page 域）
-- 只给 admin：持有 manage 码者能改内置客户端的回调地址，等同 root（与 role:manage 同理，已显式接受）。
--
-- 内置标记：platform-web-client 打 platform.client.builtin=true。它决定 token 带不带平台用户信息、
-- 能不能删 / 停用。没打标的话平台前端拿到的是「外部」token（没有 data claim）→ 全站 401。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启（fail-closed），
--    且 auth 新构建依赖内置标记。
-- ⚠️ 重放后需重新登录或等权限缓存 TTL（30s）。
-- 幂等：JSON_SET 重复执行结果相同；权限行显式 id + ODKU；角色关联按显式 id 列表。
-- ============================================================================================

UPDATE oauth2_registered_client
SET client_settings = JSON_SET(client_settings, '$."platform.client.builtin"', true)
WHERE client_id = 'platform-web-client';

-- ===== 权限行：API 域 8 行 + Page 域 1 行 =====
INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_oauth_client_list','API','/oauth-clients/list','POST','platform:oauth-client:view',
  'OAuth 客户端列表（不含密钥）'),
 ('perm_oauth_client_get','API','/oauth-clients/get','POST','platform:oauth-client:view',
  'OAuth 客户端详情（不含密钥）'),
 ('perm_oauth_client_scopes','API','/oauth-clients/scopes','POST','platform:oauth-client:view',
  'OAuth scope 目录'),
 ('perm_oauth_client_create','API','/oauth-clients/create','POST','platform:oauth-client:manage',
  '新建 OAuth 客户端（持有者等同 root）'),
 ('perm_oauth_client_update','API','/oauth-clients/update','POST','platform:oauth-client:manage',
  '编辑 OAuth 客户端（含内置客户端回调地址，持有者等同 root）'),
 ('perm_oauth_client_delete','API','/oauth-clients/delete','POST','platform:oauth-client:manage',
  '删除 OAuth 客户端'),
 ('perm_oauth_client_enable','API','/oauth-clients/enable','POST','platform:oauth-client:manage',
  '启用 / 停用 OAuth 客户端'),
 ('perm_oauth_client_reset_secret','API','/oauth-clients/reset-secret','POST','platform:oauth-client:manage',
  '重置 OAuth 客户端密钥'),
 ('perm_page_oauth_client','Page','/oauth-clients','view','platform:page:oauth-client',
  'OAuth 客户端页可见性')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 角色关联：只给 admin（显式 id 列表） =====
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_oauth_client_list','builtin_role_admin','perm_oauth_client_list'),
 ('rp_admin_oauth_client_get','builtin_role_admin','perm_oauth_client_get'),
 ('rp_admin_oauth_client_scopes','builtin_role_admin','perm_oauth_client_scopes'),
 ('rp_admin_oauth_client_create','builtin_role_admin','perm_oauth_client_create'),
 ('rp_admin_oauth_client_update','builtin_role_admin','perm_oauth_client_update'),
 ('rp_admin_oauth_client_delete','builtin_role_admin','perm_oauth_client_delete'),
 ('rp_admin_oauth_client_enable','builtin_role_admin','perm_oauth_client_enable'),
 ('rp_admin_oauth_client_reset_secret','builtin_role_admin','perm_oauth_client_reset_secret'),
 ('rp_admin_page_oauth_client','builtin_role_admin','perm_page_oauth_client')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- tenant-admin / tenant-member 有意不给：客户端管理是平台管理面。
