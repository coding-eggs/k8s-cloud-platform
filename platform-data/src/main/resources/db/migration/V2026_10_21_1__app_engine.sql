-- ============================================================================================
-- 应用商店与 Operator 管理 · 计划①：应用引擎（表结构 + 应用端点权限行）
-- ============================================================================================
-- 内容：
--   1) platform_app_catalog —— 商店目录（计划②使用；先建表，避免后续 ALTER）
--   2) platform_app         —— 应用实例
--   3) platform_app_operation —— 安装/升级/卸载操作（异步状态 + helm 日志尾）
--   4) 应用端点权限行 + 页面码（tenant:app:read / tenant:app:install / tenant:page:app）
--
-- ⚠️ 本仓没有 Flyway，手工重放；重放前不要起新构建（无权限行的新端点会被 PermissionCrossCheckRunner 拒启）。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。

CREATE TABLE IF NOT EXISTS platform_app_catalog (
  id                 varchar(64)  NOT NULL,
  source             varchar(16)  NOT NULL COMMENT 'HARBOR / ARTIFACT_HUB',
  chart_ref          varchar(512) NOT NULL COMMENT 'OCI 地址或 HTTP repo URL',
  chart_name         varchar(128) NOT NULL,
  version            varchar(64)  NOT NULL,
  digest             varchar(128) NOT NULL COMMENT 'chart 包 sha256',
  display_name       varchar(128) NOT NULL,
  description        varchar(512) NULL,
  values_schema_json MEDIUMTEXT   NULL,
  default_values_json MEDIUMTEXT  NULL,
  visible_to         varchar(16)  NOT NULL DEFAULT 'TENANT' COMMENT 'TENANT / ADMIN',
  created_by         varchar(128) NULL,
  created_at         datetime     NOT NULL,
  deleted_at         datetime     NULL,
  PRIMARY KEY (id),
  KEY idx_app_catalog_chart (chart_name, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用商店目录（计划②使用）';

CREATE TABLE IF NOT EXISTS platform_app (
  id           varchar(64)  NOT NULL,
  cluster_id   varchar(64)  NOT NULL,
  namespace    varchar(128) NOT NULL,
  release_name varchar(64)  NOT NULL,
  catalog_id   varchar(64)  NULL,
  source       varchar(16)  NOT NULL,
  chart_ref    varchar(512) NOT NULL,
  chart_name   varchar(128) NOT NULL,
  version      varchar(64)  NOT NULL,
  digest       varchar(128) NOT NULL,
  values_json  MEDIUMTEXT   NULL,
  app_kind     varchar(16)  NOT NULL DEFAULT 'APP' COMMENT 'APP / OPERATOR',
  display_name varchar(128) NOT NULL,
  status       varchar(16)  NOT NULL COMMENT 'INSTALLING / RUNNING / FAILED / UNINSTALLED',
  needs_review tinyint(1)   NOT NULL DEFAULT 0,
  tenant_id    varchar(64)  NULL,
  created_by   varchar(128) NULL,
  created_at   datetime     NOT NULL,
  updated_at   datetime     NOT NULL,
  deleted_at   datetime     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_release (cluster_id, namespace, release_name),
  KEY idx_app_scope (cluster_id, namespace, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用实例';

CREATE TABLE IF NOT EXISTS platform_app_operation (
  id              bigint       NOT NULL AUTO_INCREMENT,
  cluster_id      varchar(64)  NOT NULL,
  app_id          varchar(64)  NOT NULL,
  `action`        varchar(16)  NOT NULL COMMENT 'INSTALL / UPGRADE / UNINSTALL',
  from_version    varchar(64)  NULL,
  to_version      varchar(64)  NULL,
  values_snapshot MEDIUMTEXT   NULL,
  `status`        varchar(16)  NOT NULL COMMENT 'PENDING / RUNNING / SUCCESS / FAILED / UNKNOWN',
  message         varchar(1024) NULL,
  log_tail        MEDIUMTEXT   NULL,
  `operator`      varchar(128) NULL,
  started_at      datetime     NOT NULL,
  finished_at     datetime     NULL,
  running_lock    varchar(160) GENERATED ALWAYS AS
                  (IF(`status` IN ('PENDING','RUNNING'), CONCAT(cluster_id,'|',app_id), NULL)) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_op_running (running_lock),
  KEY idx_app_op_app (app_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用安装/升级/卸载操作';

-- ===== 权限行（API）=====
INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_app_list',        'API','/app/list',           'POST','tenant:app:read',   '应用列表'),
 ('perm_app_get',         'API','/app/get',            'POST','tenant:app:read',   '应用详情'),
 ('perm_app_preview',     'API','/app/preview',        'POST','tenant:app:read',   '渲染预览（不落盘）'),
 ('perm_app_op_get',      'API','/app/operations/get', 'POST','tenant:app:read',   '查询应用操作（轮询）'),
 ('perm_app_install',     'API','/app/install',        'POST','tenant:app:install','安装应用'),
 ('perm_app_upgrade',     'API','/app/upgrade',        'POST','tenant:app:install','升级应用'),
 ('perm_app_uninstall',   'API','/app/uninstall',      'POST','tenant:app:install','卸载应用'),
 ('perm_page_app',        'Page','/apps',              'view','tenant:page:app',    '应用商店页面可见性')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

-- ===== 角色关联 =====
-- ⚠️ 计划①只有管理员路径：**只绑 admin**。
--    租户角色先不绑——读端点还没有按分配表/归属收窄（复审 C2：绑了就等于跨租户可读 values），
--    等计划②把租户闸门（目录白名单 + 分配表 + 读收窄）一起上线时，再在本文件之后追加绑定。
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_app_list',      'builtin_role_admin','perm_app_list'),
 ('rp_admin_app_get',       'builtin_role_admin','perm_app_get'),
 ('rp_admin_app_preview',   'builtin_role_admin','perm_app_preview'),
 ('rp_admin_app_op_get',    'builtin_role_admin','perm_app_op_get'),
 ('rp_admin_app_install',   'builtin_role_admin','perm_app_install'),
 ('rp_admin_app_upgrade',   'builtin_role_admin','perm_app_upgrade'),
 ('rp_admin_app_uninstall', 'builtin_role_admin','perm_app_uninstall'),
 ('rp_admin_page_app',      'builtin_role_admin','perm_page_app')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
