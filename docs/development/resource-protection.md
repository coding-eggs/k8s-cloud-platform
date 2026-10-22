# 受管资源保护（平台组件 / 中间件实例）

> 设计：spec §7。实现在 k8s-server 的 `ManagedResourceGuard` + `MiddlewareCrLookup`；
> 前端 `utils/managedResource.ts` 只做置灰与提示，**拒绝在后端**。

## 1. 什么算"受管"

按顺序判定（命中即停）：

| # | 依据 | 结论 |
|---|---|---|
| 1 | 标签 `platform.coding/managed-by` = `middleware` / `middleware-operator` | `MIDDLEWARE` |
| 1 | 标签 `platform.coding/managed-by` = 其它值（如 `component`） | `COMPONENT` |
| 2 | 注解 `meta.helm.sh/release-name` 以 `mw-` 开头 | `MIDDLEWARE` |
| 2 | 注解 `meta.helm.sh/release-name` 以 `platform-` 开头 | `COMPONENT` |
| 3 | 标签里的实例名（Percona `app.kubernetes.io/instance`、Strimzi `strimzi.io/cluster`、ECK `elasticsearch.k8s.elastic.co/cluster-name`、Redis `app`）能回查到同名中间件 CR | `MIDDLEWARE` |

第 3 条存在的原因：Operator 给子资源（Pod/Service/PVC）打的是它自己的标签，平台创建 CR 时打的
`platform.coding/*` 不会传播下去。回查带 30s 缓存，读不到时按"非受管"处理（宁可少拦，不误拦用户资源）。

## 2. 受什么限制

- 工作负载（Deployment / StatefulSet / DaemonSet / Job / CronJob / ReplicaSet / Pod）：
  **编辑、伸缩、暂停/恢复、改镜像、回滚、删除**全部拒绝；
- 其它命名空间资源（Service / ConfigMap / Secret / Ingress(Gateway、Route) / PVC / HPA 等）：
  **更新、删除**拒绝；
- 后端返回错误码 `10048`（`RESOURCE_MANAGED_BY_PLATFORM`），前端统一提示"不能在本页修改"。

## 3. 前端表现

- 列表行内菜单：受管行多一条禁用项「由平台组件管理，请到「集群组件」页操作」/「由中间件实例管理，请到「中间件」页操作」；
  编辑、删除等写操作项置灰；
- 工作负载列表/详情：原本按 `ownerReferences` 判断"由 Operator 管理"，现在**同时**看 `managedBy`；
- CR 浏览页保持只读（与本次无关）。

## 4. 例外

- 实例 / 组件的删除请到「中间件」或「集群组件」页（那里直接调 k8s 客户端，不经过本闸）；
- 进入 Pod 的日志 / Exec 是只读动作，不受限制；
- 创建 HPA 等"新建另一个对象"的动作不受限（不修改受管对象本身）。

## 5. 已知限制

- 第 3 条依赖 Operator 标签名，新增中间件时要同步 `MiddlewareCrLookup` 的 `KINDS`；
- Nacos 实例的下级资源没有平台标签，靠 Helm release `mw-` 前缀识别（chart 不渲染自定义 labels）；
- CR 页面（自定义资源）本身就是只读，未接入 `managedBy`。
