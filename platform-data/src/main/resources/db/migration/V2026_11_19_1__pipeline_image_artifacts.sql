SET @has_artifacts := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_pipeline_run' AND COLUMN_NAME = 'artifacts_json');
SET @ddl := IF(@has_artifacts = 0,
    'ALTER TABLE platform_pipeline_run ADD COLUMN artifacts_json MEDIUMTEXT NULL', 'SELECT 1');
PREPARE artifact_migration FROM @ddl;
EXECUTE artifact_migration;
DEALLOCATE PREPARE artifact_migration;

SET @has_tenant := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_pipeline_run' AND COLUMN_NAME = 'tenant_id');
SET @ddl := IF(@has_tenant = 0,
    'ALTER TABLE platform_pipeline_run ADD COLUMN tenant_id VARCHAR(64) NULL', 'SELECT 1');
PREPARE owner_migration FROM @ddl;
EXECUTE owner_migration;
DEALLOCATE PREPARE owner_migration;
