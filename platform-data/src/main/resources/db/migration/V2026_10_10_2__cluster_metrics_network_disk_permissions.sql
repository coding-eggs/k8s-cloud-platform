-- ============================================================================================
-- B4 集群概览补 2 图（网络 / 磁盘 IO）权限行：2 行 + admin 角色关联
-- ============================================================================================
-- 背景：PermissionCrossCheckRunner 启动期交叉校验要求「每个真实端点都有权限行或在豁免清单」，否则
-- platform-api 拒绝启动（fail-closed）。本批在 ClusterController 新增 2 个端点（照 2026-10-10_1 的
-- 集群 CPU/内存曲线补齐命名空间概览那 4 图的另外两张）：
--   · POST /cluster/metrics/network   集群网络 IO（字节/秒，RX/TX）
--   · POST /cluster/metrics/disk      集群磁盘 IO（字节/秒，读/写）
--
-- 权限码选择（不造新码）：复用 platform:cluster:manage，与 /cluster/metrics/{cpu,memory} 及其
-- by-namespace 变体完全同族（同一张图的另外两条曲线，拆成不同码只会让「看集群曲线」变成需要授予两次的能力）。
-- 同样<b>不</b>给租户角色：集群级曲线跨全部命名空间聚合，属平台管理面（对比 /namespace/metrics/** 的豁免）。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被启动交叉校验拒启。
-- ⚠️ 重放后需重新登录或等权限缓存 TTL（30s）：token 里已无权限码，闭包在请求期现算。
-- 幂等：显式 id + ODKU；角色关联按显式 id 列表（不用 LIKE —— id 里的 `_` 是 LIKE 的单字符通配符）。
-- ============================================================================================

-- ===== 1) API 域：2 行 =====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_cluster_metrics_network','API','/cluster/metrics/network','POST','platform:cluster:manage',
  '集群网络 IO 曲线（字节/秒，RX/TX）'),
 ('perm_cluster_metrics_disk','API','/cluster/metrics/disk','POST','platform:cluster:manage',
  '集群磁盘 IO 曲线（字节/秒，读/写）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 2) 角色关联：admin 全给（同 V2026_10_10_1 的做法：把「这一行归谁」写死在迁移里）=====
INSERT INTO platform_role_permission (id,role_id,permission_id) VALUES
 ('rp_admin_cluster_metrics_network','builtin_role_admin','perm_cluster_metrics_network'),
 ('rp_admin_cluster_metrics_disk','builtin_role_admin','perm_cluster_metrics_disk')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- tenant-admin / tenant-member 有意不给（同 V2026_10_10_1：集群概览无租户页面，数据跨全部命名空间）。
