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
| 镜像前缀 | `harbor.ackcp.cloud/<上游主机>/<仓库路径>:<tag>`（**一个上游一个项目**，项目名就是上游主机） |
| 拉取 | **项目已设公开读**（匿名可 pull），节点不需要 imagePullSecret |
| 推送 | Harbor 的复制规则（用管理凭据）；租户项目仍是私有 + 各自 robot |

本集群 `platform` 项目 id=2，`public=true`（2026-10-28 已切）。

## 3. Harbor 侧：把镜像搬进来（复制规则）

平台**不搬运镜像**：k8s-server 只改写地址，镜像得先在 Harbor 里存在。搬运靠 Harbor 的
**Replication（复制）规则**。

> ⚠️ 一条规则只能管**一个仓库 + 一个 tag**：Harbor 的资源过滤器是 **AND** 语义（tag 也一样）—— 一条策略里放两个
> name 过滤器就什么都匹配不到（2026-10-28 实测：2 个 name → 0 个任务；`opstree/*` 通配也不匹配）。
> 所以「一条规则一个上游」不成立，规则数是仓库数量级（当前 46 条）。

### 3.0 一键脚本（推荐）

```bash
export HARBOR_URL=http://192.168.31.80:30002
export HARBOR_ADMIN_PASSWORD='...'   # 平台生成，见 Secret harbor/platform-harbor-secrets
python3 scripts/harbor-replication.py --all            # 项目 + endpoint + 规则（幂等）
python3 scripts/harbor-replication.py --dry-run        # 只看会做什么
python3 scripts/harbor-replication.py --only quay.io --trigger
python3 scripts/harbor-replication.py --status         # 最近的复制执行结果
```

**Docker Hub 限流**：匿名拉取按**出口 IP** 限流（实测 `x-ratelimit-limit: 100;w=3600`），
一批把十几条规则全触发会瞬间打满，表现是执行 `Failed` 但**零任务**（枚举 tag 那步就被挡）。
两种对策，通常一起用：

```bash
# 1) 给 docker.io 的 endpoint 配账号（PAT 只走环境变量，不进脚本）
export DOCKERHUB_USER=xxx DOCKERHUB_PAT=xxx
# 2) 串行触发：一条跑完再下一条
python3 scripts/harbor-replication.py --only docker.io --all --trigger --sequential
```

`--sequential` 会轮询每条直到 `Succeed/Failed/Stopped` 再继续，适合限额紧张时慢跑。

**偶发 EOF 也会让整条失败**：经出网代理连上游时，Harbor 偶尔拿到
`failed to list artifacts ... : EOF` 或者复制到一半断掉（执行记录里是 `Failed`、
任务数是 0，或者只有部分 platform 的 manifest 落了但没打 tag）。**直接重触发一次**即可，
不用改配置（2026-10-29 `calico/third-party-cni-plugins` 前两次都这样，第三次成功）。

脚本做四件事，都幂等（已存在就跳过/更新，不重复建）：

1. 建 6 个上游项目并设**公开读**；
2. 建 6 个上游 registry endpoint（同名或同 URL 已有就复用）；
3. 按「一个仓库一条规则」建复制策略：
   `dest_namespace = 上游主机`、`dest_namespace_replace_count = 0`、tag 过滤器 = 表里列的版本；
4. `--trigger` 时立刻触发一次复制。

**维护（加版本 / 加组件时）**：改脚本顶部的 `IMAGES` 表 —— 新增一行 `仓库:tag`，
或给已有仓库补一个 tag，然后重跑 `--all`；只会新增缺失的那条规则。

```python
IMAGES = {
    'quay.io': [
        'opstree/redis:v7.0.15',
        'strimzi/operator:1.3.0',
        # 新版本 → 加一行（或改成新 tag，旧规则会被更新）
        'strimzi/operator:1.4.0',
    ],
    ...
}
```

写 tag 的几条规矩：

- **必须写 tag**：不写就复制该仓库的全部 tag（几百个、几十 GB）；
- 中间件允许创建新版本（ES / Milvus）时，把新版本加一行 —— 脚本按 tag 精确复制；
- **一个 tag 一条规则**：tag 过滤器也是 AND，两个 tag 放同一条策略里匹配不到任何东西
  （2026-10-29 实测：`library/golang` 带 `1.22` + `1.23-alpine` → 任务 0 item；只留一个就正常）。
  脚本按 tag 数自动拆成 `-t1`、`-t2` … 多条规则；
- 规则名 = `mirror-<上游>-<仓库>-tN`，删规则/endpoint 脚本**不做**（避免误删），要删在 Harbor UI 里删。
- **删规则前先停掉没结束的执行**，否则 Harbor 报 `contains executions that aren't in final status`。
  接口是 `PUT /api/v2.0/replication/executions/<id>` + `{"status":"Stopped"}`（**PATCH 会 405**）。

