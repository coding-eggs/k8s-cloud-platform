-- ============================================================================================
-- S0.1 审计日志补：动作码候选值端点 POST /audit/actions 的权限行（1 行）
-- ============================================================================================
-- 背景：审计页的「动作码」筛选改成 autocomplete，候选值不能在前端写死
-- （那是维护一份会与 AuditAction 漂移的清单，且已被明确否决）。
-- 改为从 platform_audit_log 里按 distinct 取「实际出现过的动作码」，因此需要这个候选值端点。
-- 同族先例：/servicemonitors/relabel-labels、/podmonitors/metric-names、/calico/form-options
-- —— 都是"候选值查询"而非业务数据，同样独立成一个窄端点。
--
-- 权限码：**不造新码**，复用 platform:audit:read（同族只读，与 /audit/list 同一数据面）。
-- 只绑 admin，理由同 V2026_10_13_1：审计是平台管理面，无租户对应页面。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启（fail-closed）。
--    （反过来先起构建是安全的：多出来的权限行匹配不到端点只是幽灵行 WARN，不拒启。）
-- ⚠️ 重放后需重新登录或等权限缓存 TTL（30s）。
-- 幂等：显式 id + ODKU；角色关联按【显式 id 列表】，不用 LIKE。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_audit_actions','API','/audit/actions','POST','platform:audit:read',
  '审计动作码候选值（distinct 自 platform_audit_log，供筛选 autocomplete）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_audit_actions','builtin_role_admin','perm_audit_actions')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
