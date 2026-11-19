SET @ddl := IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='platform_tenant_namespace' AND column_name='expires_at'), 'SELECT 1', 'ALTER TABLE platform_tenant_namespace ADD COLUMN expires_at BIGINT NULL');
PREPARE lifecycle_migration FROM @ddl;
EXECUTE lifecycle_migration;
DEALLOCATE PREPARE lifecycle_migration;

SET @ddl := IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='platform_tenant_namespace' AND column_name='namespace_uid'), 'SELECT 1', 'ALTER TABLE platform_tenant_namespace ADD COLUMN namespace_uid VARCHAR(64) NULL');
PREPARE lifecycle_migration FROM @ddl;
EXECUTE lifecycle_migration;
DEALLOCATE PREPARE lifecycle_migration;

SET @ddl := IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='platform_tenant_namespace' AND column_name='lifecycle'), 'SELECT 1', 'ALTER TABLE platform_tenant_namespace ADD COLUMN lifecycle VARCHAR(24) NOT NULL DEFAULT ''ACTIVE''');
PREPARE lifecycle_migration FROM @ddl;
EXECUTE lifecycle_migration;
DEALLOCATE PREPARE lifecycle_migration;

SET @ddl := IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='platform_tenant_namespace' AND column_name='lifecycle_error'), 'SELECT 1', 'ALTER TABLE platform_tenant_namespace ADD COLUMN lifecycle_error VARCHAR(1000) NULL');
PREPARE lifecycle_migration FROM @ddl;
EXECUTE lifecycle_migration;
DEALLOCATE PREPARE lifecycle_migration;

CREATE TABLE IF NOT EXISTS platform_namespace_request (
  id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, applicant VARCHAR(255) NOT NULL,
  kind VARCHAR(16) NOT NULL, allocation_id VARCHAR(64), namespace VARCHAR(63) NOT NULL,
  cluster_id VARCHAR(64), purpose VARCHAR(1000) NOT NULL, expires_at BIGINT,
  quota_key VARCHAR(64), baseline DECIMAL(36,9), target_value DECIMAL(36,9),
  approved_value DECIMAL(36,9), reviewer VARCHAR(255), reason VARCHAR(1000),
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING', created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX ix_ns_req(tenant_id,applicant,created_at), INDEX ix_alloc_req(allocation_id)
);

CREATE TABLE IF NOT EXISTS platform_namespace_reminder (
  allocation_id VARCHAR(64) NOT NULL, expires_at BIGINT NOT NULL, days_left SMALLINT NOT NULL,
  recipient VARCHAR(255) NOT NULL, delivered_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(allocation_id,expires_at,days_left,recipient)
);

INSERT INTO platform_permission(id,domain,service,resource,action,code,description) VALUES
 ('perm_ns_req_submit','API','platform-api','/namespace-requests/submit','POST','tenant:namespace:request','空间申请与回收 submit'),
 ('perm_ns_req_mine','API','platform-api','/namespace-requests/mine','POST','tenant:namespace:request','空间申请与回收 mine'),
 ('perm_ns_req_review','API','platform-api','/namespace-requests/review','POST','platform:namespace:review','空间申请与回收 review'),
 ('perm_ns_req_list','API','platform-api','/namespace-requests/list','POST','platform:namespace:review','空间申请与回收 list'),
 ('perm_ns_req_preview','API','platform-api','/namespace-requests/preview','POST','platform:namespace:reclaim','空间申请与回收 preview'),
 ('perm_ns_req_stop','API','platform-api','/namespace-requests/stop','POST','platform:namespace:reclaim','空间申请与回收 stop'),
 ('perm_ns_req_delete','API','platform-api','/namespace-requests/delete','POST','platform:namespace:destroy','空间申请与回收 delete')
ON DUPLICATE KEY UPDATE code=VALUES(code);
INSERT INTO platform_permission(id,domain,service,resource,action,code,description) VALUES
 ('perm_ns_req_remind','API','platform-api','/namespace-requests/reminders','POST','tenant:namespace:reminders','命名空间有效期提醒')
ON DUPLICATE KEY UPDATE code=VALUES(code);
INSERT IGNORE INTO platform_role_permission(id,role_id,permission_id)
SELECT CONCAT('rn_',MD5(CONCAT(r.id,p.id))),r.id,p.id FROM platform_role r JOIN platform_permission p ON p.resource LIKE '/namespace-requests/%'
WHERE r.id='builtin_role_admin' OR (r.id IN ('builtin_role_tenant_admin','builtin_role_tenant_member') AND p.code='tenant:namespace:request');
INSERT IGNORE INTO platform_role_permission(id,role_id,permission_id) VALUES
 ('rp_ns_remind_tadmin','builtin_role_tenant_admin','perm_ns_req_remind');
