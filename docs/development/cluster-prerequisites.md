# 纳管集群的集群侧前提（标准）

> 适用范围：**任何被平台纳管的 K8s 集群**。实施方：交付/运维（不是平台代码）。
> 起因：平台的**功能能不能跑**不只看代码 —— 集群里那些**不在仓库里**的配置（Tekton 的 feature flag、
> 命名空间的配额、存储的能力）会直接决定 CI、构建缓存等功能是否可用。换集群、重建集群就会重来一遍，
> 而症状往往长得像平台 bug（2026-10-15 的两次真跑就是这么被误判的）。
> 本文是**纳管时的核对清单**：能探测的（Tekton 在不在、有没有可动态供应的 StorageClass）
> 平台已经探测并在页面上给原因（`/pipeline/capability` 那条横幅）；**探测不了的以本文为准**。

---

## 1. 清单

### 1.1 Tekton `coschedule` = `pipelineruns`（构建缓存的**硬前提**）

**为什么**：构建缓存要给同一个 TaskRun 再挂一块长期 PVC（`platform-pipeline-cache`）。
而 `coschedule: workspaces`（Tekton 默认）下，**一个 TaskRun 不允许挂两个 PVC 类 workspace** ——
`pkg/reconciler/taskrun/taskrun.go` 会直接 `MarkResourceFailed`（判据函数 `ValidateOnlyOnePVCIsUsed`，
只在 `AffinityAssistantPerWorkspace` 下检查）。症状是运行一秒内失败、Pod 都没起来。

```bash
kubectl -n tekton-pipelines patch configmap feature-flags --type merge -p '{"data":{"coschedule":"pipelineruns"}}'
```

**⚠️ 值名**：Tekton 1.15.3 的合法值是 `workspaces` / **`pipelineruns`** / `isolate-pipelinerun` / `disabled`。
老文档里的 `pipeline` **已经改名**，填错会被 `config.webhook.pipeline.tekton.dev` 直接拒。

**代价**：亲和助手从「每个 workspace 一个」变成「每条 PipelineRun 一个」——
一次运行的所有 task 被钉到**同一个节点**。对共享 workspace 的 CI 来说正是想要的；
但同一集群上别的 Tekton 用法会一起受影响，纳管前要与集群属主确认。

### 1.2 配额要求 requests/limits 时，Tekton 要有默认容器资源

**为什么**：命名空间的 ResourceQuota 若含 `requests.cpu`/`limits.cpu`（或 memory），K8s 要求**每个容器**
都写 requests/limits。而 Tekton **自己的 init 容器**（`place-scripts`、`prepare`、
`working-dir-initializer`）平台侧设不了 → Pod 直接被拒：`forbidden: failed quota: ... must specify
limits.cpu for: place-scripts,prepare,step-xxx`。**光给用户的 step 加资源没用。**

```bash
kubectl -n tekton-pipelines patch configmap config-defaults --type merge -p \
  '{"data":{"default-container-resource-requirements":"default:\n  requests:\n    cpu: 100m\n    memory: 128Mi\n  limits:\n    cpu: \"1\"\n    memory: 1Gi\n"}}'
```

**数值别拍大**：命名空间配额按"所有 Pod 的容器求和"算，一个 Pod 有 5 个左右容器，
`limits.cpu` 拍 2 就会让两条并发运行反过来撞 `exceeded quota`。上面这组（5 容器 ≈ 5 CPU 上限）
在 8 CPU 配额的命名空间里能跑两条并发。

**单个节点想要更多，不用改这里**：预设构建节点的「高级 → 内存上限」（**只填数字，单位固定 Mi**，如 `2048`）
会渲染成**一整组**
容器资源 —— `requests {cpu: 100m, memory: 128Mi}` + `limits {cpu: 1, memory: <填的>}`。

- ⚠️ **四个字段必须一起写**：Tekton 的默认资源**只在容器完全没写 resources 时才填**，
  所以一旦写了 `limits.memory`，Tekton 一个字都不补 —— 缺 `requests.cpu`/`limits.cpu`
  就被配额拒（`must specify limits.cpu for: step-xxx`）。那三个兜底值抄的就是上面这组。
