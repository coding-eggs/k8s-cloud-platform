-- ============================================================================================
-- 分配表收敛：一个命名空间只归属一个租户 + 撤掉平台代管命名空间的租户分配
-- ============================================================================================
-- 依据：docs/superpowers/specs/2026-10-31-platform-tenant-boundary-tightening-design.md §3.2 / §8
--
-- 两件事：
--   ① 分配表从「授权表」降级为「归属表」——它只回答"这个 ns 属于谁"，不再回答"平台能不能碰"
--      （平台侧改由身份判定，见 ResourceAccessResolver）。要让这个定位成立，(cluster, ns) 必须唯一。
--   ② 平台代管的命名空间（istio-system / platform-storage / kube-system …）不该分配给租户：
--      一旦分出去，同 ns 内 RBAC / 配额 / 网络 / Secret 可见性四个边界同时失效。
--      租户需要的是其中**某几个对象**，改由窄投影端点提供（/mesh/parent-gateways 等）。
--
-- ⚠️ 本仓没有 Flyway，本文件是手工重放的；启动日志里永远不会出现 Migrating schema。
-- ⚠️ 重放顺序：先跑 ①（看清楚谁和谁撞了）→ ② 去重 → ③ 撤平台 ns → ④ 加唯一键。
--    顺序反了会在 ④ 失败（有重复行时加不上唯一索引）。
-- 幂等：② 是集合差分删除（重复跑无副作用）；③ 是固定名单删除；④ 先查 information_schema。
-- ============================================================================================

-- ===== ① 预检（只读）：哪些 (cluster, namespace) 被分配给了多个租户 =====
-- 先看结果再做 ②：如果这里面有"本该一个人独占"的业务 ns，说明是分配操作出了错，
-- 去重前应当先确认留下的是对的那一个。
SELECT cluster_id, namespace,
       COUNT(*) AS tenant_count,
       GROUP_CONCAT(CONCAT(tenant_id, '@', create_time) ORDER BY create_time, id) AS allocations
FROM platform_tenant_namespace
GROUP BY cluster_id, namespace
HAVING COUNT(*) > 1;

-- ===== ② 去重：保留 create_time 最早的那一行 =====
-- 「最早那次分配」视为原本的归属；同一时刻的按 id 兜底，保证结果确定（与
-- PlatformTenantMapper.selectTenantIdByClusterAndNs 的 order by 同一口径）。
DELETE older FROM platform_tenant_namespace older
JOIN platform_tenant_namespace keeper
  ON older.cluster_id = keeper.cluster_id
 AND older.namespace  = keeper.namespace
 AND (older.create_time > keeper.create_time
      OR (older.create_time = keeper.create_time AND older.id > keeper.id));

-- ===== ③ 撤掉平台代管命名空间的租户分配 =====
-- 名单来源：platform-common 的 ProbeNamespaces.DEFAULT（平台组件探测清单）里的平台侧命名空间
--           + SystemConstant.SYSTEM_NAMESPACE。
-- ⚠️ 有意**不含** monitor：它可能是某个租户自己的监控命名空间，不是平台代管的。
--    —— 这正是这份名单不能"按前缀猜"的原因。执行前请先跑：
--       SELECT DISTINCT namespace FROM platform_tenant_namespace ORDER BY namespace;
--    确认名单里没有租户的业务命名空间。
-- ⚠️ 删行不会删集群里的 RoleBinding：那一步要另外做（`tn-tpl-*` 的 RoleBinding 仍在那些 ns 里）。
--    处置：手工删，或在删除后对该集群做一次租户清理/重下发（见运维文档）。
DELETE FROM platform_tenant_namespace
WHERE namespace IN (
  'kube-system',
  'platform-system',
  'istio-system',
  'platform-storage',
  'harbor',
  'tekton-pipelines',
  'tekton-operator',
  'cert-manager',
  'calico-system',
  'tigera-operator',
  'opentelemetry-operator-system'
);

-- ===== ④ 唯一键：一个 (cluster, namespace) 至多一行 =====
-- 旧索引 uk_tenant_cluster_ns(tenant_id, cluster_id, namespace) **有意保留**：
--   · hasNamespaceAccess 的查询正好按这三列过滤，它仍然是最合适的索引；
--   · DROP INDEX 没有 IF EXISTS，为了一次 idempotent 的收尾去引入一段动态 SQL 不值得。
-- 新索引与它不冲突，两个一起存在即可（新索引负责"一个 ns 只能归一个租户"）。
SET @has_new := (SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE()
                   AND table_name = 'platform_tenant_namespace'
                   AND index_name = 'uk_cluster_ns');
SET @ddl := IF(@has_new = 0,
  'ALTER TABLE platform_tenant_namespace ADD UNIQUE INDEX uk_cluster_ns (cluster_id, namespace)',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ===== 回退 =====
-- ALTER TABLE platform_tenant_namespace DROP INDEX uk_cluster_ns;
-- （数据侧不可回退：②③ 删掉的行没有备份。重放前先 mysqldump 这张表。）
