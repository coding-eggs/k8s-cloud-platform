# Elasticsearch（中间件 code: `elasticsearch`）

> **这是组件级 README（跨版本）**：平台怎么建实例、约定是什么、踩过哪些坑、加新版本要改哪里。
> 具体 chart 版本的来源/CRD 清单在 `3.5.0/README.md`。
> 使用与设计文档：`docs/development/middleware-store.md`。

## 1. 概览

| 项 | 值 |
|---|---|
| 部署方式 | Operator（Elastic Cloud on Kubernetes，ECK） |
| Operator chart | `3.5.0`（见 `3.5.0/`），release `platform-middleware-elasticsearch` → `platform-middleware-system` |
| CRD / apiGroup | `elasticsearches.elasticsearch.k8s.elastic.co`（可选 Kibana：`kibanas.k8s.elastic.co`） |
| 实例形态 | 只有 `CLUSTER`（节点数 ≥1）；一个 `nodeSet`（name=`default`）承载全部节点 |
| 版本 | 平台清单目前只有 **9.5.0**（ECK 支持的版本随 Operator 走，换版本前先确认 ECK 支持） |
| 凭据 | ECK 生成 → Secret `<实例名>-es-elastic-user`（键 `elastic`） |
| 业务指标 | `elasticsearch-exporter` 边车，9114（见 §6） |
| 镜像 | Operator `docker.elastic.co/eck/eck-operator`；数据 `docker.elastic.co/elasticsearch/elasticsearch:<版本>`；exporter `quay.io/prometheuscommunity/elasticsearch-exporter:v1.7.0` |
| 许可 | Elastic License 2.0 / SSPL（内部使用可接受，**不可再分发**） |

## 2. 平台约定（所有版本都适用）

- **实例名**：`mw-elasticsearch-<8位随机>`；ES CR 用实例名，Kibana CR（勾选时）同名；
- **受管标签**：CR 带 `platform.coding/managed-by=middleware` + `platform.coding/middleware-instance=<id>`；
- **Operator 监听**：ECK 默认全集群监听，不需要额外 values；
- **租户 RBAC**：租户模板需要 `elasticsearch.k8s.elastic.co`（`V2026_10_22_5`）+ 重下发全部模板；
  勾 Kibana 时还要 `kibana.k8s.elastic.co`；
- **状态**：ES 的 `status.health` 为准；装了 Kibana 时 **Kibana 非 green 会把实例判成 DEGRADED**
  并带原因（不会因为 ES 本身绿就报「运行中」）；
- **Kibana 可在编辑里开关（唯一可双向的编辑项）**：关掉 = 删同名 Kibana CR（ES 与数据卷不动，
  Kibana 也没有 PVC），再打开 = 按**线上 ES CR** 的 `spec.version` 与 TLS 补建 —— 不看台账，
  台账版本可能落后于集群实际（ECK 升级/手工改过），版本对不上 Kibana 就起不来。
  补建的 CR 与创建时同款（`kibanaSpec(...)`），**同样必须带 resources**；
- **资源**：ES 9.x **至少给 3Gi 内存**，2Gi 会 OOMKilled（真集群实锤）；改资源会触发滚动重启，
  期间 DEGRADED 是正常的。

## 2.3 IK 分词器的分发（平台对象存储）

IK 不随官方镜像发布，也不能用 `bin/elasticsearch-plugin install`（见 §5.1），所以平台自己分发：

| 项 | 值 |
|---|---|
| 分发根 | 配置项 `platform.middleware.es.ik-download-prefix`；默认 `http://seaweedfs-s3.platform-storage:8333/t-25c6a969-elastic-plugins/analysis-ik/` |
| 实际 URL | 分发根 + `<ES 版本>.zip`（例：`…/analysis-ik/9.5.0.zip`） |
| 桶 | 对象存储页的 `t-25c6a969-elastic-plugins`（**开了匿名读**，initContainer 不带凭据） |
| 已入库 | `analysis-ik/9.5.0.zip`（4 619 762 B，与上游 `get.infini.cloud` 同一份） |

