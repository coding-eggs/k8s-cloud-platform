# K8s 云管平台（k8s-cloud-platform）

面向**多集群 Kubernetes** 的 Web 管理平台。平台管理员负责纳管集群、网络与组织；租户在自己的命名空间里
做应用交付、资源管理、中间件与观测。仓库是 **Maven 多模块后端 + Vue 3 单页前端**，四个可部署服务、三个库模块。

![平台总览：平台服务与集群健康、按资源类型的整体汇总](docs/images/platform-overview.png)

> 平台自己也是"有状态"的：数据库迁移要**手工重放**、两个 AES 主密钥必须与库中密文一致、
> 认证服务必须先于其它服务启动。这几条都在 [§7 注意事项](#7-必须知道的约定与注意事项) 里，部署前请先读。

### 产品特点

- **两个视图，各司其职**：平台视图管集群、网络、组织与运维；租户视图只看自己的命名空间，做应用、资源、存储和观测。
- **多租户隔离分四层**：权限表、端点边界、资源边界、K8s RBAC 层层把关，默认拒绝；权限行漏配时应用直接拒启。
- **应用交付一条线**：服务 + 依赖 + 不可变发布（预览差异、回滚、取消、入口灰度），构建产物可追溯到流水线运行；
  对外暴露可选 NodePort 或 Istio 网关端口池，依赖可跨命名空间绑定。完整流程见 [§3.2 应用交付](#32-应用交付核心链路)。
- **中间件开箱即用**：Redis、MySQL、PostgreSQL、Kafka、RabbitMQ、Elasticsearch、Nacos、Milvus 八款，
  支持创建实例、改规格、查连接方式、管理用户与对外暴露。
- **集群组件一键安装**：仓库内置 Helm chart，Istio、Calico、Prometheus、Thanos、Tempo、SeaweedFS、Harbor 等
  可直接从页面安装；**新建集群向导**免登录生成一套可执行的 kubespray 部署包（在线 / 全离线）。
- **观测闭环**：指标、告警、日志、链路集中在同一套界面；告警可认领、备注并一键跳到相关对象/日志，
  日志可跳到对应链路，还有基于 Istio 指标的网格拓扑。
- **网络能力到位**：Gateway API 七类对象、ListenerSet 端口池、Istio ambient、Calico 地址池与 BGP 都有可视化管理。
- **租户自助**：空间申请、配额扩容、续期与到期回收（Pod 归零 / 删除命名空间两档）都在页面里走审批流。
- **全域搜索**：`Ctrl + K` 命令面板同时匹配菜单、页内操作和资源名，直接落到详情。
- **操作可追溯**：写操作和被拒绝的请求都进审计日志；权限点可按 URL 反查覆盖关系，排障直接定位到权限码。
- **深浅双主题**：顶栏一键切换，本文截图均为浅色模式。

---

## 1. 架构总览

```
浏览器（同源）
   │
   ▼
platform-web（nginx）
   ├── /api/**  ──▶  platform-gateway（:8090）── 接口级鉴权 + 403 审计
   │                    ├── /**              ──▶ platform-api（:8081）── HTTP ──▶ k8s-server（:8080）── fabric8 ──▶ 被纳管的 K8s 集群
   │                    └── /oauth-clients/**、/jwks/** ──▶ platform-auth 的 /auth/internal/**
   └── /auth/** ──▶  platform-auth（:9527/auth）── 签发 JWT、暴露 JWKS

网关 / platform-api / k8s-server / auth 的 /internal/** 都校验 JWT（网关原样透传 token）；MySQL / Redis 由后端服务按需访问。
```

| 模块 | 类型 | 端口 | 职责 |
|---|---|---|---|
| [`platform-web`](platform-web) | 部署单元 | 80（dev 50000） | Vue 3 SPA + nginx 反向代理、TLS 终止（可选） |
| [`platform-gateway`](platform-gateway) | 部署单元 | 8090 | API 网关：接口级鉴权（权限表）、403 审计、路由到 api / auth |
| [`platform-api`](platform-api) | 部署单元 | 8081 | 管理面 BFF：业务规则、数据库、聚合与缓存（只校验登录，接口级权限在网关） |
| [`k8s-server`](k8s-server) | 部署单元 | 8080 | K8s **执行面/适配器**：零业务逻辑，唯一直接访问 K8s API 的进程 |
| [`platform-auth`](platform-auth) | 部署单元 | 9527（`/auth`） | 统一认证与授权服务器：登录、OAuth2/OIDC、令牌签发、会话续期 |
| [`k8s-core`](k8s-core) | 库 | — | fabric8 封装：operations / converter / factory，只被 k8s-server 依赖 |
| [`platform-common`](platform-common) | 库 | — | 公共组件：JWT 编解码、响应码、通用模型与工具 |
| [`platform-data`](platform-data) | 库 | — | 持久层：MyBatis Mapper、实体、**SQL 迁移脚本** |

**请求链路**：浏览器 → nginx（`/api` 原样转发）→ platform-gateway（接口级鉴权）→ platform-api（业务）→ HTTP → k8s-server（边界判定 + K8s 调用）。
platform-api **不依赖 fabric8**，也不直接连集群。

**两个特例**：

- **新建集群向导**（`/clusters/new`）是纯前端页面，**免登录**（挂在 `PublicLayout` 上）：只生成 kubespray 部署包，
  不落库、不发请求，因此不需要后端参与，也不占权限码。
- **集群连接**：kubeconfig 加密存库，k8s-server 解密后用 fabric8 直连；镜像里**没有 kubectl**，
  需要落盘的清单（Calico CRD、应用发布、初始化 Job）统一走 `ManifestApplier`。

---

## 2. 技术栈与依赖

### 2.1 后端

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **21** | 全部模块；`-parameters` 编入字节码 |
| Spring Boot | **4.1.2** | 以 BOM import，不是 `spring-boot-starter-parent` |
| fabric8 Kubernetes Client | **7.9.0** | k8s-core 使用；含同版本 `tekton-client` |
| MyBatis Spring Boot Starter | 4.0.1 | `mapper/**/*.xml`，下划线转驼峰 |
| PageHelper | 4.1.1 | 分页（mapper 里不写 LIMIT） |
| Spring Security | Boot BOM | OAuth2 授权服务器（auth）/ 资源服务器（api、k8s-server、gateway） |
| Nimbus JOSE+JWT | 10.4 | JWS/JWE 编解码 |
| Google Tink | 1.21.0 | 加密工具 |
| Knife4j | 4.6.0 | OpenAPI 文档（三个服务都开） |
| Jackson | 3.x（`tools.jackson`） | Boot BOM 管理；k8s-server 同时存在 Jackson 2（fabric8 / MinIO 带入） |
| JGit | 6.10 | 流水线「触发前预览 commit」与变更清单解析（platform-api） |
| MinIO Java SDK | 8.5.17 | S3 协议客户端（SeaweedFS 桶/对象操作） |
| Caffeine | 3.2.3 | 本地缓存（auth） |
| Spring Session Redis | Boot BOM | 会话放 Redis，多实例/重启不丢 |
| Spring WebSocket | Boot BOM | Pod exec / 日志 follow 的 WS 升级 |
| Spring Data Elasticsearch | Boot BOM | 日志查询中心数据源 |
| JavaMail | Boot BOM | 告警邮件通知 |
| Helm | **4.2.0** | k8s-server 镜像内置（`docker/tools/linux-amd64/` 预置 tar 包），组件安装用 |

### 2.2 前端（`platform-web`）

| 组件 | 版本 | 用途 |
|---|---|---|
| Vue / Vue Router | 3.5 / 4.5 | SPA、history 路由 |
| Vite | 8.1 | 构建与 dev server（`/api`、`/auth` 代理） |
| TypeScript / vue-tsc | 6.0 / 3.3 | 类型检查（`npm run build` 会先跑） |
| Element Plus | 2.9 | UI 组件库 |
| Tailwind CSS | 3.4 | 原子化样式 |
| ECharts | 5.6 | 指标曲线、拓扑 |
| Vue Flow | 1.48 | 流水线画布、应用拓扑 |
| dagre | 3.1 | 网格拓扑自动布局 |
| xterm.js | 6 | 容器终端 |
| highlight.js | 11 | YAML 展示与高亮 |
| pinyin-pro | 3.29 | 命令面板（拼音首字母匹配菜单） |
| axios | 1.7 | HTTP 客户端 |

**Node 版本**：`^22.18.0 || >=24.12.0`（见 `package.json` 的 `engines`）。

### 2.3 外部基础设施

| 依赖 | 必需性 | 用途 |
|---|---|---|
| MySQL | 必需 | 用户/租户/角色/权限/集群注册/分配表/流水线与发布台账等 |
| Redis | 必需 | auth 的会话；api 的跨实例 TTL 缓存（`platform:cache:*`） |
| Kubernetes 集群 | 必需 | 被纳管对象；kubeconfig 加密存库 |
| Prometheus / Thanos | 可选 | 集群概览、指标曲线、告警规则 |
| Alertmanager | 可选 | 告警事件推送到 `/alert/webhook` |
| Elasticsearch | 可选 | 日志查询中心 |
| Tempo + OTel Collector | 可选 | 链路追踪 |
| Harbor | 可选 | 镜像仓库组件 + 平台镜像来源改写目标 |
| SeaweedFS | 可选 | 对象存储组件（桶、凭据、对象浏览） |

### 2.4 仓库内置 Helm chart

`k8s-server/src/main/resources/charts/` 下是平台"集群组件安装"和"中间件商店"直接使用的 chart：

- **components**：cert-manager、csi-driver-nfs、gateway-api、harbor、istio（base/cni/istiod/ztunnel/gateway）、metrics-server、
  otel-operator、prometheus-operator、projectcalico-crds、tigera-operator、seaweedfs、tekton-operator、tempo、thanos
- **middleware**：redis、mysql、postgresql、kafka、rabbitmq、elasticsearch、nacos、milvus
- **app**：应用发布渲染出的工作负载模板

各 chart 的约定与踩坑写在 `charts/components/<chart>/README.md`（**组件级 README**，版本目录只记录该版本事实）。

---

## 3. 功能一览

按前端导航的两个视图（平台视图 / 租户视图）组织；菜单由**路由 meta 派生**，不是手写清单。

| 域 | 主要能力 |
|---|---|
| **总览** | 平台总览（平台服务与集群健康、按资源类型的整体汇总、租户规模、快捷操作）；租户工作台（工作负载/Pod/应用/中间件/CI/告警计数、资源与配额、需要关注项、空间申请入口）。集群 / 命名空间 / 节点 / 租户各有维度概览 |
| **集群** | 接入集群（kubeconfig 加密登记）与**新建集群向导**（免登录生成 kubespray 部署包）、集群探活与启停、节点管理（cordon/驱逐/详情）、集群概览（可分配量、requests、实时用量、per-ns 明细）、命名空间管理（配额/LimitRange/描述/受保护 ns）、存储类（默认标记）、卷快照类与快照内容 |
| **组织** | 租户与成员、命名空间分配、空间申请审批（开通/配额扩容/续期/到期回收两档）、用户、角色与权限、权限点、RBAC 模板（下发到集群生成 RoleBinding/ClusterRole）、OAuth 客户端 |
| **网络** | Service、Gateway API 七类对象（GatewayClass / Gateway / ListenerSet / HTTP·GRPC·TCP·TLS·UDPRoute）、路由五合一页面、网格拓扑、Calico（IPPool 与 IPAM 派生视图 / 保留 IP / BGP 配置·对等体·过滤器） |
| **应用** | 应用发布（服务/后台进程 + 中间件/外部依赖；变量引用、待发布改动、发布/回滚/下线/取消、入口灰度、对外暴露、调度与挂载、HPA/可观测/网格、事件、拓扑）、中间件商店与实例（8 款 + 用户管理 + 对外暴露 + 指标）、流水线（Tekton：编辑、运行、日志、源码预览与 diff、产物发布、重试）、Git 凭据、镜像仓库与镜像来源纳管 |
| **资源** | 工作负载（Deployment / StatefulSet / DaemonSet，回滚/副本/重启/历史；Operator 托管的同类对象也在这里）、Pod（日志、Exec 终端）、HPA、ConfigMap、Secret、PVC、持久卷 |
| **存储** | PVC / PV、卷快照（创建/恢复）、对象存储（SeaweedFS 桶、凭据、对象浏览与预览、桶配额、匿名读） |
| **观测** | 告警（列表/详情/认领/备注/排障入口）、ServiceMonitor / PodMonitor / PrometheusRule、日志查询（ES）、链路追踪（Tempo） |
| **运维** | 审计日志、通知方式、个人中心；命令面板（`Ctrl+K`：菜单、页内操作、资源名搜索） |

平台域与租户域使用**两套权限码**（`platform:*` / `tenant:*`）；管理员可持两族，租户角色只能持 `tenant:*`。

### 3.1 界面预览

截图来自测试环境（浅色主题），内网 IP 已打码。

#### 平台视图

<table>
  <tr>
    <td width="50%"><img src="docs/images/cluster-metrics.png" alt="集群资源用量"><br><b>资源用量</b>：可分配量、requests、实时用量三线对照，调度水位与超卖一眼可见</td>
    <td width="50%"><img src="docs/images/cluster-install.png" alt="新建集群向导"><br><b>新建集群向导</b>：五步表单，末步给出拓扑、校验与可直接拷进 kubespray 的部署包（免登录）</td>
  </tr>
  <tr>
    <td><img src="docs/images/calico-ippools.png" alt="Calico 地址池"><br><b>Calico 地址池</b>：CIDR、块大小、NAT 与 IPAM 分配水位</td>
    <td><img src="docs/images/permissions.png" alt="权限点"><br><b>权限点</b>：按权限码分组折叠，支持关键词搜索和 URL 反查</td>
  </tr>
  <tr>
    <td><img src="docs/images/audit.png" alt="审计日志"><br><b>审计日志</b>：记录写操作与 403 拒绝，可按人、集群、命名空间筛选</td>
    <td><img src="docs/images/object-storage.png" alt="对象存储"><br><b>对象存储</b>：SeaweedFS 桶、用量配额、凭据与公开读</td>
  </tr>
  <tr>
    <td><img src="docs/images/tenants.png" alt="租户管理"><br><b>租户详情</b>：命名空间分配、成员与角色、空间申请与回收集中在一处</td>
    <td></td>
  </tr>
</table>

#### 租户视图

<table>
  <tr>
    <td width="50%"><img src="docs/images/tenant-overview.png" alt="租户工作台"><br><b>租户工作台</b>：配额占用、需要关注的命名空间、常用操作与空间申请入口</td>
    <td width="50%"><img src="docs/images/workloads.png" alt="工作负载"><br><b>工作负载</b>：按类型筛选，副本、状态、镜像集中展示；来自应用的标出所属应用，Operator 管理的标出来源</td>
  </tr>
  <tr>
    <td><img src="docs/images/middleware-store.png" alt="中间件商店"><br><b>中间件商店</b>：八款中间件，按单节点、集群等形态创建实例</td>
    <td><img src="docs/images/mesh-topology.png" alt="网格拓扑"><br><b>网格拓扑</b>：节点是工作负载，边是调用，线型区分 L7 / L4</td>
  </tr>
  <tr>
    <td><img src="docs/images/routes.png" alt="路由策略"><br><b>路由策略</b>：HTTP、TCP 等路由集中管理，并探测集群的 Istio 与 Gateway API 能力</td>
    <td><img src="docs/images/alerts.png" alt="告警"><br><b>告警</b>：触发/恢复、认领与处理备注、标签与注解；可跳到相关资源与事发日志</td>
  </tr>
  <tr>
    <td><img src="docs/images/logs.png" alt="日志查询"><br><b>日志查询</b>：按工作负载、Pod、关键词、级别过滤，可跳转到对应链路</td>
    <td><img src="docs/images/traces.png" alt="链路追踪"><br><b>链路追踪</b>：基于 Tempo 查询调用链，范围按租户收窄</td>
  </tr>
  <tr>
    <td><img src="docs/images/namespace-requests.png" alt="空间申请与回收"><br><b>空间申请与回收</b>：有效期与状态一目了然，可申请开通、配额扩容、续期与到期回收</td>
    <td><img src="docs/images/command-palette.png" alt="命令面板"><br><b>命令面板</b>：<code>Ctrl+K</code> 搜菜单、页内操作和资源名，回车直达</td>
  </tr>
</table>

### 3.2 应用交付（核心链路）

平台的主线是"把一组服务跑起来，并且每次改动都可控"：

**流水线产出镜像 → 应用里配服务与依赖 → 勾选发布（先看差异）→ 按需灰度 / 回滚 / 下线**。
发布是**不可变**的：每次发布生成一份快照，配置改动在发布前都只是"待发布"，线上不受影响。

<table>
  <tr>
    <td width="50%"><img src="docs/images/applications.png" alt="应用列表"><br><b>应用列表</b>：运行状态、线上发布号、所属集群与命名空间一眼可见，应用按租户跨集群展示</td>
    <td width="50%"><img src="docs/images/application-detail.png" alt="应用详情"><br><b>应用详情</b>：服务、依赖、应用设置（链路追踪 / 网格）与发布记录、拓扑、事件</td>
  </tr>
  <tr>
    <td><img src="docs/images/app-release-preview.png" alt="发布预览"><br><b>发布预览</b>：勾选要发布的服务，可顺手改 tag；本次改动、阻断问题与告警逐条列出，确认前不写集群</td>
    <td><img src="docs/images/app-service-config.png" alt="服务配置"><br><b>服务配置</b>：镜像与 tag、端口、对外暴露（不暴露 / NodePort / 网关 HTTP·TCP）、副本，另有环境变量、配置文件、调度、挂载与可观测</td>
  </tr>
  <tr>
    <td><img src="docs/images/app-topology.png" alt="应用拓扑"><br><b>应用拓扑</b>：箭头由使用方指向被依赖方，关系来自环境变量与配置文件里的变量引用，不靠手画</td>
    <td><img src="docs/images/app-releases.png" alt="发布记录与回滚"><br><b>发布记录与回滚</b>：每次发布都留快照，任一历史版本可回滚；点「回滚」先确认目标版本，再执行</td>
  </tr>
  <tr>
    <td><img src="docs/images/pipeline-run.png" alt="流水线运行详情"><br><b>从流水线到应用</b>：运行详情里的构建产物（含 sha256 摘要）可一键发布到应用，版本可追溯到某次构建</td>
    <td><img src="docs/images/app-events.png" alt="应用事件"><br><b>应用事件</b>：服务与 Pod 的告警/探针/调度事件按应用聚合，发布后异常不必再去翻集群</td>
  </tr>
</table>

---

## 4. 快速开始

### 4.1 前置

- JDK 21、Maven 3.9+（本机实测 3.10）
- Node `^22.18 || >=24.12`、npm
- MySQL（库 `k8s_cloud_platform`）、Redis
- 一个可访问的 K8s 集群及 kubeconfig（首次纳管时在页面里导入）
- **仅「新建集群向导」需要**：一份 [kubespray v2.32.0](https://github.com/kubernetes-sigs/kubespray) checkout，用来执行生成的 inventory

### 4.2 数据库迁移（**必须先做**）

本仓库**没有 Flyway / Liquibase**。迁移脚本是 `platform-data/src/main/resources/db/migration/*.sql`，
按文件名顺序**手工重放**：

```bash
ls platform-data/src/main/resources/db/migration   # V2026_09_24_1__... → 按序执行
```

首次建库可以直接导入 `docs/dbback/k8s_cloud_platform.sql`（早期全量 dump，**之后的新迁移仍要补跑**）。
规则：**先重放本次新增的 SQL，再启动新构建** —— 新代码里有过 fail-closed 的启动期交叉校验，缺表/缺权限行会直接拒启。

### 4.3 编译与测试

```bash
# 后端全量构建 + 测试
mvn -B clean package

# 只构建某个服务（含它依赖的库模块）
mvn -B -pl platform-api -am -DskipTests package

# 前端类型检查 + 构建；集群向导的纯函数有单测
cd platform-web && npm ci && npm run build
npm run test:install-plan
```

> 父 POM 用的是 BOM import，所以 `spring-boot-maven-plugin` 的 `repackage` 已在父 POM 里显式绑定 ——
> 产物是可执行的 fat jar；没有这段绑定会得到"没有 Main-Class"的瘦 jar。

### 4.4 本地启动顺序

1. MySQL、Redis
2. `platform-auth`（9527）—— **必须最先起**：其它服务首次校验令牌就要拉 JWKS，auth 没起来会全站 401
3. `k8s-server`（8080）、`platform-api`（8081）、`platform-gateway`（8090，启动即读权限表）
4. `platform-web`

```bash
java -jar platform-auth/target/platform-auth-1.0.0.jar
java -jar k8s-server/target/k8s-server-1.0.0.jar
java -jar platform-api/target/platform-api-1.0.0.jar
java -jar platform-gateway/target/platform-gateway-1.0.0.jar
```

前端开发（Vite 代理 `/api` → 网关 8090、`/auth` → `OAUTH_UPSTREAM`；默认端口 50000）：

```bash
cd platform-web && npm ci && npm run dev
```

生产构建：

```bash
cd platform-web && VITE_OAUTH_ISSUER=/auth VITE_OAUTH_CLIENT_ID=platform-web-client \
  npm ci && npm run build-only
```

登录回调地址默认取「页面 origin + `/callback`」，不用配。要指定别的值，用 `platform-web` 容器的**运行时**
环境变量 `PLATFORM_OAUTH_REDIRECT_URI`（nginx 启动时生成到 `/runtime-config.js`，改完重启容器即生效，不用重新构建）。
无论哪种，platform-auth 里 `platform-web-client` 登记的 redirect_uri 必须与前端实际用的值**一字不差**
（授权端点精确匹配，没有通配）——否则点登录会停在 auth 的错误页，浏览器根本到不了 `/callback`。
同一份构建换域名/端口不用重新构建，只改环境变量 + 在库里补登记。

### 4.5 新建集群向导（免登录）

`/clusters/new`（集群页的「新建集群」）是**纯前端**的 kubespray 配置生成器：不执行、不落库、不发请求。

- 五步：基础（名称 / K8s 版本 / 运行时）→ 节点（逐行填写或批量导入）→ 网络与入口（CIDR、API 入口、CNI）
  → 介质（在线 / 离线，镜像源与代理）→ 部署包。
- 末步给出拓扑、校验结果与 `inventory/<集群名>/`（`hosts.yaml` + `group_vars/**` + `README.md`，
  离线时含 `offline.yml`），可单文件下载或打包 `.zip`。
- 在 kubespray **v2.32.0** checkout 根目录执行：

  ```bash
  cp -r <下载目录>/<集群名> inventory/
  ansible-playbook -i inventory/<集群名>/hosts.yaml cluster.yml
  ```

- CNI 选「平台管理」时，向导里的 Pod CIDR 必须与之后在组件页安装 Calico 时填的 CIDR **一字不差**，
  否则集群立即断网 —— 生成的 README 里会写明这一点。

### 4.6 镜像构建（**两步，Dockerfile 不编译**）

```bash
# 1) 先在仓库根编译
mvn -B -pl platform-api -am -DskipTests package
# 2) 再打包镜像，构建上下文是模块目录
docker build -f platform-api/Dockerfile -t k8s-cloud-platform/platform-api:latest platform-api
```

`k8s-server` 镜像额外内置 **Helm 4.2.0**（构建期从 `docker/tools/linux-amd64/` 的预置 tar 包解压，全程离线），
并复制内置 chart 到 `/app/charts`；**镜像里没有 kubectl**（2026-11-06 起改由 `ManifestApplier` 落盘）。
四个服务的 Dockerfile 头部都写了该服务的运行参数与必填环境变量，部署前值得逐个读一遍。

---

## 5. 配置（环境变量）

默认值写在各自的 `application.yaml` / `application.yml` 里，**多数是研发环境地址**（192.168.x），
容器部署时必须覆盖为真实地址。常用项：

| 变量 | 服务 | 说明 |
|---|---|---|
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_USERNAME` / `MYSQL_PASSWORD` | api、auth、k8s-server | 同一个库；**网关用的是 `MYSQL_USER`**（不是 USERNAME） |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` / `REDIS_DATABASE` | api、auth、gateway | auth 会话固定用 database 1；api 缓存默认同库，键前缀区分（`spring:session:*` vs `platform:cache:*`） |
| `AUTH_URL` | api、k8s-server、gateway | auth 的 origin，**不带 `/auth`**；各服务自己拼 `/auth/oauth2/jwks` |
| `API_URL` / `API_CONTEXT_PATH` | gateway | platform-api 上游；context-path 默认 `/api` |
| `JWT_AUDIENCES` | api、auth、k8s-server、gateway | aud 白名单（逗号分隔），**四处同值**，默认 `platform-web-client` |
| `K8S_SERVER_URL` | api | k8s-server 地址，默认 `http://127.0.0.1:8080` |
| `KUBECONFIG_AES_KEY` | api、k8s-server | 解密集群 kubeconfig；**与库中密文绑定**，不可随意更换 |
| `CREDENTIAL_AES_KEY` | api | 凭据库主密钥；**与库中密文绑定** |
| `ALERT_WEBHOOK_TOKEN` | api | Alertmanager webhook 共享密钥 |
| `THANOS_QUERY_URL` | api | 指标曲线数据源 |
| `TEMPO_QUERY_URL` | api | 链路追踪数据源 |
| `PLATFORM_ES_URL` / `PLATFORM_ES_USERNAME` / `PLATFORM_ES_PASSWORD` | api | 日志查询（留空 = 页面提示未配置） |
| `DOCKERHUB_ENABLED` | api | 是否探测 Docker Hub 公开仓库（镜像来源解析） |
| `OBJECT_STORAGE_DEFAULT_MAX_BUCKETS` / `_MAX_UPLOAD_MB` / `_MAX_DOWNLOAD_MB` | api | 对象存储默认每租户桶上限、单次上传/下载大小上限 |
| `OUTBOUND_PROXY_SERVER` / `_PORT` / `_TARGETS` | api | **白名单**出站代理：只有列出的目标走代理，内网一律直连 |
| `PIPELINE_LOG_MAX_BYTES` / `PIPELINE_LOG_RETENTION_DAYS` | api | 单步日志上限、日志保留天数 |
| `PIPELINE_ARCHIVE_ENABLED` / `PIPELINE_RUN_TIMEOUT` / `PIPELINE_TIMEOUT_CHECK` | k8s-server | CI 归档与超时对账 |
| `PIPELINE_LOG_MAX_BYTES` | k8s-server | **必须与 platform-api 那份一致** |
| `COMPONENT_CHARTS_DIR` | k8s-server | 内置 chart 目录，镜像里是 `/app/charts` |
| `OBJECT_STORAGE_NAMESPACE` / `OBJECT_STORAGE_CONFIG_SECRET` / `OBJECT_STORAGE_S3_DEPLOYMENT` | k8s-server | SeaweedFS 组件的定位参数 |
| `HARBOR_DEFAULT_HOST` / `HARBOR_TLS_CERT_FILE` / `HARBOR_TLS_KEY_FILE` | api | Harbor 安装表单默认值 |
| `VITE_OAUTH_ISSUER` / `VITE_OAUTH_CLIENT_ID` | platform-web（**构建期**） | 改了要**重新构建**，不是重启容器 |
| `PLATFORM_OAUTH_REDIRECT_URI` | platform-web（**运行时**） | 空 = 前端取页面 origin + `/callback`；非空则原样作为 OAuth redirect_uri |
| `VITE_OAUTH_REDIRECT_URI` | platform-web（**构建期，可选**） | 运行时值为空时才生效；仅为兼容保留 |
| `PLATFORM_GATEWAY_URL` / `PLATFORM_AUTH_URL` | platform-web（nginx） | `/api/**` 一律转网关（不再直连 api）；地址**不要以 `/` 结尾** |
| `OAUTH_UPSTREAM` | platform-web dev server | 真实 auth 的 origin（不带 `/auth`） |

### 5.1 纳管集群的集群侧前提

有些能力不只看平台代码，还取决于集群里的配置（Tekton feature flag、配额下的默认容器资源、
可动态供应的 StorageClass / RWX 存储、Istio 指标采集、Alertmanager 指向平台等）。
完整清单与可粘贴的命令见 [docs/development/cluster-prerequisites.md](docs/development/cluster-prerequisites.md)；
这部分**不在仓库里**，换集群/重建集群要重做。

---

## 6. 目录结构

```
k8s-cloud-platform/
├── k8s-core/            fabric8 operations / converter / factory（仅 k8s-server 依赖）
├── k8s-server/          K8s 执行面；内置 charts、预置 Helm 与 ManifestApplier
├── platform-auth/       OAuth2/OIDC 授权服务器（/auth）
├── platform-gateway/    API 网关：接口级鉴权、403 审计、路由
├── platform-api/        管理面 BFF：业务、DB、聚合缓存
├── platform-common/     JWT、响应码、通用模型
├── platform-data/       MyBatis Mapper/实体 + resources/db/migration（**手工重放**）
├── platform-web/        Vue 3 SPA；src/views 按域分目录，utils/clusterInstall 是向导的纯函数
├── docs/                设计与运维文档（见 §8）；images/ 放 README 截图；dbback/ 早期全量 dump
├── scripts/             harbor-replication.py（镜像同步进 Harbor）、fetch-k8s-tools.sh（更新 Helm 包）
└── cert/                本地部署证书（被 .gitignore 排除）
```

被 `.gitignore` 排除的本地内容：`cert/`（部署证书）、`.claude/`、`.superpowers/`、各模块 `target/`。

---

## 7. 必须知道的约定与注意事项

### 7.1 没有数据库迁移工具

- 脚本在 `platform-data/src/main/resources/db/migration`，命名 `V<日期>_<序号>__说明.sql`。
- 启动日志里**永远不会有** "Migrating schema" —— 那是正常的，不是配置漏了。
- 上线流程固定为：**按序重放新增 SQL → 再起新构建**。代码与迁移必须同批，迁移先于构建。
- 迁移里写的权限行是 fail-closed 校验的输入（见下条）。

### 7.2 权限是 fail-closed 的，权限行必须进迁移

- API 域按 `(服务, HTTP 方法, 服务内路径)` 映射到权限码，默认拒绝，**判定在 platform-gateway**；
  各服务启动期自检（api 的 `PermissionCrossCheckRunner`、auth 的 `InternalEndpointCrossCheckRunner`），
  **漏配权限行 = 应用拒绝启动**。网关每 60s 重载权限表。
- ⚠️ api 自己只校验登录：api / auth 端口必须只对网关开放（NetworkPolicy），见 [platform-gateway/README.md](platform-gateway/README.md)。
- 权限码**不在 token 里**（2026-10-09 起）：platform-api 在请求期现算权限闭包（30s TTL 缓存），
  因此改角色**即时生效**，陈旧上界是一个缓存 TTL。
- 回滚开关：`platform.jwt.permissions-in-token=true`。
- 可分配权限集合由后端 `/role/permission/assignable` 下发，**前端不要自己按 `platform:` / `tenant:` 前缀过滤**。
- **页面码与 API 码是两件事**：页面可见性用 `pageCodes.ts` 的 Page 域码（`meta.requiresPerm`），
  接口是否放行仍由权限表按 API 码判定。

### 7.3 分层是硬规则

```
HTTP → Controller（只做绑定与包装）→ Service（业务/编排/降级）→ Client（传输）→ k8s-server
```

- controller **禁止**注入 `K8s*Client` / `K8sServerGateway`，**禁止**出现 k8s-server 路径字符串，**禁止**业务判断。
- 每个资源一个 Service，即使当前是纯透传。
- **k8s-server 零业务逻辑**（用户钦定）：它只回答"能不能碰这个端点""能碰哪些 namespace""用谁的凭据"。
- **同类资源不抽公共基类**：宁可逐资源各写一份（2026-10-09 明确否决抽象提案）。
- 完整判据与自查清单：[docs/development/backend-layering.md](docs/development/backend-layering.md)。

### 7.4 安全边界分四层，`K8s*Client` 不是边界

| 层 | 实现 | 判什么 |
|---|---|---|
| ① 网关权限表（platform-gateway） | 权限码 ANY-of，默认拒绝 | 细粒度"能不能做这件事" |
| ② k8s-server 端点边界 | `AccessBoundary`（`PLATFORM` / `TENANT` / `UPSTREAM`）+ `PLATFORM_SCOPE` | 哪个端点是平台侧 |
| ③ k8s-server 资源边界 | `ResourceAccessResolver` + 分配表三元组 | 能碰哪些 namespace、用谁的凭据 |
| ④ K8s RBAC | 租户 SA / admin kubeconfig | 集群侧第二道闸 |

新增 controller 必须继承基类或实现 `AccessBoundaryAware`，**读不到声明会被 fail-closed 拒绝**（连平台管理员一起 403）。
分配表的授权范围完全由 `platform_tenant_namespace` 决定 —— 判断"算不算越权"前先查表，别只看代码。

### 7.5 OAuth 必须同源

- `platform-auth` 配了 `server.servlet.context-path=/auth`，所有端点都在 `/auth/**`；
  反代只需一条 `/auth/` 前缀，且 **proxy_pass 不能带 URI**（会剥掉前缀）。
- platform-api / k8s-server / gateway 的 `AUTH_URL` **只写 origin**（不带 `/auth`），各服务自己拼 JWKS 地址。
- 前端 `VITE_OAUTH_ISSUER` 只写上下文路径 `/auth`；`redirect_uri` 必须与 auth 库中注册的一字不差。
- 续期/keepalive/登出依赖 `SameSite=Lax` 的会话 Cookie：`localhost` 与 `127.0.0.1` 也算跨站。
- `/internal/**` 不再用共享密钥头，改为校验 JWT（网关原样透传 token）；`X-Platform-Internal-Token` 已删除。
- 局域网 **http 是非安全上下文**：`crypto.subtle`、`navigator.clipboard` 不存在，代码里已有纯 JS / execCommand 兜底，
  新代码不要假设它们可用。

### 7.6 写 K8s 对象：SSA 与 ManifestApplier

- fabric8 7.9.0 的 `create` / `update` 统一走 Server-Side Apply，`fieldManager=platform-system`、`force`。
- **atomic list 字段**（如 Gateway 的 `listeners`、某些 CRD 的列表）SSA 不会合并，必须建模或
  "读线上对象 → 整体覆写"（fetch-overlay），否则会丢字段。
- 部分字段（如 Deployment 回滚用的 `pod-template-hash`）必须在写入前剥掉。
- **原样清单（YAML 字符串）一律走 `ManifestApplier`**，不要调 kubectl（镜像里已经没有）：Calico CRD、应用发布、
  初始化 Job 都是这条路。三个约束：按 **YAML 1.1** 解析；没建模的 CRD 落成通用模型；缺 `metadata.namespace`
  时补调用方的默认命名空间。单份清单 30s 超时。

### 7.7 数量单位

K8s `Quantity` 在 DTO 层统一为 **`BigDecimal` 基础单位**：CPU = 核数，内存/存储 = 字节。
前端负责换算与展示；新增接口沿用这一约定。

### 7.8 前端

- 菜单由**路由 meta**（`domain` / `icon` / `order` / `requiresPerm`）派生，加页面 = 在路由上补 meta，
  不要维护第二份菜单清单。同名域跨视图时必须用 `resolveNavLocation().otherFace` 换对面那份。
- 命令面板与侧栏**同源**（`buildSearchEntries`）：页内功能靠路由 `meta.actions` 声明，页面用
  `usePaletteAction` 接住 `?do=<key>`；声明了不接 = 选中后什么也不发生。
- 平台视图 = 管理员专属：非管理员没有该选项，bootstrap 自动落进第一个启用租户。
- **免登录页必须挂 `PublicLayout`**（如 `/clusters/new` 新建集群向导）：`MainLayout` 依赖登录态与权限引导，
  挂上去只会得到空壳侧栏和一串 401。
- 跨命名空间列表是**只读**的：行内操作按顶栏上下文的 namespace 解析，在全局视图里操作会作用到别的命名空间。
- 权限码不再写进 token（2026-10-09 起）。`data.permissions` 缺失表示"由服务端下发"，前端若用 `?? []` 兜底
  会把菜单清空 —— 必须区分"字段不存在"与"空数组"。
- Element Plus 弹窗内部节点没有 `data-v-*`，scoped `:deep()` 匹配不到 —— 用非 scoped 块 + 独有 class 前缀。

### 7.9 运行形态

- `k8s-server` 里的流水线归档、超时对账、集群探活、命名空间回收是**后台定时任务**，不要跑多副本。
- 本地开发与集群**共用同一个库**时，两个发布 worker 会抢执行：执行记录带 `owner_id` + 租约，别手工改这两列。
- `CREDENTIAL_AES_KEY` / `KUBECONFIG_AES_KEY` 必须与库中已有密文一致：换值 = 所有凭据/kubeconfig 解不开。
- Redis 只是优化：读失败会退化为直查源；但它与 auth 会话**共用一个 database**，运维时不要 `FLUSHDB`。
- 告警链路的集群侧配置见 [cluster-prerequisites.md](docs/development/cluster-prerequisites.md) §1.8。

---

## 8. 文档索引

| 文档 | 内容 |
|---|---|
| [docs/development/backend-layering.md](docs/development/backend-layering.md) | 后端分层、client 划分、四层安全边界、新增端点 checklist |
| [docs/development/frontend-permission-conventions.md](docs/development/frontend-permission-conventions.md) | 前端权限约定、页面码 vs API 码、视图收敛 |
| [docs/development/cluster-prerequisites.md](docs/development/cluster-prerequisites.md) | 纳管集群的集群侧前提（Tekton、配额、存储、网格指标、告警链路） |
| [docs/development/middleware-store.md](docs/development/middleware-store.md) | 中间件商店：可用条目、实例形态、指标接入、已知限制 |
| [docs/development/object-storage.md](docs/development/object-storage.md) | 对象存储：桶、凭据、权限、限制 |
| [docs/development/application-release.md](docs/development/application-release.md) | 应用发布：模型、变量引用、配置文件、暴露 / 灰度 / 调度挂载 / 事件、上线步骤与验收清单 |
| [docs/development/image-registry.md](docs/development/image-registry.md) | 镜像来源纳管与 Harbor 复制规则 |
| [docs/development/resource-protection.md](docs/development/resource-protection.md) | 受管资源保护（组件/中间件实例不可误删） |
| [docs/development/pvc-migration-nfs-csi.md](docs/development/pvc-migration-nfs-csi.md) | PVC 从 nfs-client 迁到 nfs-csi 的方案 |
| [k8s-server/README.md](k8s-server/README.md) | 执行面设计原则、端点边界、模块协作 |
| [platform-auth/README.md](platform-auth/README.md) | 授权服务器、令牌签发、session-renewal grant |
| [platform-gateway/README.md](platform-gateway/README.md) | 网关：鉴权与审计、**部署顺序**、NetworkPolicy、长连接、验收清单 |
| [docs/CHEATSHEET.md](docs/CHEATSHEET.md) | fabric8 Kubernetes Java Client 速查 |
| [docs/superpowers/specs](docs/superpowers/specs) / [plans](docs/superpowers/plans) | 各批次设计规格与实施计划（含部署 runbook）；集群向导、流水线源码预览、应用暴露/灰度、租户工作流都在这里 |

---

## 9. 当前状态

- 代码以 `main` 分支为基线；多数近期批次"已实施、待部署"，部署前请对照 `docs/superpowers/plans/`
  里对应批次的迁移清单（先重放 SQL，再重建镜像）。当前迁移序号到 `V2026_11_20_1`。
- 仓库**没有自动化部署脚本**：构建、迁移、重启按本文与各批次 runbook 手工执行。
