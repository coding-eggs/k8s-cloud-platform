-- ============================================================================================
-- S4.1：PVC 扩容端点 POST /resource/pvcs/{name}/resize 的权限行
-- ============================================================================================
-- 为什么单开一个端点，而不是把扩容塞进已有的 PUT /resource/pvcs/{name}：
--   · PUT 的语义是「更新这个 PVC」，原来只同步标签；带上 storage 就触发一次**动存储后端、
--     异步、不可逆**的扩容 —— 同一个入口两种量级完全不同的操作，文档与实际开始对不上。
--   · 权限会耦合：`tenant:pvc:update` 从「能改标签」变成「也能扩容」，自定义角色里
--     想把"改标签"给出去的人会顺带把扩容给出去。
--   · 与工作负载那边同形：改镜像有独立端点（POST /{name}/image），暂停也是（POST /{name}/pause）。
--
-- 码**沿用** `tenant:pvc:update`（与 perm_res_wl_pause 复用 update 动词同一条理由：
-- 端点和权限行按「一次有意义的操作」分，权限码按「资源 + write 家族」共享）。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启。
--    （反过来先起构建是安全的：多出来的权限行是幽灵行 WARN，不拒启。）
-- ⚠️ 重放后需重新登录或等权限缓存 TTL（30s）：token 里已无权限码，闭包在请求期现算。
-- ⚠️ 本仓没有 Flyway，本文件是手工重放的；启动日志里永远不会出现 Migrating schema。
-- 幂等：显式 id + ODKU。角色关联按**显式 id** 写，不用 LIKE ——
--       `perm_res_%` 里那个 `_` 是 LIKE 的单字符通配符，历史上有踩过（V2026_10_08_5）。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符，否则整个文件重放失败。
-- ============================================================================================

-- ⚠️⚠️ 路径**不带 `/resource` 前缀** —— 那个前缀在 V2026_10_08_1__drop_api_resource_url_prefix.sql
--      里被统一去掉了，`resource` 列现在与 Spring MVC 的 pattern 一字不差。
--      别去照抄 V2026_09_29_1 的文本（那是去前缀**之前**的）：照抄会得到一行
--      `/resource/pvcs/{name}/resize`，与真实端点 `/pvcs/{name}/resize` 永远匹配不上，
--      于是 PermissionCrossCheckRunner 在**启动时**就把服务拦下来（2026-10-16 真撞到）。
--      拿不准就照着库里现成的行抄：`select resource from platform_permission where domain='API'`。
INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_res_pvc_resize', 'API','/pvcs/{name}/resize', 'POST','tenant:pvc:update',
  '扩容 PVC（在线扩容量，只能往大改；与 update 共享动词）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

-- ⚠️ 不含 tenant_member：它只拿读动词，扩容不在其中（与创建/删除同一档）
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_tadmin_res_pvc_resize', 'builtin_role_tenant_admin','perm_res_pvc_resize'),
 ('rp_admin_res_pvc_resize',  'builtin_role_admin',       'perm_res_pvc_resize')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
