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

-- admin → 6 个 API 行（**必须**：admin 的闭包同样只来自关联表，没有任何硬编码兜底；
-- 漏了它，管理员能看见页面但列表/详情/YAML/建/改/删全是 403 —— 同 V2026_09_29_2 那一课的教训）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_admin_pr_', p.id), 'builtin_role_admin', p.id
FROM platform_permission p
WHERE p.id LIKE 'perm_res_pr_%'
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- admin → 全部 Page 行（admin 代管态要能进租户页面；与 V2026_10_07_1 同一口径）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_page_admin_pr_', p.id), 'builtin_role_admin', p.id
FROM platform_permission p
WHERE p.id IN ('perm_page_pr_list','perm_page_pr_edit')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- ⚠️ 自定义角色不会自动获得这两个页面 —— 升级后需在「角色与权限」页补勾（同 V2026_10_07_1 的说明）。

-- ============================================================================================
-- 告警事件表
-- ============================================================================================
-- 幂等键 = (cluster_id, fingerprint, starts_at)，见 spec §8.1：
--   · 只用 fingerprint 不行 —— 它是**标签集的哈希**，一条告警 fire→resolve→再 fire 时不变，
--     会把两次会话压成一行、丢掉历史。
--   · 加上 starts_at 就对了 —— Alertmanager 在同一次会话内保持 startsAt 不变
--     （resolve 时只补 endsAt），重新 fire 才产生新的 startsAt。
--   · 时间统一按毫秒（DATETIME(3)）归一，避免精度差异让唯一键失配、产生重复行。
--
-- 刻意**不加 tenant_id 列**：归属在查询时 join platform_tenant_namespace 决定 —— 不冗余就不会漂，
-- 而且一个命名空间可以分配给多个租户，定死 tenant_id 表达不了（spec 决策 6）。

CREATE TABLE IF NOT EXISTS `platform_alert_event` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增（非 ULID）—— 理由同 platform_audit_log：追加型日志表',
  `cluster_id`  VARCHAR(64)  NOT NULL,
  `namespace`   VARCHAR(253)          DEFAULT NULL COMMENT '来自 labels.namespace，规则作者自报；归属靠查询时 join 分配表',
  `alertname`   VARCHAR(253) NOT NULL,
  `severity`    VARCHAR(64)           DEFAULT NULL COMMENT 'labels.severity',
  `status`      VARCHAR(16)  NOT NULL COMMENT 'firing | resolved',
  `fingerprint` VARCHAR(64)  NOT NULL COMMENT 'Alertmanager 给，标签集的哈希',
  `labels`      JSON                  DEFAULT NULL,
  `annotations` JSON                  DEFAULT NULL,
  `starts_at`   DATETIME(3)  NOT NULL COMMENT '本次 firing 会话开始时刻',
  `ends_at`     DATETIME(3)           DEFAULT NULL COMMENT 'resolved 才有',
  `received_at` DATETIME(3)  NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_alert_episode` (`cluster_id`, `fingerprint`, `starts_at`),
  KEY `idx_alert_ns_time` (`cluster_id`, `namespace`, `starts_at`),
  KEY `idx_alert_time`    (`cluster_id`, `starts_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警事件（一次 firing 会话一行）';

-- ===== 告警（4 API + 1 Page）=====
-- ⚠️ /alert/webhook **不建权限行**：它不是用户身份调的（Alertmanager 打的），
--    进 ExemptPaths 并自校验共享密钥 —— 见 Task 16。
-- ⚠️ /alert/list 一个端点两行权限（先例：perm_alloc_list_self）：
--    platform:alert:read = 管理员看全部；tenant:alert:list = 租户看自己命名空间里的。
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_alert_list_platform','API','/alert/list','POST','platform:alert:read','告警列表（平台侧，全部命名空间）'),
 ('perm_alert_list_tenant','API','/alert/list','POST','tenant:alert:list','告警列表（租户侧，仅本租户命名空间）'),
 ('perm_alert_self_test','API','/alert/self-test','POST','platform:alert:test','告警链路自检（投一条测试告警给自己）'),
 ('perm_alert_delete','API','/alert/{id}','DELETE','platform:alert:read','删除告警记录（清测试数据）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- 页面码用**租户域**（已核对：builtin_role_admin 实际持有全部 tenant:page:* 页面码，
-- 故管理员与租户管理员都能看见同一页；PLATFORM 域页面码会被 RoleService.assertScopeMatches
-- 拒给 TENANT 角色，那条路走不通 —— 见 Task 13 Step 2 的定案）。
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_page_alert','Page','/alerts','VIEW','tenant:page:alert','页面·告警列表')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 角色关联 =====
-- admin：平台侧告警行的三个 + 页面
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_admin_alert_', p.id), 'builtin_role_admin', p.id
FROM platform_permission p
WHERE p.id IN ('perm_alert_list_platform','perm_alert_self_test','perm_alert_delete','perm_page_alert')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- tenant-admin：租户侧列表 + 页面（能看到菜单、按 token 收窄到自己的命名空间）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_tadmin_alert_', p.id), 'builtin_role_tenant_admin', p.id
FROM platform_permission p
WHERE p.id IN ('perm_alert_list_tenant','perm_page_alert')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- ⚠️ 自定义角色不会自动获得这个页面 —— 升级后需在「角色与权限」页补勾。
