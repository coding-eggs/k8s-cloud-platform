-- ============================================================================================
-- 平台网关：权限行加「所属服务」列 + JWK 生成的权限行
-- ============================================================================================
-- 背景：接口级鉴权上收到 platform-gateway（spec docs/superpowers/specs/2026-11-03-platform-gateway-design.md §8）。
-- 网关后面不止 platform-api 一个服务（auth 的 /internal/** 也挂上来），同一路径在不同服务里是不同端点，
-- 故权限行要带 service，与网关路由的 service 对应。
--
-- · service 默认 platform-api：存量行全是 api 的，无需回填；老代码插入不带 service 也落在 api。
-- · perm_jwk_generate：auth 的 /internal/jwk/generate（生成签名/加密密钥，带优先级插入 jwk 表）。
--   以前它挂在 auth 的会话鉴权下、任何登录用户都能调；网关上线后由本行收口，只给 admin。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 新构建的 mapper 会读 service 列，没有这一列直接 SQL 报错。
-- 幂等：加列前先查 information_schema；权限行显式 id + ODKU；角色关联 INSERT IGNORE。
-- ============================================================================================

SET @has_service := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_permission' AND COLUMN_NAME = 'service');
SET @ddl := IF(@has_service = 0,
    "ALTER TABLE platform_permission ADD COLUMN service varchar(32) NOT NULL DEFAULT 'platform-api' COMMENT '所属服务，与网关路由 service 对应' AFTER domain",
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT INTO platform_permission (id, domain, service, resource, action, code, description) VALUES
 ('perm_jwk_generate','API','platform-auth','/internal/jwk/generate','POST','platform:jwk:manage',
  '生成签名/加密密钥')
ON DUPLICATE KEY UPDATE service=VALUES(service), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

-- 只给 admin：能插高优先级签名密钥 = 能决定平台用哪把钥匙签 token，等同 root
INSERT IGNORE INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_jwk_generate','builtin_role_admin','perm_jwk_generate');
