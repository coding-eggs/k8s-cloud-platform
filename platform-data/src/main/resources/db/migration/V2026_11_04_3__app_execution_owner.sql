-- ============================================================================================
-- 应用发布执行：实例归属 owner_id
-- ============================================================================================
-- 背景：发起人的 access token 只存在**发起请求的那个 platform-api 进程内存里**
-- （ApplicationDeployCredentials，有意不落库），而执行推进由所有实例共用的定时任务
-- 从 platform_app_execution 里抢着做。于是只要有两个实例连同一个库（典型：本地 IDE 起的
-- platform-api 和集群里那套共用一个 MySQL），另一边抢到就会因为「本进程没有凭据」把执行
-- 打成 INTERRUPTED —— 页面显示「发布已中断」，点「继续」也只是把凭据交给某一个进程。
--
-- 判据（配合 AppExecutionLease 与 claim 的 SQL）：
--   · 创建 / 继续时就把 owner_id 写成当前实例、lease_until 写成 now + 3min —— 别人不会来抢
--   · 之后每拍 claim 成功才推进，并顺带把租约续到 now + 3min（不再每步结束就释放租约）
--   · 只有 owner_id 变了才叫「接管」，意味着原实例的租约已过期（进程死了），此时没有凭据是正常的
--
-- ⚠️ 本仓没有 Flyway，迁移手工重放；先重放本文件再起新构建（新构建的 insert / claim 都会写这一列）。
-- ⚠️ 不幂等：重复执行会报 Duplicate column，重放前先 `show columns from platform_app_execution` 看一眼。
-- ============================================================================================

ALTER TABLE platform_app_execution
  ADD COLUMN owner_id VARCHAR(64) NULL COMMENT '当前推进该执行的 platform-api 实例' AFTER lease_until;
