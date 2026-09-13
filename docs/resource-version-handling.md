# 跨版本资源：分类与处理约定

> 本文档回答一个问题：**哪些 K8s 资源将来需要「按集群版本选不同 converter」，以及到时候怎么做。**
> 当前平台还没有按版本分派的 converter（还在建设期），所以本文只做归类与约定；**能力快照的采集 + 持久化已落地**（见下「判定」一条），缺的只是 `build()` 里读它选版本的那一步。

## 判定标准：看 DTO 字段形状是否发散，而不是「历史上有没有多个版本」

一个资源要不要按版本分派 converter，唯一依据是：**它的平台侧 DTO 在不同 apiVersion 下字段形状是否不同**。

- **形状稳定 → 单 converter 足够**：Workload（Deployment/StatefulSet/DaemonSet）、RBAC（ServiceAccount/RoleBinding/ClusterRole）、corev1（Pod/Service/ConfigMap/Secret/PVC）。它们虽有 `v1beta1 → v1` 的历史，但平台 DTO 只对齐当前稳定版，形状不随集群版本变。→ 在 [`KubernetesOperationsFactory.build()`](../k8s-core/src/main/java/com/coding/k8score/factory/KubernetesOperationsFactory.java) 里**直接 `new`**，不做任何能力探测。
- **形状发散 → 需要按版本选 converter**：就是下面这一类。

> 为什么不用 fabric8 的 generic `HasMetadata`/`JsonNode` 兜底？对结构稳定的资源可以；但 HPA/CRD 这种字段大改的，DTO 映射本身要按版本走完全不同的字段，用 generic 会把「版本差异」甩给每个调用方手写，更乱。所以这类仍需要 per-version converter，只是把**选择逻辑收敛在 `build()` 一处**。

## 发散资源清单（归为一类）

| 资源 | 版本对 | 发散程度 | 具体发散点 |
|---|---|---|---|
| **HPA** HorizontalPodAutoscaler | `autoscaling/v1` ↔ `v2` | **大** | v2 重写 metrics 模型：v1 的 `targetCPUUtilizationPercentage` → v2 的 `metrics[]`（Resource / ContainerResource / Pods / Object / External 五类）+ `behavior`（v2.1+ 的 scale-up/down 稳定期与速率）。两套字段几乎不重叠，DTO 必须分两个。 |
| **CRD** CustomResourceDefinition | `apiextensions/v1beta1` ↔ `v1` | **大** | v1beta1：`versions[]` 每项自带 `schema`；v1：顶层单一 `schema` + `versions[].{name,served,storage,additionalPrinterColumns}`，且 v1 **强制要求** schema。names/scope 结构也不同。 |
| **Ingress** | `extensions/v1beta1` → `networking.k8s.io/v1` | **中** | group 迁移过两次；v1 引入必填 `pathType`（Prefix/Exact/ImplementationSpecific）；backend 从 `{serviceName, servicePort: IntOrString}` 改为 `{service: {name, port: number}}`。 |
| **CronJob** | `batch/v1beta1` ↔ `v1` | 小 | 形状大体一致，v1 微调 `successLimit`/`startingDeadlineSeconds` 语义与默认值。 |
| **PDB** PodDisruptionBudget | `policy/v1beta1` ↔ `v1` | 小 | 仅 group 从 `policy/v1beta1` 迁到 `policy/v1`，字段基本一致。 |

> 说明：Ingress / CronJob / PDB 的旧版 apiVersion 已在新集群被移除（extensions/v1beta1 Ingress、batch/v1beta1 CronJob、policy/v1beta1 PDB 均在 1.22~1.25 移除；apiextensions/v1beta1 CRD 在 1.22 移除）。**只要平台声明支持的集群版本下限高于这些移除线，就只剩单一版本、无需分派**——届时它们自动降级为「形状稳定」一类。真正长期需要双 converter 的只有 **HPA（v1 至今仍在 served）** 和 **CRD**。

## 到时候怎么做（机制约定）

1. **选择逻辑只放在 [`KubernetesOperationsFactory.build()`](../k8s-core/src/main/java/com/coding/k8score/factory/KubernetesOperationsFactory.java)。** 对应 `case` 里按「集群支持的 apiVersion」返回不同的 `new XxxOperations(client, new XxxV1Converter())` / `XxxV2Converter`。不重造全局 capability 探测 + 定时刷新那套（旧方案的问题：逐 endpoint 探测、失败会污染 10min 缓存、启动即打一轮 API）。
2. **判定集群支持哪个版本，用运行时 discovery 快照，不靠「版本查表推断」。** K8s 的 deprecation 时间线虽固定已知（见上表），但非标准发行版（裁剪/自建）不可尽信；所以平台**问集群真实支持**：k8s-server `client.getApiGroups()` 探测 → `{group: [version, ...]}` 快照持久化到 `k8s_cluster.capability`。刷新时机：集群**新建 / 改 kubeconfig / 重新启用**时自动 best-effort 重探，外加集群管理页的**手动「刷新能力」按钮**。`build()` 直接读该快照选版本——零运行时探测开销（不逐 endpoint 打 API、无定时轮询）。
3. **converter 按版本拆成独立类**（`XxxV1Converter` / `XxxV2Converter`），DTO 若两版字段差异大到无法共用一个 DTO，则分两个 DTO + 在 controller 层做归一；能共用就共用一个 DTO、由 converter 各自填各自的字段。
4. **未支持的组合要显式报错**（抛业务异常），不要返回 `null`——旧工厂「不匹配返回 null → 下游 NPE」正是这次重构去掉的坑。

## 一句话总结

当前所有已实现资源都是「形状稳定」，直接 `new`；HPA/CRD/Ingress/CronJob/PDB 是**唯一需要按版本分派的一类**。能力快照的**采集 + 持久化已落地**（k8s-server `getApiGroups()` discovery → `k8s_cluster.capability`，事件/手动刷新）；剩下的是第一个发散资源落地时，在 `build()` 里「读 capability 选 converter」——把 `clusterId` 透传进 `build()`、按存储的 group→versions 判断首选 version 是否可用。