### 3.1 规则怎么配（手工等价操作）

Harbor → `Administration → Replications → New Replication Rule`：

| 字段 | 值 |
|---|---|
| Name | `mirror-dockerio` / `mirror-quay` / … |
| Replication mode | **Pull-based**（Harbor 主动去上游拉） |
| Source registry | 该上游的 endpoint（下表） |
| Destination namespace | **上游主机名**（`docker.io` / `quay.io` / `ghcr.io` / `registry.k8s.io` / `docker.elastic.co` / `registry.istio.io`）—— 也就是目标项目名 |
| `dest_namespace_replace_count` | **必须设 0**。默认的 `-1`（legacy）会把源路径的第一段换成项目名：`opstree/redis-operator` → `quay.io/redis-operator`，丢掉 `opstree` |
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

> `registry.istio.io` 不在列表里：Istio 1.31.3 起 chart 的 `hub` 改成 `docker.io/istio`
> （registry.istio.io 只有 1.30.x）。内置 chart 里的 1.30.6 目录**已于 2026-10-29 删除**
> （没有 recipe 引用，留着只会让渲染护栏报一堆指向不存在仓库的镜像）。
> 真要支持 1.30.6，先把 chart 目录加回来，再给上游建 endpoint（Harbor 得能连上）并补规则。

> **落点必须与改写规则一致**：平台把 `quay.io/opstree/redis` 改写成
> `harbor.ackcp.cloud/quay.io/opstree/redis`，所以目标项目就是 `quay.io`，且
> `replace_count=0` 才能保留完整的 `opstree/redis`（2026-10-28 在真 Harbor 上验过：`-1` 会得到
> `quay.io/redis-operator`，`0` 才是 `quay.io/opstree/redis-operator`）。
> 私有上游（docker.io 的 library 镜像）需要 Harbor 管理员为 docker.io 配一个只读 robot 或允许匿名
> —— 匿名拉 Docker Hub 有速率限制，正式环境建议配账号。

### 3.2 基础集：需要复制到 `platform` 的仓库

下面是**内置 chart 默认值 + 中间件实例**真正会拉的仓库（平台改写后都在 `<上游主机>/…` 项目里）。
**这张表与 `scripts/harbor-replication.py` 的 `IMAGES` 是同一份**：动手时改脚本那张表（执行用的权威表），
这里跟着同步。

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
| `docker.io/istio/pilot`、`docker.io/istio/install-cni`、`docker.io/istio/ztunnel`、`docker.io/istio/proxyv2` | istiod / CNI / ztunnel / 网关与注入的 sidecar（1.31.x）。**proxyv2 容易漏**：gateway 的 Deployment 写的是 `image: auto`，由 istiod 按 `global.hub` 拼成 `<hub>/proxyv2:<tag>` |
| `quay.io/jetstack/cert-manager-controller`、`-cainjector`、`-webhook`、`-startupapicheck` | cert-manager |
| `quay.io/tigera/operator` | Calico operator —— **也不改写，走官方**（整份 chart 跳过，见下方） |
| `quay.io/calico/calico`、`quay.io/calico/node`、`quay.io/calico/third-party-cni-plugins` | Calico **工作负载** —— **有意不纳管，固定走上游**（见下方） |
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

> **calico 的工作负载镜像有意不纳管，固定走上游 quay.io。**
>
> 它们的镜像由 tigera-operator **运行时**决定（chart 里没有，`helm template` 看不到，
> `kubectl get imagesets` 也是空的；v3.33.0 实际只有 `calico/calico`、`calico/node`、
> `calico/third-party-cni-plugins` 三个，cni/typha/csi/kube-controllers 并进了 `calico` 镜像）。
> 要整体换前缀只能写 `Installation.spec.registry`。
>
> **2026-10-29 试过，结论是不要**：改 Installation → operator 把 calico-node / typha /
> kube-controllers 一次性滚动重建 → calico-node 一停 CNI 就断，其余 Pod 的沙箱全卡住
> （kubelet 反复重试 CNI DEL）→ 新来的 tigera-operator Pod 自己也要 CNI，起不来 →
> `helm --wait` 900s 超时 → 平台按既有行为 `--rollback-on-failure` 回滚，改动被原样撤回。
>
> 本质是**自举问题**：换 CNI 的镜像，得先有 CNI。跟 Harbor 组件本身永远走官方是同一个道理。
> 所以 **tigera-operator 整份 chart 被直接跳过**（`ChartImagePins.skipRewrite`）：`Installation.spec.registry`
> 不注入，连 chart 里那个 operator 的 Deployment 镜像也保持官方，`IMAGES` 表里也没有它的条目。
> **安装表单里也不再给「平台 Harbor」这个选项**（`ComponentStatusDTO.imageMirroring=false`）——
> 判据只有一处：`platform-common` 的 `ImageMirroring`，三边（k8s-server 改写 / platform-api 传参 /
> 前端表单）都读它。
> （后续交付方向是**离线部署 calico**，届时另行规划。）

