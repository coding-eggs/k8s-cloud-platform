CREATE TABLE IF NOT EXISTS platform_alert_handling (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  event_id BIGINT NOT NULL,
  actor VARCHAR(255) NOT NULL,
  kind VARCHAR(16) NOT NULL,
  note VARCHAR(1000),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX ix_alert_handling(event_id,id)
);

INSERT INTO platform_permission(id,domain,service,resource,action,code,description) VALUES
 ('perm_alert_history_p','API','platform-api','/alert/handling/list','POST','platform:alert:read','告警处理记录'),
 ('perm_alert_history_t','API','platform-api','/alert/handling/list','POST','tenant:alert:list','租户告警处理记录'),
 ('perm_alert_handle','API','platform-api','/alert/handling/update','POST','tenant:alert:handle','认领告警和填写备注')
ON DUPLICATE KEY UPDATE code=VALUES(code);
INSERT IGNORE INTO platform_role_permission(id,role_id,permission_id)
SELECT CONCAT('rp_ah_',MD5(CONCAT(rp.role_id,p.id))),rp.role_id,p.id FROM platform_role_permission rp
JOIN platform_permission old ON old.id=rp.permission_id
JOIN platform_permission p ON p.code=old.code AND p.resource='/alert/handling/list'
WHERE old.resource='/alert/list';
INSERT IGNORE INTO platform_role_permission(id,role_id,permission_id)
SELECT CONCAT('rp_ah_',MD5(role_id)),role_id,'perm_alert_handle' FROM platform_role_permission
WHERE permission_id IN ('perm_alert_list_platform','perm_alert_list_tenant')
AND role_id IN ('builtin_role_admin','builtin_role_tenant_admin','builtin_role_tenant_member');
