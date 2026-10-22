-- ============================================================================================
-- 中间件商店 · 批①-1：Operator 状态 / 实例台账 / 操作记录 + 权限行
-- ============================================================================================
-- ⚠️ 本仓没有 Flyway，手工重放；重放前不要起新构建（无权限行的新端点会被拒启）。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。

CREATE TABLE IF NOT EXISTS platform_middleware_operator (
  id            varchar(64)  NOT NULL,
  cluster_id    varchar(64)  NOT NULL,
  code          varchar(32)  NOT NULL COMMENT 'mysql/redis/kafka/elasticsearch/postgresql/rabbitmq/nacos',
  chart_version varchar(64)  NOT NULL,
  status        varchar(16)  NOT NULL COMMENT 'NOT_INSTALLED/INSTALLING/READY/FAILED/DEGRADED',
  message       varchar(1024) NULL,
  installed_at  datetime     NULL,
  updated_at    datetime     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mwo_cluster_code (cluster_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='中间件 Operator 安装状态';

CREATE TABLE IF NOT EXISTS platform_middleware_instance (
  id           varchar(64)  NOT NULL,
  cluster_id   varchar(64)  NOT NULL,
  namespace    varchar(128) NOT NULL,
  code         varchar(32)  NOT NULL,
  mode         varchar(16)  NOT NULL COMMENT 'SINGLE/CLUSTER/SENTINEL/STANDALONE/HA',
  name         varchar(63)  NOT NULL COMMENT 'K8s 对象名（平台生成 mw-<code>-<8hex>）',
  display_name varchar(128) NOT NULL,
  version      varchar(32)  NOT NULL,
  spec_json    MEDIUMTEXT   NULL COMMENT '表单规格（不含凭据）',
  status       varchar(16)  NOT NULL COMMENT 'CREATING/READY/DEGRADED/FAILED/DELETING',
  message      varchar(1024) NULL,
  tenant_id    varchar(64)  NULL,
  created_by   varchar(128) NULL,
  created_at   datetime     NOT NULL,
  updated_at   datetime     NOT NULL,
  deleted_at   datetime     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mwi_name (cluster_id, namespace, name),
  KEY idx_mwi_code (cluster_id, code, deleted_at),
  KEY idx_mwi_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='中间件实例';

CREATE TABLE IF NOT EXISTS platform_middleware_operation (
  id          bigint       NOT NULL AUTO_INCREMENT,
  cluster_id  varchar(64)  NOT NULL,
  instance_id varchar(64)  NOT NULL,
  `action`    varchar(16)  NOT NULL COMMENT 'CREATE/DELETE/SCALE/UPGRADE',
  `status`    varchar(16)  NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/UNKNOWN',
  message     varchar(1024) NULL,
  log_tail    MEDIUMTEXT   NULL,
  `operator`  varchar(128) NULL,
  started_at  datetime     NOT NULL,
  finished_at datetime     NULL,
  running_lock varchar(160) GENERATED ALWAYS AS
               (IF(`status` IN ('PENDING','RUNNING'), CONCAT(cluster_id,'|',instance_id), NULL)) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mwo_running (running_lock),
  KEY idx_mwo_instance (instance_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='中间件实例操作';

-- ===== 权限行 =====
INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_mw_catalog',        'API','/middleware/catalog',           'POST','tenant:middleware:read',   '中间件清单与 Operator 状态'),
 ('perm_mw_instance_list',  'API','/middleware/instance/list',     'POST','tenant:middleware:read',   '实例列表'),
 ('perm_mw_instance_get',   'API','/middleware/instance/get',      'POST','tenant:middleware:read',   '实例详情'),
 ('perm_mw_op_get',         'API','/middleware/operation/get',     'POST','tenant:middleware:read',   '实例操作（轮询）'),
 ('perm_mw_instance_create','API','/middleware/instance/create',   'POST','tenant:middleware:write',  '创建实例'),
 ('perm_mw_instance_scale', 'API','/middleware/instance/scale',    'POST','tenant:middleware:write',  '扩容实例'),
 ('perm_mw_instance_delete','API','/middleware/instance/delete',   'POST','tenant:middleware:write',  '删除实例'),
 ('perm_mw_operator_install','API','/middleware/operator/install', 'POST','platform:middleware:manage','安装/升级中间件 Operator')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

-- ===== 角色绑定（显式，不继承）=====
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_mw_catalog',        'builtin_role_admin','perm_mw_catalog'),
 ('rp_admin_mw_instance_list',  'builtin_role_admin','perm_mw_instance_list'),
 ('rp_admin_mw_instance_get',   'builtin_role_admin','perm_mw_instance_get'),
 ('rp_admin_mw_op_get',         'builtin_role_admin','perm_mw_op_get'),
 ('rp_admin_mw_instance_create','builtin_role_admin','perm_mw_instance_create'),
 ('rp_admin_mw_instance_scale', 'builtin_role_admin','perm_mw_instance_scale'),
 ('rp_admin_mw_instance_delete','builtin_role_admin','perm_mw_instance_delete'),
 ('rp_admin_mw_operator_install','builtin_role_admin','perm_mw_operator_install'),
 ('rp_tadmin_mw_catalog',        'builtin_role_tenant_admin','perm_mw_catalog'),
 ('rp_tadmin_mw_instance_list',  'builtin_role_tenant_admin','perm_mw_instance_list'),
 ('rp_tadmin_mw_instance_get',   'builtin_role_tenant_admin','perm_mw_instance_get'),
 ('rp_tadmin_mw_op_get',         'builtin_role_tenant_admin','perm_mw_op_get'),
 ('rp_tadmin_mw_instance_create','builtin_role_tenant_admin','perm_mw_instance_create'),
 ('rp_tadmin_mw_instance_scale', 'builtin_role_tenant_admin','perm_mw_instance_scale'),
 ('rp_tadmin_mw_instance_delete','builtin_role_tenant_admin','perm_mw_instance_delete'),
 ('rp_tmember_mw_catalog',       'builtin_role_tenant_member','perm_mw_catalog'),
 ('rp_tmember_mw_instance_list', 'builtin_role_tenant_member','perm_mw_instance_list'),
 ('rp_tmember_mw_instance_get',  'builtin_role_tenant_member','perm_mw_instance_get'),
 ('rp_tmember_mw_op_get',        'builtin_role_tenant_member','perm_mw_op_get')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
