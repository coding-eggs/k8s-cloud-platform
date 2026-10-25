-- ============================================================================================
-- 应用编排（画布）· 计划①：应用台账 / 版本 / 操作 / 资源（2026-10-25）
-- ============================================================================================
-- spec JSON 是真相源；每次部署写一条不可变 revision（spec + 解析结果 + 渲染清单）；
-- 操作是平台侧阶段机，current_step 持久化，重启后从断点续跑；
-- 资源台账用于 prune 与资源页反查。
--
-- 幂等：CREATE TABLE IF NOT EXISTS + 显式 id + ODKU；⚠️ 本仓没有 Flyway，手工重放。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

CREATE TABLE IF NOT EXISTS platform_application (
  id               varchar(64)  NOT NULL,
  tenant_id        varchar(64)  NOT NULL,
  cluster_id       varchar(64)  NOT NULL,
  namespace        varchar(128) NOT NULL,
  name             varchar(128) NOT NULL,
  slug             varchar(64)  NOT NULL COMMENT 'K8s 对象名前缀',
  display_name     varchar(128) NULL,
  description      varchar(512) NULL,
  status           varchar(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / DEPLOYING / READY / FAILED / DELETING',
  spec_json        MEDIUMTEXT   NOT NULL,
  current_revision int          NOT NULL DEFAULT 0,
  created_by       varchar(128) NULL,
  created_at       datetime     NOT NULL,
  updated_at       datetime     NOT NULL,
  deleted_at       datetime     NULL,
  active_key       varchar(160) GENERATED ALWAYS AS
                   (IF(deleted_at IS NULL, CONCAT(cluster_id, '/', namespace, '/', name), NULL)) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_active (active_key),
  KEY idx_app_tenant (tenant_id, cluster_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用（画布）台账';

CREATE TABLE IF NOT EXISTS platform_application_revision (
  id            bigint       NOT NULL AUTO_INCREMENT,
  app_id        varchar(64)  NOT NULL,
  revision      int          NOT NULL,
  spec_json     MEDIUMTEXT   NOT NULL,
  resolved_json MEDIUMTEXT   NULL COMMENT '解析后的注入上下文（不含任何密码明文）',
  manifest      MEDIUMTEXT   NULL COMMENT '渲染后的多文档 YAML',
  status        varchar(16)  NOT NULL DEFAULT 'RENDERED' COMMENT 'RENDERED / DEPLOYED / FAILED',
  message       varchar(1024) NULL,
  created_by    varchar(128) NULL,
  created_at    datetime     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_revision (app_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用的不可变版本快照';

CREATE TABLE IF NOT EXISTS platform_application_operation (
  id               bigint      NOT NULL AUTO_INCREMENT,
  app_id           varchar(64) NOT NULL,
  revision         int         NOT NULL,
  action           varchar(16) NOT NULL COMMENT 'DEPLOY / UPGRADE / DELETE / RETRY',
  status           varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / SUCCESS / FAILED',
  current_step     varchar(32) NOT NULL DEFAULT 'PRECHECK'
                   COMMENT 'PRECHECK / RENDER / CONFLICT / APPLY / PRUNE / HEALTH / DONE',
  node_states_json varchar(4096) NULL COMMENT '{节点key: 状态}',
  message          varchar(1024) NULL,
  started_at       datetime    NOT NULL,
  finished_at      datetime    NULL,
  created_by       varchar(128) NULL,
  PRIMARY KEY (id),
  KEY idx_app_op_active (app_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用的部署操作（阶段机）';

CREATE TABLE IF NOT EXISTS platform_application_resource (
  id          bigint       NOT NULL AUTO_INCREMENT,
  app_id      varchar(64)  NOT NULL,
  revision    int          NOT NULL,
  api_version varchar(128) NOT NULL,
  kind        varchar(64)  NOT NULL,
  namespace   varchar(128) NOT NULL,
  name        varchar(253) NOT NULL,
  node_key    varchar(64)  NULL,
  object_uid  varchar(64)  NULL,
  created_at  datetime     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_res (app_id, revision, kind, namespace, name),
  KEY idx_app_res_rev (app_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用创建的资源台账（prune 与反查）';

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_page_application',        'Page','/applications',                 'view','tenant:page:application',  '应用编排页面可见性'),
 ('perm_app_list',                'API', '/application/list',             'POST','tenant:application:read',  '应用列表'),
 ('perm_app_get',                 'API', '/application/get',              'POST','tenant:application:read',  '应用详情'),
 ('perm_app_preview',             'API', '/application/preview',          'POST','tenant:application:read',  '应用渲染预览'),
 ('perm_app_operation_get',       'API', '/application/operation/get',    'POST','tenant:application:read',  '应用操作轮询'),
 ('perm_app_revision_list',       'API', '/application/revision/list',    'POST','tenant:application:read',  '应用版本历史'),
 ('perm_app_options',             'API', '/application/options',          'POST','tenant:application:read',  '应用可选项目录'),
 ('perm_app_create',              'API', '/application/create',           'POST','tenant:application:write', '创建应用'),
 ('perm_app_update',              'API', '/application/update',           'POST','tenant:application:write', '编辑应用'),
 ('perm_app_delete',              'API', '/application/delete',           'POST','tenant:application:write', '删除应用'),
 ('perm_app_deploy',              'API', '/application/deploy',           'POST','tenant:application:write', '部署应用')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_page_application',        'builtin_role_admin',        'perm_page_application'),
 ('rp_tenant_admin_page_application', 'builtin_role_tenant_admin', 'perm_page_application'),
 ('rp_tenant_member_page_application','builtin_role_tenant_member','perm_page_application'),
 ('rp_admin_app_list',                'builtin_role_admin',        'perm_app_list'),
 ('rp_admin_app_get',                 'builtin_role_admin',        'perm_app_get'),
 ('rp_admin_app_preview',             'builtin_role_admin',        'perm_app_preview'),
 ('rp_admin_app_op',                  'builtin_role_admin',        'perm_app_operation_get'),
 ('rp_admin_app_rev',                 'builtin_role_admin',        'perm_app_revision_list'),
 ('rp_admin_app_options',             'builtin_role_admin',        'perm_app_options'),
 ('rp_admin_app_create',              'builtin_role_admin',        'perm_app_create'),
 ('rp_admin_app_update',              'builtin_role_admin',        'perm_app_update'),
 ('rp_admin_app_delete',              'builtin_role_admin',        'perm_app_delete'),
 ('rp_admin_app_deploy',              'builtin_role_admin',        'perm_app_deploy'),
 ('rp_tenant_admin_app_list',         'builtin_role_tenant_admin', 'perm_app_list'),
 ('rp_tenant_admin_app_get',          'builtin_role_tenant_admin', 'perm_app_get'),
 ('rp_tenant_admin_app_preview',      'builtin_role_tenant_admin', 'perm_app_preview'),
 ('rp_tenant_admin_app_op',           'builtin_role_tenant_admin', 'perm_app_operation_get'),
 ('rp_tenant_admin_app_rev',          'builtin_role_tenant_admin', 'perm_app_revision_list'),
 ('rp_tenant_admin_app_options',      'builtin_role_tenant_admin', 'perm_app_options'),
 ('rp_tenant_admin_app_create',       'builtin_role_tenant_admin', 'perm_app_create'),
 ('rp_tenant_admin_app_update',       'builtin_role_tenant_admin', 'perm_app_update'),
 ('rp_tenant_admin_app_delete',       'builtin_role_tenant_admin', 'perm_app_delete'),
 ('rp_tenant_admin_app_deploy',       'builtin_role_tenant_admin', 'perm_app_deploy'),
 ('rp_tenant_member_app_list',        'builtin_role_tenant_member','perm_app_list'),
 ('rp_tenant_member_app_get',         'builtin_role_tenant_member','perm_app_get'),
 ('rp_tenant_member_app_preview',     'builtin_role_tenant_member','perm_app_preview'),
 ('rp_tenant_member_app_op',          'builtin_role_tenant_member','perm_app_operation_get'),
 ('rp_tenant_member_app_rev',         'builtin_role_tenant_member','perm_app_revision_list'),
 ('rp_tenant_member_app_options',     'builtin_role_tenant_member','perm_app_options')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