- ⚠️ 改这里那份默认**不会**同步到平台侧那三个兜底值（各写各的），别把它们当成同一个真相。
- ⚠️ 典型场景：1Gi 的容器里 Node 的默认堆约 512MB，`vue-tsc` 会 `Reached heap limit`
  （2026-10-16 真撞到）。只加 `NODE_OPTIONS=--max-old-space-size` 没用 —— 会从"撞 Node 的堆上限"
  变成"被 cgroup 杀掉"（exit 137），**必须同时把容器上限提上去**。
- ⚠️ 提之前看配额还剩多少：`kubectl -n <ns> get resourcequota`（按"所有容器求和"算，一个构建 Pod 约 3 个容器）。

### 1.3 存储：可动态供应的 StorageClass；构建缓存另需 **RWX**

- **CI 能跑** 需要至少一个能动态供应的 StorageClass（共享 workspace 是现开的 PVC）——
  这一条**已被平台能力探测覆盖**，不可用时页面上会直说。
- **构建缓存能开** 还需要**共享卷（ReadWriteMany）**：缓存要给多条运行同时挂。
  平台一律按 RWX 建缓存卷；集群只支持 RWO 时那块卷会一直 Pending →
  **平台自动关掉缓存**（退化成每次运行自己的空目录，照样能跑、只是每次重下依赖），不会让运行卡住。

### 1.4 出网：Pod 需要能到"要用的地方"

构建发生在 Pod 里，而不是在节点上 —— **节点能拉镜像不等于 Pod 能下载依赖**（这台集群上就是：
节点经 docker 的 aliyun 镜像拉得到 docker.io，Pod 里却解析到被污染的地址）。2026-10-15 实测：

| 目标 | Pod 里 | 说明 |
|---|---|---|
| Maven Central / npm / PyPI | ✅ 通 | Java / Node / Python 构建的依赖没问题 |
| `proxy.golang.org` | ❌ 不通 | Go 构建必须换 `GOPROXY`（如 `https://goproxy.cn,direct`） |
| `registry-1.docker.io` / `auth.docker.io` | ❌ 不通 | 镜像节点的 base image 拉取、push 到 Docker Hub 都受影响 |
| GitHub 大文件 | ⚠️ 会在 ~10MiB 处卡死 | `git clone` 小仓库能过，大仓库要代理 |

对策：节点 schema 里带**代理**字段（git 克隆 / 构建并推送镜像两个节点的高级区），
或集群级给 Tekton 注入 `default-pod-template` 的 `HTTPS_PROXY`；目标仓库优先用**集群内可达的 Harbor**。

### 1.5 构建镜像**不需要** fuse / privileged（平台侧已在脚本里绕开）

2026-10-16 真跑撞到：镜像节点在 `FROM` 之后立刻挂掉，日志是

```
using mount program /usr/bin/fuse-overlayfs: unknown argument ignored: lazytime
fuse: device not found, try 'modprobe fuse' first
fuse-overlayfs: cannot mount: No such file or directory
Error: mounting new container: … exit status 1
```

原因两条，都在容器本身、与集群配置无关：

- **容器里没有 `/dev/fuse`**。k8s 给每个容器的是最小 `/dev`，只有
  `null / zero / full / tty / random / urandom`（本集群里 `ls /dev` 实测过）。
  而 buildah 镜像默认就是**照无根（rootless）用法做的** —— overlay 存储驱动挂
  `fuse-overlayfs`，于是哪怕进程是 root 也照样去要 `/dev/fuse`。
- **容器没有 `CAP_SYS_ADMIN`**（默认能力集 14 个里没有它），所以换成内核 overlay 驱动
  也一样 `mount(2)` EPERM。

平台的对策是**改脚本里的两个环境变量**，不动集群：

| 变量 | 值 | 换来什么 |
|---|---|---|
| `STORAGE_DRIVER` | `vfs` | vfs 驱动的"挂载"是空操作（返回目录本身），不需要 fuse、也不需要 `mount(2)` |
| `BUILDAH_ISOLATION` | `chroot` | 不起 runc → 不建命名空间 → 不需要 `CAP_SYS_ADMIN`；`CAP_SYS_CHROOT` 在默认能力集里 |

