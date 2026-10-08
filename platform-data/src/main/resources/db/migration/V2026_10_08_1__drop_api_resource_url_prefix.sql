-- 去掉 API 域权限行 resource 上的 `/resource` 前缀。
--
-- 背景：platform_permission.resource 存的是 **platform-api 的 URL 模式**。本批把 api 侧
-- `/resource/configmaps` 之类的路由整体去掉 `/resource` 段（k8s-server 侧同步去掉 `/resources`、`/admin`
-- 两族前缀），两跳的 URL 空间因此重合。权限行必须与代码同批改写，否则 platform-api 启动期的
-- PermissionCrossCheckRunner（fail-closed）会因"真实端点无权限行"拒绝启动。
--
-- ⚠️ 必须带 domain='API'：domain='Page' 行的 resource 是**前端路由**（如 /resources/workloads），
--    那是浏览器 URL，与 HTTP 端点无关，本批有意不动。
--
-- 有意不加 `deleted_at IS NULL`：软删的行一并改写。它们是同一批数据，若将来被恢复（或做数据审计），
-- 留着旧前缀会得到一条指向不存在端点的幽灵行 —— 而幽灵行在启动期只会 WARN，不会拦住任何人。
--
-- 幂等：LIKE '/resource/%' 在重放后不再匹配任何行（改写后已无该前缀）。
-- 无唯一键风险：uk_domain_resource_action 已在 V2026_09_24_2 降级为普通索引
-- （同 (domain, resource, action) 多 code 是 ANY-of 模型，见该文件的说明）。
UPDATE platform_permission
SET resource = SUBSTRING(resource, LENGTH('/resource') + 1)
WHERE domain = 'API'
  AND resource LIKE '/resource/%';

-- 部署提示：本迁移与两侧代码必须同批上线。先起新构建、后重放迁移会让 platform-api 启动失败
-- （裸端点拒启）；先重放、后起新构建则旧构建的权限行匹配不到端点，授权全 401/403。
-- 细节见 docs/superpowers/plans/2026-10-08-prefix-and-boundary.md 的部署 runbook。
