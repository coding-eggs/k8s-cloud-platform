-- ============================================================================================
-- 应用编排 · 计划②：中间件来源列 + 引用台账（2026-10-26）
-- ============================================================================================
-- 1) 中间件实例记录「由哪个应用的哪个节点创建」（只用于展示与删除应用时的提示，
--    实例的生命周期仍归中间件商店）；
-- 2) 引用表：谁在用这个实例（删除守卫的反查表）。设计文档 §8 列了这张表，
--    但计划①的迁移漏建了，本批补上。
--
-- 幂等：条件式 ADD COLUMN + CREATE TABLE IF NOT EXISTS；⚠️ 本仓没有 Flyway，手工重放。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_middleware_instance'
               AND COLUMN_NAME = 'app_id');
SET @ddl := IF(@col = 0,
  'ALTER TABLE platform_middleware_instance ADD COLUMN app_id varchar(64) NULL COMMENT ''创建它的应用；空 = 中间件商店直接建的''',
  'SET @skip_app_id = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_middleware_instance'
               AND COLUMN_NAME = 'app_node_id');
SET @ddl := IF(@col = 0,
  'ALTER TABLE platform_middleware_instance ADD COLUMN app_node_id varchar(64) NULL COMMENT ''应用里的节点 key''',
  'SET @skip_app_node_id = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

CREATE TABLE IF NOT EXISTS platform_application_reference (
  id         bigint       NOT NULL AUTO_INCREMENT,
  app_id     varchar(64)  NOT NULL,
  node_id    varchar(64)  NOT NULL COMMENT '应用里的节点 key',
  ref_type   varchar(32)  NOT NULL COMMENT 'MIDDLEWARE_INSTANCE / SECRET',
  ref_id     varchar(64)  NOT NULL,
  created_at datetime     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_ref (app_id, node_id, ref_type, ref_id),
  KEY idx_ref (ref_type, ref_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用对平台对象的引用（删除守卫）';
