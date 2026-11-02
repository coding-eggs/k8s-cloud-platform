-- ============================================================================================
-- 存储类：「设为/取消默认」端点的权限行（2026-11-02）
-- ============================================================================================
-- StorageClass 收归平台管理员（V2026_10_07_3），设/取消默认同样归管理员。复用现有三端点同码
-- platform:cluster:manage（list/get/yaml 都是它，admin 天然持有全部 platform:%）。
-- 单默认：设为默认时 k8-core 会清掉该集群其它已默认的 SC 的注解（storageclass.kubernetes.io/is-default-class）。
--
-- ⚠️ 路径**不带 /resource 前缀**：V2026_10_08_1 已把全部行的 /resource 段去掉，真实端点是
--    POST /storageclasses/{name}/default。写错前缀 = 该行永远匹配不上（同 V2026_10_16_3 的坑）。
-- ⚠️ 新权限行必须显式绑角色（不按 code 继承）：这里直接绑 builtin_role_admin。
-- ⚠️ 先重放本文件再起 platform-api 构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启（fail-closed）。
-- ⚠️ 重放后需重新登录：token 的权限闭包在签发时计算。
-- ⚠️ 本仓没有 Flyway，本文件是手工重放的；启动日志里永远不会出现 Migrating schema。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- 幂等：显式 id + ODKU。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_res_sc_setdefault', 'API', '/storageclasses/{name}/default', 'POST',
  'platform:cluster:manage', '设为/取消集群默认存储类（设时清掉其它已默认的 SC，单默认）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_res_sc_setdefault', 'builtin_role_admin', 'perm_res_sc_setdefault')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
