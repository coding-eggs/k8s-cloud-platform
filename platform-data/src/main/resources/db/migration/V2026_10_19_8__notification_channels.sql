-- ============================================================================================
-- S2.2.4 通知方式：渠道 / 规则绑定 / 投递记录 + 权限行
-- ============================================================================================
-- 设计见 docs/superpowers/specs/2026-10-19-notification-design.md。
--
-- ⚠️ 本仓**没有 Flyway**：本文件是**手工重放**的。部署顺序 = 先重放本文件，再起新构建，
--    否则 PermissionCrossCheckRunner 会因为端点没有权限行而拒启（fail-closed）。
--
-- ⚠️ 文件版本号用 10_19_8：10_19_1~7 已被同批的其它改动占用。本仓无 Flyway，版本号只用于人读，
--    但重放顺序仍按文件名排序。
--
-- ⚠️ 路径**不带 `/resource` 前缀**。API 域无前缀；Page 域用页面自身路径。
--
-- 幂等：显式 id 命中 PRIMARY KEY + ON DUPLICATE KEY UPDATE，同 V2026_09_27_1 范式。

CREATE TABLE IF NOT EXISTS `platform_notification_channel` (
  `id`          VARCHAR(64)  NOT NULL,
  `code`        VARCHAR(64)  NOT NULL COMMENT '写进 label 的值；K8s label 合法；全局唯一',
  `name`        VARCHAR(128) NOT NULL,
  `type`        VARCHAR(32)  NOT NULL COMMENT 'EMAIL（本轮唯一）',
  `config`      JSON                  DEFAULT NULL COMMENT '按类型的连接配置；敏感字段加密后存字符串',
  `tenant_id`   VARCHAR(64)           DEFAULT NULL COMMENT 'NULL=全局；非空=该租户专用',
  `is_default`  TINYINT      NOT NULL DEFAULT 0,
  `enabled`     TINYINT      NOT NULL DEFAULT 1,
  `created_at`  DATETIME(3)  NOT NULL,
  `updated_at`  DATETIME(3)  NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notify_channel_code` (`code`),
  KEY `idx_notify_channel_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知方式（渠道）';

CREATE TABLE IF NOT EXISTS `platform_rule_notify` (
  `id`           VARCHAR(64)  NOT NULL,
  `cluster_id`   VARCHAR(64)  NOT NULL,
  `namespace`    VARCHAR(253) NOT NULL,
  `rule_name`    VARCHAR(253) NOT NULL,
  `channel_code` VARCHAR(64)  NOT NULL,
  `user_ids`     JSON                  DEFAULT NULL COMMENT '收件人平台用户 id 数组',
  `created_at`   DATETIME(3)  NOT NULL,
  `updated_at`   DATETIME(3)  NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule_notify` (`cluster_id`, `namespace`, `rule_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则 → 通知方式/收件人 绑定（CR 级）';

CREATE TABLE IF NOT EXISTS `platform_alert_delivery` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '追加型日志表，同 platform_audit_log',
  `event_id`     BIGINT       NOT NULL,
  `channel_code` VARCHAR(64)  NOT NULL,
  `alert_status` VARCHAR(16)  NOT NULL COMMENT '投递时的告警状态 firing/resolved',
  `status`       VARCHAR(16)  NOT NULL COMMENT 'PENDING/SENT/FAILED',
  `attempts`     INT          NOT NULL DEFAULT 0,
  `last_error`   VARCHAR(1024)         DEFAULT NULL,
  `created_at`   DATETIME(3)  NOT NULL,
  `sent_at`      DATETIME(3)           DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_delivery_once` (`event_id`, `channel_code`, `alert_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警投递记录';

-- ===== 权限行（5 API + 1 Page + 2 候选）=====
-- ⚠️ 「通知方式」页是**平台管理员专属**：用 platform 域 page 码，租户角色不绑（同 perm_page_cluster 范式）。
-- ⚠️ 两个候选端点用 tenant: 码并**同时绑 admin**（平台+租户共用能力必须用 tenant: 码）。
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_page_notifychannel','Page','/notification-channels','VIEW','platform:page:notifychannel','页面·通知方式（仅管理员）'),
 ('perm_notifychannel_list','API','/notification-channels/list','POST','platform:notifychannel:list','通知方式列表'),
 ('perm_notifychannel_create','API','/notification-channels','POST','platform:notifychannel:create','新建通知方式'),
 ('perm_notifychannel_update','API','/notification-channels/{code}','PUT','platform:notifychannel:update','修改通知方式'),
 ('perm_notifychannel_delete','API','/notification-channels/{code}','DELETE','platform:notifychannel:delete','删除通知方式'),
 ('perm_notifychannel_options','API','/notification-channels/options','POST','tenant:notifychannel:list','通知方式候选（规则编辑页）'),
 ('perm_notificationuser_list','API','/notification-users/list','POST','tenant:notificationuser:list','本租户用户候选（规则编辑页）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 角色关联（新 code 不会自动挂角色，漏挂 = 部署后 403）=====
-- admin：全部 7 行
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_admin_notify_', p.id), 'builtin_role_admin', p.id
FROM platform_permission p
WHERE p.id IN ('perm_page_notifychannel','perm_notifychannel_list','perm_notifychannel_create',
               'perm_notifychannel_update','perm_notifychannel_delete','perm_notifychannel_options',
               'perm_notificationuser_list')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- tenant-admin：只有两个候选（规则编辑页要用）；「通知方式」页面本身不给
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_tadmin_notify_', p.id), 'builtin_role_tenant_admin', p.id
FROM platform_permission p
WHERE p.id IN ('perm_notifychannel_options','perm_notificationuser_list')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- ⚠️ 自定义角色不会自动获得这些页面 —— 升级后需在「角色与权限」页补勾。
