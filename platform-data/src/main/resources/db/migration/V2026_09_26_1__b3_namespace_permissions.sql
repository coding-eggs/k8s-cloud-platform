-- Task B3（命名空间管理增强）：为 §5.3 新增的 10 个 /namespace/** 端点补权限行。
-- 背景：PermissionCrossCheckRunner 启动期交叉校验要求「每个真实端点都有权限行或在豁免清单」，
-- 否则 platform-api 拒绝启动。B3 在 V2026_09_24_2 seed 之后新增了 get/yaml/create/update +
-- quota/limitrange 的 get/upsert/delete,这些端点无对应行 → 合并后必启动失败。本迁移补齐。
--
-- 复用既有 code（不造新码、不需新增角色绑定）：
--   读端点 → platform:allocation:list   （admin 已经 perm_ns_list 持有该 code）
--   写端点 → platform:allocation:manage （admin 已经 perm_ns_delete 持有该 code）
-- PermissionAuthorizationManager 按 code 判权（用户持 PERM:<code> 即过），
-- 故新行只要 code 落在 admin 已绑定的集合内即自动生效,无需再跑 role_permission 绑定 SELECT。
-- id 前缀 perm_ns_b3_ 与 V2026_09_24_2 的 perm_ns_list/perm_ns_delete 区分,避免 PRIMARY KEY 撞行。
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_ns_b3_get',        'API','/namespace/get',              'POST','platform:allocation:list',  '命名空间详情（含描述/标签）'),
 ('perm_ns_b3_yaml',       'API','/namespace/yaml',             'POST','platform:allocation:list',  '命名空间 YAML 只读'),
 ('perm_ns_b3_create',     'API','/namespace/create',           'POST','platform:allocation:manage','创建命名空间'),
 ('perm_ns_b3_update',     'API','/namespace/update',           'POST','platform:allocation:manage','编辑命名空间（描述/标签）'),
 ('perm_ns_b3_quota_get',  'API','/namespace/quota/get',        'POST','platform:allocation:list',  '读命名空间配额（含用量）'),
 ('perm_ns_b3_quota_upsert','API','/namespace/quota/upsert',    'POST','platform:allocation:manage','创建/更新命名空间配额'),
 ('perm_ns_b3_quota_delete','API','/namespace/quota/delete',    'POST','platform:allocation:manage','删除命名空间配额'),
 ('perm_ns_b3_lr_get',     'API','/namespace/limitrange/get',   'POST','platform:allocation:list',  '读命名空间限制范围'),
 ('perm_ns_b3_lr_upsert',  'API','/namespace/limitrange/upsert','POST','platform:allocation:manage','创建/更新命名空间限制范围'),
 ('perm_ns_b3_lr_delete',  'API','/namespace/limitrange/delete','POST','platform:allocation:manage','删除命名空间限制范围')
ON DUPLICATE KEY UPDATE code=VALUES(code), description=VALUES(description);
