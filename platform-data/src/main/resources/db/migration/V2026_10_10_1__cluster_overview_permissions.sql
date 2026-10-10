-- ============================================================================================
-- B4 集群概览权限行：6 行 + admin 角色关联
-- ============================================================================================
-- 背景：PermissionCrossCheckRunner 启动期交叉校验要求「每个真实端点都有权限行或在豁免清单」，否则
-- platform-api 拒绝启动（fail-closed）。本批在 ClusterController（platform-api，@RequestMapping("/cluster")）
-- 新增 6 个端点：
--   · POST /cluster/overview                       概览快照（总量 + 健康/异常 + 存储 + 能力摘要）
--   · POST /cluster/resource-breakdown             per-namespace 资源明细（懒加载）
--   · POST /cluster/metrics/cpu                    集群 CPU 用量曲线
--   · POST /cluster/metrics/memory                 集群内存用量曲线
--   · POST /cluster/metrics/cpu/by-namespace       各命名空间 CPU 用量
--   · POST /cluster/metrics/memory/by-namespace    各命名空间内存用量
--
-- 权限码选择（不造新码）：<b>全部复用 platform:cluster:manage</b>。集群概览是平台管理面 —— 它的数据面是
-- 「整个集群的运维快照」（跨全部命名空间、含 kube-system，还能看到 NotReady 节点与异常 Pod 明细），
-- 这正是 cluster:manage 的语义面；与 /cluster/list、/cluster/get 同族（同一条 spec §3.3「集群管理整族」）。
-- 刻意<b>不</b>给租户角色：概览页入口是平台管理组的集群列表，租户没有这个页面，给码只会把
-- 「整个集群的视图」开放给不需要它的人。
--
-- 为什么指标端点也不豁免（对比 /namespace/metrics/** 的豁免）：命名空间那 4 个是「已分配的 ns 内只读展示」，
-- 任意登录用户可看与「命名空间读全开放」一致；而集群级曲线跨全部命名空间聚合，是平台管理面的数据。
-- 同族先例：/resource/nodes/{name}/metrics/* 也是 platform:cluster:manage，不豁免。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被启动交叉校验拒启。
-- ⚠️ 重放后需重新登录或等权限缓存 TTL（30s）：token 里已无权限码，闭包在请求期现算。
-- 幂等：显式 id + ODKU。角色关联按 <b>显式 id 列表</b>关联，不用 LIKE（id 里的 `_` 是 LIKE 的单字符
-- 通配符，前缀匹配会连带命中同前缀的其它行，V2026_10_08_5 已踩过）。
-- ============================================================================================

-- ===== 1) API 域：6 行 =====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_cluster_overview','API','/cluster/overview','POST','platform:cluster:manage',
  '集群概览快照（总量 + 节点健康/异常 Pod + 存储 + 能力摘要）'),
 ('perm_cluster_res_breakdown','API','/cluster/resource-breakdown','POST','platform:cluster:manage',
  '集群资源明细 per-namespace（资源明细 tab 懒加载）'),
 ('perm_cluster_metrics_cpu','API','/cluster/metrics/cpu','POST','platform:cluster:manage',
  '集群 CPU 用量曲线（核）'),
 ('perm_cluster_metrics_memory','API','/cluster/metrics/memory','POST','platform:cluster:manage',
  '集群内存用量曲线（字节）'),
 ('perm_cluster_metrics_cpu_ns','API','/cluster/metrics/cpu/by-namespace','POST','platform:cluster:manage',
  '各命名空间 CPU 用量（sum by namespace）'),
 ('perm_cluster_metrics_memory_ns','API','/cluster/metrics/memory/by-namespace','POST','platform:cluster:manage',
  '各命名空间内存用量（sum by namespace）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 2) 角色关联：admin 全给 =====
-- admin 本已通过既有 /cluster/* 行持有 platform:cluster:manage，这里仍显式绑定 —— 与 V2026_10_09_1 同做法：
-- 把「这一行归谁」写死在迁移里，日后若权限闭包的取数口径改成按行绑定，这里不会成为缺口。
INSERT INTO platform_role_permission (id,role_id,permission_id) VALUES
 ('rp_admin_cluster_overview','builtin_role_admin','perm_cluster_overview'),
 ('rp_admin_cluster_res_breakdown','builtin_role_admin','perm_cluster_res_breakdown'),
 ('rp_admin_cluster_metrics_cpu','builtin_role_admin','perm_cluster_metrics_cpu'),
 ('rp_admin_cluster_metrics_memory','builtin_role_admin','perm_cluster_metrics_memory'),
 ('rp_admin_cluster_metrics_cpu_ns','builtin_role_admin','perm_cluster_metrics_cpu_ns'),
 ('rp_admin_cluster_metrics_memory_ns','builtin_role_admin','perm_cluster_metrics_memory_ns')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- tenant-admin / tenant-member 有意不给：集群概览是平台管理面（无对应页面，且数据跨全部命名空间）。