- 代价：vfs **每一层都是整棵树的拷贝**（overlay 是写时复制）。构建会慢一些、临时盘吃得多一些，
  所以**别把构建 Pod 的 1.2 那档资源/配额卡得太死**（节点上有 40G+ 富余就够）。
- 有意**不做**"加 privileged + hostPath 挂 /dev/fuse"那条路：那要同时把 Tekton 的
  `set-security-context` 从 `false` 打开（否则 Tekton 会把 step 的 `securityContext` 整个丢掉）、
  给到 privileged、还得给 Pod 塞 `podTemplate` —— 为一个构建节点把整个集群的隔离打开，不划算。

### 1.6 Dockerfile 里的**短名**按 Docker 的规矩解析（平台侧已钉死）

2026-10-16 真跑撞到：同一个 Dockerfile，`FROM node:20-alpine` 过、`FROM maven:3.9-eclipse-temurin-21` 挂：

```
Error: creating build container: short-name resolution enforced but cannot prompt without a TTY
```

短名解析是**两段**：先查 alias 表（命中且只有一个候选就直接用），查不到才退到
`unqualified-search-registries`。差别就在日志里 —— 命中 alias 的会多一行
`Resolved "node" as an alias (/etc/containers/registries.conf.d/000-shortnames.conf)`。

buildah 镜像（Fedora 底）那张表里有**不止一个**候选源（fedora / redhat / docker.io …），
所以没命中 alias 的名字会去**交互式问人选哪个** —— 构建里没有 TTY，直接报错。

平台的对策：把候选源**钉死成 `docker.io`**（主文件 + 编号最大的 drop-in 两处都写，
不管合并顺序如何都生效），也就是

| 你写的 | 解析成 |
|---|---|
| `maven:3.9-eclipse-temurin-21` | `docker.io/library/maven:3.9-eclipse-temurin-21` |
| `me/app` | `docker.io/me/app` |
| `harbor.example.com/proj/app` | 原样（写全了就不经过这一步） |

**要别的仓库就把名字写全** —— 这与 Docker / docker-compose 的规矩一致，写 Dockerfile 的人本来就是这么预期的。

### 1.7 服务网格的指标要**自己接上**（S2.4 的前置；2026-10-17 已在 coding-cluster 做完）

Istio **不会**自动把指标送进你的 Prometheus：代理各自暴露 `/metrics`，但没人去抓就没有。
2026-10-17 实测：`kube-prometheus-stack` 的 14 个 ServiceMonitor **全是标准组件**（apiserver /
coredns / kubelet / node-exporter…），网格一条都没有 → Thanos 的 2279 个指标名里，
`istio`/`ztunnel`/`waypoint`/`envoy` 命中 **全是 0**。

补上这五个对象即可。以 istiod 那条为模板（`release: ps` 是 kube-prometheus-stack 认的 label）：

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata: { name: istio-istiod, namespace: istio-system, labels: { release: ps } }
spec:
  namespaceSelector: { matchNames: [istio-system] }
  selector: { matchLabels: { app: istiod } }
  endpoints: [{ port: http-monitoring, path: /metrics, interval: 30s }]
