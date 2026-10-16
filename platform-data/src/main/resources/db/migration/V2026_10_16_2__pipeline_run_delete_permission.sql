-- ============================================================================================
-- S3.5：删运行记录端点 POST /pipeline/run/delete 的权限行
-- ============================================================================================
-- 为什么：运行历史原先只能等保留期（默认 90 天，PipelineRetentionService）自己过期 ——
-- 用户想立刻清掉某一条没有入口。
--
-- 这一步**不可恢复**：归档那一刻集群里的对象与 Pod 就删了，库里的行是**日志与 YAML 的唯一留存**。
-- 所以：
--   · 端点独立成行（每个写端点一行，PermissionCrossCheckRunner 交叉校验，fail-closed）
--   · 码沿用 `tenant:pipeline:manage` —— 能改流水线的人才该能删它的运行历史
--     （与 /pipeline/cache/clear 同一条理由：动的都是"流水线留下的东西"）
--   · 角色关联与其它 manage 行**完全一致**：tenant_admin + admin，不含 tenant_member
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启。
--    （反过来先起构建是安全的：多出来的权限行匹配不到端点只是幽灵行 WARN，不拒启。）
-- ⚠️ 重放后需重新登录或等权限缓存 TTL（30s）：token 里已无权限码，闭包在请求期现算。
-- ⚠️ 本仓没有 Flyway，本文件是手工重放的；启动日志里永远不会出现 Migrating schema。
-- 幂等：权限行与角色关联都显式 id + ODKU（`perm_` / `rp_` 里的 `_` 是 LIKE 的单字符通配符，
--       所以角色关联一律按**显式 id 列表**写，不用 LIKE —— V2026_10_08_5 已踩过）。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符，否则整个文件重放失败。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_pipeline_rundelete', 'API','/pipeline/run/delete', 'POST','tenant:pipeline:manage',
  '删掉一条运行记录（运行历史里的行；不可恢复 —— 日志与 YAML 只在那一行上）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_tadmin_pl_rundel', 'builtin_role_tenant_admin','perm_pipeline_rundelete'),
 ('rp_admin_pl_rundel',  'builtin_role_admin',       'perm_pipeline_rundelete')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
