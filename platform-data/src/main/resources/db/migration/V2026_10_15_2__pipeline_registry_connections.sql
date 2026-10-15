-- ============================================================================================
-- S3.5 第 ① 片补：镜像节点的连接候选（1 行 API 权限 + 3 行角色关联）
-- ============================================================================================
-- 背景：V2026_10_15_1 只给了 git 连接的窄投影（/pipeline/git-connections）。
-- 但「构建并推送镜像」节点**必须**引用一条被授权的 registry 连接 ——
-- 目标镜像地址由那条连接的 endpoint 拼出来（ImagePushNode 解析不出就拒渲染）。
-- 没有这个候选端点，租户只能靠手打连接 id，而他们看不到凭据页（要 platform:credential:read）。
--
-- ⚠️ 复用同一个 code `tenant:pipeline:read`：它和 git-connections 是同一件事
--    （「列出可在流水线里使用的连接」）的两个入口，与 S0.2 把「测试连接」并进 read 同一条判断，
--    也与 S3.1 把三个只读端点归进 tenant:registry:catalog 同形。
--
-- ⚠️ permission_id 必须与 platform_permission.id 逐字一致。本仓不加外键，写错了只会留一条
--    悬空关联（角色没有它 → 下拉恒空、日志一片安静）。重放后必须做 join 对账。
-- ⚠️ 部署顺序：先重放本文件再起新构建（无行的新端点会被 PermissionCrossCheckRunner 拒启）。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_pipeline_regconns', 'API','/pipeline/registry-connections','POST','tenant:pipeline:read',
  '可在流水线里使用的镜像仓库连接（窄投影：只回 id/name/endpoint）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_tadmin_pl_regconns', 'builtin_role_tenant_admin','perm_pipeline_regconns'),
 ('rp_tmember_pl_regconns','builtin_role_tenant_member','perm_pipeline_regconns'),
 ('rp_admin_pl_regconns',  'builtin_role_admin','perm_pipeline_regconns')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
