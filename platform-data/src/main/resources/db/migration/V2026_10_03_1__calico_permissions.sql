-- B7（网络 Calico）：为新增的 11 个 /calico/** 端点补权限行。
-- 背景：PermissionCrossCheckRunner 启动期交叉校验要求「每个真实端点都有权限行或在豁免清单」，
-- 否则 platform-api 拒绝启动。B7 新增 CalicoController（IPPool CRUD + IPAM 派生查询）共 11 端点，无对应行 → 必启动失败。
--
-- 复用既有 code（不造新码、不需新增角色绑定）：全部 → platform:cluster:manage
--   （集群级网络基础设施 = PLATFORM:admin 纵深防御，与 /resource/nodes/** 同族；admin 已经持有该 code，
--    PermissionAuthorizationManager 按 code 判权 → 新行自动对 admin 生效，无需再跑 role_permission 绑定 SELECT）。
-- id 前缀 perm_calico_ 与既有行区分，避免 PRIMARY KEY 撞行。幂等：显式 id + ODKU（同 V2026_09_27_1 / V2026_09_29_1 范式）。
-- ⚠️ 部署顺序：先重放本文件再起新构建——无行的 /calico/** 端点会被启动交叉校验拒启（fail-closed）。

INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_calico_ippool_list',   'API','/calico/ippool/list','POST','platform:cluster:manage','列出 Calico IPPool'),
 ('perm_calico_ippool_get',    'API','/calico/ippool/{name}','GET','platform:cluster:manage','查看 IPPool 详情'),
 ('perm_calico_ippool_yaml',   'API','/calico/ippool/{name}/yaml','GET','platform:cluster:manage','查看 IPPool YAML'),
 ('perm_calico_ippool_create', 'API','/calico/ippool','POST','platform:cluster:manage','创建 IPPool'),
 ('perm_calico_ippool_update', 'API','/calico/ippool/{name}','PUT','platform:cluster:manage','更新 IPPool'),
 ('perm_calico_ippool_delete', 'API','/calico/ippool/{name}','DELETE','platform:cluster:manage','删除 IPPool（带守卫）'),
 ('perm_calico_ipam_summary',  'API','/calico/ipam/summary','GET','platform:cluster:manage','池 IPAM 汇总'),
 ('perm_calico_ipam_blocks',   'API','/calico/ipam/blocks','GET','platform:cluster:manage','已物化块表'),
 ('perm_calico_ipam_isfree',   'API','/calico/ipam/is-free','GET','platform:cluster:manage','点查 IP/块是否空闲'),
 ('perm_calico_ipam_nextfree', 'API','/calico/ipam/next-free-blocks','GET','platform:cluster:manage','下一批空闲块（分页）'),
 ('perm_calico_ipam_blockips', 'API','/calico/ipam/block-ips','GET','platform:cluster:manage','单块 per-IP 明细')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);
