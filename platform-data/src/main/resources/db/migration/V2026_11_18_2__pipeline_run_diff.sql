-- ============================================================================================
-- 历史运行的变更清单端点：/pipeline/run/diff（2026-11-18）
-- ============================================================================================
-- 预览（V2026_11_18_1）回答「这次**将要**构建什么」；这个端点回答「那次**已经**构建了什么、
-- 相对基线改了什么」—— 输入全部来自运行行里的 source_json / canvas_json，按需现算。
--
-- 权限沿用 tenant:pipeline:run（能运行的人才能看它跑了哪些文件），绑定从 /pipeline/run 那行复制。
-- ⚠️ 无权限行的新端点会被 PermissionCrossCheckRunner 拒启，先重放本文件再起新构建。
-- ⚠️ 本仓没有 Flyway，手工重放；重放后需重新登录或等权限缓存 TTL（30s）。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, service, resource, action, code, description) VALUES
 ('perm_pipeline_rundiff', 'API', 'platform-api', '/pipeline/run/diff', 'POST', 'tenant:pipeline:run', '查看历史运行的变更文件清单')
ON DUPLICATE KEY UPDATE
  domain = VALUES(domain), service = VALUES(service), resource = VALUES(resource), action = VALUES(action),
  code = VALUES(code), description = VALUES(description);

-- 新权限行不会按 code 自动继承到角色上：按「已持有触发运行」的旧行回填（内置角色与自定义角色一视同仁）。
INSERT INTO platform_role_permission (id, role_id, permission_id)
SELECT CONCAT('rp_dif_', SUBSTRING(MD5(CONCAT(rp.role_id, '|', 'perm_pipeline_rundiff')), 1, 32)),
       rp.role_id, 'perm_pipeline_rundiff'
FROM platform_role_permission rp
WHERE rp.permission_id = 'perm_pipeline_run'
GROUP BY rp.role_id
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);