> **流水线预设节点的默认镜像也指向 Harbor**（`k8score.pipeline.PipelineBaseImages`）。
> 涉及：git 克隆（`alpine/git`）、构建并推送（`quay.io/buildah/stable`）、四个构建节点的默认
> （maven / golang / node / python）以及 Java/Go/Node 的预设 —— 注意**预设和节点默认的 tag 不一样**
> （`golang:1.22` vs `golang:1.23-alpine`、`node:20` vs `node:20-alpine`），两个都得搬。
>
> 这些是**写死的 Harbor 地址**，渲染期不做任何改写：用户改了镜像就用他填的那个（镜像框带候选补全，
> 候选来自**镜像仓库连接**，与工作负载的镜像框是同一套 `useImageSuggest`），不会被强行改回 Harbor。
> 换镜像仓库地址只需改 `PipelineBaseImages` 一处。

### 3.3 不受这层改写管的三处

| 位置 | 原因 | 处理 |
|---|---|---|
| CNPG 的 **PG 实例镜像**（`ghcr.io/cloudnative-pg/postgresql:<major>`） | 适配器有意不写 `imageName`，由 Operator 内置默认决定 | 想断网装：在 Harbor 里留同名路径，或在适配器里补 `spec.imageName` |
| Milvus 的 **inCluster etcd 镜像** | 由 milvus-operator 内置 chart 决定，values 里没有 | 同上 |
| **平台自身 4 个镜像** | 不在本仓库 | 见 §7 |

### 3.4 首次全量 + 核对

1. `python3 scripts/harbor-replication.py --all --trigger`（或在 `Replications` 里对每条规则点 **REPLICATE**）。
2. 核对：`GET /v2/<repo>/manifests/<tag>`（用 Harbor 凭据）返回 200；或直接看 `Projects → platform → Repositories` 的仓库数。
3. 平台页面上的「镜像清单」抽屉（组件页 → 镜像清单）列的是**当前内置 chart 能识别的上游镜像**，
   可以用来生成过滤器的 Name 列表；它与改写共用同一套规则，但看不到「Operator 运行时才决定」的那两处（见 §3.3）。

### 3.5 不想用复制规则

也可以 `docker pull` → `docker tag` → `docker push` 到 `harbor.ackcp.cloud/<上游主机>/<仓库>`，
脚本化时注意路径里**保留上游主机名**（`docker.io` 也要带上，`library/` 单段仓库要补全）。

### 3.6 出网代理（节点够不到上游时必配）

复制和漏洞扫描都是 **Harbor 在集群里**发起的。内网通常够不到 docker.io，表现是创建
registry endpoint 时直接报 `the registry is unhealthy`。

在组件页重放 Harbor 时填「出网代理」（`http://<代理地址>:<端口>`），平台会把它写进 chart 的
`proxy.httpProxy / httpsProxy / noProxy`，作用于 `core`、`jobservice`、`trivy` 三个组件。
`noProxy` 留空时平台自动补一份「集群内 + 内网」，否则 Harbor 连自己的 DB/Redis 也会走代理。

> 代理挂在你自己的工作站上时：工作站关机/休眠，复制就会失败；正式环境建议放到常开的机器上。
> 另外注意 —— 配了代理之后 Harbor 拉上游不再依赖节点直连，但**节点拉 Harbor 本身**仍然走内网，
> 不受影响。


## 4. 平台配置

| 配置 | 位置 | 说明 |
|---|---|---|
| `platform.image-registry.host` | platform-api `application.yaml` / 环境变量 | 默认镜像仓库（一般留空：平台先取本集群、再取管理集群的 Harbor 安装记录） |
| `platform.image-registry.project` | 同上 | **默认留空** = 一上游一项目；填了会多一层前缀（`<harbor>/<project>/<上游主机>/…`），这种布局复制规则落不了，只能手工搬 |
| `platform.image-registry.enabled` | 同上 | `false` = 全局关闭改写（回退开关） |
| `k8s_cluster.image_registry_host/project` | 集群设置 | 按集群覆盖（业务集群连不到平台 Harbor 时用） |

地址解析顺序：**集群列 → 本集群 Harbor 安装记录 → 管理集群（MANAGEMENT）上的 Harbor 安装记录 →
平台默认**；都没有 → 视为未配置，不改写。

