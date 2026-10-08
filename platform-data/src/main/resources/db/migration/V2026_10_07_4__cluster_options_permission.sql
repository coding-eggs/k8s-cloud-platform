-- ============================================================================================
-- /cluster/options 权限行（新增窄投影端点）
-- ============================================================================================
-- 背景：命名空间管理/编辑/概览三个页面需要一个"集群选择框"，此前调的是 /cluster/list ——
-- 那是平台管理面（含版本/描述等登记信息），权限码 platform:cluster:manage。结果是"只做命名空间运维"
-- 的角色一进这几个页面就必然 403 并弹错（前端主动越权清单第 7 项）。
--
-- 修法不是给它补一个权限位门控就算了（那只是不发请求），而是暴露与需求同粒度的窄端点
-- /cluster/options（只返回 clusterId/clusterName/enabled），权限码与这些页面一致：platform:allocation:list。
--
-- 复用既有 code（不造新码、不需新增角色绑定）：admin 已通过 perm_ns_list/perm_ns_b3_* 持有该 code。
--
-- ⚠️ fail-closed 顺序：PermissionCrossCheckRunner 在启动期核对"每个活端点都有权限行或豁免"，
--    无行即拒绝启动。故本文件必须<b>先于</b>新构建重放。
--
-- 幂等：显式 id + ON DUPLICATE KEY UPDATE，同既有范式。
-- ============================================================================================

INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_cluster_options','API','/cluster/options','POST','platform:allocation:list',
  '集群下拉选项（窄投影：id/名称/启停；命名空间页选择框用）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);
