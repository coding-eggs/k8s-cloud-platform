# 受管资源保护（平台组件 / 中间件实例）

> 设计：spec §7。实现在 k8s-server 的 `ManagedResourceGuard` + `MiddlewareCrLookup`；
> 前端 `utils/managedResource.ts` 只做置灰与提示，**拒绝在后端**。

## 1. 什么算"受管"

按顺序判定（命中即停）：

| # | 依据 | 结论 |
|---|---|---|
| 1 | 标签 `platform.coding/managed-by` 以 `middleware` 开头 | `MIDDLEWARE` |
| 1 | 标签 `platform.coding/managed-by` = 其它值 | `COMPONENT` |
| 2 | 标签 `platform.coding/managed=true` | `COMPONENT` |
| 3 | 注解 `meta.helm.sh/release-name` 以 `mw-` 开头 | `MIDDLEWARE` |
| 3 | 注解 `meta.helm.sh/release-name` 以 `platform-` 开头 | `COMPONENT` |
| 3 | 注解 `meta.helm.sh/release-namespace` 属平台命名空间（`platform-system`、`monitor`、`istio-system`、`tekton-pipelines`、`cert-manager` 等，可配 `platform.managed-namespaces`） | `COMPONENT` |
| 4 | `ownerReferences` 的属主名以 `mw-` / `platform-` 开头 | `MIDDLEWARE` / `COMPONENT` |
| 5 | 实例名标签（`app.kubernetes.io/instance`、`strimzi.io/cluster`、`elasticsearch.k8s.elastic.co/cluster-name`、`app`）的值以 `mw-` 开头 | `MIDDLEWARE` |

- 第 4 条是 PVC/Pod 这类"派生对象"的主要依据：Nacos 的 PVC 没有任何标签与 Helm 注解，只能靠属主
  StatefulSet 的名字（`mw-nacos-…`）识别；组件 Pod 的属主 `platform-thanos-…-<hash>` 同理。
- 第 5 条只认平台自己的命名约定（实例名 `mw-<code>-<8hex>`），**不做"标签名→CR 回查"**：
  那样会把用户 `app: redis` 这类通用标签误判成受管，也会在列表里产生 N+1 集群请求。
- 判定全部基于元数据，**零集群读**；列表页可以逐行调用。
- 前提：DTO 必须带 labels / annotations / ownerReferences —— `MetaUtil.copy` 统一搬运（2026-10-22 复审补）。

## 2. 受什么限制

- 工作负载（Deployment / StatefulSet / DaemonSet / Job / CronJob / ReplicaSet）：
  **编辑、伸缩、暂停/恢复、改镜像、回滚、删除**全部拒绝；
- **Pod 例外**：删除 Pod **不受**限制（含 Operator / 中间件 / 平台组件工作负载的 Pod）——
  删 Pod 等于"重启一个副本"，控制器会按原模板重建，不改变任何配置；工作负载本身的写入仍被上一条拦住。
  Pod 本来就没有 create/update；
- 其它命名空间资源（Service / ConfigMap / Secret / Ingress(Gateway、Route) / PVC / HPA 等）：
  **更新、删除**拒绝；
- 后端返回错误码 `10048`（`RESOURCE_MANAGED_BY_PLATFORM`），前端统一提示"不能在本页修改"。

## 3. 前端表现

- 列表行内菜单：受管行多一条禁用项「由平台组件管理，请到「集群组件」页操作」/「由中间件实例管理，请到「中间件」页操作」，
  写操作项置灰；**Pod 列表是例外：只显示日志/Exec/Yaml/删除，「删除」不置灰**（与 §2 一致）；
- 工作负载列表/详情：原本按 `ownerReferences` 判断"由 Operator 管理"，现在**同时**看 `managedBy`；
- CR 浏览页保持只读（与本次无关）。

## 4. 例外

- 实例 / 组件的删除请到「中间件」或「集群组件」页（那里直接调 k8s 客户端，不经过本闸）；
- 进入 Pod 的日志 / Exec 是只读动作，不受限制；
- **删除 Pod 不受限制**：`PodController` 覆写 `assertDeletable` 留空（k8s-server 侧），
  前端 Pod 列表/工作负载详情的「删除」也不置灰 —— 这是唯一一个"受管对象仍可写"的动词；
- 创建 HPA 本身允许，但**目标工作负载受管时会被拒**（否则等于绕开伸缩禁令）。

## 5. 已知限制

- 第 4/5 条依赖平台命名约定（`mw-` / `platform-`）：在平台之外手工创建的对象不会被识别为受管；
- 用户自建 Helm release 若取名 `platform-*` 或部署在平台命名空间，会被当成组件（有意：那些命名空间归平台）；
- CR 页面（自定义资源）本身就是只读，未接入 `managedBy`；
- 为受管工作负载创建 HPA 会被拒（否则等于绕开伸缩禁令）。
