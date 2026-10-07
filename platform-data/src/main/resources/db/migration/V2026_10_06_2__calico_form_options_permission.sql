-- B7 Phase 3.5 补充（BGP 编辑器下拉候选端点 + admin 绑定补齐）。
-- 背景：PermissionCrossCheckRunner 启动期交叉校验要求「每个真实端点都有权限行或在豁免清单」，否则 platform-api 拒绝启动。
--
-- 1) BGP 编辑器下拉候选（级联）：
--    POST /calico/form-options            → namespaces + workloads（一次加载的小集合）
--    GET  /calico/form-options/secrets    → 所选命名空间下的 Secret 引用（name + data keys，级联第二级按需查）
--    均复用 platform:cluster:manage（与 BGP 读同族；能进 BGP 页即可取候选）。只回名字/keys，不含 Secret 数据值。
--
-- 2) ⚠️ admin 绑定补齐：权限闭包按 role→role_permission(行 id)→code join（selectPermissionCodesByRoleIds），
--    V2026_09_24_2 的「admin→全部 platform:%」是一次性快照 SELECT，此后的 perm_calico_% 新行（V2026_10_03_1 /
--    V2026_10_03_2 / V2026_10_05_1）从未绑到 builtin_role_admin → admin 调 /calico/** 会 403。
--    本段把所有未绑定的 perm_calico_% 行幂等补绑（含本文件新增行）。
-- id 前缀 perm_calico_ 与既有行区分，避免 PRIMARY KEY 撞行。幂等：显式 id + ODKU（同 V2026_10_03_1 范式）。
-- ⚠️ 部署顺序：先重放本文件再起新构建——无行的 /calico/form-options/** 端点会被启动交叉校验拒启（fail-closed）。

INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_calico_form_options',       'API','/calico/form-options','POST','platform:cluster:manage','BGP 编辑器下拉候选（命名空间/工作负载，只读）'),
 ('perm_calico_form_options_secrets','API','/calico/form-options/secrets','GET','platform:cluster:manage','某命名空间下 Secret 引用候选（级联第二级，只读）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- admin → 全部未绑定的 perm_calico_% 行（幂等：已绑的行被 NOT EXISTS 过滤）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_admin_calico_', p.id), 'builtin_role_admin', p.id
FROM platform_permission p
WHERE p.id LIKE 'perm_calico\_%' AND p.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM platform_role_permission rp
    WHERE rp.role_id = 'builtin_role_admin' AND rp.permission_id = p.id
  );
