# 镜像来源纳管 · 运维手册

> 2026-10-28。设计：[specs/2026-10-28-image-registry-mirror-design.md](../superpowers/specs/2026-10-28-image-registry-mirror-design.md)
> 实施计划：[plans/2026-10-28-image-registry-mirror.md](../superpowers/plans/2026-10-28-image-registry-mirror.md)

## 1. 这套东西是什么

组件、中间件（含 Operator）用到的镜像**改从平台 Harbor 的 `platform` 项目拉**，而不是公网。
表单里可选「平台 Harbor（默认）/ 官方」；切回官方 = 行为完全回到纳管之前。

## 2. 涉及的地址与凭据

| 项 | 值 |
|---|---|
| Harbor 入口 | `https://harbor.ackcp.cloud`（DigiCert 证书，2027-01-25 到期） |
| 镜像前缀 | `harbor.ackcp.cloud/platform/<上游主机>/<仓库路径>:<tag>` |
| 拉取 | **项目已设公开读**（匿名可 pull），节点不需要 imagePullSecret |
| 推送 | Harbor 的复制规则（用管理凭据）；租户项目仍是私有 + 各自 robot |

本集群 `platform` 项目 id=2，`public=true`（2026-10-28 已切）。

## 3. Harbor 侧：把镜像搬进来（复制规则）

平台**不搬运镜像**：k8s-server 只改写地址，镜像得先在 Harbor 里存在。搬运靠 Harbor 的
**Replication（复制）规则**，一条规则对一个上游。

### 3.1 规则怎么配

Harbor → `Administration → Replications → New Replication Rule`：

| 字段 | 值 |
|---|---|
| Name | `mirror-dockerio` / `mirror-quay` / … |
| Replication mode | **Pull-based**（Harbor 主动去上游拉） |
| Source registry | 该上游的 endpoint（下表） |
| Destination namespace | **上游主机名**（`docker.io` / `quay.io` / `ghcr.io` / `registry.k8s.io` / `docker.elastic.co` / `registry.istio.io`） |
| Source resource filter | `Name` 填仓库路径，`Tag` 留空（`*`） |
| Trigger mode | `Manual`（首次全量按需点）+ `Scheduled`（每日增量） |
| Bandwidth / Override | 默认；勾 **Override**，否则上游重新打 tag 时不会更新 |

Endpoint 地址：

| 上游 | Endpoint URL |
|---|---|
| docker.io | `https://registry-1.docker.io` |
| quay.io | `https://quay.io` |
| ghcr.io | `https://ghcr.io` |
| registry.k8s.io | `https://registry.k8s.io` |
| docker.elastic.co | `https://docker.elastic.co` |
| registry.istio.io | `https://registry.istio.io` |

> **Destination namespace 必须与改写规则一致**：平台把 `quay.io/opstree/redis` 改写成
> `harbor.ackcp.cloud/platform/quay.io/opstree/redis`，所以复制目标的 namespace 就是 `quay.io`。
> 私有上游（docker.io 的 library 镜像）需要 Harbor 管理员为 docker.io 配一个只读 robot 或允许匿名
> —— 匿名拉 Docker Hub 有速率限制，正式环境建议配账号。

### 3.2 基础集：需要复制到 `platform` 的仓库

下面是**内置 chart 默认值 + 中间件实例**真正会拉的仓库（平台改写后都在 `platform/<上游主机>/…`）。

**Kubernetes / Istio / 监控栈**

| 上游仓库 | 用途 |
|---|---|
| `registry.k8s.io/metrics-server/metrics-server` | metrics-server |
| `registry.k8s.io/kube-state-metrics/kube-state-metrics` | kube-state-metrics |
| `quay.io/prometheus-operator/prometheus-operator` | Prometheus Operator |
| `quay.io/prometheus/prometheus` | Prometheus |
| `quay.io/prometheus/alertmanager` | Alertmanager |
| `quay.io/prometheus/node-exporter` | node-exporter |
| `quay.io/kiwigrid/k8s-sidecar` | Grafana 面板 sidecar |
| `docker.io/grafana/grafana` | Grafana |
| `docker.io/bats/bats` | Grafana 测试钩子（可留） |
| `quay.io/thanos/thanos` | Thanos |
| `docker.io/library/busybox` | 各处 init 容器 |

**组件**

