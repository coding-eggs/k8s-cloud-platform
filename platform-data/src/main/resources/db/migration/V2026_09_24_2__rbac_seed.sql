-- Task 4: 权限目录 seed（权限点 + 内置角色 + 关联）。运行时只读，Task 6/9/11 依赖本文件的 API 行。
-- 全部 INSERT ... ON DUPLICATE KEY UPDATE：幂等靠每行显式唯一 id（命中 PRIMARY KEY），非二级唯一索引。

-- ===== 前置：放宽 platform_permission 两处唯一索引 =====
-- spec §3.3 / §5.4 与 Task 9 PermissionRegistry 的 ANY-of 模型，天然要求：
--   (a) 同一 code 覆盖多个 URL（如 platform:user:manage 管 /user/create…/user/get 共 7 行）——与 UNIQUE(code) 冲突；
--   (b) 同一 (domain,resource,action) 挂多个 code（/tenant/member/add 同时是 tenant:member:manage 与 platform:member:manage）——与 UNIQUE(uk_domain_resource_action) 冲突。
-- 保留这两个唯一索引会让下面的 seed 在 ON DUPLICATE KEY UPDATE 下静默塌行、丢失端点覆盖，Task 11 交叉校验必失败。
-- 该表为本特性新建、dump 内无既有记录（见 k8s_cloud_platform.sql），删索引安全。降级为普通索引以保留查询性能（selectByCode / Registry 扫描）。
ALTER TABLE platform_permission DROP INDEX `code`;
ALTER TABLE platform_permission DROP INDEX `uk_domain_resource_action`;
ALTER TABLE platform_permission ADD INDEX `idx_code`(`code`);
ALTER TABLE platform_permission ADD INDEX `idx_domain_resource_action`(`domain`,`resource`,`action`);

-- ===== 权限点（API 域，resource=URL 模式，action=HTTP 方法，本平台全 POST）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_tenant_provision','API','/tenant/create','POST','platform:tenant:provision','建租户'),
 ('perm_tenant_provision_upd','API','/tenant/update','POST','platform:tenant:update','改租户'),
 ('perm_tenant_delete','API','/tenant/delete','POST','platform:tenant:delete','删租户'),
 ('perm_tenant_provision_sa','API','/tenant/provision','POST','platform:tenant:provision:sa','重开通租户 SA'),
 ('perm_tenant_read','API','/tenant/list','POST','platform:tenant:read','租户列表'),
 ('perm_tenant_get','API','/tenant/get','POST','platform:tenant:get','租户详情'),
 ('perm_alloc_manage','API','/tenant/namespace/allocate','POST','platform:allocation:manage','分配命名空间'),
 ('perm_alloc_manage_del','API','/tenant/namespace/deallocate','POST','platform:allocation:manage','取消分配'),
 ('perm_alloc_list','API','/tenant/namespace/list','POST','platform:allocation:list','分配列表'),
 ('perm_cluster_manage','API','/cluster/create','POST','platform:cluster:manage','建集群'),
 ('perm_user_manage','API','/user/create','POST','platform:user:manage','建用户'),
 ('perm_user_manage2','API','/user/update','POST','platform:user:manage','改用户'),
 ('perm_user_manage3','API','/user/delete','POST','platform:user:manage','删用户'),
 ('perm_user_manage4','API','/user/platformRole/grant','POST','platform:user:manage','授予平台角色'),
 ('perm_user_manage5','API','/user/platformRole/revoke','POST','platform:user:manage','回收平台角色'),
 ('perm_user_list','API','/user/list','POST','platform:user:manage','用户列表'),
 ('perm_user_get','API','/user/get','POST','platform:user:manage','用户详情'),
 ('perm_role_manage','API','/role/create','POST','platform:role:manage','建角色'),
 ('perm_role_manage2','API','/role/update','POST','platform:role:manage','改角色'),
 ('perm_role_manage3','API','/role/delete','POST','platform:role:manage','删角色'),
 ('perm_role_manage4','API','/role/permission/save','POST','platform:role:manage','保存角色权限'),
 ('perm_role_read','API','/role/list','POST','platform:role:read','角色列表'),
 ('perm_perm_read','API','/permission/list','POST','platform:role:read','权限目录'),
 ('perm_member_manage_self','API','/tenant/member/add','POST','tenant:member:manage','加入成员(自管/代管 ANY-of)'),
 ('perm_member_manage_del','API','/tenant/member/remove','POST','tenant:member:manage','移除成员'),
 ('perm_member_role_grant','API','/tenant/member/role/grant','POST','tenant:member:manage','授予租户角色'),
 ('perm_member_role_revoke','API','/tenant/member/role/revoke','POST','tenant:member:manage','回收租户角色'),
 ('perm_member_delegate','API','/tenant/member/add','POST','platform:member:manage','代管成员加入'),
 ('perm_member_delegate2','API','/tenant/member/remove','POST','platform:member:manage','代管成员移除'),
 ('perm_member_delegate3','API','/tenant/member/role/grant','POST','platform:member:manage','代管授予角色'),
 ('perm_member_delegate4','API','/tenant/member/role/revoke','POST','platform:member:manage','代管回收角色'),
 ('perm_member_list','API','/tenant/member/list','POST','tenant:overview:view','看本租户成员')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== Step 2 补全：存量管理端点（ClusterController / NamespaceController / RbacTemplateController）=====