**为什么不自取**：`get.infini.cloud` 是第三方站点，内网/断网环境根本下不到。仓库里每加一个 ES 版本，
就往桶里放一份同名 zip —— 分发点跟着平台走，集群只要能访问对象存储即可。

**放新版本**（在能访问集群 S3 的机器上做；`38333` 是本集群对象存储的对外 S3 入口，端口按实际环境换）：

```bash
ver=9.5.0                       # 要放的 ES 版本
curl -fsSL -o analysis-ik-$ver.zip "https://get.infini.cloud/elasticsearch/analysis-ik/$ver"
# ak/sk = 该桶的凭据（对象存储 → 桶 → 发放凭据，或看 Secret platform-seaweedfs-cred-*）
curl -fsS -T analysis-ik-$ver.zip --aws-sigv4 "aws:amz:us-east-1:s3" -u "$ak:$sk" \
  "http://192.168.31.80:38333/t-25c6a969-elastic-plugins/analysis-ik/$ver.zip"
```

**校验**（两处都要 200、大小一致）：

```bash
curl -o /dev/null -w '%{http_code} %{size_download}\n' \
  http://192.168.31.80:38333/t-25c6a969-elastic-plugins/analysis-ik/$ver.zip
kubectl -n platform-storage exec deploy/seaweedfs-s3 -- curl -o /dev/null \
  -w '%{http_code} %{size_download}\n' \
  http://seaweedfs-s3.platform-storage:8333/t-25c6a969-elastic-plugins/analysis-ik/$ver.zip
```

> 分发根写死在默认值里是因为桶名带租户短码（`t-25c6a969-`）；换个部署就改
> `platform.middleware.es.ik-download-prefix`（环境变量 `PLATFORM_MIDDLEWARE_ES_IK_DOWNLOAD_PREFIX`），
> 不用改代码。

## 3. 形态、TLS 与可选能力

| 能力 | 说明 |
|---|---|
| 节点数 | 一个 `nodeSet` 的 `count`，≥1；生产建议 ≥3 |
| HTTPS（默认开） | 关闭时给 ES/Kibana 的 CR 写 `spec.http.tls.selfSignedCertificate.disabled: true`（只关 HTTP 层，transport 仍加密）。**ECK 仍会生成 `es-http-certs-*` Secret，属正常残留** |
| Kibana（可选） | 额外建一个同名 `Kibana` CR，`elasticsearchRef` 指向 ES；跟随 ES 的 http/https；**编辑里可关可开**（见 §2） |
| IK 中文分词器（可选） | initContainer 从**平台对象存储**下载 zip 解压到共享 emptyDir 的 `plugins/analysis-ik`（见 §2.3）；**单向，不支持在线卸载** |

## 4. 表单字段

命名空间、显示名、StorageClass、CPU/内存（≥3Gi）、存储、`replicas`（节点数）、版本下拉、
HTTPS 开关（默认开）、IK 分词器开关（默认关）、同时部署 Kibana（默认不勾）、「采集业务指标」开关（默认关）。
其中 **Kibana 在编辑里也能改（可关可开）**，其余开关是单向的（IK 只补装、业务指标只开启）。

## 5. 坑（真集群实锤，别回退）

1. **IK 不能用 `bin/elasticsearch-plugin install`**：它要对 config 目录 chown，ECK 默认
   `runAsNonRoot` + drop ALL，实测 `Operation not permitted`。必须用 initContainer `curl` 下载 zip + `unzip`
   到共享 emptyDir；initContainer **必须写 resources**（否则撞配额）。下载源是**平台对象存储**的
   插件桶（§2.3），不再是 `get.infini.cloud` —— 那个站点内网/断网环境拿不到。
2. **Kibana 的 Pod 必须显式给容器 resources**：真集群事件 `must specify limits.cpu for: kibana`
   （命名空间有 limits 配额时，ECK 不会给 Kibana 容器补默认值）。
