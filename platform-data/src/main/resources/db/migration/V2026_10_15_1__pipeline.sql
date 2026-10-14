-- ============================================================================================
-- S3.5 第 ① 片：CI（Tekton）—— 三张表 + 一列 + 5 个权限码
-- ============================================================================================
-- 设计全文见 docs/superpowers/specs/2026-10-15-ci-tekton-design.md。
--
-- 范围：流水线定义（画布 JSON）+ 运行记录 + 运行日志 + 连接的「可在流水线里使用」开关。
--
-- 权限码（5 个，落在 14 行权限上）：
--   · tenant:pipeline:read    —— API，7 行：list / get / node-types / git-connections /
--                                preview / run/list / run/get
--   · tenant:pipeline:manage  —— API，3 行：create / update / delete
--   · tenant:pipeline:run     —— API，2 行：run / run/cancel
--   · tenant:pipeline:logs    —— API，1 行：run/logs。**单独成码**：日志是容器输出，
--                                可能含敏感内容（台账 S3.5.12 的「定义/触发/看日志分开授权」）
--   · tenant:page:pipeline    —— Page，1 行，页面可见性
--
-- ⚠️ 必须是 tenant: 前缀：RoleService.assertScopeMatches 规定 TENANT 角色只能持 tenant: 码。
--
-- ⚠️ 同一 code 对应多行是既有的 ANY-of 约定（RoleService「权限点 code 自 Task 4 起不再唯一」）。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启。
-- ⚠️ 重放后需重新登录或等权限缓存 TTL（30s）。
-- ⚠️ 本仓没有 Flyway，本文件是手工重放的；启动日志里永远不会出现 Migrating schema。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符，否则整个文件重放失败。
--
-- ⚠️⚠️ permission_id 必须与上面 platform_permission 的 id 逐字一致。本仓**不加外键**，
--    写错了不会报任何错，只留一条悬空关联 → 菜单不显示、路由跳回总览，且日志一片安静。
--    重放后**必须做 join 对账**，不能只数行数。
-- ============================================================================================

-- ===== 表 1：流水线（平台自己的表 —— 它不是一个 K8s 对象）=====
CREATE TABLE IF NOT EXISTS `platform_pipeline` (
  `id`          varchar(64)  NOT NULL COMMENT 'ULIDGenerator',
  `cluster_id`  varchar(64)  NOT NULL,
  `namespace`   varchar(253) NOT NULL COMMENT '命名空间级资源 —— 跟 Deployment/Service 一样',
  `name`        varchar(253) NOT NULL COMMENT '同命名空间内唯一（靠下面那条唯一索引，本仓不加外键）',
  `description` varchar(255)          DEFAULT NULL,
  `canvas_json` text         NOT NULL COMMENT '画布：节点 + 连线。**流水线的唯一真相源** —— 集群里不留 Pipeline 对象',
  `created_by`  varchar(64)           DEFAULT NULL,
  `created_at`  datetime              DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_pipeline`(`cluster_id` ASC, `namespace` ASC, `name` ASC) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC
  COMMENT='流水线（画布 JSON 是唯一真相源）';

-- ===== 表 2：运行记录 =====
-- ⚠️ start_time / completion_time 是 **varchar**（存 Tekton 给的 RFC3339 原文），不是 datetime：
--    原文最便于排查，代价是**字符串比较只在格式统一时才等价于时间比较**。
--    `idx_completion` 只服务于「按时间排序/取区间」这类同格式比较；
--    「早于 N 天」的保留清理在 Java 侧把字符串 parse 成 Instant 再比（见 PipelineRetentionService）。
CREATE TABLE IF NOT EXISTS `platform_pipeline_run` (
  `id`              varchar(64)  NOT NULL,
  `pipeline_id`     varchar(64)  NOT NULL,
  `cluster_id`      varchar(64)  NOT NULL,
  `namespace`       varchar(253) NOT NULL,
  `run_name`        varchar(253) NOT NULL COMMENT '集群里的 PipelineRun 名，**由平台先生成** —— 归档幂等键',
  `status`          varchar(32)  NOT NULL COMMENT 'RUNNING / SUCCEEDED / FAILED / CANCELLED / TIMEOUT',
  `reason`          varchar(255)          DEFAULT NULL,
  `triggered_by`    varchar(64)           DEFAULT NULL,
  `start_time`      varchar(64)           DEFAULT NULL,
  `completion_time` varchar(64)           DEFAULT NULL,
  `steps_json`      text                  DEFAULT NULL COMMENT '每个节点的状态与耗时',
  `created_at`      datetime              DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  -- ⚠️ run_name **全局唯一**（不按 cluster/namespace 分组）—— 归档幂等靠它一个键。
  --    所以 PipelineRunService 生成的 run 名必须带上时间戳 + 随机后缀（不是"流水线名"本身）。
  UNIQUE INDEX `uk_run_name`(`run_name` ASC) USING BTREE,
  INDEX `idx_pipeline`(`pipeline_id` ASC) USING BTREE,
  INDEX `idx_completion`(`completion_time` ASC) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC
  COMMENT='流水线运行记录（集群对象归档后删掉，本表是运行历史的唯一来源）';

-- ===== 表 3：运行日志 =====
CREATE TABLE IF NOT EXISTS `platform_pipeline_run_log` (
  `id`         varchar(64) NOT NULL,
  `run_id`     varchar(64) NOT NULL,
  `node_name`  varchar(253) NOT NULL COMMENT '画布上的节点 id（= Tekton task 名）',
  `content`    mediumtext             COMMENT '**单步上限 1 MB**，超出截断',
  `truncated`  tinyint     NOT NULL DEFAULT 0,
  `size_bytes` int         NOT NULL DEFAULT 0,
  `created_at` datetime             DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_run_node`(`run_id` ASC, `node_name` ASC) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC
  COMMENT='流水线运行日志（归档时抓下来；集群里的 Pod 随后被删）';

-- ===== platform_credential 加一列：允许在流水线里使用 =====
-- 默认 0（关）。S0.2 特意留着「租户侧没有凭据码」这条边界，CI 正面撞上了它 ——
-- 所以改成**逐条显式授权**，管理员看得见哪条连接被引用（spec §5.3）。
--
-- ⚠️ 用 information_schema 条件式 ADD COLUMN，**不是** `ADD COLUMN IF NOT EXISTS`
--    —— 后者是 MariaDB 的语法，MySQL 8 会直接语法错误。本仓 V2026_09_24_1 起就是这个写法。
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_credential'
               AND COLUMN_NAME = 'usable_by_pipelines');
