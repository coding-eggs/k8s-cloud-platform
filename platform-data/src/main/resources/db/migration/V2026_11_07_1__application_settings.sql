-- ============================================================================================
-- 应用级设置：链路追踪 / 服务网格从服务级挪到应用级（2026-11-07）
-- ============================================================================================
-- platform_application 加 settings_json（期望值，发布时按勾选写进快照 app 字段）+ settings_version（乐观锁）。
-- 新接口 /application/settings/update 归 tenant:application:write；角色绑定显式写出：admin、tenant_admin。
--
-- 幂等：information_schema 判断后再 ALTER；显式 id + ON DUPLICATE KEY UPDATE；不加外键。
-- ⚠️ 本仓没有 Flyway，手工重放；先重放本文件再起新构建（新 mapper 会读这两列，没有直接 SQL 报错）。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_application' AND COLUMN_NAME = 'settings_json');
SET @s := IF(@c = 0,
  'ALTER TABLE platform_application ADD COLUMN settings_json MEDIUMTEXT NULL COMMENT ''应用级设置（链路追踪/网格）期望值'' AFTER live_release_no',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_application' AND COLUMN_NAME = 'settings_version');
SET @s := IF(@c = 0,
  'ALTER TABLE platform_application ADD COLUMN settings_version INT NOT NULL DEFAULT 0 COMMENT ''settings_json 乐观锁'' AFTER settings_json',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_app_settings_update', 'API', '/application/settings/update', 'POST', 'tenant:application:write', '修改应用设置')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_app_settings_update',        'builtin_role_admin',        'perm_app_settings_update'),
 ('rp_tenant_admin_app_settings_update', 'builtin_role_tenant_admin', 'perm_app_settings_update')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
