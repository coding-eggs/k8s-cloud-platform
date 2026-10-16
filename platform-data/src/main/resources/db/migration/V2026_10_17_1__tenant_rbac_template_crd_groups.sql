-- ============================================================================================
-- 租户 RBAC 模板补 CRD apiGroup（monitoring.coreos.com / gateway.networking.k8s.io / tekton.dev）
-- ============================================================================================
-- 症状（2026-10-17 实测）：租户身份打开任何 CRD 页面都失败。第二道闸缺规则 ——
--   kubectl auth can-i list servicemonitors.monitoring.coreos.com \
--     --as=system:serviceaccount:platform-system:tn-default -n <ns>   → no
--
-- 为什么只有第二道闸出问题：链路是
--   platform-api 透传用户 token → k8s-server BoundaryAuthorizationManager 读端点声明
--   → 这些 controller 都继承 AbstractNamespacedResourceController 的 TENANT 边界
--   → token 带 tenantInfo → adminMode=false → clientFactory.getTenantClient（租户 SA）
--   → K8s RBAC。第一道闸（platform_permission 里的 tenant:servicemonitor:* / tenant:gateway:*
--   / tenant:httproute:* 等码）本来就是齐的，所以这不是权限表的问题，本文件也就不含权限行。
--
-- 为什么必须改内置模板而不是让运维在页面上加：RbacTemplateService.update 对 built_in = 1
-- 直接抛 RBAC_TEMPLATE_BUILTIN_PROTECTED —— 内置模板在「RBAC 模板」页根本改不了。
--
-- ⚠️ 重放本文件**只是第一步**。平台只在「保存单个模板」时 sync，不重下发的话各集群上已有的
--     tn-tpl-* ClusterRole 还是旧 rules，等于没改。重放后必须点一次「重下发全部模板」
--     （RBAC 模板页 → 或 POST /rbacTemplate/sync-all）。（V2026_10_12_1 是同一个坑。）
-- 幂等：三条 UPDATE 各带自己的 NOT LIKE 守卫，且**分三条写**而不是合成一条 ——
--       合成一条时"只漏了一个组"的半补状态会被整条前置条件跳过，分三条能各自补齐。
-- 实现为什么用 JSON_ARRAY_APPEND 而不是字符串 REPLACE（V2026_10_12_1 用的那种）：
--       那次只改一个已有字符串，这次要**追加整个 rule 对象**。规则串里每个 rule 都以
--       `"verbs":["*"]}` 结尾，即 `]}`；REPLACE 是全局替换，`REPLACE(rules, ']}', ...)`
--       会把四条既有规则一起打碎。JSON_ARRAY_APPEND 按路径追加，不碰正文。
--       副作用：MySQL 的 JSON 类型会给对象键重排序、并输出带空格的规范形式，所以回读的
--       rules 文本与手写的紧凑形式不同。Jackson 侧无所谓（parseRules 照常解析），仅影响观感。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符，否则整个文件重放失败。
-- ⚠️ 本仓没有 Flyway，本文件是手工重放的；启动日志里永远不会出现 Migrating schema。
-- ============================================================================================

-- ===== 1) monitoring.coreos.com：ServiceMonitor / PodMonitor（租户域，/servicemonitors 与 /podmonitors）=====
-- PrometheusRule / Probe 有意不加：前者目前没有端点，等 S2.2 的 PrometheusRule 页落地时随那批加 ——
-- 现在加等于给一个还没有消费方的权限。
UPDATE platform_rbac_template
SET rules = JSON_ARRAY_APPEND(rules, '$.rules',
        CAST('{"apiGroups":["monitoring.coreos.com"],"resources":["servicemonitors","podmonitors"],"verbs":["*"]}' AS JSON))
WHERE built_in = 1
  AND deleted_at IS NULL
  AND rules NOT LIKE '%monitoring.coreos.com%';

-- ===== 2) gateway.networking.k8s.io：Gateway + 5 类 Route =====
-- 只列命名空间级的那些。GatewayClass 是集群级、平台域（GatewayClassController 走
-- AbstractClusterResourceController → PLATFORM → admin client），租户侧既不该有端点也不该有权限，
-- 放进模板等于把平台入口的父资源交给租户。
-- L4 三类（tcp/tls/udp）在旧集群上规范是 v1alpha2，但 RBAC 只认 apiGroup + resource，与版本无关。
UPDATE platform_rbac_template
SET rules = JSON_ARRAY_APPEND(rules, '$.rules',
        CAST('{"apiGroups":["gateway.networking.k8s.io"],"resources":["gateways","httproutes","grpcroutes","tcproutes","tlsroutes","udproutes"],"verbs":["*"]}' AS JSON))
WHERE built_in = 1
  AND deleted_at IS NULL
  AND rules NOT LIKE '%gateway.networking.k8s.io%';

-- ===== 3) tekton.dev：PipelineRun + TaskRun =====
-- TaskRun 是走查代码时查出来容易漏的一个：PipelineRunOperations.steps() 逐条
-- client.resources(TaskRun.class).get() 去解析「run → TaskRun → Pod → 容器」，
-- 只读 PipelineRun.status.childReferences 不够 —— 缺 taskruns 的话运行详情与节点日志整条不工作。
-- 平台不建 Task/TaskRun 对象（TaskSpec 内联在 PipelineRun 里），所以 tasks 不在列表内。
UPDATE platform_rbac_template
SET rules = JSON_ARRAY_APPEND(rules, '$.rules',
        CAST('{"apiGroups":["tekton.dev"],"resources":["pipelineruns","taskruns"],"verbs":["*"]}' AS JSON))
WHERE built_in = 1
  AND deleted_at IS NULL
  AND rules NOT LIKE '%tekton.dev%';

-- ===== 有意不做的 =====
-- · 自定义模板（built_in = 0，如 only-read）不动：它们在页面上可编辑，管理员按需自行加。
-- · projectcalico.org 不进模板：ippools / ipreservations / bgp* 全部挂在
--   AbstractClusterResourceController（PLATFORM 边界、admin client），租户侧没有可达路径。
-- · 不改 k8s-server 的 AccessBoundary：把这些端点改成 admin client 等于把租户的第二道闸
--   整个拆掉（admin client 是 cluster-admin，租户 A 就能读租户 B 命名空间的同名资源）。
