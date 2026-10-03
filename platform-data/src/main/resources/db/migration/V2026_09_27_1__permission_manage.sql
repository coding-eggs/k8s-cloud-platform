-- 权限点管理端点的 seed 行（幂等：显式 id 命中 PRIMARY KEY + ODKU，同 V2026_09_24_2 范式）。
-- ⚠️ 部署顺序：先重放本文件再起新构建——新端点无行时启动交叉校验 fail-closed 拒启。
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_perm_create','API','/permission/create','POST','platform:role:manage','新建权限点'),
 ('perm_perm_update','API','/permission/update','POST','platform:role:manage','编辑权限点'),
 ('perm_perm_delete','API','/permission/delete','POST','platform:role:manage','删除权限点'),
 ('perm_perm_reload','API','/permission/reload','POST','platform:role:manage','重载运行时授权规则')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);
