-- ============================================================================================
-- 租户 RBAC 模板：core/v1 events（只读）
-- ============================================================================================
-- 背景：中间件实例详情页新增「事件」块（排障第一现场：Pod 为什么起不来、卷为什么没绑上）。
--       k8s-server 用租户 client 读事件，内置模板里没有 events 时会是 403，
--       详情页只能显示「事件读取失败」的提示。
--
-- ⚠️ 重放后必须点一次「重下发全部模板」（POST /rbacTemplate/sync-all），否则已有租户 ClusterRole 还是旧 rules。
-- 幂等：带自己的 NOT LIKE 守卫。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

UPDATE platform_rbac_template
SET rules = JSON_ARRAY_APPEND(rules, '$.rules',
        CAST('{"apiGroups":[""],"resources":["events"],"verbs":["get","list","watch"]}' AS JSON))
WHERE built_in = 1
  AND deleted_at IS NULL
  AND rules NOT LIKE '%"events"%';