| 上游仓库 | 用途 |
|---|---|
| `docker.io/istio/pilot`、`docker.io/istio/install-cni`、`docker.io/istio/ztunnel` | istiod / CNI / ztunnel（1.31.x） |
| `registry.istio.io/release/pilot`、`.../install-cni`、`.../ztunnel` | 旧的 1.30.6 三件套 |
| `quay.io/jetstack/cert-manager-controller`、`-cainjector`、`-webhook`、`-startupapicheck` | cert-manager |
| `quay.io/tigera/operator` | Calico |
| `quay.io/strimzi/operator` + `quay.io/strimzi/kafka` | Strimzi（operator 与 broker） |
| `docker.io/chrislusf/seaweedfs` | 对象存储 |
| `ghcr.io/open-telemetry/opentelemetry-operator/opentelemetry-operator` | otel operator |
| `docker.io/grafana/tempo` | Tempo |
| `ghcr.io/tektoncd/operator/operator-303303c315a48490ba6517859ef65b77`、`.../webhook-f2bb711aa8f0c0892856a4cbf6d9ddd8` | Tekton（名字带内容哈希，随 chart 版本变） |
| `ghcr.io/jkroepke/kube-webhook-certgen` | Prometheus Operator 的证书 Job |

**中间件（Operator + 实例）**

| 上游仓库 | 用途 |
|---|---|
| `quay.io/opstree/redis-operator`、`quay.io/opstree/redis`、`-redis-sentinel`、`-redis-exporter` | Redis |
| `percona/percona-server-mysql-operator`、`percona/percona-server:8.4`、`percona/percona-xtradb-cluster-operator:1.14.1-haproxy`、`prom/mysqld-exporter` | MySQL |
| `docker.elastic.co/eck/eck-operator`、`docker.elastic.co/elasticsearch/elasticsearch`、`quay.io/prometheuscommunity/elasticsearch-exporter` | Elasticsearch（含 IK 的 init 容器） |
| `ghcr.io/cloudnative-pg/cloudnative-pg` | PostgreSQL Operator |
| `milvusdb/milvus-operator`、`milvusdb/milvus` | Milvus |
| `docker.io/bitnamilegacy/rabbitmq-cluster-operator`、`docker.io/bitnamilegacy/rmq-default-credential-updater`、`docker.io/library/rabbitmq` | RabbitMQ（见下方注意） |
| `nacos/nacos-server` | Nacos（已暂缓，chart 仍在） |

> **RabbitMQ 用的是 `bitnamilegacy`**：Bitnami 2025 起把历史 tag 挪到了 `bitnamilegacy/*`，
> 原来的 `bitnami/*` 已 `manifest unknown`。这条覆盖写在
> `MiddlewareOperatorCatalog` 里，复制规则要照着它配。

### 3.3 不受这层改写管的三处

| 位置 | 原因 | 处理 |
|---|---|---|
| CNPG 的 **PG 实例镜像**（`ghcr.io/cloudnative-pg/postgresql:<major>`） | 适配器有意不写 `imageName`，由 Operator 内置默认决定 | 想断网装：在 Harbor 里留同名路径，或在适配器里补 `spec.imageName` |
| Milvus 的 **inCluster etcd 镜像** | 由 milvus-operator 内置 chart 决定，values 里没有 | 同上 |
| **平台自身 4 个镜像** | 不在本仓库 | 见 §7 |

### 3.4 首次全量 + 核对

1. 按 §3.2 建好规则后，在 `Replications` 里对每条规则点 **REPLICATE**（或建一条 `Manual` 触发的批量任务）。
2. 核对：`GET /v2/<repo>/manifests/<tag>`（用 Harbor 凭据）返回 200；或直接看 `Projects → platform → Repositories` 的仓库数。
3. 平台页面上的「镜像清单」抽屉（组件页 → 镜像清单）列的是**当前内置 chart 能识别的上游镜像**，
   可以用来生成过滤器的 Name 列表；它与改写共用同一套规则，但看不到「Operator 运行时才决定」的那两处（见 §3.3）。

### 3.5 不想用复制规则

也可以 `docker pull` → `docker tag` → `docker push` 到 `harbor.ackcp.cloud/platform/<上游主机>/<仓库>`，
脚本化时注意路径里**保留上游主机名**（`docker.io` 也要带上，`library/` 单段仓库要补全）。


## 4. 平台配置

