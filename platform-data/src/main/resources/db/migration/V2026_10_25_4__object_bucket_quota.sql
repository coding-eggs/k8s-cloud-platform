-- ============================================================================================
-- 对象存储：桶容量上限（2026-10-25）
-- ============================================================================================
-- SeaweedFS **原生支持每桶容量配额**（S3 扩展 `PUT /{bucket}?seaweedfs-quota`）：
-- 超限时把桶标成 readOnly → 写入 403 AccessDenied，删除仍允许；4.48 的 S3 网关在每分钟的
-- bucket-size 循环里自动执行 enforce（双向），不需要外部脚本。
--
-- 这里存"声明值"，真正的执行在 S3 侧；NULL = 不限。
--
-- ⚠️ 本仓没有 Flyway，手工重放；幂等（information_schema 条件式 ADD COLUMN）。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_object_bucket'
               AND COLUMN_NAME = 'quota_bytes');
SET @ddl := IF(@col = 0,
  'ALTER TABLE platform_object_bucket ADD COLUMN quota_bytes bigint NULL COMMENT ''桶容量上限（字节）；NULL = 不限。同时写进 SeaweedFS 的每桶 quota''',
  'SET @bucket_quota_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
