-- ============================================================================================
-- S0.2 凭据管理：建表 + 权限行（6 行）+ admin 角色关联
-- ============================================================================================
-- 背景：平台没有凭据库。本批新增 platform_credential，以及 platform-api 的 /credential/** 端点。
-- 设计全文见 docs/superpowers/specs/2026-10-13-credential-management-design.md。
--
-- 范围：git / registry 两类连接的 CRUD + 测试连接。不含投递（命名空间 Secret / imagePullSecret /
-- 构建 Pod 注入）—— 那些留给 S3.1 / S3.5。
--
-- 权限码（3 个）：
--   · platform:credential:read   —— 列表（脱敏）+ 测试连接
--   · platform:credential:write  —— 新建 / 更新 / 删除
--   · platform:page:credential   —— 前端页面可见性（Page 域）
-- 只给 admin：纯平台级凭据，无租户对应页面（spec 已定决策「凭据归属」）。
--
-- ⚠️ 测试连接与列表**共用 read**（有意）：本批三个码全绑 admin，独立成码不产生任何角色差异。
--    后果是「能看列表 = 能点测试」。设计与理由见 spec §6.2。
--
-- 有意不做：
--   · 不加 deleted_at —— 硬删除。凭据的删除语义是「让它消失」，软删除会把密文留在库里，
--     而如果删除的原因是「这个 token 泄露了」，软删除正好使它失效（spec 已定决策「删除语义」）。
--   · 不加 tenant_id / scope —— 纯平台级 + 全局可用，留个空列只会让人以为有个没实现的功能。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启（fail-closed）。
-- ⚠️ 重放后需重新登录或等权限缓存 TTL（30s）：token 里已无权限码，闭包在请求期现算。
-- ⚠️ 本仓没有 Flyway，本文件是手工重放的；启动日志里永远不会出现 Migrating schema。
-- 幂等：表用 IF NOT EXISTS；权限行与角色关联显式 id + ODKU；角色关联按【显式 id 列表】，不用 LIKE
-- （id 里的 `_` 是 LIKE 的单字符通配符，V2026_10_08_5 已踩过）。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符，否则整个文件重放失败。
-- ============================================================================================

CREATE TABLE IF NOT EXISTS `platform_credential` (
  `id`                 varchar(64)  NOT NULL COMMENT 'ULIDGenerator（16 位 hex），同库内其它表惯例',
  `name`               varchar(64)  NOT NULL COMMENT '显示名，全局唯一',
  `type`               varchar(32)  NOT NULL COMMENT 'GIT / REGISTRY（v1 仅此两类）',
  `endpoint`           varchar(512) NOT NULL COMMENT 'host 基址：GIT=https://gitlab.example.com，REGISTRY=harbor.example.com',
  `auth_type`          varchar(32)  NOT NULL COMMENT 'BASIC（v1 唯一实现）；加 SSH 时在此扩展，不动表',
  `secret_encrypted`   text                  DEFAULT NULL COMMENT 'AES 密文（含 v1: 前缀），明文是认证字段 JSON。v1 恒非空（BASIC 必填）；可空是为将来 NONE 型（匿名）留位',
  `secret_fingerprint` varchar(64)           DEFAULT NULL COMMENT 'SHA-256(认证字段 JSON) 前 16 hex；用于区分「改了凭据」与「只改了描述」',
  `description`        varchar(255)          DEFAULT NULL,
  `enabled`            tinyint      NOT NULL DEFAULT 1 COMMENT '0=停用：仍在列表里，但消费方不应选它',
  `last_verified_at`   datetime              DEFAULT NULL COMMENT '上次**测试通过**的时刻；失败不改它',
  `last_verify_error`  varchar(255)          DEFAULT NULL COMMENT '上次测试的失败原因；成功则置 NULL',
  `created_at`         datetime              DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_name`(`name` ASC) USING BTREE,
  INDEX `idx_type`(`type` ASC) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC
  COMMENT='平台级凭据（git / registry 连接）';

-- ===== 权限行：API 域 5 行 + Page 域 1 行 =====
INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_credential_list',   'API','/credential/list',   'POST','platform:credential:read',
  '凭据列表（脱敏，永不回显凭据内容）'),
 ('perm_credential_test',   'API','/credential/test',   'POST','platform:credential:read',
  '测试连接（与列表同码：能看即能测）'),
 ('perm_credential_create', 'API','/credential/create', 'POST','platform:credential:write',
  '新建凭据'),
 ('perm_credential_update', 'API','/credential/update', 'POST','platform:credential:write',
  '更新凭据（可轮换密钥）'),
 ('perm_credential_delete', 'API','/credential/delete', 'POST','platform:credential:write',
  '删除凭据（硬删除）'),
 ('perm_page_credential',   'Page','/credentials',      'view','platform:page:credential',
  '凭据管理页可见性')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 角色关联：admin 全给（显式 id 列表） =====
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_credential_list',   'builtin_role_admin','perm_credential_list'),
 ('rp_admin_credential_test',   'builtin_role_admin','perm_credential_test'),
 ('rp_admin_credential_create', 'builtin_role_admin','perm_credential_create'),
 ('rp_admin_credential_update', 'builtin_role_admin','perm_credential_update'),
 ('rp_admin_credential_delete', 'builtin_role_admin','perm_credential_delete'),
 ('rp_admin_credential_page',   'builtin_role_admin','perm_credential_page')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- tenant-admin / tenant-member 有意不给：纯平台级凭据，无租户对应页面。
