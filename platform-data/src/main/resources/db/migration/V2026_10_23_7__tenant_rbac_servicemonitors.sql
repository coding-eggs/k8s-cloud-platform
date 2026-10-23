-- ============================================================================================
-- 租户 RBAC 模板：Prometheus Operator 的 ServiceMonitor
-- ============================================================================================
-- 背景：中间件实例的业务指标由平台随实例创建 `<名字>-metrics` 的 Service + ServiceMonitor，
--       租户在自己的命名空间建实例时用的是租户 client（第二道闸），模板里缺这个 apiGroup 会 403
--       （表现为：实例建好了，但详情页业务指标一直没数据）。
--
-- ⚠️ 重放后必须点一次「重下发全部模板」（POST /rbacTemplate/sync-all），否则已有租户 ClusterRole 还是旧 rules。
-- 幂等：带自己的 NOT LIKE 守卫。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

UPDATE platform_rbac_template
SET rules = JSON_ARRAY_APPEND(rules, '$.rules',
        CAST('{"apiGroups":["monitoring.coreos.com"],"resources":["servicemonitors"],"verbs":["get","list","watch","create","update","patch","delete"]}' AS JSON))
WHERE built_in = 1
  AND deleted_at IS NULL
  AND rules NOT LIKE '%monitoring.coreos.com%';
