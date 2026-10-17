-- ============================================================================================
-- S2.2 告警：权限行 + 告警事件表
-- ============================================================================================
-- 设计全文见 docs/superpowers/specs/2026-10-17-alerting-design.md。
--
-- ⚠️ 本仓**没有 Flyway**：本文件是**手工重放**的。部署顺序 = 先重放本文件，再起新构建，
--    否则 PermissionCrossCheckRunner 会因为端点没有权限行而拒启（fail-closed）。
--
-- ⚠️ 文件版本号用 10_17_3：10_17_1（租户 RBAC 模板补 CRD apiGroup）与
--    10_17_2（网格拓扑权限）已被同批的其它改动占用。本仓无 Flyway，版本号只用于人读，
--    但重放顺序仍按文件名排序。
--
-- ⚠️ 路径**不带 `/resource` 前缀**：那是 V2026_10_08_1 之前的旧写法。现存行形如
--    `/servicemonitors/list`、`/pvcs/{name}`。照抄 V2026_09_29_1 的文本会让交叉校验拒启。
--    API 域无前缀；Page 域用 `/resources/...`。
--
-- 幂等：显式 id 命中 PRIMARY KEY + ON DUPLICATE KEY UPDATE，同 V2026_09_27_1 范式。

-- ===== prometheusrules（6；本 CRD 没有 discovery 候选端点，故不需要豁免行）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_res_pr_list','API','/prometheusrules/list','POST','tenant:prometheusrule:list','列出 PrometheusRule'),
 ('perm_res_pr_get','API','/prometheusrules/{name}','GET','tenant:prometheusrule:get','查看 PrometheusRule 详情'),
 ('perm_res_pr_yaml','API','/prometheusrules/{name}/yaml','GET','tenant:prometheusrule:yaml','查看 PrometheusRule YAML'),
 ('perm_res_pr_create','API','/prometheusrules','POST','tenant:prometheusrule:create','创建 PrometheusRule'),
 ('perm_res_pr_update','API','/prometheusrules/{name}','PUT','tenant:prometheusrule:update','更新 PrometheusRule'),
 ('perm_res_pr_delete','API','/prometheusrules/{name}','DELETE','tenant:prometheusrule:delete','删除 PrometheusRule')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 页面（2）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_page_pr_list','Page','/resources/prometheusrules','VIEW','tenant:page:prometheusrule.list','页面·PrometheusRule 列表'),
 ('perm_page_pr_edit','Page','/resources/prometheusrules/editor','VIEW','tenant:page:prometheusrule.edit','页面·PrometheusRule 编辑')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 内置角色关联（新 code 不会自动挂角色，漏挂 = 部署后全 403）=====
-- tenant-admin → 全部 prometheusrule 行（写动词 + 两个页面）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_tadmin_pr_', p.id), 'builtin_role_tenant_admin', p.id
FROM platform_permission p
WHERE p.id LIKE 'perm_res_pr_%' OR p.id LIKE 'perm_page_pr_%'
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- tenant-member → 仅读动词（list/get/yaml）+ 列表页（不含编辑器）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_tmember_pr_', p.id), 'builtin_role_tenant_member', p.id
FROM platform_permission p
WHERE (p.id LIKE 'perm_res_pr_%'
       AND (p.code LIKE '%:list' OR p.code LIKE '%:get' OR p.code LIKE '%:yaml'))
   OR p.id = 'perm_page_pr_list'
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- admin → 全部 Page 行（admin 代管态要能进租户页面；与 V2026_10_07_1 同一口径）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_page_admin_pr_', p.id), 'builtin_role_admin', p.id
FROM platform_permission p
WHERE p.id IN ('perm_page_pr_list','perm_page_pr_edit')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- ⚠️ 自定义角色不会自动获得这两个页面 —— 升级后需在「角色与权限」页补勾（同 V2026_10_07_1 的说明）。
