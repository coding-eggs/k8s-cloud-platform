-- 2026-10-31：把历史遗留的「平台级 Docker Hub 凭据」归属到 default 租户
--
-- 与 V2026_10_31_1（GIT）同因：tenant_id 是 V2026_10_27_1 才加的、加列未回填，
-- 更早建的凭据都是平台级；2026-10-30 的 641b18ff 收紧 visibleTo 后，
-- 平台级连接在**任何租户帽下**都不可用（管理员也一样，判据是帽子不是角色）。
--
-- 为什么 DOCKERHUB 可以迁、HARBOR 不行：
--   · DOCKERHUB 行只被两处消费 —— 流水线的镜像节点（pipelineUsableRegistryConnections）
--     和镜像仓库浏览页（RegistryService 按行答「我名下」）。两处都是租户语境。
--   · HARBOR 行还担着镜像仓库页、镜像改写、组件安装的镜像来源，迁走会连锁打断，**不要动**。
--   · 内置的 Docker Hub 是 BuiltinRegistry 档、不是凭据行，不受影响。

-- ---------------------------------------------------------------------------
-- ① 先看影响面
-- ---------------------------------------------------------------------------
-- SELECT id, name, endpoint, type, source, usable_by_pipelines
--   FROM platform_credential
--  WHERE tenant_id IS NULL AND type = 'DOCKERHUB';
--
-- ② 有没有 default 以外的租户在引用（镜像节点在 canvas_json 里存 credentialId）：
-- SELECT a.tenant_id, p.cluster_id, p.namespace, p.name
--   FROM platform_pipeline p
--   JOIN platform_tenant_namespace a
--     ON a.cluster_id = p.cluster_id AND a.namespace = p.namespace
--  WHERE p.canvas_json LIKE '%<上一步查到的 id>%';
--
--    出现别的租户时不要直接迁：给那个租户复制一条（同一 endpoint/认证材料、tenant_id 不同），
--    再把它的 canvas_json 里的 credentialId 换成新 id。

-- ---------------------------------------------------------------------------
-- ③ 归属
--    platform_credential 是**硬删**，没有 deleted_at 列；platform_tenant 也不假设有软删列
--    （service_account 上有唯一索引，标量子查询必得一行）。
-- ---------------------------------------------------------------------------
UPDATE platform_credential
   SET tenant_id = (SELECT id FROM platform_tenant WHERE service_account = 'default')
 WHERE tenant_id IS NULL
   AND type = 'DOCKERHUB';

-- ④ 复核：应返回 0 行
-- SELECT COUNT(*) FROM platform_credential
--  WHERE tenant_id IS NULL AND type = 'DOCKERHUB';
