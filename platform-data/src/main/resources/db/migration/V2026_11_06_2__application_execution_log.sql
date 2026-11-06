-- ============================================================================================
-- 应用发布：执行过程日志（2026-11-06）
-- ============================================================================================
-- 执行表只有一个 message 列（只在健康检查写「等待就绪」），看不出发布走到哪、卡在哪。
-- 新表按阶段追加日志：预检 / 渲染出哪些对象 / 下发 / 健康检查的每次变化（含 Pod 级原因：拉镜像失败、
-- 调度不上、探针没过……）/ 清理了哪些旧对象 / 结束或失败原因。只追加不改；按 (app_id, release_no) 查。
--
-- 接口 POST /application/execution/logs 走 tenant:application:read，绑定显式写出：admin、tenant_admin、tenant_member。
--
-- 幂等：CREATE TABLE IF NOT EXISTS + 显式 id + ODKU。⚠️ 本仓没有 Flyway，手工重放；先重放再起新构建
-- （新构建写日志失败只记 warn、不影响发布，但读日志接口会报错）。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

CREATE TABLE IF NOT EXISTS platform_app_execution_log (
  id            bigint        NOT NULL AUTO_INCREMENT,
  execution_id  bigint        NOT NULL,
  app_id        varchar(64)   NOT NULL,
  release_no    int           NOT NULL,
  stage         varchar(32)   NULL COMMENT '写日志时的阶段',
  `level`       varchar(8)    NOT NULL DEFAULT 'INFO' COMMENT 'INFO / WARN / ERROR',
  message       varchar(2000) NOT NULL,
  created_at    datetime(3)   NOT NULL,
  PRIMARY KEY (id),
  KEY idx_app_exec_log_release (app_id, release_no, id),
  KEY idx_app_exec_log_exec (execution_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用发布执行的过程日志';

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_app_execution_logs', 'API', '/application/execution/logs', 'POST','tenant:application:read', '发布过程日志')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_app_execution_logs',         'builtin_role_admin',         'perm_app_execution_logs'),
 ('rp_tenant_admin_app_execution_logs',  'builtin_role_tenant_admin',  'perm_app_execution_logs'),
 ('rp_tenant_member_app_execution_logs', 'builtin_role_tenant_member', 'perm_app_execution_logs')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
