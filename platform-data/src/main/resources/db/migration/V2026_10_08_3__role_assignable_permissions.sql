-- ============================================================================================
-- /role/permission/assignable 权限行（角色「可分配权限点」列表）
-- ============================================================================================
-- 背景：角色管理页的勾选列表此前由**前端**按「角色 scope → code 前缀」自行过滤
--   （PLATFORM 只见 platform:、TENANT 只见 tenant:），并硬编码 `code === 'admin'` 作豁免。
--   本次把这套判据整体搬到后端：新增本端点，返回"该角色可分配的权限点行"，
--   前端只渲染后端给的东西。理由见 docs/development/frontend-permission-conventions.md §1.1：
--   可分配范围是授权策略，不是展示口径，放前端必然会与 RoleService 漂移。
--
-- 规则（RoleService.assignablePermissions）：
--   · TENANT 角色 → 仅 tenant: 族（真安全线：防"租户管理员"被勾出建租户权限）
--   · PLATFORM 角色 → 两族都可。平台族在运行时本就是租户族的超集：TokenExtrasService.permissions()
--     把平台族角色的码**无条件**并入，租户角色的码只在带 tenantInfo 时才并入。配置面此前按
--     "两族互斥"限制，与运行时不一致；且为救内置 admin（持全部资源域 tenant:* 码）而硬编码了
--     `code === 'admin'` 特例，本次一并删除。
--   ⚠️ 语义提醒：给 PLATFORM 角色勾租户码 = 授权它对**任何**租户做那件事（无 tenantInfo 的 token
--      走 ResourceAccessResolver 的 admin 分支，边界只剩分配表三元组）。UI 提示已写明。
--
-- 幂等：显式 id + ON DUPLICATE KEY UPDATE（同 V2026_09_27_1 范式）。
-- 无需补角色绑定：授权按 code 判（PERM:<code>），builtin_role_admin 已持有 platform:role:read。
--
-- ⚠️ fail-closed 顺序：PermissionCrossCheckRunner 启动期核对"每个活端点都有权限行或豁免"，
--    无行即拒绝启动。故本文件必须**先于**新构建重放。
-- ============================================================================================

INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_role_perm_assignable','API','/role/permission/assignable','POST','platform:role:read',
  '查看该角色可分配的权限点（可分配范围由后端判定，前端不自行过滤）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);
