-- ============================================================================================
-- 变更文件的补丁正文端点：/pipeline/run/diff/file（2026-11-18）
-- ============================================================================================
-- 变更清单回答「改了哪些文件、各多少行」；这个端点回答「这个文件具体改了哪几行」。
-- 补丁可能很大，所以按文件按需取，不跟着清单一起返回。
--
-- 权限沿用 tenant:pipeline:run（能运行的人才能看代码内容），绑定从 /pipeline/run 那行复制。
-- ⚠️ 无权限行的新端点会被 PermissionCrossCheckRunner 拒启，先重放本文件再起新构建。
-- ⚠️ 本仓没有 Flyway，手工重放；重放后需重新登录或等权限缓存 TTL（30s）。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, service, resource, action, code, description) VALUES
 ('perm_pipeline_runpatch', 'API', 'platform-api', '/pipeline/run/diff/file', 'POST', 'tenant:pipeline:run', '查看变更文件的补丁正文')
ON DUPLICATE KEY UPDATE
  domain = VALUES(domain), service = VALUES(service), resource = VALUES(resource), action = VALUES(action),
  code = VALUES(code), description = VALUES(description);

-- 新权限行不会按 code 自动继承到角色上：按「已持有触发运行」的旧行回填（内置角色与自定义角色一视同仁）。
INSERT INTO platform_role_permission (id, role_id, permission_id)
SELECT CONCAT('rp_pat_', SUBSTRING(MD5(CONCAT(rp.role_id, '|', 'perm_pipeline_runpatch')), 1, 32)),
       rp.role_id, 'perm_pipeline_runpatch'
FROM platform_role_permission rp
WHERE rp.permission_id = 'perm_pipeline_run'
GROUP BY rp.role_id
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);
