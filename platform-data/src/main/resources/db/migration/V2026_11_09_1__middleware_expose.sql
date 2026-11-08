-- ============================================================================================
-- 中间件对外暴露（2026-11-09）：端口分配表加归属类型、暴露方式表、两个端点的权限行
-- ============================================================================================
-- 设计见 docs/superpowers/specs/2026-11-09-middleware-exposure-design.md。
--
-- - platform_ingress_port.owner_kind：APP / MIDDLEWARE，只用于展示归属（ListenerSet 页）。
--   中间件行 app_id = 实例 id（mwi_ 前缀）、service_key = 端点 key；唯一性不变。
-- - platform_middleware_expose：(instance_id, endpoint_key) 唯一；不暴露 = 无行。不加外键。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 新 mapper 读 owner_kind 列；无行的新端点会被 PermissionCrossCheckRunner 拒启。
-- ⚠️ owner_kind 的 ALTER 不幂等：重放第二次会报 Duplicate column，忽略即可（或先确认列不存在）。
-- ⚠️ 本仓没有 Flyway，手工重放；重放后需重新登录或等权限缓存 TTL（30s）。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

ALTER TABLE platform_ingress_port
  ADD COLUMN owner_kind varchar(16) NOT NULL DEFAULT 'APP' COMMENT 'APP / MIDDLEWARE（归属类型，仅展示）' AFTER service_key;

CREATE TABLE IF NOT EXISTS platform_middleware_expose (
  id            bigint       NOT NULL AUTO_INCREMENT,
  instance_id   varchar(64)  NOT NULL,
  endpoint_key  varchar(32)  NOT NULL,
  `mode`        varchar(16)  NOT NULL COMMENT 'GATEWAY / NODEPORT',
  node_port     int          NULL COMMENT 'NODEPORT 时平台分配的端口',
  created_at    datetime     NOT NULL,
  updated_at    datetime     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mw_expose (instance_id, endpoint_key),
  KEY idx_mw_expose_node_port (node_port)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='中间件端点对外暴露方式';

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_mw_instance_expose_list',   'API','/middleware/instance/expose/list',  'POST','tenant:middleware:read', '实例对外访问方式'),
 ('perm_mw_instance_expose_update', 'API','/middleware/instance/expose/update','POST','tenant:middleware:write','修改实例对外访问方式')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

-- 新权限行必须显式绑角色：读三类内置角色都给；写给 admin / 租户管理员（同改名）
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_mw_expose_list',     'builtin_role_admin',         'perm_mw_instance_expose_list'),
 ('rp_admin_mw_expose_update',   'builtin_role_admin',         'perm_mw_instance_expose_update'),
 ('rp_tadmin_mw_expose_list',    'builtin_role_tenant_admin',  'perm_mw_instance_expose_list'),
 ('rp_tadmin_mw_expose_update',  'builtin_role_tenant_admin',  'perm_mw_instance_expose_update'),
 ('rp_tmember_mw_expose_list',   'builtin_role_tenant_member', 'perm_mw_instance_expose_list')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- 自定义角色：已能改中间件显示名（同为 tenant:middleware:write）的给写；能看详情的给读
INSERT INTO platform_role_permission (id, role_id, permission_id)
SELECT CONCAT('rp_mwx_', SUBSTRING(MD5(CONCAT(rp.role_id, '|', x.pid)), 1, 32)), rp.role_id, x.pid
FROM platform_role_permission rp
JOIN (SELECT 'perm_mw_instance_detail' AS src, 'perm_mw_instance_expose_list' AS pid
      UNION ALL SELECT 'perm_mw_instance_rename', 'perm_mw_instance_expose_update') x ON x.src = rp.permission_id
WHERE rp.role_id NOT IN ('builtin_role_admin', 'builtin_role_tenant_admin', 'builtin_role_tenant_member')
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);
