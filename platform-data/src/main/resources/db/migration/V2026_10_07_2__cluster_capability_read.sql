-- ============================================================================================
-- /cluster/capability/get 补租户只读行（ANY-of）
-- ============================================================================================
-- 背景：集群能力快照（group→versions，运行时 discovery 结果）是前端的能力门禁数据源：
-- HPA 编辑器据此判 autoscaling v2、工作负载编辑器判 projectcalico.org 是否可用等。它此前只有
-- platform:cluster:manage 一个 code，于是<b>租户用户一开 HPA 页就必然 403 并弹错</b>
-- ——这是「前端主动越权」清单里的一项（见 docs/development/frontend-permission-conventions.md）。
--
-- 安全评估：该端点返回的是"这个集群装了哪些 API group/version"，属集群基础设施的公开能力面，
-- 不含任何租户数据、不含凭据、不含对象清单。授予租户只读不扩大数据可达范围，故补 ANY-of 行。
-- 同 URL 两个 code → 依赖 V2026_09_24_2 已放宽的 uk_domain_resource_action（同 URL 多 code 是 ANY-of 模型）。
--
-- 刻意<b>不</b>放松的相邻端点：/cluster/capability/refresh（会真的跑一次 discovery 并持久化快照，
-- 属写操作）继续独占 platform:cluster:manage；前端「刷新能力」按钮仅平台管理员可见。
--
-- 幂等：显式 id + ON DUPLICATE KEY UPDATE，同既有范式。
-- ============================================================================================

INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_cluster_cap_get_t','API','/cluster/capability/get','POST','tenant:cluster:capability:view',
  '读取集群 API 能力快照（只读；租户侧能力门禁用）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- 绑定两个内置租户角色（admin 已持 platform:cluster:manage，无需补）
INSERT INTO platform_role_permission (id,role_id,permission_id) VALUES
 ('rp_capview_tadmin','builtin_role_tenant_admin','perm_cluster_cap_get_t'),
 ('rp_capview_tmember','builtin_role_tenant_member','perm_cluster_cap_get_t')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
