-- ============================================================================================
-- 租户 RBAC 模板：KafkaUser（用户管理）
-- ============================================================================================
-- 背景：租户在实例详情页「用户」页签里建 Kafka 用户，k8s-server 用 **admin client** 执行；
--       但租户自己也应当能在集群里看到这些 KafkaUser（资源页/排障），且租户 SA 的模板里
--       原本没有 kafka.strimzi.io 的 kafkausers 规则。
--
-- ⚠️ 重放后必须点一次「重下发全部模板」（POST /rbacTemplate/sync-all），否则已有租户的
--    ClusterRole 还是旧 rules，等于没改。
-- 幂等：带自己的 NOT LIKE 守卫（同 V2026_10_25_8 的写法）。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

UPDATE platform_rbac_template
SET rules = JSON_ARRAY_APPEND(rules, '$.rules',
        CAST('{"apiGroups":["kafka.strimzi.io"],"resources":["kafkausers"],"verbs":["get","list","watch"]}' AS JSON))
WHERE built_in = 1
  AND deleted_at IS NULL
  AND rules NOT LIKE '%kafkausers%';
