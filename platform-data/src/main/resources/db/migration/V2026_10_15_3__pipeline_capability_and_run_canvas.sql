-- ============================================================================================
-- S3.5 第 ① 片补 2：capability 门控接线 + 运行详情的三件事
-- ============================================================================================
-- 三件事：
-- 1. **capability 门控终于有端点** —— PipelineCapabilityService 在此之前是死代码：
--    它算得出「这个集群能不能跑 CI」，却没有任何人问它，页面也不会因此改样子。
-- 2. 运行详情要看「每个节点现在跑到哪了」→ `/pipeline/run/steps`（还在跑时现抓）。
-- 3. 运行详情要看**渲染出来的 YAML** → `/pipeline/run/yaml`。
--    ⚠️ 归档后集群对象被删了，所以**必须**留下「跑的时候那张画布」→ 第 4 件事 ↓
-- 4. `platform_pipeline_run.canvas_json`：触发那一刻的画布。
--    没有它，历史运行详情画的是**今天的**流水线 —— 改过名字/换过镜像/删过节点之后，
--    用户看到的与当年跑的对不上，排障时被彻底误导。
--
-- ⚠️ 三个新端点的 code 全部复用 `tenant:pipeline:read`：它们都是「读一次运行 / 读这个集群能不能跑 CI」，
--    与 run/get、run/list 同一域，不新造码（同 V2026_10_15_2 对 registry-connections 的判断）。
-- ⚠️ permission_id 必须与 platform_permission.id 逐字一致。本仓**不加外键**，写错只会留一条
--    悬空关联（角色没有它 → 端点 403、日志一片安静）。重放后必须做 join 对账。
-- ⚠️ 部署顺序：先重放本文件再起新构建（无行的新端点会被 PermissionCrossCheckRunner 拒启）。
-- ============================================================================================

-- 4. `platform_pipeline_run.canvas_json` + `run_yaml`：一次运行自己带走的两份东西。
--    ⚠️ `canvas_json` 由 **platform-api** 在触发时写（它手里就有画布）；
--       `run_yaml` 由 **k8s-server** 在**归档那一刻**抓（那时集群对象还活着 —— 归档之后它就删了，
--       而「当年跑的到底是什么」只有那一瞬间能拿到；事后拿今天的画布重渲染，
--       对引用了连接的流水线根本渲染不出来，也会画出一份当年没跑过的东西）。
--
-- ⚠️ 用 information_schema 条件式 ADD COLUMN，**不是** `ADD COLUMN IF NOT EXISTS`
--    —— 后者是 MariaDB 的语法，MySQL 8 会直接语法错误（本仓 V2026_09_24_1 起就是这个写法）。
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_pipeline_run'
               AND COLUMN_NAME = 'canvas_json');
SET @ddl := IF(@col = 0,
  'ALTER TABLE platform_pipeline_run ADD COLUMN canvas_json mediumtext DEFAULT NULL COMMENT ''触发那一刻的画布 JSON —— 历史运行详情画它，不是画今天的流水线'' AFTER `namespace`',
  'SET @pipeline_v3_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_pipeline_run'
               AND COLUMN_NAME = 'run_yaml');
SET @ddl := IF(@col = 0,
  'ALTER TABLE platform_pipeline_run ADD COLUMN run_yaml mediumtext DEFAULT NULL COMMENT ''归档那一刻抓下来的 PipelineRun YAML（对象随后被删，这是「当年跑的是什么」的唯一存续形式）'' AFTER steps_json',
  'SET @pipeline_v3_skip_yaml = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- 2. 三个新端点 ----
INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_pipeline_capability','API','/pipeline/capability', 'POST','tenant:pipeline:read',
  '这个集群能不能跑 CI（Tekton + 可动态供应的 StorageClass；不可用时带原因）'),
 ('perm_pipeline_runsteps',  'API','/pipeline/run/steps', 'POST','tenant:pipeline:read',
  '一次运行里每个节点现在的落点（还在跑时现抓；归档后状态在 run 行的 stepsJson 里）'),
 ('perm_pipeline_runyaml',   'API','/pipeline/run/yaml',  'POST','tenant:pipeline:read',
  '一次运行渲染出来的 YAML（还在跑抓集群对象；归档后按当时的画布重渲染）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_tadmin_pl_cap',    'builtin_role_tenant_admin','perm_pipeline_capability'),
 ('rp_tmember_pl_cap',   'builtin_role_tenant_member','perm_pipeline_capability'),
 ('rp_admin_pl_cap',     'builtin_role_admin','perm_pipeline_capability'),
 ('rp_tadmin_pl_rstep',  'builtin_role_tenant_admin','perm_pipeline_runsteps'),
 ('rp_tmember_pl_rstep', 'builtin_role_tenant_member','perm_pipeline_runsteps'),
 ('rp_admin_pl_rstep',   'builtin_role_admin','perm_pipeline_runsteps'),
 ('rp_tadmin_pl_ryaml',  'builtin_role_tenant_admin','perm_pipeline_runyaml'),
 ('rp_tmember_pl_ryaml', 'builtin_role_tenant_member','perm_pipeline_runyaml'),
 ('rp_admin_pl_ryaml',   'builtin_role_admin','perm_pipeline_runyaml')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
