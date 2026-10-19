# 组件 chart 目录与文档

平台通过组件安装引擎落地的 chart 都在这里。每个版本目录的结构：

```
charts/components/<chart>/<version>/
├── chart/            # 上游 chart，原样入库（不改模板）
├── provenance.yaml   # 来源、版本、拉取命令
└── README.md         # 本版本的平台文档（安装契约 / 使用 / 踩坑 / 新增版本）
```

组件的探测判据在 platform-api `ClusterComponentCatalog`，安装契约在 `ComponentRecipeCatalog`。
**文档与代码同目录**：改 recipe 时顺手改同一目录下的 README，避免两处漂移。

| 组件 code | 文档 |
|---|---|
| `metrics-server` | [metrics-server/3.14.0](metrics-server/3.14.0/README.md) |
| `prometheus-operator` | [prometheus-operator/92.10.1](prometheus-operator/92.10.1/README.md) |
| （Prometheus 组件的管理集群底座） | [thanos/0.47.2](thanos/0.47.2/README.md) |
| `tekton` | [tekton-operator/0.81.2](tekton-operator/0.81.2/README.md) |
| `istio`（**含 Gateway API**） | [istio-base/1.30.6](istio-base/1.30.6/README.md)（`gateway-api/1.6.2` 是它的第一个 release） |
| `otel-operator` | [otel-operator/0.126.0](otel-operator/0.126.0/README.md)、[tempo/3.2.0](tempo/3.2.0/README.md) |

> `gateway-api` 于 2026-10-19 并入 `istio` 组件（同一个安装单元），目录里不再有独立的 gateway-api 行。
> 其余组件（calico / cert-manager 等）暂未单独成文；recipe 同样在
> `ComponentRecipeCatalog`，可照本目录格式补。

---

## 1. 文档约定

- **每条「坑」尽量写清来源**：用户对话里报过的现象、集群实测输出、或单测钉住的行为。
  不要凭代码「看起来会怎样」下结论；没验证过的判断标成「未验证」。
- 契约值（🔒）写错的后果不是「装不上」，而是「装上了但平台功能用不了」—— 文档要写清**为什么**。
- 新增版本时，文档与 `provenance.yaml` 一起更新。

## 2. 安装引擎的通用行为

所有组件走同一条链路：**前端组件面板 → platform-api `POST /cluster/components/apply` →
k8s-server `POST /components/apply` → helm**。

### 2.1 异步与状态

- platform-api 先落一条 `platform_component_operation`（`PENDING`），虚拟线程执行，前端轮询；
- 执行中 `RUNNING`，结束 `SUCCESS` / `FAILED`；
- 同一组件同时只允许一个操作：DB 生成列 `running_lock` 唯一键兜底并发；
- 进程重启/超时中断的 `RUNNING`，启动时超过 15 分钟标 `UNKNOWN`；
- 管理员可「中止操作」：只把平台侧记录置 `CANCELED`，**不 kill helm 进程**；
- **失败可重试**：`FAILED` / `UNKNOWN` 也算「平台装过」（`countAttempted`），不会把组件永久锁成外部安装。
  这条是用户报「该组件不是平台安装的，平台不接管」之后补的。

### 2.2 预检与执行

k8s-server 对每个 release 依次：

1. `helm template`（客户端渲染）—— **硬门禁**；
2. `helm upgrade --install --dry-run=server` —— **只记警告**。同一份 manifest 同时有 CRD 和 CR 时
   （kube-prometheus-stack、TektonConfig、OTel CR）会报「CRD 尚不存在」的假阴性；
3. `helm upgrade --install --rollback-on-failure --force-conflicts --timeout 10m`。

- 多个 release **顺序执行、失败即停**（Tekton / OTel 的两阶段靠这个）；
- Helm 4 没有 `--atomic`，回滚用 `--rollback-on-failure`；
- `--force-conflicts` 接管手工 `helm install` 残留的 CRD；
- 平台**不提供卸载**；手工 `helm uninstall` 不删 CRD，CRD 残留不等于组件还在跑。

### 2.3 values 合并顺序

`recipe 可编辑默认值` < `上次成功操作的值快照` < `本次用户显式传值` < `契约值（🔒，不可改）`，深合并。

### 2.4 新增一个 chart 版本

1. `helm pull <chart> --repo <repo> --version <x.y.z> --untar`（**不改模板**）；
2. 放到 `charts/components/<chart>/<x.y.z>/chart/`，写 `provenance.yaml`；
3. 在 `ComponentRecipeCatalog` 对应组件的 `List<ReleaseSpec>` **最前面**加一条（新 → 旧排序）；
4. 两阶段的组件（Tekton / OTel）**两处版本一起改**；
5. 复制并更新本目录的 README；
6. 跑测试 + 在**非生产集群**真装一次；
7. 重建 **k8s-server**（charts 入镜像）+ **platform-api**（recipe）。

> 版本找不到时 `ChartBundle` **直接报错**，不会回退 —— 防止 recipe 与镜像漂移后静默装错版本。

### 2.5 排障入口

- 操作记录：`platform_component_operation.message / log_tail`（前端「上次操作」也能看 helm 日志）；
- 集群侧：`helm list -n <ns>`、`kubectl -n <ns> get pods,deploy,sts`；
- 探测：`ClusterComponentCatalog` 里该组件的 `ComponentProbe`。
