# 组件 chart 目录与文档

平台通过组件安装引擎落地的 chart 都在这里。**README 分两层**（2026-10-24 起）：

```
charts/components/<chart>/
├── README.md             # 组件级（跨版本）：怎么装、约定、坑、加版本 checklist
└── <version>/
    ├── chart/            # 上游 chart，原样入库（不改模板）
    ├── provenance.yaml   # 来源、版本、拉取命令
    └── README.md         # 版本级：来源 / digest / 本版本注意
```

组件的探测判据在 platform-api `ClusterComponentCatalog`，安装契约在 `ComponentRecipeCatalog`。
**文档与代码同目录**：改 recipe 时顺手改同一目录下的 README，避免两处漂移。

| 组件 code | 组件级文档 |
|---|---|
| `metrics-server` | [metrics-server/README.md](metrics-server/README.md) |
| `prometheus-operator` | [prometheus-operator/README.md](prometheus-operator/README.md)（管理集群追加 [thanos](thanos/README.md)） |
| `tekton` | [tekton-operator/README.md](tekton-operator/README.md) |
| `istio`（**含 Gateway API**） | [istio-base/README.md](istio-base/README.md)（六个 chart：`gateway-api` + 五个 Istio chart；各 chart 目录另有 chart 级 README） |
| `otel-operator` | [otel-operator/README.md](otel-operator/README.md)（MANAGEMENT 追加 [tempo](tempo/README.md)） |
| `calico` | [tigera-operator/README.md](tigera-operator/README.md)（CRD chart 见 [projectcalico-crds](projectcalico-crds/README.md)） |
| `cert-manager` | [cert-manager/README.md](cert-manager/README.md) |
| `seaweedfs`（对象存储） | [seaweedfs/README.md](seaweedfs/README.md) |
| `harbor`（镜像仓库） | [harbor/README.md](harbor/README.md) |

> `gateway-api` 于 2026-10-19 并入 `istio` 组件（同一个安装单元），没有独立组件行；
> 它的 chart 级说明在 `gateway-api/README.md`，组件总文档仍在 `istio-base/README.md`。

---

## 1. 文档约定

- **每条「坑」尽量写清来源**：用户对话里报过的现象、集群实测输出、或单测钉住的行为。
  不要凭代码「看起来会怎样」下结论；没验证过的判断标成「未验证」。
- 契约值（🔒）写错的后果不是「装不上」，而是「装上了但平台功能用不了」—— 文档要写清**为什么**。
- 新增版本时，文档与 `provenance.yaml` 一起更新。

## 2. 安装引擎的通用行为

所有组件走同一条链路：**前端组件面板 → platform-api `POST /cluster/components/apply` →
k8s-server `POST /components/apply` → helm**。

> ⚠️ 一个例外：release 标了 `applyMode=SERVER_SIDE_APPLY` 时（目前只有 calico 的 CRD chart），
> 渲染结果走 `kubectl apply --server-side` 直接落盘、**不建 Helm release**（Helm 的 release Secret 只有 1MiB，
> 装不下这个 3.5MB 的 chart）。细节见 [calico 组件文档](tigera-operator/README.md)。

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
3. `helm upgrade --install --force-conflicts --timeout 10m` + 一个失败开关：
   - release **已经成功部署过** → `--rollback-on-failure`（升级失败回滚到上一个成功版本）；
   - **首次安装** → `--wait`（失败**不卸载**，release 与已建对象留在集群里，重试就是 upgrade）。

- 多个 release **顺序执行、失败即停**（Tekton / OTel 的两阶段靠这个）；
- Helm 4 没有 `--atomic`；`--rollback-on-failure` 对 **install** 的语义是 uninstall，
  所以首次安装不用它 —— 否则装到一半的 PVC/Service 会被拆掉（带 `resource-policy: keep`
  的盘还得手工删了才能重试），2026-10-27 就这么撞过一次。
- `--force-conflicts` 接管手工 `helm install` 残留的 CRD；
- 平台**不提供卸载**；手工 `helm uninstall` 不删 CRD，CRD 残留不等于组件还在跑。

### 2.3 values 合并顺序

`recipe 可编辑默认值` < `上次成功操作的值快照` < `本次用户显式传值` < `契约值（🔒，不可改）`，深合并。

### 2.3.1 版本口径（2026-10-20）

一个组件可以是多个 chart 的安装单元（Istio 六个、管理集群的 Prometheus 两个、OTel 两个）。
版本必须分两层声明：

- **chart 版本**（`chartVersion`）与**应用版本**（`withAppVersion(...)`，取自 chart 的 `Chart.yaml`）
  是两个编号，每个 release 都要写；`ComponentRecipeCatalogTest` 会和内置 chart 的 `Chart.yaml` 交叉校验。
- 每个 recipe **恰好一个主 release**（`asPrimary()`）—— 组件本体。
  `availableVersions`、升级目标、操作记录的 `toVersion` 都只看主 release；
  随附 chart（Gateway API / Thanos / Tempo）的版本不参与比较。
- 组件页的「版本」列：应用版本来自集群探测，chart 版本来自上次成功操作；
  多 chart 组件额外给「N 个 chart」展开，逐个列出 chart 版本与应用版本。

> 2026-10-20 用户提出：Istio 的版本显示成了 Gateway API 的 1.6.2 —— 因为旧实现取「第一个 release」，
> 而合并后排第一的是 Gateway API。改成显式主 release 后，组件版本固定为 istiod 的 1.30.6。

### 2.4 新增一个 chart 版本

1. `helm pull <chart> --repo <repo> --version <x.y.z> --untar`（**不改模板**）；
2. 放到 `charts/components/<chart>/<x.y.z>/chart/`，写 `provenance.yaml`；
3. 在 `ComponentRecipeCatalog` 对应组件的 `List<ReleaseSpec>` **最前面**加新版本，
   并给新 release 标 `asPrimary()` + `withAppVersion(...)`（主 release 不一定是排第一的那个，
   通常它也是新版本的那一条）；
4. 两阶段的组件（Tekton / OTel）**两处版本一起改**；
5. 在**组件级** README 更新版本行，并在版本目录写 `<x.y.z>/README.md`（来源 / digest / 本版本注意）；
6. 跑测试 + 在**非生产集群**真装一次；
7. 重建 **k8s-server**（charts 入镜像）+ **platform-api**（recipe）。

> 版本找不到时 `ChartBundle` **直接报错**，不会回退 —— 防止 recipe 与镜像漂移后静默装错版本。

### 2.5 排障入口

- 操作记录：`platform_component_operation.message / log_tail`（前端「上次操作」也能看 helm 日志）；
- 集群侧：`helm list -n <ns>`、`kubectl -n <ns> get pods,deploy,sts`；
- 探测：`ClusterComponentCatalog` 里该组件的 `ComponentProbe`。
