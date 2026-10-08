-- ============================================================================================
-- /configmaps/list-all 与 /pods/list-all 权限行（跨命名空间列举，平台侧）
-- ============================================================================================
-- 背景：k8s-server 侧的命名空间级资源只有 /list（namespace 必填，且要过分配表三元组），
-- 跨命名空间的读只有按节点的 /nodes/{name}/pods —— 拿不到"集群里现在有哪些 Pod/ConfigMap"。
-- 本批在 k8s-server 基类新增 /list-all（该端点对**所有**命名空间级资源都存在；支不支持由各自 ops 的
-- listAll 是否实现决定，暴不暴露由本表决定），并给 ConfigMap / Pod 两个资源实现了它；
-- 普通租户调用方到不了该端点（平台侧身份校验会拒）。
--
-- 为什么独立成码，而不是复用 platform:cluster:manage：
--   跨命名空间列举会回带**所有**命名空间的对象（Pod 的镜像/环境、ConfigMap 的全部键值），
--   这是一次平台侧的数据面扩张，应当能单独授予/收回 —— 例如只给审计角色 Pod 全局只读、
--   不给 ConfigMap（它常含配置里的凭据类字段）。
--
-- 为什么 api 侧必须是独立**路径**（而不是让 /list 的 namespace 可选）：
--   权限表按 (方法, 路径) 定码，同一路径同一方法只能是一组 code，无法给两个动作配不同授权。
--
-- ⚠️ fail-closed 顺序：PermissionCrossCheckRunner 在启动期核对"每个活端点都有权限行或豁免"，
--    无行即拒绝启动。故本文件必须<b>先于</b>新构建重放。幂等：显式 id + ON DUPLICATE KEY UPDATE。
-- ============================================================================================

INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_cm_list_all','API','/configmaps/list-all','POST','platform:configmap:list-all',
  '跨全部命名空间列出 ConfigMap（平台侧；含不属于任何租户的命名空间）'),
 ('perm_pod_list_all','API','/pods/list-all','POST','platform:pod:list-all',
  '跨全部命名空间列出 Pod（平台侧；含不属于任何租户的命名空间）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- 绑定内置超管（V2026_09_24_2 的「admin→全部 platform:%」是一次性快照 SELECT，新 code 不会自动进 admin）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_admin_allns_', p.id), 'builtin_role_admin', p.id
FROM platform_permission p
WHERE p.code IN ('platform:configmap:list-all','platform:pod:list-all') AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
