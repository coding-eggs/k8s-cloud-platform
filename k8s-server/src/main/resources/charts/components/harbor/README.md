# Harbor（镜像仓库，集群组件）

> **组件级 README（跨版本）**：平台怎么装、约定是什么、踩过哪些坑、加新版本要改哪里。
> 当前 chart 版本的事实见 `1.19.4/README.md`。

## 1. 概览

| 项 | 值 |
|---|---|
| 组件 code | `harbor` |
| 画像 | BUSINESS / MANAGEMENT |
| release / namespace | `platform-harbor` / `harbor` |
| 探测判据 | `harbor` Deployment `harbor-core`（镜像含 `goharbor/harbor-core`） |
| 平台功能 | `container-registry` |
| 暴露 | 三选一（默认 `nodePort`）：`nodePort` / `loadBalancer` / `route`（HTTPRoute） |
| 管理口令 | Secret `platform-harbor-secrets`（`HARBOR_ADMIN_PASSWORD` / `secretKey`） |
| 前置组件 | 仅 `route` 模式需要 `istio`（external-gateway）；另两种不要求 |

## 2. 装之前必须确认

`host` 必须**同时**能被以下三边访问：

1. 集群内 Pod（流水线的 push/pull）；
2. 平台所在机器（仓库浏览与租户项目同步）；
3. 外部 docker/podman 客户端。

Harbor 的 token 服务按 `externalURL` 返回地址；三边不一致的典型症状是
「登录成功但 push 到错误地址 / 解析失败」。

**IP 直连**：只能用 `nodePort` 或 `loadBalancer`。`route` 模式走 Gateway API 的
`HTTPRoute.hostnames`，那份规范明确**不允许 IP**（也不接受端口）—— 用 IP 会被拒或路由挂不上。

## 3. 契约 values（🔒）

```yaml
fullnameOverride: harbor
existingSecretAdminPassword: platform-harbor-secrets
existingSecretSecretKey: platform-harbor-secrets
persistence: { enabled: true, resourcePolicy: keep }
database: { type: internal }
redis: { type: internal }
```

- `fullnameOverride`：探测认 `harbor-core`，不钉名字 release 名一变就永远「未安装」。
- `resourcePolicy: keep`：卸载 release 不删镜像与数据库 PVC。
- `database/redis`：**默认 internal**；安装表单可选外部（见 §4.1），外部时由表单拼
  `type: external` 与 `existingSecret`，口令不进 values。

**`expose.*` 与 `externalURL` 不在契约里**：由安装表单按暴露方式拼（见 §4）。
三种模式都只走 HTTP（`expose.tls.enabled=false`），客户端要配 `insecure-registries`：

```toml
# /etc/docker/daemon.json（或 /etc/containers/registries.conf 的 insecure = true）
{ "insecure-registries": ["10.0.0.5:30002"] }
```

## 4. 安装参数（表单，⚙️）

| 字段 | 默认 | 说明 |
|---|---|---|
| `exposeMode` | `nodePort` | `nodePort` / `loadBalancer` / `route` |
| `host` | 空 = `platform.harbor.default-host` | 不带 scheme/path/端口。route = 域名；另两种 = 节点/LB 的 IP 或域名 |
| `https` | `false` | route 模式 TLS 在网关 443 终止（Harbor 自己仍是 HTTP）；另两种由 Harbor 挂证书 |
| `tlsCert` / `tlsKey` | 空 = `platform.harbor.tls-cert-file` / `tls-key-file` | 仅入参：PEM，不进 values、不进快照、不回显；都没有 = 沿用集群里已有的 `harbor-tls` |
| `httpProxy` / `httpsProxy` / `noProxy` | 空 | 出网代理（复制/扫描是 Harbor 在集群里发起的）。`noProxy` 留空自动补集群内 + 内网 |
| `nodePort` | `30002` | 仅 nodePort 模式；K8s 默认范围 30000-32767 |
| `port` | `80` | 仅 loadBalancer 模式；Service 端口 |
| `databaseMode` | `internal` | `internal` / `external`；**只能在首次安装时选** |
| `dbHost` / `dbPort` / `dbUsername` / `dbName` / `dbSslMode` | 空 / 5432 / 空 / `registry` / `disable` | 仅外部数据库 |
| `dbPassword` | 空 | 仅入参：首次安装必填；编辑留空 = 不改（沿用已有 Secret） |
| `redisMode` | `internal` | `internal` / `external`；**只能在首次安装时选** |
| `redisAddr` / `redisUsername` | 空 | 仅外部 Redis：`host:port`（sentinel 暂不支持）、ACL 用户名可空 |
| `redisPassword` | 空 | 仅入参：首次安装必填；编辑留空 = 不改 |
| 5 块 PVC 的 StorageClass / 容量 | 空 / 20Gi、1Gi、5Gi、1Gi、5Gi | StorageClass 空 = 跟随集群默认 |
| `trivy.enabled` | `false` | 内网拉不到漏洞库，默认不装 |

