-- B7 Phase 3.5（BGP* 全 CRUD，用户 2026-10-06 确认扩围）：为新增的 9 个 /calico/bgp* 写端点补权限行。
-- 背景：PermissionCrossCheckRunner 启动期交叉校验要求「每个真实端点都有权限行或在豁免清单」，否则 platform-api 拒绝启动。
--
-- 权限分层（与计划 Phase 3.5 一致）：
--   BGPPeer / BGPFilter 写 ×6 → 复用 platform:cluster:manage（与读同族：能看即能管；admin 已持有该 code，自动生效）。
--   BGPConfiguration 写 ×3 → 新 code platform:bgp:config:manage（集群全局 BGP 默认配置，误改可能中断节点间/对外 BGP，
--     仅平台管理员可操作）。⚠️ V2026_09_24_2 的「admin→全部 platform:%」是一次性快照 SELECT，新 code 不会自动进 admin，
--     必须显式补 role_permission 绑定（下方第二段）；权限闭包在 token 签发时按 role→role_permission→code 动态计算，绑了即生效。
-- id 前缀 perm_calico_bgp_ 与既有行区分。幂等：显式 id + ODKU（同 V2026_10_03_1 范式）。
-- ⚠️ 部署顺序：先重放本文件再起新构建——无行的 /calico/bgp* 写端点会被启动交叉校验拒启（fail-closed）。

INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_calico_bgp_cfg_create', 'API','/calico/bgpconfiguration','POST','platform:bgp:config:manage','创建 BGPConfiguration（仅平台管理员）'),
 ('perm_calico_bgp_cfg_update', 'API','/calico/bgpconfiguration/{name}','PUT','platform:bgp:config:manage','更新 BGPConfiguration（仅平台管理员）'),
 ('perm_calico_bgp_cfg_delete', 'API','/calico/bgpconfiguration/{name}','DELETE','platform:bgp:config:manage','删除 BGPConfiguration（仅平台管理员）'),
 ('perm_calico_bgp_peer_create','API','/calico/bgppeer','POST','platform:cluster:manage','创建 BGPPeer'),
 ('perm_calico_bgp_peer_update','API','/calico/bgppeer/{name}','PUT','platform:cluster:manage','更新 BGPPeer'),
 ('perm_calico_bgp_peer_delete','API','/calico/bgppeer/{name}','DELETE','platform:cluster:manage','删除 BGPPeer'),
 ('perm_calico_bgp_filter_create','API','/calico/bgpfilter','POST','platform:cluster:manage','创建 BGPFilter'),
 ('perm_calico_bgp_filter_update','API','/calico/bgpfilter/{name}','PUT','platform:cluster:manage','更新 BGPFilter'),
 ('perm_calico_bgp_filter_delete','API','/calico/bgpfilter/{name}','DELETE','platform:cluster:manage','删除 BGPFilter')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- 新 code 只绑内置 admin 角色（管理员仍可在角色编辑器显式下放，属主动授权）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_admin_bgp_cfg_', p.id), 'builtin_role_admin', p.id
FROM platform_permission p
WHERE p.code = 'platform:bgp:config:manage' AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
