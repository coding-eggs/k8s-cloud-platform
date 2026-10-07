-- B7 Phase 2（保留 IP / IPReservation）：为新增的 6 个 /calico/ipreservation/** 端点补权限行。
-- 背景：PermissionCrossCheckRunner 启动期交叉校验要求「每个真实端点都有权限行或在豁免清单」，
-- 否则 platform-api 拒绝启动。Phase 2 在 CalicoController 新增 IPReservation CRUD 共 6 端点，无对应行 → 必启动失败。
--
-- 复用既有 code（不造新码、不需新增角色绑定）：全部 → platform:cluster:manage
--   （集群级网络基础设施 = PLATFORM:admin 纵深防御，与 /calico/ippool/** 同族；admin 已持有该 code）。
-- id 前缀 perm_calico_ipres_ 与既有行区分，避免 PRIMARY KEY 撞行。幂等：显式 id + ODKU（同 V2026_10_03_1 范式）。
-- ⚠️ 部署顺序：先重放本文件再起新构建——无行的 /calico/ipreservation/** 端点会被启动交叉校验拒启（fail-closed）。

INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_calico_ipres_list',   'API','/calico/ipreservation/list','POST','platform:cluster:manage','列出 Calico IPReservation（保留 IP）'),
 ('perm_calico_ipres_get',    'API','/calico/ipreservation/{name}','GET','platform:cluster:manage','查看 IPReservation 详情'),
 ('perm_calico_ipres_yaml',   'API','/calico/ipreservation/{name}/yaml','GET','platform:cluster:manage','查看 IPReservation YAML'),
 ('perm_calico_ipres_create', 'API','/calico/ipreservation','POST','platform:cluster:manage','创建 IPReservation（保留段）'),
 ('perm_calico_ipres_update', 'API','/calico/ipreservation/{name}','PUT','platform:cluster:manage','更新 IPReservation（改保留段）'),
 ('perm_calico_ipres_delete', 'API','/calico/ipreservation/{name}','DELETE','platform:cluster:manage','删除 IPReservation（释放保留段）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);