```

其余四条**同结构**，只换这几处（端口名都取自各自 Pod/Service 的 spec，不是猜的）：

| 对象 | kind | selector（matchLabels） | 端口名 | 路径 |
|---|---|---|---|---|
| `istio-ztunnel` | PodMonitor | `app: ztunnel` | `ztunnel-stats` | `/metrics` |
| `istio-cni` | PodMonitor | `k8s-app: istio-cni-node` | `metrics` | `/metrics` |
| `istio-external-gateway` | PodMonitor | `gateway.networking.k8s.io/gateway-name: external-gateway` | `metrics` | `/stats/prometheus` |
| `istio-waypoint` | PodMonitor | `gateway.istio.io/managed: istio.io-mesh-controller` | `metrics` | `/stats/prometheus` |

（后两条用 `podMetricsEndpoints` 而不是 `endpoints`。**waypoint 那条的
`namespaceSelector` 必须写 `{ any: true }`** —— 见下面第 1 个坑。
要现成的整份，直接从集群 dump：`kubectl get servicemonitors,podmonitors -A -l release=ps -o yaml`。）

⚠️ **三个坑，都踩过**：

1. **`namespaceSelector: {}` 不是"所有命名空间"，是"本命名空间"**。给 waypoint 那条这么写，
   operator 会生成 `names: [istio-system]`，而 waypoint 的 pod 在 `default` → **0 个目标**，
   而且不报错。要写 `any: true`（prometheus-operator ≥ v0.64；本集群是 v0.93.1）。
2. **Label 必须是 `release: ps`**，否则 Prometheus 的 `serviceMonitorSelector/podMonitorSelector` 根本看不见它。
3. **抓到了 ≠ 有数据**。网格基础设施自己只吐 xDS / 进程指标；**L7（`istio_requests_total`）
   只有 Envoy 类代理产出** —— 即**外部网关**（南北向）和 **waypoint**（东西向）。
   2026-10-17 实测：抓取配好后 ztunnel/waypoint 全 up，但业务一条都没有 ——
   因为**没有任何负载被纳入网格**（所有 ns/pod 都是 `dataplane-mode: none`）。

**把负载纳入网格**（会影响流量路径，改前先想清楚动谁）：

```bash
# ① 进 ambient：给 **pod 模板** 打标记（会触发滚动重启，标记必须建 pod 时就带上）
kubectl -n <ns> patch deploy <name> --type merge \
  -p '{"spec":{"template":{"metadata":{"labels":{"istio.io/dataplane-mode":"ambient"}}}}}'
# ② 让入向流量走 waypoint（**不加这步只有 L4**，没有 istio_requests_total）
kubectl -n <ns> label svc <svc> istio.io/use-waypoint=<waypoint 名>
```

**验证**（做完应当看到边，而不只是指标名）：

```bash
curl -sG "$THANOS/api/v1/query" --data-urlencode \
  'query=sum by (source_workload,destination_service_name) (istio_requests_total)'
```

本集群做完之后：Thanos 里 `istio_*` 指标名 **0 → 76**，可查到的边 5 条
（外部网关 → minio / movie-web / thanos-query / prometheus，以及 movie-web → movie-service）。
被纳入网格的是 `default` 里的 movie 前后端，**mysql / es / redis 有意没动**。

---

## 2. 一条命令核对

```bash
kubectl -n tekton-pipelines get cm feature-flags -o jsonpath='{.data.coschedule}{"\n"}' \
  && kubectl -n tekton-pipelines get cm config-defaults -o jsonpath='{.data.default-container-resource-requirements}{"\n"}' \
  && kubectl get sc \
  && kubectl get servicemonitors,podmonitors -A | grep -c istio
```

期望：第一行 `pipelineruns`（或一条明确的例外说明）；第二行有内容（**当且仅当**目标命名空间有
1.2 那类配额）；第三行有能动态供应的 SC；第四行 **≥ 5**（§1.7 那五个网格采集对象都在）。

## 3. 变更记录

| 日期 | 集群 | 改动 | 为什么 |
|---|---|---|---|
| 2026-10-15 | coding-cluster（研发） | `config-defaults` 加 `default-container-resource-requirements` | 配额逼的：Tekton init 容器平台侧设不了，CI 一秒内失败且没有日志 |
| 2026-10-15 | coding-cluster（研发） | `feature-flags` 的 `coschedule`: `workspaces` → `pipelineruns` | 构建缓存要两个 PVC workspace |
| 2026-10-17 | coding-cluster（研发） | 加 5 个 ServiceMonitor/PodMonitor（istiod / ztunnel / istio-cni / waypoint / external-gateway），全部 `release: ps` | 网格指标以前**一条都没人抓**（Thanos 里 `istio_*` = 0），S2.4 拓扑图没米下锅 |
| 2026-10-17 | coding-cluster（研发） | `default`：movie 前后端 pod 模板打 `istio.io/dataplane-mode: ambient`，`movie-service` 打 `istio.io/use-waypoint: waypoint-all` | 光抓不够 —— 没有负载在网格里，抓到的只有基础设施自身的 xDS 指标，**没有 L7**。mysql/es/redis 有意没动 |

**这些改动只存在于集群里、不在仓库里** —— 换集群/重建集群时必须重做（§1.7 里有可直接粘贴的清单）。
