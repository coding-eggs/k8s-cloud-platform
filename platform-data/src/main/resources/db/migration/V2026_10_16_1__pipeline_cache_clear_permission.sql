-- ============================================================================================
-- S3.5 第 ① 片补 4：构建缓存的「清缓存」端点权限行
-- ============================================================================================
-- 为什么：构建缓存是每命名空间一块**跨运行保留**的卷（platform-pipeline-cache，10Gi）。
-- 它会涨（旧版本的 jar/npm 包不会自己消失），所以必须有一个「清掉它」的入口 ——
-- 落在流水线列表页工具栏（作用域正好是「当前集群 + 命名空间」，与缓存卷一一对应）。
--
-- ⚠️ 缓存**状态**不新增权限行：它随已有的 `/pipeline/capability` 一起回
--    （「能不能开缓存」与「能不能跑 CI」是同一类问题，页面本来就为后者发一次请求）。
--    所以这里只有一条写端点的行。
--
-- 码沿用 `tenant:pipeline:manage`：能改流水线的人才该能清缓存。
-- 角色关联与其它 manage 行（/pipeline/create|update|delete）**完全一致**：tenant_admin + admin，
-- 不含 tenant_member（member 不能改流水线，也就不该能清缓存）。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启（fail-closed）。
-- ⚠️ 重放后需重新登录或等权限缓存 TTL（30s）：token 里已无权限码，闭包在请求期现算。
-- ⚠️ 本仓没有 Flyway，本文件是手工重放的；启动日志里永远不会出现 Migrating schema。
-- 幂等：权限行与角色关联都显式 id + ODKU（`perm_` / `rp_` 里的 `_` 是 LIKE 的单字符通配符，
--       所以角色关联一律按**显式 id 列表**写，不用 LIKE —— V2026_10_08_5 已踩过）。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符，否则整个文件重放失败。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_pipeline_cacheclear', 'API','/pipeline/cache/clear', 'POST','tenant:pipeline:manage',
  '清掉这个命名空间的构建缓存（删那块长期卷，下次运行自动重建）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_tadmin_pl_cacheclr', 'builtin_role_tenant_admin','perm_pipeline_cacheclear'),
 ('rp_admin_pl_cacheclr',  'builtin_role_admin',       'perm_pipeline_cacheclear')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