**域名与证书可以留空**：生产里它们是固定的，每次重放都重新粘 PEM 既啰嗦又容易贴错。
平台侧配置（`platform-api/application.yaml` → `platform.harbor.*`，都可用环境变量覆盖）：

| 配置 | 环境变量 | 默认 |
|---|---|---|
| `default-host` | `HARBOR_DEFAULT_HOST` | `harbor.ackcp.cloud` |
| `tls-cert-file` | `HARBOR_TLS_CERT_FILE` | 空（例：`/app/cert/harbor.ackcp.cloud.pem`） |
| `tls-key-file` | `HARBOR_TLS_KEY_FILE` | 空（例：`/app/cert/harbor.ackcp.cloud.key`） |

`cert/` 在 `.gitignore` 里，证书不会进镜像 —— 部署时挂载目录并设这两个环境变量，或第一次安装时在表单里粘一次
（之后证书进了 `harbor-tls` Secret，重放留空即可）。

生成的 values：

| 模式 | `expose` | `externalURL` |
|---|---|---|
| `nodePort` | `type: nodePort`，http 端口 80→`nodePort` | `http://<host>:<nodePort>` |
| `loadBalancer` | `type: loadBalancer`，`ports.httpPort=<port>` | `http://<host>[:<port>]`（80 省略） |
| `route` | `type: route`，hosts=[host]，parentRefs=`istio-system/external-gateway` | `http://<host>` |

平台调 Harbor API 与写进租户凭据的 endpoint 用**同一个** authority（nodePort/LB 带端口），
快照里记在 `__platform.harbor.externalHost`。

### 4.0 存储容量的可改性（编辑时）

- **数据库 / Redis / 扫描器**：chart 用 StatefulSet 的 `volumeClaimTemplates`，**不可变** ——
  表单在编辑时只读展示。
- **镜像数据（registry）/ 作业日志（jobservice）**：chart 建的是**独立 PVC**，编辑时可以调
  **容量**（只能调大；StorageClass 必须开 `allowVolumeExpansion`，缩容会被 API server 拒）；
  `storageClassName` 不可改。

**盘是「重装也不删」的**：契约里 `persistence.resourcePolicy: keep`，所以上次安装失败
（helm 回滚 = uninstall）后 PVC 会留在 `harbor` 命名空间。PVC 的 `storageClassName` 创建后
不可变，而表单留空 = 跟随集群默认 = manifest 里没有这个字段 —— helm 4 的 SSA 会把它抹成
null，直接报 `spec is immutable after creation`（2026-10-27 真集群撞到的就是这条）。

平台在下发前会读一遍命名空间里已有的 PVC 并对齐（`ComponentInstallService.alignHarborStorageClasses`）：

- 表单留空 → 按**盘上的**存储类填回 values（复用旧盘，重试就能装成）；
- 表单显式选了别的 → 直接拒，并说出盘上实际的存储类（改不了，只能删盘或改回）。

