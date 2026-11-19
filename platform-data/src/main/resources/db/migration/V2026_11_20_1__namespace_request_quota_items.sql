SET @ddl := IF(EXISTS(
  SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='platform_namespace_request' AND column_name='quota_items_json'
), 'SELECT 1', 'ALTER TABLE platform_namespace_request ADD COLUMN quota_items_json TEXT NULL');
PREPARE namespace_request_quota_migration FROM @ddl;
EXECUTE namespace_request_quota_migration;
DEALLOCATE PREPARE namespace_request_quota_migration;
