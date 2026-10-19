-- ============================================================================================
-- 组件操作「中止」端点：API 行 1 条 + admin 绑定
-- ============================================================================================
-- 背景：worker 随 platform-api 重启消失后，platform_component_operation 会一直停在 RUNNING，
-- 并发守卫（countRunning）把该组件锁死，要到 15 分钟后的惰性标定才解锁。管理员需要一个
-- 立刻释放锁的出口（尤其 helm 早已失败/退出、只是调用链没回来的情况）。
--
--   · ClusterController（platform-api） —— POST /cluster/components/operations/{id}/abandon
--     只把记录置为 CANCELED，不 kill helm 进程；helm 若仍在跑，下一次提交会被 helm 的
--     release 锁拒绝（报错可见），不会静默并发。
--
-- 权限码：复用 platform:cluster:manage（与 /cluster/components/apply、operations/get|list 同族）。
-- 只绑 admin：组件安装/升级本身就是平台管理员动作。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被启动交叉校验拒启（fail-closed）。
-- 幂等：显式 id + ODKU。
-- ============================================================================================

-- ===== 1) API 域：1 行 =====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_res_cluster_components_op_abandon','API','/cluster/components/operations/{id}/abandon','POST',
  'platform:cluster:manage','中止进行中的组件操作（只释放平台侧并发锁）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 2) 角色关联：仅 admin =====
INSERT INTO platform_role_permission (id,role_id,permission_id) VALUES
 ('rp_admin_cluster_components_op_abandon','builtin_role_admin','perm_res_cluster_components_op_abandon')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