-- 这些 controller 早于本设计存在，Task 11 交叉校验要求每个非豁免端点都有权限行。归族见 report。
-- /cluster/** → platform:cluster:manage（spec §3.3：集群管理整族）
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_cluster_list','API','/cluster/list','POST','platform:cluster:manage','集群列表'),
 ('perm_cluster_get','API','/cluster/get','POST','platform:cluster:manage','集群详情'),
 ('perm_cluster_update','API','/cluster/update','POST','platform:cluster:manage','改集群'),
 ('perm_cluster_toggle','API','/cluster/toggleEnabled','POST','platform:cluster:manage','启用/禁用集群'),
 ('perm_cluster_delete','API','/cluster/delete','POST','platform:cluster:manage','删集群'),
 ('perm_cluster_provision','API','/cluster/provision','POST','platform:cluster:manage','重新开通集群'),
 ('perm_cluster_cap_refresh','API','/cluster/capability/refresh','POST','platform:cluster:manage','刷新集群 API 能力'),
 ('perm_cluster_cap_get','API','/cluster/capability/get','POST','platform:cluster:manage','读取集群 API 能力')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- /namespace/*：删除属命名空间生命周期 → platform:allocation:manage；
-- 列表当前返回"全集群 ns + 已分配租户"合并视图（未按租户过滤），故先只给 platform:allocation:list；
-- 待服务层加租户过滤后，再仿 member 模式追加 tenant:overview:view 的 ANY-of 行（见 report 关注点）。
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_ns_list','API','/namespace/list','POST','platform:allocation:list','集群命名空间合并视图'),
 ('perm_ns_delete','API','/namespace/delete','POST','platform:allocation:manage','删除平台管理且未分配的命名空间')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- /rbacTemplate/** → platform:template:manage（spec §Step2 新起 RBAC 模板族；list/get 同族，管理页内浏览）
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_tpl_create','API','/rbacTemplate/create','POST','platform:template:manage','建模板'),
 ('perm_tpl_list','API','/rbacTemplate/list','POST','platform:template:manage','模板列表'),
 ('perm_tpl_get','API','/rbacTemplate/get','POST','platform:template:manage','模板详情'),
 ('perm_tpl_update','API','/rbacTemplate/update','POST','platform:template:manage','改模板'),
 ('perm_tpl_delete','API','/rbacTemplate/delete','POST','platform:template:manage','删模板')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- Task 15 计划端点补漏：RoleController 有 /role/permission/list（回显某角色已勾权限码），brief 目录未列；
-- Task 11 上线后须有行否则启动失败。归 platform:role:read（与 /permission/list 目录读取同族）。
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_role_perm_list','API','/role/permission/list','POST','platform:role:read','查看角色已授予权限码')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- Task 20 补漏：/tenant/member/list 原只有 tenant:overview:view 单行（自管轨），
-- admin 代管轨（租户管理页「成员与角色」tab 查看任意租户成员）会 403 ——
-- 与 add/remove/grant/revoke 各自的 platform:member:manage 行不对称（Task 16 report 即按 ANY-of 声明）。
-- 补 ANY-of 行对齐；迁移为人工/重放应用（ON DUPLICATE KEY by 显式 id，幂等）。
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_member_list_delegate','API','/tenant/member/list','POST','platform:member:manage','代管查看租户成员')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- Task 16 forward：/tenant/namespace/list 对租户 ANY-of tenant:overview:view（限本租户；服务层用 token 租户覆盖入参）
-- （同 plan 指定文本；与 perm_alloc_list 同 URL 不同 code → 依赖上方已放宽的 uk_domain_resource_action）
INSERT INTO platform_permission (id,domain,resource,action,code,description)
VALUES ('perm_alloc_list_self','API','/tenant/namespace/list','POST','tenant:overview:view','看本租户分配')
ON DUPLICATE KEY UPDATE description=VALUES(description);

-- ===== 内置角色（admin 已存在于 Task 1，补 scope/built_in；两个租户族角色新建）=====
INSERT INTO platform_role (id,name,code,description,scope,built_in,status) VALUES
 ('builtin_role_tenant_admin','租户管理员','tenant-admin','租户内管成员与看边界','TENANT',1,1),
 ('builtin_role_tenant_member','租户成员','tenant-member','租户内只读边界','TENANT',1,1)
ON DUPLICATE KEY UPDATE description=VALUES(description), scope=VALUES(scope), built_in=VALUES(built_in);

-- ===== admin 平台角色 → 全部 PLATFORM 权限 =====
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_admin_', p.id), 'builtin_role_admin', p.id
FROM platform_permission p
WHERE p.domain='API' AND p.code LIKE 'platform:%' AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- ===== tenant-admin → 全部 tenant:* 权限 =====
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_tadmin_', p.id), 'builtin_role_tenant_admin', p.id
FROM platform_permission p
WHERE p.domain='API' AND p.code LIKE 'tenant:%' AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- ===== tenant-member → tenant:overview:view =====
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_tmember_', p.id), 'builtin_role_tenant_member', p.id
FROM platform_permission p
WHERE p.domain='API' AND p.code='tenant:overview:view' AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
