-- ============================================================================================
-- Harbor 集群组件 + 租户镜像项目集成（2026-10-27）
-- ============================================================================================
-- 设计：docs/superpowers/specs/2026-10-27-harbor-registry-design.md
-- ⚠️ 本仓没有 Flyway，手工重放；幂等（information_schema 条件式 ADD COLUMN + IF NOT EXISTS）。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ⚠️ 先重放本文件再起新构建：新端点无权限行会被启动期交叉校验拒启。
-- ============================================================================================

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_credential'
               AND COLUMN_NAME = 'tenant_id');
SET @ddl := IF(@col = 0,
  'ALTER TABLE platform_credential ADD COLUMN tenant_id varchar(64) NULL COMMENT ''归属租户；NULL = 平台级（现有全局凭据语义）''',
  'SET @harbor_tenant_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_credential'
               AND COLUMN_NAME = 'source');
SET @ddl := IF(@col = 0,
  'ALTER TABLE platform_credential ADD COLUMN source varchar(16) NOT NULL DEFAULT ''MANUAL'' COMMENT ''MANUAL / HARBOR（Harbor 自动维护，凭据页只读）''',
  'SET @harbor_source_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_credential'
               AND INDEX_NAME = 'idx_credential_tenant');
SET @ddl := IF(@idx = 0,
  'ALTER TABLE platform_credential ADD INDEX idx_credential_tenant (tenant_id)',
  'SET @harbor_idx_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

CREATE TABLE IF NOT EXISTS platform_registry_project (
  id             varchar(64)  NOT NULL COMMENT 'ULIDGenerator',
  cluster_id     varchar(64)  NOT NULL,
  tenant_id      varchar(64)  NULL COMMENT 'NULL = 平台共享项目（platform）',
  host           varchar(255) NOT NULL COMMENT 'Harbor 访问地址（host[:port]，不带 scheme）',
  project_name   varchar(255) NOT NULL,
  credential_id  varchar(64)  NULL COMMENT 'platform_credential.id（source=HARBOR）',
  robot_id       bigint       NULL COMMENT 'Harbor 机器人账号 id（重建时按它删）',
  status         varchar(16)  NOT NULL DEFAULT 'READY' COMMENT 'READY / ERROR',
  message        varchar(1024) NULL,
  last_synced_at datetime     NULL,
  created_at     datetime     NOT NULL,
  updated_at     datetime     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_registry_project (cluster_id, project_name),
  KEY idx_registry_project_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户在某个 Harbor 上的镜像项目台账';

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_registry_projects_list',    'API','/registry/projects/list',    'POST','platform:registry:project:read',
  'Harbor 租户镜像项目列表（含凭据状态）'),
 ('perm_registry_projects_sync',    'API','/registry/projects/sync',    'POST','platform:registry:project:manage',
  '同步全部租户的 Harbor 项目与机器人账号'),
 ('perm_registry_projects_rebuild', 'API','/registry/projects/rebuild', 'POST','platform:registry:project:manage',
  '重建某个租户（或平台共享）的 Harbor 机器人账号与凭据')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_registry_projects_list',    'builtin_role_admin','perm_registry_projects_list'),
 ('rp_admin_registry_projects_sync',    'builtin_role_admin','perm_registry_projects_sync'),
 ('rp_admin_registry_projects_rebuild', 'builtin_role_admin','perm_registry_projects_rebuild')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
