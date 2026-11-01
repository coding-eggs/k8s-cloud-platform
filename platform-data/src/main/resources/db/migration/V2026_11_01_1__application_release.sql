-- ============================================================================================
-- 应用模块去画布重做：服务 / 依赖 / 发布 / 执行（2026-11-01）
-- ============================================================================================
-- 应用 = 一组服务 + 一组依赖；发布是不可变快照；执行是可续跑的阶段机，每个应用最多一条活动执行。
-- platform_application 加 live_release_no，spec_json 改可空（旧画布 spec 不再使用）。
--
-- 幂等：CREATE TABLE IF NOT EXISTS + information_schema 判断后再 ALTER；不加外键。
-- ⚠️ 本仓没有 Flyway，手工重放。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_application'
               AND COLUMN_NAME = 'live_release_no');
SET @ddl := IF(@col = 0,
  'ALTER TABLE platform_application ADD COLUMN live_release_no int NOT NULL DEFAULT 0 COMMENT ''线上发布号；0=尚未发布''',
  'SET @app_live_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

-- MODIFY 本身可重复执行（改成可空是幂等的）
ALTER TABLE platform_application MODIFY COLUMN spec_json MEDIUMTEXT NULL;

CREATE TABLE IF NOT EXISTS platform_app_service (
  id            bigint       NOT NULL AUTO_INCREMENT,
  app_id        varchar(64)  NOT NULL,
  service_key   varchar(64)  NOT NULL COMMENT '创建后不可改',
  desired_json  MEDIUMTEXT   NOT NULL COMMENT '期望配置（对齐 AppWorkloadValues，镜像拆 imageRepo / imageTag）',
  pending_delete tinyint(1)  NOT NULL DEFAULT 0 COMMENT '下次发布时移除',
  version       int          NOT NULL DEFAULT 0 COMMENT '乐观锁',
  created_at    datetime     NOT NULL,
  updated_at    datetime     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_service (app_id, service_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用的服务';

CREATE TABLE IF NOT EXISTS platform_app_dependency (
  id            bigint       NOT NULL AUTO_INCREMENT,
  app_id        varchar(64)  NOT NULL,
  dep_key       varchar(64)  NOT NULL,
  `type`        varchar(16)  NOT NULL COMMENT 'MIDDLEWARE / EXTERNAL',
  ownership     varchar(16)  NULL COMMENT 'CREATED / BOUND（仅 MIDDLEWARE）',
  instance_id   varchar(64)  NULL COMMENT '中间件实例 id',
  external_json MEDIUMTEXT   NULL COMMENT 'EXTERNAL 的连接信息，不含密码明文',
  init_hash     varchar(64)  NULL COMMENT '初始化内容摘要，变化才重跑',
  init_status   varchar(16)  NULL COMMENT 'PENDING / RUNNING / SUCCESS / FAILED',
  init_log      MEDIUMTEXT   NULL,
  created_at    datetime     NOT NULL,
  updated_at    datetime     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_dep (app_id, dep_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用的依赖';

CREATE TABLE IF NOT EXISTS platform_app_release (
  id                 bigint       NOT NULL AUTO_INCREMENT,
  app_id             varchar(64)  NOT NULL,
  release_no         int          NOT NULL,
  snapshot_json      MEDIUMTEXT   NOT NULL COMMENT '发布时的服务与依赖快照',
  objects_json       MEDIUMTEXT   NULL COMMENT '渲染出的对象清单',
  manifest           MEDIUMTEXT   NULL COMMENT '渲染后的多文档 YAML',
  `type`             varchar(16)  NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL / ROLLBACK',
  source_release_no  int          NULL COMMENT 'ROLLBACK 时的来源发布号',
  note               varchar(512) NULL,
  `status`           varchar(16)  NOT NULL DEFAULT 'RENDERED' COMMENT 'RENDERED / DEPLOYED / FAILED',
  created_by         varchar(128) NULL,
  created_at         datetime     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_release_no (app_id, release_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用的不可变发布';

CREATE TABLE IF NOT EXISTS platform_app_execution (
  id           bigint       NOT NULL AUTO_INCREMENT,
  app_id       varchar(64)  NOT NULL,
  release_no   int          NOT NULL,
  `status`     varchar(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / INTERRUPTED / FAILED / SUCCESS',
  stage        varchar(32)  NULL COMMENT '阶段机当前阶段，重启后从这里续跑',
  message      varchar(1024) NULL,
  lease_until  datetime     NULL COMMENT '执行者租约；过期才可被接管',
  started_by   varchar(128) NULL,
  started_at   datetime     NOT NULL,
  finished_at  datetime     NULL,
  active_key   varchar(64) GENERATED ALWAYS AS
               (IF(status IN ('PENDING','RUNNING','INTERRUPTED'), app_id, NULL)) STORED
               COMMENT '活动执行占位；FAILED 不占，新发布可覆盖',
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_exec_active (active_key),
  KEY idx_app_exec_app (app_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用发布的执行（阶段机）';
