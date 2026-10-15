-- ============================================================================================
-- S3.5 第 ① 片补 3：platform_pipeline_run.reason 放大到 varchar(1024)
-- ============================================================================================
-- 为什么：reason 从 2026-10-15 起存的是**失败那个节点的原话**（Tekton TaskRun condition 的
-- message），而不再只是顶层那句统计口径的 `Failed`。原话可以很长，例如：
--
--   failed to create task run pod "…": pods "…" is forbidden: failed quota: default:
--   must specify limits.cpu for: place-scripts,prepare,step-clone; limits.memory for: 同上;
--   requests.cpu for: 同上; requests.memory for: 同上. Maybe invalid TaskSpec
--
-- 截到 255 会把结尾那半句（恰恰是「哪些资源没写」的后半）砍掉 —— 真环境里出现过
-- 结尾停在 `limits.memor…`，用户据此查不出问题。
--
-- ⚠️ 宽度必须与 k8s-server 的 PipelineArchiveService.REASON_MAX 一致：
--    那里按列宽截断，是因为超长在 MySQL 严格模式下是 SQLException，而
--    「归档落库失败 = 集群里那条运行永远不删」（PipelineRunFinisher 的守卫）。
--    改一边必须改另一边。
--
-- 幂等：MODIFY 重复执行结果一致（第二次是 no-op）。varchar(255) → varchar(1024) 会改长度前缀，
-- MySQL 需要重建这张表 —— 它只存运行元数据，量级很小，代价可接受。
--
-- ⚠️ 部署顺序：**先重放本文件，再起新构建**。反了的话：新代码把长 reason 写进还是 255 的列 →
--    严格模式下报错 → 归档失败 → 集群里的 PipelineRun 一直不删、台账一直重试。
--
-- 回滚：ALTER TABLE `platform_pipeline_run` MODIFY COLUMN `reason` varchar(255) DEFAULT NULL;
--       （回滚前先把超过 255 的行截短，否则会报错）
-- 注意：MySQL 注释 `--` 后面必须跟空白字符，否则整个文件重放失败。
-- ============================================================================================

ALTER TABLE `platform_pipeline_run`
  MODIFY COLUMN `reason` varchar(1024) DEFAULT NULL
  COMMENT '失败原因：优先是**失败那个节点的原话**（Tekton TaskRun 的 message），取不到才退回顶层 condition 的 reason';
