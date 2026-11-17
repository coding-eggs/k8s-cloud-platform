-- ============================================================================================
-- 流水线运行记录源码信息：sha 预览 + 变更 diff（2026-11-18）
-- ============================================================================================
-- platform_pipeline_run 加 source_json：本次运行每个 git 节点解析出的仓库/分支/commit/基线。
-- 新端点 /pipeline/run/preview 挂 tenant:pipeline:run（能运行的人才能预览源码）。
--
-- 为什么必须落库：归档会把集群里的 PipelineRun / TaskRun / Pod 全删掉，运行行只留快照；
-- 「这次构建的是哪个 commit」「上次成功的是什么」只有落在这里才留得住。
--
-- 幂等：information_schema 判断后再 ALTER；显式 id + ON DUPLICATE KEY UPDATE；不加外键。
-- ⚠️ 部署顺序：先重放本文件，再部署 platform-api（新 mapper 会读这一列），
--    最后部署 k8s-server 与前端。k8s-server 早于 platform-api 也安全（未知字段忽略），
--    但**反过来的顺序会让 pin 被忽略**——预览与真正执行就不再是同一个提交。
-- ⚠️ 无权限行的新端点会被 PermissionCrossCheckRunner 拒启，先重放本文件再起新构建。
-- ⚠️ 本仓没有 Flyway，手工重放；重放后需重新登录或等权限缓存 TTL（30s）。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_pipeline_run' AND COLUMN_NAME = 'source_json');
SET @s := IF(@c = 0,
  'ALTER TABLE platform_pipeline_run ADD COLUMN source_json MEDIUMTEXT NULL COMMENT ''本次运行各 git 节点解析出的源码信息（JSON 数组：repo/分支/sha/提交标题/基线）'' AFTER canvas_json',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

INSERT INTO platform_permission (id, domain, service, resource, action, code, description) VALUES
 ('perm_pipeline_runpreview', 'API', 'platform-api', '/pipeline/run/preview', 'POST', 'tenant:pipeline:run', '触发前预览源码（分支/标签对应的 commit）')
ON DUPLICATE KEY UPDATE
  domain = VALUES(domain), service = VALUES(service), resource = VALUES(resource), action = VALUES(action),
  code = VALUES(code), description = VALUES(description);

-- 新权限行不会按 code 自动继承到角色上：按「已持有触发运行」的旧行回填（内置角色与自定义角色一视同仁）。
INSERT INTO platform_role_permission (id, role_id, permission_id)
SELECT CONCAT('rp_prv_', SUBSTRING(MD5(CONCAT(rp.role_id, '|', 'perm_pipeline_runpreview')), 1, 32)),
       rp.role_id, 'perm_pipeline_runpreview'
FROM platform_role_permission rp
WHERE rp.permission_id = 'perm_pipeline_run'
GROUP BY rp.role_id
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);
