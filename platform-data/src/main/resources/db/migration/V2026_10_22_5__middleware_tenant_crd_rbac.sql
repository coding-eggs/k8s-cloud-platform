-- ============================================================================================
-- 租户 RBAC 模板：中间件 CRD 的 apiGroup（MySQL / Kafka / Elasticsearch）
-- ============================================================================================
-- 背景：批①-2 让租户可以在自己的命名空间创建 MySQL/Kafka/ES 实例，走的是租户 client（第二道闸）。
--       内置模板里只有 redis.redis.opstreelabs.in（V2026_10_22_1），缺这三个 group 时创建 CR 会 403。
--
-- ⚠️ 重放后必须点一次「重下发全部模板」（POST /rbacTemplate/sync-all），否则已有租户 ClusterRole 还是旧 rules。
-- 幂等：每条 UPDATE 带自己的 NOT LIKE 守卫，分三条写（同 V2026_10_17_1 的理由：半补状态各自补齐）。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

-- ===== 1) Percona Operator for MySQL =====
UPDATE platform_rbac_template
SET rules = JSON_ARRAY_APPEND(rules, '$.rules',
        CAST('{"apiGroups":["ps.percona.com"],"resources":["perconaservermysqls"],"verbs":["*"]}' AS JSON))
WHERE built_in = 1
  AND deleted_at IS NULL
  AND rules NOT LIKE '%ps.percona.com%';

-- ===== 2) Strimzi（KRaft：Kafka + KafkaNodePool）=====
UPDATE platform_rbac_template
SET rules = JSON_ARRAY_APPEND(rules, '$.rules',
        CAST('{"apiGroups":["kafka.strimzi.io"],"resources":["kafkas","kafkanodepools"],"verbs":["*"]}' AS JSON))
WHERE built_in = 1
  AND deleted_at IS NULL
  AND rules NOT LIKE '%kafka.strimzi.io%';

-- ===== 3) ECK =====
UPDATE platform_rbac_template
SET rules = JSON_ARRAY_APPEND(rules, '$.rules',
        CAST('{"apiGroups":["elasticsearch.k8s.elastic.co"],"resources":["elasticsearches"],"verbs":["*"]}' AS JSON))
WHERE built_in = 1
  AND deleted_at IS NULL
  AND rules NOT LIKE '%elasticsearch.k8s.elastic.co%';
