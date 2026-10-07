-- B7 Phase 3（BGP* 只读）：为新增的 9 个 /calico/bgp*/** 端点补权限行。
-- 背景：PermissionCrossCheckRunner 启动期交叉校验要求「每个真实端点都有权限行或在豁免清单」，
-- 否则 platform-api 拒绝启动。Phase 3 在 CalicoController 新增 BGPConfiguration/BGPPeer/BGPFilter
-- 各 list/get/yaml 共 9 端点（只读），无对应行 → 必启动失败。
--
-- 复用既有 code（不造新码、不需新增角色绑定）：全部 → platform:cluster:manage
--   （集群级网络基础设施 = PLATFORM:admin 纵深防御，与 /calico/ippool/** 同族；admin 已持有该 code）。
-- id 前缀 perm_calico_bgp_ 与既有行区分，避免 PRIMARY KEY 撞行。幂等：显式 id + ODKU（同 V2026_10_03_1 范式）。
-- ⚠️ 部署顺序：先重放本文件再起新构建——无行的 /calico/bgp*/** 端点会被启动交叉校验拒启（fail-closed）。

INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_calico_bgp_cfg_list',   'API','/calico/bgpconfiguration/list','POST','platform:cluster:manage','列出 Calico BGPConfiguration'),
 ('perm_calico_bgp_cfg_get',    'API','/calico/bgpconfiguration/{name}','GET','platform:cluster:manage','查看 BGPConfiguration 详情'),
 ('perm_calico_bgp_cfg_yaml',   'API','/calico/bgpconfiguration/{name}/yaml','GET','platform:cluster:manage','查看 BGPConfiguration YAML'),
 ('perm_calico_bgp_peer_list',  'API','/calico/bgppeer/list','POST','platform:cluster:manage','列出 Calico BGPPeer'),
 ('perm_calico_bgp_peer_get',   'API','/calico/bgppeer/{name}','GET','platform:cluster:manage','查看 BGPPeer 详情'),
 ('perm_calico_bgp_peer_yaml',  'API','/calico/bgppeer/{name}/yaml','GET','platform:cluster:manage','查看 BGPPeer YAML'),
 ('perm_calico_bgp_filter_list','API','/calico/bgpfilter/list','POST','platform:cluster:manage','列出 Calico BGPFilter'),
 ('perm_calico_bgp_filter_get', 'API','/calico/bgpfilter/{name}','GET','platform:cluster:manage','查看 BGPFilter 详情'),
 ('perm_calico_bgp_filter_yaml','API','/calico/bgpfilter/{name}/yaml','GET','platform:cluster:manage','查看 BGPFilter YAML')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);
