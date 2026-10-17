-- ============================================================================================
-- S3.6 批①：组件生命周期（安装 / 升级）
-- ============================================================================================
-- 内容：
--   1) k8s_cluster.cluster_role —— 画像（MANAGEMENT / BUSINESS），决定组件 recipe 取哪套
--   2) platform_component_operation —— 一次安装/升级操作的记录（异步状态 + 失败原因）
--   3) 三条端点权限行：apply / operations/get / operations/list
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启。
-- ⚠️ 本仓没有 Flyway，手工重放。
-- 幂等：列存在性查 information_schema + PREPARE（MySQL 不支持 ADD COLUMN IF NOT EXISTS）；
--      权限行显式 id + ODKU；角色关联按显式 id。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

-- ===== 1) 集群画像 =====
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'k8s_cluster'
               AND COLUMN_NAME = 'cluster_role');
SET @ddl := IF(@col = 0,
  'ALTER TABLE k8s_cluster ADD COLUMN cluster_role varchar(16) NOT NULL DEFAULT ''BUSINESS'' '
  'COMMENT ''集群画像：MANAGEMENT=平台自身所在集群（多装 Thanos 等底座）；BUSINESS=业务集群''',
  'SET @component_v1_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

-- ⚠️ 重放后请把平台自身所在集群手工改为 MANAGEMENT（唯一一条 UPDATE，按实际情况改条件）：
--   UPDATE k8s_cluster SET cluster_role='MANAGEMENT' WHERE cluster_name='coding-cluster';

-- ===== 2) 操作记录 =====
CREATE TABLE IF NOT EXISTS platform_component_operation (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  cluster_id      VARCHAR(64)  NOT NULL,
  component_code  VARCHAR(64)  NOT NULL,
  action          VARCHAR(16)  NOT NULL COMMENT 'INSTALL / UPGRADE',
  profile         VARCHAR(16)  NOT NULL COMMENT '本次使用的画像（MANAGEMENT/BUSINESS）',
  from_version    VARCHAR(64)  NULL,
  to_version      VARCHAR(64)  NULL COMMENT '目标 chart 版本',
  release_names   TEXT         NULL COMMENT '本次涉及的 release 名（JSON 数组）',
  values_snapshot MEDIUMTEXT   NULL COMMENT '最终合并后的 values（升级时复用）',
  status          VARCHAR(16)  NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/UNKNOWN',
  message         VARCHAR(1024) NULL,
  log_tail        MEDIUMTEXT   NULL COMMENT 'helm 输出去敏后的尾部',
  `operator`      VARCHAR(128) NULL,
  started_at      DATETIME     NOT NULL,
  finished_at     DATETIME     NULL,
  PRIMARY KEY (id),
  KEY idx_component_op_cluster_component (cluster_id, component_code, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组件安装/升级操作记录';

-- ===== 3) 权限行 =====
INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_res_cluster_components_apply', 'API','/cluster/components/apply','POST','platform:cluster:manage',
  '安装/升级集群组件（helm 执行，cluster-admin 级写入）'),
 ('perm_res_cluster_components_op_get', 'API','/cluster/components/operations/get','POST','platform:cluster:manage',
  '查询一条组件操作记录（轮询进度用）'),
 ('perm_res_cluster_components_op_list', 'API','/cluster/components/operations/list','POST','platform:cluster:manage',
  '按集群列出各组件最近一次操作')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_cluster_components_apply', 'builtin_role_admin','perm_res_cluster_components_apply'),
 ('rp_admin_cluster_components_op_get','builtin_role_admin','perm_res_cluster_components_op_get'),
 ('rp_admin_cluster_components_op_list','builtin_role_admin','perm_res_cluster_components_op_list')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