| 配置 | 位置 | 说明 |
|---|---|---|
| `platform.image-registry.host` | platform-api `application.yaml` / 环境变量 | 默认镜像仓库（本集群可留空，平台会取 Harbor 安装记录） |
| `platform.image-registry.project` | 同上 | 默认 `platform` |
| `platform.image-registry.enabled` | 同上 | `false` = 全局关闭改写（回退开关） |
| `k8s_cluster.image_registry_host/project` | 集群设置 | 按集群覆盖（业务集群连不到平台 Harbor 时用） |

地址解析顺序：**集群列 → Harbor 组件安装记录 → 平台默认**；三者都没有 → 视为未配置，不改写。

## 5. 切换与回退

- 组件安装 / 中间件创建表单里的「镜像来源」：`PLATFORM`（默认）/ `OFFICIAL`。
- 切回官方：表单选 OFFICIAL 重放一次即可（**不需要**回滚代码）。
- 全局回退：`platform.image-registry.enabled=false`。
- Harbor 本身的镜像**永远走官方**（先有鸡还是先有蛋）：装 Harbor 组件时即使选了平台 Harbor 也不改写。

## 6. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| Pod `ImagePullBackOff` + `manifest unknown` | 镜像还没被复制到 Harbor | 按 §3 触发复制 |
| Pod `x509: certificate signed by unknown authority` | Harbor 证书链不完整（缺中间证书） | 重新下发完整 PEM（`tls.crt` 含中间证书） |
| Pod `x509: certificate has expired` | Harbor 证书到期 | 见 [续期方案](../superpowers/specs/2026-10-28-harbor-tls-renewal-design.md) |
| `no such host: harbor.ackcp.cloud` | 节点解析不到该域名 | 内网 DNS / 节点 hosts 加记录（**containerd 用节点解析器，不是 CoreDNS**） |
| 装了组件但镜像仍走公网 | 该 chart 的 image 字段不在识别口径里 | 跑 `ChartImageRenderGuardTest`（本机有 helm 才会执行），它会渲染出具体是哪一份 chart 的哪个镜像还指向上游 |

## 7. 平台自身镜像

platform-api / k8s-server / platform-web / platform-auth 的 Deployment 不在本仓库里，平台只提供清单。
**顺序很重要**：先把这 4 个镜像推到 `platform/<上游主机>/...`，再改部署清单的 image 与
`imagePullSecrets`（若项目仍是私有）——否则 k8s-server 起不来，连"下发 Secret"的机会都没有。

## 8. 改写覆盖到哪一层

镜像在 chart 里的写法并不统一，`ImageRewriter` 认这几种：

| 写法 | 例子 | 改写结果 |
|---|---|---|
| 整串 | `image: quay.io/opstree/redis:v7.0.15` | `image` 换成 Harbor 地址 |
| `registry + repository + tag` | `image: {registry: docker.io, repository: grafana/grafana}` | `registry = <harbor>/<project>`、`repository = docker.io/grafana/grafana` |
| `registry + image`（兄弟键） | tigera 的 `{registry: quay.io, image: tigera/operator}` | 两个键一起改，拼出来才是 Harbor 地址 |
| `imageRegistry + imageNamespace` | cert-manager | `imageRegistry = <harbor>/<project>`、`imageNamespace = quay.io/jetstack` |
| `defaultImageRegistry + defaultImageRepository` | Strimzi（operator 与 broker 都走它） | 同上 |
| `hub` | Istio 的 `global.hub` | 整串改写 |
| 模板里拼死的 | tekton 的 operator/webhook 镜像名 | 靠 `ChartImagePins` 的兜底表 |

另外两条容易漏的：

- **子 chart 也要改**：helm 只用父 values 覆盖子 chart 的默认值，父 values 里没写的镜像仍然来自
  `chart/charts/<sub>/values.yaml`。`ChartImageOverlay` 会遍历所有子 chart，按父 `Chart.yaml`
  依赖里的 `alias` 挂载（grafana / kube-state-metrics / node-exporter 就在这里）。
- **bitnami 系的镜像校验**：替换镜像后 chart 会拒绝渲染（`allowInsecureImages`），
  overlay 里会自动关掉这个开关。

护栏是 `ChartImageRenderGuardTest`：对每份内置 chart 真跑一次 `helm template`（叠上 overlay），
断言渲染出来的每个 `image:` 都在 Harbor 上。它需要本机有 helm，没有则跳过——所以它在开发机/流水线
里跑才有意义，在容器里跑会静默跳过。

