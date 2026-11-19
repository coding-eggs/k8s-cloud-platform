INSERT INTO platform_permission(id,domain,service,resource,action,code,description) VALUES
 ('perm_find_pod','API','platform-api','/resource-lookup/pods','POST','tenant:pod:list','按名称查找 Pod'),
 ('perm_find_wl','API','platform-api','/resource-lookup/workloads','POST','tenant:workload:list','按名称查找工作负载'),
 ('perm_find_app','API','platform-api','/resource-lookup/applications','POST','tenant:application:read','按名称查找应用')
ON DUPLICATE KEY UPDATE code=VALUES(code);
INSERT IGNORE INTO platform_role_permission(id,role_id,permission_id)
SELECT CONCAT('rf_',MD5(CONCAT(rp.role_id,p.id))),rp.role_id,p.id FROM platform_role_permission rp
JOIN platform_permission old ON old.id=rp.permission_id
JOIN platform_permission p ON p.code=old.code AND p.resource LIKE '/resource-lookup/%'
WHERE old.resource IN ('/pods/list','/workloads/list','/application/list');
