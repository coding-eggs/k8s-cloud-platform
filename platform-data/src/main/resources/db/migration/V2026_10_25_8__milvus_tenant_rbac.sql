-- ============================================================================================
-- 租户 RBAC 模板：Milvus CRD 的 apiGroup
-- ============================================================================================
-- 背景：租户要在自己的命名空间创建 Milvus 实例（走租户 client 的第二道闸），
--       内置模板缺 milvus.io 时创建 CR 会 403（K8S_RBAC_FORBIDDEN）。
--
-- ⚠️ 重放后必须点一次「重下发全部模板」（POST /rbacTemplate/sync-all），否则已有租户 ClusterRole 还是旧 rules。
-- 幂等：带自己的 NOT LIKE 守卫（同 V2026_10_22_5 的写法）。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

UPDATE platform_rbac_template
SET rules = JSON_ARRAY_APPEND(rules, '$.rules',
        CAST('{"apiGroups":["milvus.io"],"resources":["milvuses","milvusclusters","milvusupgrades"],"verbs":["*"]}' AS JSON))
WHERE built_in = 1
  AND deleted_at IS NULL
  AND rules NOT LIKE '%milvus.io%';
