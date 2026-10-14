-- ============================================================================================
-- S3.1 镜像仓库：权限行（4 行）+ 角色关联（12 行 = 4 权限行 × 3 角色）
-- ============================================================================================
-- 背景：S3.1 给两处镜像输入框加 repository/tag 补全，并新增一个只读浏览页。设计全文见
-- docs/superpowers/specs/2026-10-14-registry-design.md。
--
-- 权限码（2 个，落在 4 行权限上）：
--   · tenant:registry:catalog  —— API 域，**3 行**，覆盖三个只读端点：
--                                 /registry/suggest     （补全候选）
--                                 /registry/browse      （浏览页翻层）
--                                 /registry/connections （浏览页的连接下拉，窄投影）
--     三者是同一件事（看镜像目录）的三个入口，都只读、都归同一页面同一批人 ——
--     与 S0.2 把「测试连接」并进 read 是同一条判断。
--   · tenant:page:registry     —— Page 域，**1 行**，浏览页可见性。
--     拆成 API 码 + Page 码，是为了让「能补全但进不了浏览页」可表达（给 catalog 不给 page）。
--
-- ⚠️ 同一 code 对应多行是既有的 ANY-of 约定（见 RoleService 类注释「权限点 code 自 Task 4 起
--    不再唯一」）—— 勾选一个 code 时后端会把该 code 的**全部**行都落关联，否则运行时按行 id
--    查闭包会漏掉未关联的 URL。
--
-- ⚠️ 必须是 tenant: 前缀：RoleService.assertScopeMatches 规定 TENANT 角色只能持 tenant: 码，
--    而补全的使用者（更新镜像）正是租户。
--
-- ⚠️ 本批不做 imagePullSecret（用户裁定后续再说），所以没有「下发」相关的码。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启（fail-closed）。
-- ⚠️ 重放后需重新登录或等权限缓存 TTL（30s）。
-- ⚠️ 本仓没有 Flyway，本文件是手工重放的；启动日志里永远不会出现 Migrating schema。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符，否则整个文件重放失败。
--
-- ⚠️⚠️ permission_id 必须与上面 platform_permission 的 id 逐字一致。本仓**不加外键**，
--    写错了不会报任何错，只留一条悬空关联 → 码在库里、角色却没有它 → 菜单不显示、路由跳回总览，
--    且日志一片安静。S0.2 交付当天踩过一次（perm_credential_page vs perm_page_credential）。
--    重放后**必须做 join 对账**，不能只数行数。
-- ============================================================================================

-- ===== 权限行：API 域 3 行 + Page 域 1 行 =====
INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_registry_suggest',     'API','/registry/suggest',     'POST','tenant:registry:catalog',
  '镜像候选值（repository / tag 补全）'),
 ('perm_registry_browse',      'API','/registry/browse',      'POST','tenant:registry:catalog',
  '镜像目录浏览（沿层级往下看）'),
 ('perm_registry_connections', 'API','/registry/connections', 'POST','tenant:registry:catalog',
  '可浏览的镜像仓库连接（窄投影：只回 id/name/type/endpoint）'),
 ('perm_page_registry',        'Page','/registries',          'view','tenant:page:registry',
  '镜像仓库浏览页可见性')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 角色关联：tenant-admin / tenant-member / admin 各 4 行（显式 id 列表，共 12 行） =====
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_tadmin_reg_suggest',   'builtin_role_tenant_admin','perm_registry_suggest'),
 ('rp_tadmin_reg_browse',    'builtin_role_tenant_admin','perm_registry_browse'),
 ('rp_tadmin_reg_conn',      'builtin_role_tenant_admin','perm_registry_connections'),
 ('rp_tadmin_reg_page',      'builtin_role_tenant_admin','perm_page_registry'),
 ('rp_tmember_reg_suggest',  'builtin_role_tenant_member','perm_registry_suggest'),
 ('rp_tmember_reg_browse',   'builtin_role_tenant_member','perm_registry_browse'),
 ('rp_tmember_reg_conn',     'builtin_role_tenant_member','perm_registry_connections'),
 ('rp_tmember_reg_page',     'builtin_role_tenant_member','perm_page_registry'),
 ('rp_admin_reg_suggest',    'builtin_role_admin','perm_registry_suggest'),
 ('rp_admin_reg_browse',     'builtin_role_admin','perm_registry_browse'),
 ('rp_admin_reg_conn',       'builtin_role_admin','perm_registry_connections'),
 ('rp_admin_reg_page',       'builtin_role_admin','perm_page_registry')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