3. **ES 9.x 内存**：2Gi 会 OOMKilled，至少 3Gi；改资源会滚动重启。
4. **exporter 边车三个必须**：
   - 地址/TLS 走 **args**：`--es.uri=https://localhost:9200`、`--es.ssl-skip-verify`（镜像**不认**
     `ES_URI` / `ES_SKIP_TLS_VERIFY` 环境变量，实测仍走 http 且证书校验失败）；
   - 凭据走 env `ES_USERNAME` / `ES_PASSWORD`（secretKeyRef 到 `<实例名>-es-elastic-user`）；
   - **`securityContext.runAsUser: 65534`**：ECK 在 Pod 级设了 `runAsNonRoot`，镜像用户是 nobody（非数字），
     不给数字 uid 会 `CreateContainerConfigError`。
5. **「已安装 IK」要按 CR 现读**（`initContainers` 里有没有 `install-ik`），并且创建时要把 `ik` 写进台账
   `spec_json` —— 只靠其中一条都会显示错（曾出现「已安装」文案配着关闭的开关）。
6. 存储与形态不能在线改；要改删实例重建（保留 PVC）。

## 6. 业务指标

- 源：`nodeSets[0].podTemplate.spec.containers` 追加 `elasticsearch-exporter`（9114）；
- ServiceMonitor 的 Pod 选择器：`elasticsearch.k8s.elastic.co/cluster-name=<实例名>`（Kibana 的标签不同，
  不会误选）；Service 是 `<实例名>-platform-metrics`，ServiceMonitor 按
  `platform.coding/middleware-monitor=<service 名>` 选 Service；
- **ES 9 已没有 `/_prometheus/metrics`**，别指望内置导出；指标定义在
  `MiddlewareCatalog.ELASTICSEARCH_METRICS`（集群状态/节点/分片/未分配/待处理任务/堆/查询与索引速率/文档数/存储）。

## 7. 加一个新版本怎么做

**加一个 ES 版本**：

1. 在 `MiddlewareCatalog` 的 ES 版本清单里加（目前 `List.of("9.5.0")`，顺序即展示顺序）；
2. 确认 ECK 版本支持该 ES 版本（不支持会在 CR 上报错，实例永远不起 Pod）；
3. **往插件桶里放对应版本的 IK**：`analysis-ik/<新版本>.zip`（做法见 §2.3）。
   漏了这一步，勾了 IK 的实例会卡在 initContainer 的 `curl: 404`；
4. 真集群建一个该版本实例：确认 Pod 起来、green、`_cluster/health` 正常、exporter 指标出数；
5. 更新本文件 §1 的「版本」行与 §3 的注意事项（新大版本的 TLS/API 变化）。

**升级 ECK Operator chart**：

1. `helm pull` 到 `charts/middleware/elasticsearch/<新版本>/chart/` + 新建 `<新版本>/README.md`；
2. ECK 的 **CRD 以子 chart 模板方式安装**（可随 upgrade 更新），但仍要对比 `eck-operator-crds` 的差异；
3. 改 `MiddlewareOperatorCatalog`（版本、必需 CRD、镜像）；
4. 跑测试 → 真集群装一次 → 起一个 ES 实例验证。

> ⚠️ ES 的大版本升级（如 8→9）不是改个 tag：需要走滚动升级流程并评估索引兼容性，平台不提供入口。

## 用户管理（2026-10-26）

- 通道：`_security` API；**scheme 只看 CR**（`http.tls.selfSignedCertificate.disabled=true` 即 http，
  与指标 exporter 同一条规则 —— 关掉 TLS 后 ECK 照样生成 cert Secret，拿 Secret 判定会拿 https 去连明文端口），
  https 时用 `<实例名>-es-http-certs-public` 的 `ca.crt` 建信任链（**不** trust-all；
  别读 `-es-http-ca-internal`，那里只有 CA 的 `tls.crt`/`tls.key`），
  没有 CA 说明实例没开 TLS，走 http。
- 用户名空间全局：平台建的账号加 `platform_` 前缀，列表剥前缀显示；只展示 `elastic` 与 `platform_*`。
- 角色：只读 → `viewer`、读写 → `editor`、管理员 → `superuser`。
- 改 `elastic` 口令后写回 `<实例名>-es-elastic-user`。
