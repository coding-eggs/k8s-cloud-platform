-- ============================================================================================
-- 工作负载列表页三个行内动作（改镜像 / 历史版本 / 回滚）+ 节点地址窄投影 + 模板重下发：API 行 5 条
-- + 角色关联 + 租户 RBAC 模板补 controllerrevisions
-- ============================================================================================
-- 背景：PermissionCrossCheckRunner 启动期交叉校验要求「每个真实端点都有权限行或在豁免清单」，否则
-- platform-api 拒绝启动（fail-closed）。本批新增端点：
--   · WorkloadController（platform-api） —— POST /workloads/{name}/image      改一个容器的镜像（直接发版）
--                                          GET  /workloads/{name}/revisions  历史版本列表
--                                          POST /workloads/{name}/rollback   回滚到指定版本
--   · NodeController（platform-api）     —— GET  /nodes/addresses             节点地址窄投影（NodePort 直连）
--   · RbacTemplateController（platform-api）—— POST /rbacTemplate/sync-all    重下发全部模板到所有集群
-- k8s-server 侧对应端点（同路径）不参与本校验：那边的授权是 AccessBoundary，不是权限表。
--
-- 权限码选择（只造 0 个新码）：
--   · image / rollback → tenant:workload:update。<b>有意复用既有动词码</b>：两者本质都是"改这个工作负载"
--     （前者改 pod template 的 image，后者用历史版本覆盖 pod template），与 /workloads/{name}/pause、
--     /workloads/{name}/mesh-toggle 完全同性质 —— 它们也共享 tenant:workload:update。
--   · revisions → tenant:workload:get（只读展示，与 get/yaml/指标同族）
--   · /nodes/addresses → tenant:cluster:capability:view（"租户可读的集群只读快照"，V2026_10_07_2 起的既有码）
--   · /rbacTemplate/sync-all → platform:template:manage（模板管理族，与 create/update/delete 同码）
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被启动交叉校验拒启。
-- ⚠️ 本批还有一步不在 SQL 里：模板 rules 改完后，<b>管理员需点一次「重下发全部模板」</b>
--    （RBAC 模板页 → 或直接 POST /rbacTemplate/sync-all）。平台只在保存单个模板时 sync，
--    不重下发的话各集群已有的 tn-tpl-* ClusterRole 还是旧 rules → 租户回滚 StatefulSet/DaemonSet 会 403
--    （Deployment 回滚不受影响：它读的是 ReplicaSet，租户 SA 本来就有 replicasets 权限）。
-- 幂等：显式 id + ODKU；角色关联用显式 id 列表（不用 LIKE —— id 里的 `_` 是 LIKE 单字符通配符）。
-- ============================================================================================

-- ===== 1) API 域：5 行 =====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_wl_image','API','/workloads/{name}/image','POST','tenant:workload:update',
  '改一个容器的镜像（共享 update 动词，触发滚动更新）'),
 ('perm_wl_revisions','API','/workloads/{name}/revisions','GET','tenant:workload:get',
  '工作负载历史版本列表（共享 get 动词）'),
 ('perm_wl_rollback','API','/workloads/{name}/rollback','POST','tenant:workload:update',
  '回滚到指定历史版本（共享 update 动词，触发滚动更新）'),
 ('perm_node_addresses','API','/nodes/addresses','GET','tenant:cluster:capability:view',
  '节点地址窄投影（NodePort 直连用；只回 name+IP+Ready）'),
 ('perm_tpl_sync_all','API','/rbacTemplate/sync-all','POST','platform:template:manage',
  '重下发全部 RBAC 模板到所有启用集群')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 2) 角色关联：admin / tenant-admin / tenant-member =====
-- admin → 全 5 行（含 tenant:* 资源码：admin 要在租户上下文里代管资源页，同 V2026_09_29_2 的结论）
INSERT INTO platform_role_permission (id,role_id,permission_id) VALUES
 ('rp_admin_wl_image','builtin_role_admin','perm_wl_image'),
 ('rp_admin_wl_revisions','builtin_role_admin','perm_wl_revisions'),
 ('rp_admin_wl_rollback','builtin_role_admin','perm_wl_rollback'),
 ('rp_admin_node_addresses','builtin_role_admin','perm_node_addresses'),
 ('rp_admin_tpl_sync_all','builtin_role_admin','perm_tpl_sync_all'),
-- tenant-admin → 工作负载三行 + 节点地址（列表页点 nodePort 直连，要能拿节点 IP）
 ('rp_tadmin_wl_image','builtin_role_tenant_admin','perm_wl_image'),
 ('rp_tadmin_wl_revisions','builtin_role_tenant_admin','perm_wl_revisions'),
 ('rp_tadmin_wl_rollback','builtin_role_tenant_admin','perm_wl_rollback'),
 ('rp_tadmin_node_addresses','builtin_role_tenant_admin','perm_node_addresses'),
-- tenant-member → 只给读的两行（revisions 是 GET；节点地址是"集群只读快照"族，本来就有该码的读行）
 ('rp_tmember_wl_revisions','builtin_role_tenant_member','perm_wl_revisions'),
 ('rp_tmember_node_addresses','builtin_role_tenant_member','perm_node_addresses')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- tenant-member 有意不给 image / rollback：两者都是写操作（且都触发滚动更新）。
-- 其它角色（自定义）不给：新行的码不会自动挂到它们身上，需要管理员在权限页按需授予。

-- ===== 3) 租户 RBAC 模板补 controllerrevisions（StatefulSet/DaemonSet 的历史版本读取）=====
-- 为什么：STS/DS 的版本历史存在 ControllerRevision 里，而模板里的 apps 组只列了
-- deployments/replicasets/statefulsets/daemonsets —— 租户 SA 读不到 CR，回滚会 403。
-- 只改"已经列了 daemonsets 的模板"（= 已把 apps 工作负载交给租户的模板），不新造规则、不动其它模板。
-- 幂等：已含 controllerrevisions 的模板直接跳过。
-- ⚠️ 改完必须重下发（见文件头），否则各集群已有的 tn-tpl-* ClusterRole 不会变。
UPDATE platform_rbac_template
SET rules = REPLACE(rules, '"daemonsets"', '"daemonsets","controllerrevisions"')
WHERE rules LIKE '%"daemonsets"%'
  AND rules NOT LIKE '%controllerrevisions%'
  AND deleted_at IS NULL;
