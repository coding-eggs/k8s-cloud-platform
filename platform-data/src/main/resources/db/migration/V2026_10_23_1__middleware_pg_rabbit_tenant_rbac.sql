-- ============================================================================================
-- 租户 RBAC 模板：中间件 CRD 的 apiGroup（PostgreSQL / RabbitMQ）
-- ============================================================================================
-- 背景：批② 让租户可以在自己的命名空间创建 PostgreSQL（CloudNativePG）/ RabbitMQ（Cluster Operator）
--       实例，走的是租户 client（第二道闸）。内置模板里缺这两个 group 时创建 CR 会 403。
--
-- ⚠️ 重放后必须点一次「重下发全部模板」（POST /rbacTemplate/sync-all），否则已有租户 ClusterRole 还是旧 rules。
-- 幂等：每条 UPDATE 带自己的 NOT LIKE 守卫，分两条写（同 V2026_10_22_5 的理由：半补状态各自补齐）。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

-- ===== 1) CloudNativePG =====
UPDATE platform_rbac_template
SET rules = JSON_ARRAY_APPEND(rules, '$.rules',
        CAST('{"apiGroups":["postgresql.cnpg.io"],"resources":["clusters","backups","scheduledbackups","poolers"],"verbs":["*"]}' AS JSON))
WHERE built_in = 1
  AND deleted_at IS NULL
  AND rules NOT LIKE '%postgresql.cnpg.io%';

-- ===== 2) RabbitMQ Cluster Operator =====
UPDATE platform_rbac_template
SET rules = JSON_ARRAY_APPEND(rules, '$.rules',
        CAST('{"apiGroups":["rabbitmq.com"],"resources":["rabbitmqclusters"],"verbs":["*"]}' AS JSON))
WHERE built_in = 1
  AND deleted_at IS NULL
  AND rules NOT LIKE '%rabbitmq.com%';
