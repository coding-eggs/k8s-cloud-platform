-- ============================================================================================
-- 平台总览 + 各维度概览计数卡 · 权限行（S1.3，2026-11-06）
-- ============================================================================================
-- POST /overview/platform        平台总览（新码 platform:overview:view，仅 admin）
-- POST /cluster/summary          集群概览计数卡（同 /cluster/overview：platform:cluster:manage）
-- POST /namespace/summary        命名空间概览计数卡（同 /namespace/get：platform:allocation:list）
-- POST /tenant/overview/summary  租户概览计数卡：自管轨 tenant:overview:view + 代管轨 platform:allocation:list（ANY-of，同 /tenant/overview）
-- GET  /nodes/{name}/podstat     单节点 Pod 统计（同节点详情：platform:cluster:manage）
-- 计数卡内部各块（应用 / 中间件 / CI / 告警）另按原列表页的读码逐块放行，没权限的块返回 null —— 不在这里加行。
-- 角色绑定显式写出（不按 code 继承）。service 列取默认 platform-api。
--
-- ⚠️ 表驱动授权：没有权限行时 PermissionCrossCheckRunner 启动期 fail-closed，platform-api 起不来
--    —— 先重放本文件再起新构建。本仓没有 Flyway，手工重放。重放后用户需重新登录（权限闭包 30s TTL 亦可）。
-- 幂等：显式 id + ON DUPLICATE KEY UPDATE。⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_overview_platform',    'API', '/overview/platform',       'POST', 'platform:overview:view',   '平台总览'),
 ('perm_cluster_summary',      'API', '/cluster/summary',         'POST', 'platform:cluster:manage',  '集群概览计数卡'),
 ('perm_ns_summary',           'API', '/namespace/summary',       'POST', 'platform:allocation:list', '命名空间概览计数卡'),
 ('perm_tenant_summary_self',  'API', '/tenant/overview/summary', 'POST', 'tenant:overview:view',     '租户概览计数卡（自管轨）'),
 ('perm_tenant_summary_proxy', 'API', '/tenant/overview/summary', 'POST', 'platform:allocation:list', '租户概览计数卡（代管轨）'),
 ('perm_node_podstat',         'API', '/nodes/{name}/podstat',    'GET',  'platform:cluster:manage',  '单节点 Pod 统计')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_overview_platform',       'builtin_role_admin',         'perm_overview_platform'),
 ('rp_admin_cluster_summary',         'builtin_role_admin',         'perm_cluster_summary'),
 ('rp_admin_ns_summary',              'builtin_role_admin',         'perm_ns_summary'),
 ('rp_admin_tenant_summary_self',     'builtin_role_admin',         'perm_tenant_summary_self'),
 ('rp_tadmin_tenant_summary_self',    'builtin_role_tenant_admin',  'perm_tenant_summary_self'),
 ('rp_tmember_tenant_summary_self',   'builtin_role_tenant_member', 'perm_tenant_summary_self'),
 ('rp_admin_tenant_summary_proxy',    'builtin_role_admin',         'perm_tenant_summary_proxy'),
 ('rp_admin_node_podstat',            'builtin_role_admin',         'perm_node_podstat')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
