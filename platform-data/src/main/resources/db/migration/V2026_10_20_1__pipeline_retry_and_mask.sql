-- 流水线节点重试 + 节点屏蔽（设计见 docs/superpowers/specs/2026-10-20-pipeline-retry-and-mask-design.md）
-- ⚠️ 本仓没有 Flyway：这个文件必须手工重放，否则新代码启动即报 Unknown column。

-- ===== 1) 运行行的六个新列 =====
-- 用 information_schema 判存在，重放不会因为「列已存在」整条失败
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_pipeline_run'
             AND COLUMN_NAME = 'parent_run_id');
SET @ddl := IF(@c = 0,
  'ALTER TABLE platform_pipeline_run ADD COLUMN parent_run_id varchar(32) NULL COMMENT ''重试来源运行 id；NULL = 首跑''',
  'SET @pipeline_retry_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_pipeline_run'
             AND COLUMN_NAME = 'attempt_no');
SET @ddl := IF(@c = 0,
  'ALTER TABLE platform_pipeline_run ADD COLUMN attempt_no int NOT NULL DEFAULT 1 COMMENT ''同一条重试链的第几次尝试''',
  'SET @pipeline_retry_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_pipeline_run'
             AND COLUMN_NAME = 'retry_from_node');
SET @ddl := IF(@c = 0,
  'ALTER TABLE platform_pipeline_run ADD COLUMN retry_from_node varchar(64) NULL COMMENT ''用户点的那个断点（画布节点 id）''',
  'SET @pipeline_retry_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_pipeline_run'
             AND COLUMN_NAME = 'masked_nodes_json');
SET @ddl := IF(@c = 0,
  'ALTER TABLE platform_pipeline_run ADD COLUMN masked_nodes_json mediumtext NULL COMMENT ''本次运行屏蔽的节点（JSON 数组，闭包展开后）''',
  'SET @pipeline_retry_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_pipeline_run'
             AND COLUMN_NAME = 'reused_nodes_json');
SET @ddl := IF(@c = 0,
  'ALTER TABLE platform_pipeline_run ADD COLUMN reused_nodes_json mediumtext NULL COMMENT ''重试时沿用的节点（JSON 数组，父运行里已成功）''',
  'SET @pipeline_retry_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_pipeline_run'
             AND COLUMN_NAME = 'workspace_pvc');
SET @ddl := IF(@c = 0,
  'ALTER TABLE platform_pipeline_run ADD COLUMN workspace_pvc varchar(128) NULL COMMENT ''本次运行（链）使用的共享工作区 PVC 名''',
  'SET @pipeline_retry_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

-- ===== 2) 四条端点权限行（不新增 code，全部复用既有 5 个码）=====
-- ⚠️ 授权按「端点行」匹配：没有权限行的新端点会被 PermissionCrossCheckRunner 判成裸端点并拒启。
INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_pipeline_runretry',      'API','/pipeline/run/retry',          'POST','tenant:pipeline:run',  '从断点继续重跑'),
 ('perm_pipeline_runretryplan',  'API','/pipeline/run/retry/plan',     'POST','tenant:pipeline:read', '重试计划（哪些节点可点、会跑哪些）'),
 ('perm_pipeline_retryws',       'API','/pipeline/retry-workspaces',   'POST','tenant:pipeline:read', '待重试工作区列表'),
 ('perm_pipeline_retrywsclear',  'API','/pipeline/retry-workspaces/clear','POST','tenant:pipeline:run', '清理待重试工作区')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

-- ===== 3) 角色关联：4 行 × 3 角色 = 12 行 =====
-- ⚠️ 按 permission_id 绑，不按 code 继承 —— 漏绑 = 上线可见但点了 403
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_tadmin_pl_retry',      'builtin_role_tenant_admin','perm_pipeline_runretry'),
 ('rp_tadmin_pl_retryplan',  'builtin_role_tenant_admin','perm_pipeline_runretryplan'),
 ('rp_tadmin_pl_retryws',    'builtin_role_tenant_admin','perm_pipeline_retryws'),
 ('rp_tadmin_pl_retrywsc',   'builtin_role_tenant_admin','perm_pipeline_retrywsclear'),
 ('rp_tmember_pl_retry',     'builtin_role_tenant_member','perm_pipeline_runretry'),
 ('rp_tmember_pl_retryplan', 'builtin_role_tenant_member','perm_pipeline_runretryplan'),
 ('rp_tmember_pl_retryws',   'builtin_role_tenant_member','perm_pipeline_retryws'),
 ('rp_tmember_pl_retrywsc',  'builtin_role_tenant_member','perm_pipeline_retrywsclear'),
 ('rp_admin_pl_retry',       'builtin_role_admin','perm_pipeline_runretry'),
 ('rp_admin_pl_retryplan',   'builtin_role_admin','perm_pipeline_runretryplan'),
 ('rp_admin_pl_retryws',     'builtin_role_admin','perm_pipeline_retryws'),
 ('rp_admin_pl_retrywsc',    'builtin_role_admin','perm_pipeline_retrywsclear')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