读不到 PVC（没权限 / 集群不可达）时跳过对齐，让 helm 自己报错。

### 4.1 外部数据库 / Redis（可选）

外部模式下 chart 不建 `harbor-database` / `harbor-redis`，平台在 helm 之前写两个 Secret：

| Secret | 键 | 用途 |
|---|---|---|
| `platform-harbor-external-db` | `password` | chart 映射成 `POSTGRESQL_PASSWORD` |
| `platform-harbor-external-redis` | `REDIS_PASSWORD`（可选 `REDIS_USERNAME`） | chart 用 helm `lookup` 读它，所以必须先于 helm 存在 |

前提与限制：

- **PostgreSQL**：先建好库（默认 `registry`）与账号并授权；Harbor 会自己建表。
- **Redis**：单实例 `host:port`（**sentinel 列表暂不支持**）；Harbor 固定用 DB index
  `0/1/2/5`，不要与别的应用共用实例里的这几个库。
- **来源（内置/外部）只能在首次安装时选**：从内置切外部需要迁移内库数据，不是一次
  helm upgrade 能完成的，所以编辑时锁定。
- 口令由 platform-api 随安装请求传给 k8s-server 写 Secret，**不进 values 快照、不回显**；
  编辑时留空 = 沿用已有 Secret（Secret 不存在会被拒）。
- 平台不做连通性预检（平台机不一定能到你的库），只校验地址/端口格式。

## 5. 装完之后

```bash
kubectl -n harbor get deploy,sts,svc
kubectl -n harbor get httproute
kubectl -n harbor get secret platform-harbor-secrets -o jsonpath='{.data}' | jq 'keys'
```

- route 模式：HTTPRoute `Accepted=True` 且 `AttachedRoutes=1`；
- nodePort/loadBalancer 模式：`kubectl -n harbor get svc` 的端口与 `externalURL` 一致；
- 外部依赖：`kubectl -n harbor get secret platform-harbor-external-db platform-harbor-external-redis`；
- 平台「集群组件」页 Harbor 行 →「镜像项目」能看到每租户的 project 与凭据状态，
  装完 Harbor 与新建租户时平台会自动同步（失败可在该面板重试/重建）。

## 6. 管理口令（不可重建自愈）

口令由 k8s-server 生成，只在首次初始化时写进 Harbor 库；`secretKey` 还用于加密库里的字段。
**Secret 丢失且 `harbor-core` 已存在时，平台会拒绝安装/升级** —— 重新生成的口令与库里的哈希
不一致，同步必然 401。此时需人工按 Harbor 官方方式重置管理员口令 / secretKey 后再操作。

## 7. 已知限制

- 资源占用较大（core/registry/db/redis/jobservice 合计约 1~2GB 内存起）；
- 镜像较大，慢网络下 `helm --timeout 10m` 可能不够，失败后重试（upgrade）；
- 本期不做：租户登录 Harbor、项目配额/保留策略/复制/签名、外部 DB、TLS 终止。

## 8. 加一个新版本怎么做

1. `helm pull harbor --repo https://helm.goharbor.io --version <x.y.z> --untar` → 入库
   `harbor/<x.y.z>/chart/` + `provenance.yaml` + `<x.y.z>/README.md`；
2. 核对 values 字段名（本期用到：`expose.type/route/tls`、`externalURL`、
   `existingSecretAdminPassword`、`existingSecretSecretKey`、
   `persistence.persistentVolumeClaim.<registry|jobservice.jobLog|database|redis|trivy>.{storageClass,size}`、
   `trivy.enabled`、各组件 `resources`）；
3. 改 `ComponentRecipeCatalog.HARBOR_RELEASES` 的 `chartVersion` 与 `withAppVersion`（新→旧）；
4. 跑 `ComponentRecipeCatalogTest`（会与 `Chart.yaml` 交叉校验）；
5. 真集群装一次，按 §5 验证；
6. 重建 k8s-server（chart 入镜像）+ platform-api。
