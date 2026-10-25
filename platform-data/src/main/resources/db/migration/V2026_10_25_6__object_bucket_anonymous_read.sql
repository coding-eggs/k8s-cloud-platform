-- ============================================================================================
-- 对象存储：桶的「匿名读」开关（2026-10-25）
-- ============================================================================================
-- 机制：SeaweedFS 的 S3 配置里有一条没有 credentials 的 `anonymous` 身份（全局唯一），
-- 不带签名的请求按它的 actions 判定。开关 = 往它 actions 里加/删 `Read:<桶>`：
--   - 只给 Read（不给 List）→ 知道完整 key 能下载，列不出目录；
--   - 只影响该桶，其它桶照旧要签名。
-- 改完必须滚动 S3 网关（配置只在启动时读），由 k8s-server 的 rollS3 完成。
--
-- 幂等：条件式 ADD COLUMN + 显式 id + ODKU；⚠️ 本仓没有 Flyway，手工重放。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_object_bucket'
               AND COLUMN_NAME = 'anonymous_read');
SET @ddl := IF(@col = 0,
  'ALTER TABLE platform_object_bucket ADD COLUMN anonymous_read tinyint(1) NOT NULL DEFAULT 0 COMMENT ''1=该桶允许匿名读（S3 的 anonymous 身份里有 Read:<桶>）''',
  'SET @bucket_anon_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_os_buckets_anonymous', 'API', '/object-storage/buckets/anonymous', 'POST',
  'platform:object-storage:manage', '开/关桶的匿名读')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_os_buckets_anonymous', 'builtin_role_admin', 'perm_os_buckets_anonymous')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
