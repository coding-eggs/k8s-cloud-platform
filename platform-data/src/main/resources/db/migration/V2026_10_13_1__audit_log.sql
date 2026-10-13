-- ============================================================================================
-- S0.1 审计日志：建表 + 权限行（3 行）+ admin 角色关联
-- ============================================================================================
-- 背景：平台对写操作零留痕。本批新增 platform_audit_log，以及 platform-api 的 /audit/** 两端点。
-- 设计全文见 docs/superpowers/specs/2026-10-13-audit-log-design.md。
--
-- 范围：写操作 + 403 被拒请求。不含变更前后值、不含读操作、不存请求体全文。
--
-- 权限码：
--   · platform:audit:read   —— 审计列表（写操作 + 403 被拒记录，含筛选与游标分页）
--   · platform:audit:purge  —— 按时间范围删除
--   · platform:page:audit   —— 前端页面可见性（Page 域）
-- 只给 admin：审计是平台管理面，无租户对应页面（spec「可见范围 = 只有平台管理员」）。
--
-- 有意不做 POST /audit/get：列表本就返回全部 18 个字段，详情是行展开，
-- 单条详情端点纯冗余（spec §6 的偏离记录）。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启（fail-closed）。
-- ⚠️ 重放后需重新登录或等权限缓存 TTL（30s）：token 里已无权限码，闭包在请求期现算。
-- 幂等：表用 IF NOT EXISTS；权限行显式 id + ODKU；角色关联按【显式 id 列表】，不用 LIKE
-- （id 里的 `_` 是 LIKE 的单字符通配符，V2026_10_08_5 已踩过）。
-- ============================================================================================

CREATE TABLE IF NOT EXISTS `platform_audit_log` (
  `id`                 bigint       NOT NULL AUTO_INCREMENT,
  `occurred_at`        datetime(3)  NOT NULL COMMENT '发生时刻（毫秒）',
  `actor_username`     varchar(64)  NOT NULL COMMENT '操作者账号；取不到身份时记字面量 unknown（不记 NULL）',
  `actor_display_name` varchar(64)           DEFAULT NULL COMMENT '冗余存：账号改名/删除后记录仍可读',
  `tenant_id`          varchar(26)           DEFAULT NULL COMMENT '被操作的租户（代操作时是目标租户；平台侧无租户操作为 NULL）',
  `cluster_id`         varchar(26)           DEFAULT NULL,
  `namespace`          varchar(63)           DEFAULT NULL,
  `action`             varchar(64)           DEFAULT NULL COMMENT '写操作=动作码 <resource>:<verb>；DENIED=被拒的权限码。语义随 result 变',
  `target_name`        varchar(253)          DEFAULT NULL COMMENT '目标对象名；DENIED 为空（拒绝发生在方法调用前）',
  `result`             varchar(16)  NOT NULL COMMENT 'SUCCESS / FAILED / DENIED',
  `error_code`         varchar(32)           DEFAULT NULL COMMENT 'FAILED 的业务码',
  `error_msg`          varchar(512)          DEFAULT NULL COMMENT 'FAILED 的业务 msg',
  `http_method`        varchar(8)            DEFAULT NULL COMMENT '请求方法 —— DENIED 与写操作都有；非 HTTP 线程为空',
  `request_path`       varchar(512)          DEFAULT NULL COMMENT '请求路径 —— 同上；写操作记录下来是 PUT /workloads/web-a 这样，自描述',
  `client_ip`          varchar(45)           DEFAULT NULL COMMENT '兼容 IPv6',
  `user_agent`         varchar(256)          DEFAULT NULL COMMENT '安全排查用',
  `duration_ms`        int                   DEFAULT NULL COMMENT '耗时；DENIED 为 NULL',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_occurred`   (`occurred_at`, `id`) USING BTREE,
  KEY `idx_actor`      (`actor_username`, `occurred_at`) USING BTREE,
  KEY `idx_cluster_ns` (`cluster_id`, `namespace`, `occurred_at`) USING BTREE,
  KEY `idx_result`     (`result`, `occurred_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC
  COMMENT='平台审计日志（写操作 + 403 被拒请求）';

-- ===== 权限行：API 域 2 行 + Page 域 1 行 =====
INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_audit_list','API','/audit/list','POST','platform:audit:read',
  '审计日志列表（写操作 + 403 被拒；筛选与游标分页）'),
 ('perm_audit_purge','API','/audit/purge','POST','platform:audit:purge',
  '按时间范围删除审计日志'),
 ('perm_page_audit','Page','/audit','view','platform:page:audit',
  '审计日志页可见性')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 角色关联：admin 全给（显式 id 列表） =====
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_audit_list','builtin_role_admin','perm_audit_list'),
 ('rp_admin_audit_purge','builtin_role_admin','perm_audit_purge'),
 ('rp_admin_page_audit','builtin_role_admin','perm_page_audit')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- tenant-admin / tenant-member 有意不给：审计是平台管理面。