第三项是给业务集群的：**业务集群装不了 Harbor**（Harbor 组件只在 MANAGEMENT 画像里有），
所以它的地址自动取管理集群那一个，不用手工填。手工填只在要指向**别的**仓库时才用。

## 5. 切换与回退

- 组件安装 / 中间件创建表单里的「镜像来源」：`PLATFORM`（默认）/ `OFFICIAL`。
- 切回官方：表单选 OFFICIAL 重放一次即可（**不需要**回滚代码）。
- 全局回退：`platform.image-registry.enabled=false`。
- Harbor 本身的镜像**永远走官方**（先有鸡还是先有蛋）：装 Harbor 组件时即使选了平台 Harbor 也不改写。

## 6. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| Pod `ImagePullBackOff` + `manifest unknown` | 镜像还没被复制到 Harbor | 按 §3 触发复制 |
| 建 registry endpoint 报 `the registry is unhealthy` | Harbor 自己够不到上游（典型是 docker.io） | 给 Harbor 配出网代理，见 §3.6 |
| Pod `x509: certificate signed by unknown authority` | Harbor 证书链不完整（缺中间证书） | 重新下发完整 PEM（`tls.crt` 含中间证书） |
| Pod `x509: certificate has expired` | Harbor 证书到期 | 见 [续期方案](../superpowers/specs/2026-10-28-harbor-tls-renewal-design.md) |
| `no such host: harbor.ackcp.cloud` | 节点解析不到该域名 | 内网 DNS / 节点 hosts 加记录（**containerd 用节点解析器，不是 CoreDNS**） |
| 装了组件但镜像仍走公网 | 该 chart 的 image 字段不在识别口径里 | 跑 `ChartImageRenderGuardTest`（本机有 helm 才会执行），它会渲染出具体是哪一份 chart 的哪个镜像还指向上游 |

## 7. 平台自身镜像

platform-api / k8s-server / platform-web / platform-auth 的 Deployment 不在本仓库里，平台只提供清单。
**顺序很重要**：先把这 4 个镜像推到 `platform/...`（平台自己的构建产物放 `platform` 项目），再改部署清单的 image 与
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
| `hub` | Istio 的 `global.hub` | **只加前缀**（见下），`hub` 后面 chart 还会拼 `/pilot:tag` |
| 模板里拼死的 | tekton 的 operator/webhook 镜像名 | 靠 `ChartImagePins` 的兜底表 |

另外两条容易漏的：

- **子 chart 也要改**：helm 只用父 values 覆盖子 chart 的默认值，父 values 里没写的镜像仍然来自
  `chart/charts/<sub>/values.yaml`。`ChartImageOverlay` 会遍历所有子 chart，按父 `Chart.yaml`
  依赖里的 `alias` 挂载（grafana / kube-state-metrics / node-exporter 就在这里）。
- **bitnami 系的镜像校验**：替换镜像后 chart 会拒绝渲染（`allowInsecureImages`），
  overlay 里会自动关掉这个开关。
- **`hub` 是路径前缀，不是完整镜像**：`docker.io/istio` 后面 chart 还要拼 `/pilot:1.31.3`。
  按完整镜像解析会把 `istio` 当成单段仓库、补出 `library/`，拼成
  `…/docker.io/library/istio/pilot:1.31.3` —— 这个路径 Harbor 里没有（2026-10-29 重建 Istio
  时真撞到，Pod `ImagePullBackOff`）。所以 `hub` 走 `ImageReference.parsePrefix`，不做 library 补全。

护栏是 `ChartImageRenderGuardTest`：对每份内置 chart 真跑一次 `helm template`（叠上 overlay），
断言渲染出来的每个 `image:` 都在 Harbor 上。它需要本机有 helm，没有则跳过——所以它在开发机/流水线
里跑才有意义，在容器里跑会静默跳过。

> **前缀通过 ≠ 路径存在。** 护栏只能证明「以 `harbor.ackcp.cloud/` 开头」，证明不了那个仓库真的
> 搬进来了 —— `docker.io/library/istio/pilot`（hub 被当完整镜像）和 seaweedfs 的双前缀都是这么漏的。
> 要真核对，把渲染清单落盘再跟 Harbor 比：
>
> ```bash
> IMAGE_GUARD_DUMP=D:/tmp/rendered-images.txt mvn -pl k8s-server -am test -Dtest=ChartImageRenderGuardTest
> ```
>
> 然后对文件里每一行取 `<项目>/<仓库>`（项目 = 第一段），逐个查 Harbor 的
> `/api/v2.0/projects/{项目}/repositories` 里有没有。