SET @ddl := IF(@col = 0,
  'ALTER TABLE platform_credential ADD COLUMN usable_by_pipelines tinyint NOT NULL DEFAULT 0 COMMENT ''1=允许租户在流水线的 git/推送节点里引用这条连接；默认 0（关）''',
  'SET @pipeline_v1_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

-- ===== 权限行：API 域 13 行 + Page 域 1 行 =====
INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_pipeline_list',      'API','/pipeline/list',        'POST','tenant:pipeline:read',   '流水线列表'),
 ('perm_pipeline_get',       'API','/pipeline/get',         'POST','tenant:pipeline:read',   '流水线详情（含画布 JSON）'),
 ('perm_pipeline_nodetypes', 'API','/pipeline/node-types',  'POST','tenant:pipeline:read',   '内置节点类型与表单 schema'),
 ('perm_pipeline_gitconns',  'API','/pipeline/git-connections','POST','tenant:pipeline:read','可在流水线里使用的 git 连接（窄投影）'),
 ('perm_pipeline_preview',   'API','/pipeline/preview',     'POST','tenant:pipeline:read',   '把画布渲染成 YAML（预览）'),
 ('perm_pipeline_runlist',   'API','/pipeline/run/list',    'POST','tenant:pipeline:read',   '运行历史'),
 ('perm_pipeline_runget',    'API','/pipeline/run/get',     'POST','tenant:pipeline:read',   '一次运行的详情'),
 ('perm_pipeline_create',    'API','/pipeline/create',      'POST','tenant:pipeline:manage', '新建流水线'),
 ('perm_pipeline_update',    'API','/pipeline/update',      'POST','tenant:pipeline:manage', '更新流水线'),
 ('perm_pipeline_delete',    'API','/pipeline/delete',      'POST','tenant:pipeline:manage', '删除流水线'),
 ('perm_pipeline_run',       'API','/pipeline/run',         'POST','tenant:pipeline:run',    '触发一次运行'),
 ('perm_pipeline_runcancel', 'API','/pipeline/run/cancel',  'POST','tenant:pipeline:run',    '取消一次运行'),
 ('perm_pipeline_runlogs',   'API','/pipeline/run/logs',    'POST','tenant:pipeline:logs',   '看运行日志（单独成码：日志可能含敏感内容）'),
 ('perm_page_pipeline',      'Page','/pipelines',           'view','tenant:page:pipeline',   '流水线页面可见性')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

-- ===== 角色关联：14 + 11 + 14 = 39 行 =====
-- ⚠️ member 拿 read + logs + run，**不拿 manage**。理由（spec §5.1）：
--    run 只能跑**别人定义好的**流水线；要往流水线里塞任意命令需要 manage。
--    这条把「构建 Pod 能跑任意代码」的风险面收敛到了 manage 上。
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_tadmin_pl_list',   'builtin_role_tenant_admin','perm_pipeline_list'),
 ('rp_tadmin_pl_get',    'builtin_role_tenant_admin','perm_pipeline_get'),
 ('rp_tadmin_pl_nt',     'builtin_role_tenant_admin','perm_pipeline_nodetypes'),
 ('rp_tadmin_pl_gc',     'builtin_role_tenant_admin','perm_pipeline_gitconns'),
 ('rp_tadmin_pl_pv',     'builtin_role_tenant_admin','perm_pipeline_preview'),
 ('rp_tadmin_pl_rl',     'builtin_role_tenant_admin','perm_pipeline_runlist'),
 ('rp_tadmin_pl_rg',     'builtin_role_tenant_admin','perm_pipeline_runget'),
 ('rp_tadmin_pl_c',      'builtin_role_tenant_admin','perm_pipeline_create'),
 ('rp_tadmin_pl_u',      'builtin_role_tenant_admin','perm_pipeline_update'),
 ('rp_tadmin_pl_d',      'builtin_role_tenant_admin','perm_pipeline_delete'),
 ('rp_tadmin_pl_run',    'builtin_role_tenant_admin','perm_pipeline_run'),
 ('rp_tadmin_pl_cancel', 'builtin_role_tenant_admin','perm_pipeline_runcancel'),
 ('rp_tadmin_pl_logs',   'builtin_role_tenant_admin','perm_pipeline_runlogs'),
 ('rp_tadmin_pl_page',   'builtin_role_tenant_admin','perm_page_pipeline'),
 ('rp_tmember_pl_list',  'builtin_role_tenant_member','perm_pipeline_list'),
 ('rp_tmember_pl_get',   'builtin_role_tenant_member','perm_pipeline_get'),
 ('rp_tmember_pl_nt',    'builtin_role_tenant_member','perm_pipeline_nodetypes'),
 ('rp_tmember_pl_gc',    'builtin_role_tenant_member','perm_pipeline_gitconns'),
 ('rp_tmember_pl_pv',    'builtin_role_tenant_member','perm_pipeline_preview'),
 ('rp_tmember_pl_rl',    'builtin_role_tenant_member','perm_pipeline_runlist'),
 ('rp_tmember_pl_rg',    'builtin_role_tenant_member','perm_pipeline_runget'),
 ('rp_tmember_pl_run',   'builtin_role_tenant_member','perm_pipeline_run'),
 ('rp_tmember_pl_cancel','builtin_role_tenant_member','perm_pipeline_runcancel'),
 ('rp_tmember_pl_logs',  'builtin_role_tenant_member','perm_pipeline_runlogs'),
 ('rp_tmember_pl_page',  'builtin_role_tenant_member','perm_page_pipeline'),
 ('rp_admin_pl_list',    'builtin_role_admin','perm_pipeline_list'),
 ('rp_admin_pl_get',     'builtin_role_admin','perm_pipeline_get'),
 ('rp_admin_pl_nt',      'builtin_role_admin','perm_pipeline_nodetypes'),
 ('rp_admin_pl_gc',      'builtin_role_admin','perm_pipeline_gitconns'),
 ('rp_admin_pl_pv',      'builtin_role_admin','perm_pipeline_preview'),
 ('rp_admin_pl_rl',      'builtin_role_admin','perm_pipeline_runlist'),
 ('rp_admin_pl_rg',      'builtin_role_admin','perm_pipeline_runget'),
 ('rp_admin_pl_c',       'builtin_role_admin','perm_pipeline_create'),
 ('rp_admin_pl_u',       'builtin_role_admin','perm_pipeline_update'),
 ('rp_admin_pl_d',       'builtin_role_admin','perm_pipeline_delete'),
 ('rp_admin_pl_run',     'builtin_role_admin','perm_pipeline_run'),
 ('rp_admin_pl_cancel',  'builtin_role_admin','perm_pipeline_runcancel'),
 ('rp_admin_pl_logs',    'builtin_role_admin','perm_pipeline_runlogs'),
 ('rp_admin_pl_page',    'builtin_role_admin','perm_page_pipeline')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
