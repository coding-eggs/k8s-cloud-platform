-- ============================================================================================
-- 对象存储页面在**平台视图**也能进（先例同链路追踪）
--
-- 为什么：/object-storage 是集群级页面（前端路由没有 context，自带集群选择器），能力面本来
-- 就是平台级的 —— ObjectStorageView 的管理员分支能看全部租户的桶、代建桶、设每租户桶数上限、
-- 发放/吊销凭据。但此前只有 tenant:page:object-storage 一个页面码，而 nav 的 faceOf() 只看
-- 页面码前缀判面，于是这一项只出现在租户视图 —— 管理员要管对象存储，必须先切进某个租户。
--
-- 做法：再建一条 platform 族页面码；前端路由的 requiresPerm 同时列两族（ANY-of），
-- faceOf() 见到两族即判 both，两个视图都出现。租户那条保持不变。
--
-- ⚠️ 本仓没有 Flyway，手工重放。
-- ⚠️ Page 行不参与启动期的端点交叉校验（PermissionCrossCheck 只扫 API 端点），
--    所以漏放本文件不会挡住启动 —— 但 platform:page:object-storage 这个码在库里就不存在，
--    平台视图的入口只能靠 tenant 那半条撑着，也没法单独收回。
-- 幂等：显式 id + ODKU。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_page_object_storage_platform','Page','/object-storage','VIEW','platform:page:object-storage','页面·对象存储（平台视图）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

-- 只绑内置 admin：这是平台视图的入口，租户族角色不应拿到 platform: 族的码
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_page_object_storage_platform','builtin_role_admin','perm_page_object_storage_platform')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
