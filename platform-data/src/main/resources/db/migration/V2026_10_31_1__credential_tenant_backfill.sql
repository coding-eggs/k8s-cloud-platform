-- 2026-10-31：把历史遗留的「平台级 Git 凭据」归属到 default 租户
--
-- 背景
--   platform_credential.tenant_id 是 V2026_10_27_1 才加的（NULL = 平台级），**加列时没有回填** ——
--   所以更早建的 Git 连接全部是平台级。2026-10-30 的 641b18ff 收紧了 visibleTo：
--   带租户帽的请求只能用「本租户」的连接，管理员戴帽时也一样。后果是这些历史连接在租户视图里
--   一律不可用（报「平台级连接仅管理员可用」），而流水线通常在租户视图里编辑。
--
-- 处置
--   Git 凭据从此归租户（平台视图不再给入口，见 router 的 git-credentials 去掉 platformDomain）。
--   把平台级 GIT 行指给 default 租户。
--
--   ⚠️ **只动 type='GIT'**：平台级 HARBOR 行被镜像仓库页 / 镜像改写 / 组件安装使用，
--   迁走会连锁打断它们。

-- ---------------------------------------------------------------------------
-- ① 先看影响面（手工执行时先跑这两条，确认再跑下面的 UPDATE）
-- ---------------------------------------------------------------------------
-- SELECT id, name, endpoint, type, source
--   FROM platform_credential
--  WHERE tenant_id IS NULL AND type = 'GIT';
--
-- ② 有没有**别的租户**的流水线在引用这些连接（canvas_json 里存的是 credentialId，
--    流水线表没有 tenant_id，租户由分配表推出）：
-- SELECT a.tenant_id, p.cluster_id, p.namespace, p.name
--   FROM platform_pipeline p
--   JOIN platform_tenant_namespace a
--     ON a.cluster_id = p.cluster_id AND a.namespace = p.namespace
--  WHERE p.canvas_json LIKE '%<上一步查到的 id>%';
--
--    若结果里出现 default 以外的 tenant_id，说明那个租户也在用同一条连接：
--    迁给 default 会打断它 —— 应先给那个租户复制一条（endpoint/auth 相同、tenant_id 不同），
--    再把它的流水线 canvas_json 里的 credentialId 改成新 id。**不要**把同一条行迁来迁去。

-- ---------------------------------------------------------------------------
-- ③ 归属
--    platform_credential 是**硬删**，没有 deleted_at 列；platform_tenant 也不假设有软删列
--    （service_account 上有唯一索引，标量子查询必得一行）。
-- ---------------------------------------------------------------------------
UPDATE platform_credential
   SET tenant_id = (SELECT id FROM platform_tenant WHERE service_account = 'default')
 WHERE tenant_id IS NULL
   AND type = 'GIT';

-- ④ 复核：应返回 0 行
-- SELECT COUNT(*) FROM platform_credential
--  WHERE tenant_id IS NULL AND type = 'GIT';
