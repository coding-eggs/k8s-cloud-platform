# 组件（内置 chart）文档

本目录记录**平台通过组件安装引擎落地的 chart 组件**：怎么装、平台哪些功能依赖它、踩过哪些坑、后续怎么加版本。

组件清单与探测判据在 `platform-api` 的 `ClusterComponentCatalog`；安装契约（chart、版本、values）在
`ComponentRecipeCatalog`；chart 本体在 `k8s-server/src/main/resources/charts/components/<chart>/<version>/chart/`。

| 组件 code | 文档 | 包含的 chart |
|---|---|---|
| `metrics-server` | [metrics-server.md](metrics-server.md) | metrics-server |
| `prometheus-operator` | [prometheus-operator.md](prometheus-operator.md) | kube-prometheus-stack（管理集群还追加 Thanos） |
| `tekton` | [tekton.md](tekton.md) | tekton-operator（同一个 release 跑两遍） |
| `otel-operator` | [otel-operator.md](otel-operator.md) | opentelemetry-operator（两遍）+ tempo（仅管理集群） |

> 其余组件（istio / calico / cert-manager / gateway-api 等）暂未单独成文；它们的 recipe 同样在
> `ComponentRecipeCatalog`，可照本目录的格式补。

---

## 1. 安装引擎的通用行为

所有组件都走同一条链路：**前端组件面板 → platform-api `POST /cluster/components/apply` →
k8s-server `POST /components/apply` → helm**。

### 1.1 异步与状态

- platform-api 先落一条 `platform_component_operation`（`PENDING`），虚拟线程里执行，前端轮询；
- 执行中 `RUNNING`，结束 `SUCCESS` / `FAILED`；
- 同一组件同时只允许一个操作：DB 生成列 `running_lock` 唯一键兜底并发；
- 进程重启/超时中断的 `RUNNING`，启动时超过 15 分钟标 `UNKNOWN`；
- 管理员可「中止操作」：只把平台侧记录置 `CANCELED`，**不 kill helm 进程**；
- **失败可重试**：`FAILED` / `UNKNOWN` 也算「平台装过」（`countAttempted`），不会把组件永久锁成外部安装。

### 1.2 预检与执行

k8s-server 对每个 release 依次：

1. `helm template`（客户端渲染）—— **硬门禁**，模板/values 错直接失败；
2. `helm upgrade --install --dry-run=server` —— **只记警告**。同一份 manifest 里同时有 CRD 和 CR 时
   （kube-prometheus-stack、TektonConfig、OTel CR）会报「CRD 尚不存在」的假阴性，不能当门禁；
3. `helm upgrade --install --rollback-on-failure --force-conflicts --timeout 10m`。

补充：

- 多个 release **顺序执行、失败即停**（Tekton / OTel 的两阶段就靠这个）；
- Helm 4 没有 `--atomic`，回滚用 `--rollback-on-failure`；
- `--force-conflicts` 用来接管手工 `helm install` 残留的 CRD（SSA 冲突）；
- 平台**不提供卸载**。卸载需手工 `helm uninstall`，且 Helm 不会删 CRD；CRD 残留不等于组件还在跑，
  组件状态以工作负载判据为准（见 `ClusterComponentCatalog` 注释）。

### 1.3 values 合并顺序

`recipe 可编辑默认值` < `上次成功操作的值快照` < `本次用户显式传值` < `契约值（🔒，不可改）`，深合并。

契约值写错的后果不是「装不上」，而是「装上了但平台功能用不了」，所以新增版本时**不得**改动契约值，
除非你确认新 chart 的语义已经变化（此时要同步改 recipe 和本文档）。

### 1.4 新增一个 chart 版本

1. 拉 chart（**不改模板**）：`helm pull <chart> --repo <repo> --version <x.y.z> --untar`；
2. 放到 `k8s-server/src/main/resources/charts/components/<chart>/<x.y.z>/chart/`，
   同目录写 `provenance.yaml`（source / chart / version / appVersion / fetched_at / fetch_command / note）；
3. 在 `ComponentRecipeCatalog` 对应组件的 `List<ReleaseSpec>` **最前面**加一条（列表按新 → 旧排序，
   前端默认选第一个）。release 名、namespace、契约 values 保持不变；可编辑默认值按需同步；
4. 两个 release 是同一 chart 两阶段的组件（Tekton / OTel），**两处版本必须一起改**；
5. 跑测试 + 在**非生产集群**真装一次（`helm template` 只能证明能渲染，证明不了能装上）；
6. 重建 **k8s-server**（镜像会把 charts 复制到 `/app/charts`）；platform-api 也要重建（recipe 在它这里）。

> 版本找不到时 `ChartBundle` **直接报错**，不会回退到别的版本 —— 防止 recipe 与镜像漂移后静默装错版本。

### 1.5 排障入口

- 组件状态：`platform-api` 日志 + `platform_component_operation.message / log_tail`；
- 集群侧：`helm list -n <ns>`、`kubectl -n <ns> get pods,deploy,sts`；
- 探测：`ClusterComponentCatalog` 里该组件的 `ComponentProbe`（namespace / kind / imageRepository / name）。
