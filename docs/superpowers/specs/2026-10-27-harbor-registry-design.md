# Harbor 集群组件 + 租户镜像项目集成 · 设计

- 日期：2026-10-27
- 状态：已与用户逐节确认，待实施
- 关联：S3.6 组件安装（`ComponentRecipeCatalog` / `charts/components`）、S0.2 凭据管理、S3.5 流水线镜像节点、S3.1 镜像仓库浏览

## 1. 背景与目标

集群组件体系（S3.6）已能安装 metrics-server / Prometheus / Istio / Tekton / Calico / cert-manager / OTel / SeaweedFS，
但**没有镜像仓库**。平台现有的 CI 只能把镜像推到外部 Harbor / Docker Hub：凭据要管理员手工在
「凭据管理」里登记，Harbor 侧的项目与机器人账号也要人工建好，租户才能配出可用的镜像节点。

本批做两件事：

1. **Harbor 作为可安装的集群组件**（chart 入库、探测、安装表单、平台生成管理口令）；
2. **装完之后平台自动把租户接进去**：按租户建 Harbor project、发 robot 账号、把
   `endpoint + 项目 + 账号` 登记成一条**租户归属**的 REGISTRY 凭据，流水线的镜像节点开箱可用。

成功的判据：在一个装了 Harbor 的集群里，租户管理员打开流水线编辑器，能在镜像节点里选到
「自己租户的那条 Harbor 连接」，填 `repository:tag` 后运行，镜像成功 push 到本集群 Harbor；
另一个租户看不到、也用不了这条连接。

## 2. 范围

**做**

- Harbor chart 1.19.4（app 2.15.4）入库 + recipe + 探测 + 安装/编辑表单 + 管理口令 Secret；
- 每个租户一个 project + 一个 push/pull robot + 一条租户凭据；平台共享一个 `platform` project/凭据；
- 触发：Harbor 安装成功、新建/删除/停用租户、管理员手动同步/重建；
- 凭据的租户归属与流水线/仓库浏览侧的隔离；
- 迁移、权限行、前端三处小改、测试与真集群验收步骤。

**不做（本期）**

- 租户登录 Harbor 控制台（OIDC / 账号下发）；
- 删除租户时删项目或镜像（只回收 robot 与凭据行，数据保留）；
- 项目配额、镜像保留策略、远程复制、签名（Cosign）；
- 外部 PostgreSQL/Redis、TLS 终止（当前是 external-gateway 的 HTTP listener）；
- 一个集群多套 Harbor、跨集群共享同一 Harbor。

## 3. 组件安装

### 3.1 基本信息

| 项 | 值 |
|---|---|
| 组件 code | `harbor` |
| 画像 | BUSINESS / MANAGEMENT 都有（同一 recipe） |
| chart | `harbor` 1.19.4，appVersion 2.15.4，来源 `https://helm.goharbor.io` |
| 入库路径 | `k8s-server/src/main/resources/charts/components/harbor/1.19.4/chart/`（官方 chart 原样，不改模板） |
| release / namespace | `platform-harbor` / `harbor`（Helm `--create-namespace`） |
| 探测判据 | `harbor` 命名空间 Deployment `harbor-core`，镜像含 `goharbor/harbor-core` |
| 前置组件 | `istio`（route 暴露依赖 external-gateway） |
| 平台功能 code | `container-registry` |

chart 自带 PostgreSQL / Redis / registry / jobservice / portal / core 的模板（1.19.x 无子 chart），
大小约 634KB，普通 Helm release 即可（不触发 1MiB 的 release Secret 上限，不需要 `SERVER_SIDE_APPLY`）。

### 3.2 契约值（🔒）

```yaml
fullnameOverride: harbor                 # 探测认 harbor-core；release 名可变不影响
expose:
  type: route                            # Gateway API HTTPRoute；不部署 nginx
  tls:
    enabled: false                       # TLS 在网关上终止，Harbor 自身 HTTP
  route:
    parentRefs:
      - name: external-gateway
        namespace: istio-system
    hosts: ["${harbor.host}"]
externalURL: "http://${harbor.host}"
existingSecretAdminPassword: platform-harbor-secrets   # 键 HARBOR_ADMIN_PASSWORD
existingSecretSecretKey: platform-harbor-secrets       # 键 secretKey（Harbor 要求 16 字符）
persistence:
  enabled: true
  resourcePolicy: keep
database: { type: internal }
redis: { type: internal }
# core / portal / registry / jobservice / database / redis / trivy 的 resources 显式钉住
```

| 值 | 为什么是契约 |
|---|---|
| `fullnameOverride: harbor` | 探测认 `harbor-core`；不钉名字，release 名一变面板就永远「未安装」（与 metrics-server / SeaweedFS 同款坑） |
| `expose.type: route` | 用户选定走 `istio-system/external-gateway` 的 HTTPRoute。**route 模式不部署 nginx**（chart 模板条件），core/portal 的 ClusterIP Service 直接作为 backend，跨命名空间路由由 Gateway 的 `allowedRoutes.namespaces.from: All` 允许 |
| `expose.tls.enabled: false` | route 模式忽略 tls 证书段；当前 external-gateway 的 443 是 TLS Passthrough、80 是 HTTP，本期走明文 HTTP |
| `externalURL` | Harbor 的 token 服务按它返回地址，docker/podman 登录与 push 都依赖它；**必须与 route host 一致** |
| `existingSecretAdminPassword` / `existingSecretSecretKey` | 管理口令与加密 secretKey 由平台生成、`helm upgrade` 不轮换；不进 values 快照，也不进 platform-api 日志 |
| `persistence.resourcePolicy: keep` | 卸载 release 不删镜像与数据库 PVC（平台不提供卸载入口，但手工 uninstall 也不该毁数据） |
| `database.type: internal` / `redis.type: internal` | 内网单集群部署，本期不接外部中间件 |
| resources | Harbor 默认不带 requests；显式钉住避免把节点挤爆（也就不会撞命名空间配额） |

### 3.3 表单（⚙️）

专属字段只有一个：

| 字段 | 默认 | 说明 |
|---|---|---|
| `host` | 无（必填） | 如 `harbor.example.com`，不带 scheme/path；校验后写进 `externalURL` 与 `expose.route.hosts` |

其余用 `editableValues` 的「路径 → 标量」模型（前端按现有分组渲染）：

| 路径 | 默认 |
|---|---|
| `persistence.persistentVolumeClaim.registry.storageClass` / `.size` | 空 / `20Gi` |
| `persistence.persistentVolumeClaim.jobservice.jobLog.storageClass` / `.size` | 空 / `1Gi` |
| `persistence.persistentVolumeClaim.database.storageClass` / `.size` | 空 / `5Gi` |
| `persistence.persistentVolumeClaim.redis.storageClass` / `.size` | 空 / `1Gi` |
| `persistence.persistentVolumeClaim.trivy.storageClass` / `.size` | 空 / `5Gi` |
| `trivy.enabled` | `false`（内网拉不到漏洞库，默认不装；表单可开） |

StorageClass 空串按现有语义**跳过**（跟随集群默认存储类）；K8s 里 `storageClassName: ""` 表示
「不用动态供给」，会装出一组永远 Pending 的 PVC。

`expose.route.hosts` 是 list，`editableValues` 表达不了，故 `host` 走专属表单 + 占位符
`${harbor.host}`（`ValueTemplate` 递归解析，list 里也能替换）。

### 3.4 管理口令 Secret

安装前 k8s-server 确保 `harbor/platform-harbor-secrets` 存在（幂等 SSA，已存在不轮换）：

| 键 | 内容 |
|---|---|
| `HARBOR_ADMIN_PASSWORD` | 随机 32 位 |
| `secretKey` | 随机 16 位（Harbor 硬要求 16 字符） |

新增 k8s-server 端点 `POST /components/harbor-secrets`（照 `seaweed-admin-secret` 的形状），
platform-api 在 helm 之前调用。口令不经过 platform-api 的 values 快照，也不落库。

⚠️ **Secret 丢失不等于可以重新生成**：Harbor 只在首次初始化时读 `HARBOR_ADMIN_PASSWORD` 写库，
`secretKey` 还用于加密库里的字段。所以端点规则是：

- Secret 不存在且 `harbor-core` **尚不存在**（首次安装）→ 生成并写入；
- Secret 不存在但 `harbor-core` 已存在（已初始化）→ **报错拒绝**，提示「管理口令 Secret 丢失，
  Harbor 已初始化，需人工按 Harbor 官方方式重置管理员口令/secretKey 后再安装」；
- Secret 已存在 → 原样保留（幂等），升级不轮换。

### 3.5 探针与功能 code

- `ComponentDef("harbor", "Harbor（镜像仓库）", null, [Probe(harbor, deployment, goharbor/harbor-core, harbor-core)], ["container-registry"])`
- 功能 code `container-registry` 本期只用于组件页展示与后续门控，**不**用来禁用流水线页
  （流水线可以指向别的仓库，没有「必须先装 Harbor」这条产品规则）。

### 3.6 暴露链路与运维前提

HTTPRoute（`harbor` 命名空间）→ `harbor-core` / `harbor-portal` 的 ClusterIP Service；
parentRef 指向 `istio-system/external-gateway`，其 listener 的 `allowedRoutes.namespaces.from=All`。

⚠️ **`host` 必须能被三边解析**：集群内 Pod（流水线 push/pull）、平台所在机器（仓库浏览/同步）、
外部 docker 客户端。Harbor 的 token 服务按 `externalURL` 返回地址，三边不一致时表现为
「登录成功但 push 到错误地址 / 解析失败」。这条写进组件 README 的「装之前必须确认」。

⚠️ Harbor 镜像较大（core / registry / db / redis 合计 >1GB），`helm --timeout 10m` 在慢网络下可能不够；
失败后可在组件页重试（release 已创建，重试是 upgrade）。

## 4. 平台集成

### 4.1 概念

| 概念 | 含义 |
|---|---|
| project | Harbor 的镜像隔离单位；一个租户一个（平台自己一个） |
| robot 账号 | Harbor 项目级机器人账号，只对该 project 有 repository push/pull；Harbor 内名字形如 `robot$<project>+ci` |
| 凭据行 | `platform_credential` 里的一条 `REGISTRY` 连接：endpoint = Harbor host，认证字段 = robot 名 + secret |
| 项目台账 | `platform_registry_project`：把 (集群, 租户, host, project, robot id, 凭据 id) 绑在一起，供同步、重建、UI 使用 |

租户通过**一条平台凭据**使用 Harbor，不感知 Harbor 账号体系；流水线节点引用这条连接，
运行期由平台解密并下发成构建 Pod 的 registry secret（现有链路，不改）。

### 4.2 数据模型

**迁移 `V2026_10_27_1__harbor_registry.sql`**（手工重放；幂等：`information_schema` 条件式 ADD COLUMN + `CREATE TABLE IF NOT EXISTS`）

```sql
ALTER TABLE platform_credential
  ADD COLUMN tenant_id varchar(64) NULL COMMENT '归属租户；NULL = 平台级（现有全局凭据语义）',
  ADD COLUMN source    varchar(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / HARBOR（自动维护，凭据页只读）';
-- 索引 idx_credential_tenant (tenant_id)

CREATE TABLE IF NOT EXISTS platform_registry_project (
  id             varchar(64) NOT NULL,
  cluster_id     varchar(64) NOT NULL,
  tenant_id      varchar(64) NULL COMMENT 'NULL = 平台共享项目',
  host           varchar(255) NOT NULL,
  project_name   varchar(255) NOT NULL,
  credential_id  varchar(64) NULL,
  robot_id       bigint      NULL COMMENT 'Harbor 机器人账号 id（重建时按它删）',
  status         varchar(16) NOT NULL DEFAULT 'READY' COMMENT 'READY / ERROR',
  message        varchar(1024) NULL,
  last_synced_at datetime NULL,
  created_at     datetime NOT NULL,
  updated_at     datetime NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_registry_project (cluster_id, project_name),
  KEY idx_registry_project_tenant (cluster_id, tenant_id)
) COMMENT='租户在某个 Harbor 上的镜像项目台账';
```

`platform_credential.tenant_id` 为 NULL 的行与今天的全局凭据完全同义 —— 现有调用方行为不变。

### 4.3 命名

| 对象 | 规则 |
|---|---|
| 租户 project | `t-<tenant.serviceAccount>`（serviceAccount 全局唯一、已满足 Harbor 的小写/长度约束） |
| 平台 project | `platform` |
| robot 名 | `ci`（Harbor 自动加前缀：`robot$t-xxx+ci`）；权限 = 该 project 的 `repository` push + pull；不设过期 |
| 凭据行名 | `Harbor <project>@<clusterId>`（≤64；同一集群内 project 唯一，跨集群同名项目不冲突） |
| 凭据 description | `Harbor 自动创建：<clusterName> / project <project>` |

### 4.4 同步流程（幂等）

对每个目标（每租户 + 平台共享）：

1. 取 Harbor 管理账户：platform-api 经 k8s-server 既有 Secret 读取端点读 `platform-harbor-secrets`
   的 `HARBOR_ADMIN_PASSWORD`（只在内存里拼 Basic 头，不写日志、不落库）。
2. `POST /api/v2.0/projects` 建 project；**409 = 已存在，接管**（message 记「项目已存在，已接管」）。
3. 若台账行已有可用凭据（凭据行存在、secret 可解密、`enabled=1`）→ **跳过**（普通同步不重建）。
4. 否则：按名字/robot_id 删掉同名旧 robot（拿不到旧 secret，只能删后重建）→ 建 robot → 用返回的
   `secret` 加密写凭据行（AES，与手工凭据同一套 `CredentialCipher`）→ 写台账行 `status=READY`。
5. 任一步失败：台账行 `status=ERROR` + `message`，**不回滚**已建的项目；汇总返回。

**重建账号**（`force`）：跳过第 3 步，删 robot → 建新 robot → 覆盖凭据密文。用于口令泄露或凭据被手工改坏。

**并发**：同进程内按 `clusterId` 加互斥锁；平台当前单副本部署，跨实例并发不在本期保证（写进已知限制）。

### 4.5 触发时机与失败语义

| 触发 | 行为 | 失败影响 |
|---|---|---|
| Harbor 安装/升级成功（`ComponentInstallService.run`） | 对全部启用租户 + 平台共享同步一次 | 沿用「告警联动」先例：组件已装好但同步失败 → 该次组件操作记 `FAILED`，message 写「组件已安装，但镜像项目/凭据同步失败：…」；可在组件行重试 |
| 新建租户（`TenantService.create`） | 对已装 Harbor 的集群 best-effort 同步该租户 | **不阻塞建租户**；日志 + 留给手动同步 |
| 删除租户（`TenantService.delete`） | 删 robot + 凭据行 + 台账行；**保留 Harbor 项目与镜像** | best-effort，日志 |
| 停用/启用租户（`TenantService.update`） | 凭据行 `enabled` 跟随租户状态 | 同上 |
| 管理员手动「同步全部」/「重建账号」 | 全量对账 / 强制重建 | 返回逐租户结果摘要 |

「已装 Harbor 的集群」= 该集群存在 `harbor` 组件**最近一次成功**的安装/升级操作记录（只读平台库，
不为此逐个集群探活）。

### 4.6 凭据的租户隔离（本批必须补的洞）

现状：`CredentialService.pipelineUsableRegistryConnections`、`PipelineController.registry-connections`、
`RegistryService.allSources`（供租户的「镜像仓库」页做连接列表/补全/浏览）都会把**全部**启用凭据
返回给任何租户。加入租户归属的 Harbor 凭据后，这会变成「租户 A 拿租户 B 的 robot 账号推 B 的项目」。

改动：

- 新增统一判据：`visibleTo(c, hatTenantId) := c.tenantId == null || c.tenantId.equals(hatTenantId)`。
- `pipelineUsableRegistryConnections(hatTenantId)`、`pipelineUsableConnections(type, hatTenantId)` 用它过滤；
- `assertUsableByPipelines(id, expectedType, hatTenantId)` 同样校验（防止手填别家的 id）；
- `RegistryService.allSources(hatTenantId)` 只换这一个真相源（补全/浏览/连接列表都从它来），
  其余语义（`enabled`、内置 Docker Hub 档）不变；
- 由 controller 把 `AuthContext.hatTenantId()` 显式传进 service（不把 AuthContext 塞进 RegistryService，
  保持现有单测可手工构造）；base token（管理员）为 null → 看全量。

`ConnectionView` 增 `project` 字段（从 `platform_registry_project` 关联；手工凭据为 null），
镜像节点下拉显示「连接名（项目 t-xxx）」。

### 4.7 Harbor API 调用清单

| 用途 | 方法 / 路径 |
|---|---|
| 建项目 | `POST /api/v2.0/projects`，body `{"project_name":"<p>","public":false}`；409 = 已存在 |
| 查项目 | `GET /api/v2.0/projects/{project_name}`；404 = 不存在 |
| 建 robot | `POST /api/v2.0/robots`，body `{name:"ci", level:"project", duration:-1, permissions:[{kind:"project", namespace:"<p>", access:[{resource:"repository",action:"push"},{resource:"repository",action:"pull"}]}]}`；响应含 `id` 与一次性 `secret` |
| 删 robot | `DELETE /api/v2.0/robots/{robot_id}` |
| 列 robot | `GET /api/v2.0/robots?q=Level=project,ProjectID=<id>`（找同名旧账号） |

- 出站规矩与 `RegistryHttpClient` 一致：显式连接/读超时、**不跟随重定向**、Basic 认证、
  内网地址不走代理（`OutboundProxy` 白名单）。
- ⚠️ 上述字段名按 Harbor v2.0 API 编写，**实施时先用真集群的 `/devcenter-api-2.0` 核对一遍**
  （尤其 `duration` 的 -1 语义与 robot 响应里的 `secret` 字段），核对结果回写本节。

## 5. API 与权限

| 端点 | 权限码 | 绑定 |
|---|---|---|
| `POST /registry/projects/list`（body: clusterId） | `platform:registry:project:read` | admin |
| `POST /registry/projects/sync`（body: clusterId） | `platform:registry:project:manage` | admin |
| `POST /registry/projects/rebuild`（body: clusterId, tenantId） | `platform:registry:project:manage` | admin |

权 `V2026_10_27_1` 里同时插入权限行与 `builtin_role_admin` 关联（显式 id 列表 + ODKU，沿用本仓惯例：
新权限行必须显式绑角色，不靠 code 继承）。

租户侧**不开新端点**：项目名随 `/pipeline/registry-connections`（`tenant:pipeline:read`）与
`/registry/connections`（`tenant:registry:catalog`）带出。

写操作（sync / rebuild）加审计注解（`@Auditable`），与其它平台写操作一致。

## 6. 前端

1. `ClusterComponentsPanel.vue`
   - Harbor 行：安装/编辑弹窗（`host` 输入 + 5 组 StorageClass/容量 + Trivy 开关）；
   - 行操作下拉加「镜像项目」→ 弹窗列出该集群的每租户/平台项目、robot 状态、上次同步结果；
     带「同步全部」与逐行「重建账号」。
   - 专属表单分派沿用现有 `row.seaweedStorage` / `row.calicoNetwork` 那套：新增 `harborRegistry` 标记。
2. `CredentialView.vue`
   - 加「归属」（平台 / 租户名）与「来源」（手工 / Harbor）列；
   - `source=HARBOR` 行只读（编辑/删除禁用），提示「由 Harbor 组件同步维护，请在集群组件面板重建」。
3. `NodePropsPanel.vue`
   - 镜像仓库连接下拉 label 带项目名；不影响选中值（仍是连接 id）。

## 7. 错误处理与边界

| 场景 | 行为 |
|---|---|
| Secret 不存在（首次安装前） | 平台生成并写入（§3.4） |
| Secret 丢失但 Harbor 已初始化 | 拒绝安装/升级并提示人工恢复（§3.4）：重新生成的口令与 Harbor 库里的哈希不一致，同步必然 401 |
| Harbor 401/403 | 同步失败，摘要提示核对管理口令；不自动重试 |
| Harbor 不可达 / DNS 未就绪 | 台账 `ERROR`，摘要里给地址与原因；修 DNS 后手动同步 |
| project 已存在但不是我方创建 | 接管并在 message 里标注，不删除、不报错 |
| robot 同名但非我方 | 按名字匹配后删除重建（有可能误删人工账号）——**只删名字恰为 `ci` 且属于该 project 的 robot**，并在 message 里记录 |
| 租户已软删除 | 同步时跳过（`deleted_at IS NULL`） |
| 同一租户在两个集群都有 Harbor | 两条项目台账 + 两条凭据（名字带 clusterId 区分），流水线按集群各自选 |
| Harbor 组件被手工卸载 | 凭据仍在（流水线会失败）；组件页探测显示未安装；重新安装后手动同步可恢复（新 Harbor 无旧 robot，会重建） |

## 8. 测试

- `ComponentRecipeCatalogTest`：新 chart 版本与内置 `Chart.yaml` 交叉校验（现有测试自动覆盖）；
  新增断言 `harbor` 的契约值含 `expose.type=route`、主 release 正确。
- `ClusterComponentCatalogTest`：组件顺序断言补 Harbor 一行。
- 新增 `HarborNamingTest`：project / 凭据名 / host 校验（拒绝带 scheme、路径、空串）。
- 新增 `HarborAdminClientTest`（`MockRestServiceServer`）：project 201 与 409、robot 建删、响应无 secret
  时视为失败、非 2xx 转业务码且消息含状态码。
- 新增 `HarborRegistryServiceTest`：首次同步建项目/robot/凭据；第二次同步零创建请求（幂等）；
  `force` 重建会先删后建并覆盖密文；失败时台账 `ERROR` 且不回滚项目。
- 扩展 `CredentialServiceTest`：租户过滤（自己的 + 平台级可见、别人的不可见）；
  `assertUsableByPipelines` 对别人的租户凭据抛错。
- 扩展 `RegistryServiceTest`：`allSources` 过滤后，租户 A 用 B 的 credentialId 浏览得到「不存在或已停用」。

真集群验收（test-work-cluster）：

1. 装 Harbor → `kubectl -n harbor get pods` 全 Ready；HTTPRoute `Accepted=True`、`AttachedRoutes=1`；
2. 组件页「同步全部」→ Harbor 里出现 `t-*` 与 `platform` 项目、`robot$t-*+ci`；
3. 租户 A 建流水线，镜像节点只看到自己的连接；`docker login <host>` + push 成功；
4. 租户 B 登录后看不到 A 的连接；手工传 A 的 credentialId 调 `/registry/browse` 被拒。

## 9. 部署与回滚

1. 重放 `V2026_10_27_1__harbor_registry.sql`（**先于新构建**：新端点无权限行会被启动期交叉校验拒启）；
2. 重建 **k8s-server**（chart 进镜像 + 新 Secret 端点）、**platform-api**、**platform-web**；
3. 重新登录（权限闭包请求期现算，缓存 30s；稳妥起见重登）；
4. 在目标集群装 Harbor，同步后按 §8 验收。

回滚：本批无数据破坏性变更（新列可空、新表独立）；停用路径是把 Harbor 组件从面板卸载
（平台不提供卸载入口，手工 `helm uninstall` 会保留 PVC，见 `resourcePolicy: keep`）。
凭据行的 `source=HARBOR` 只读规则由前端控制，后端不做硬拒绝（方便手工清理）。

## 10. 风险与已知限制

- **DNS 是硬前提**（§3.6）；三边解析不一致是本期最容易踩的坑。
- **管理口令 Secret 不可重建自愈**（§3.4）：丢失后要人工重置 Harbor 管理员口令与 `secretKey`
  （后者变了会让库里已加密字段失效），属于运维事故而不是重试能解决的失败。
- Harbor 资源占用大（core+registry+db+redis+jobservise 合计约 1~2GB 内存起），小集群需先确认容量。
- 同步时按名字删 robot（§7）理论上可能误删人工建的 `ci` 账号；用 `ci` 之外的名字可规避。
- 跨实例并发同步未加分布式锁（平台当前单副本）。
- 凭据轮换只能「重建」（旧 secret 无法从 Harbor 取回）。
- Harbor API 字段以实施时真机核对为准（§4.7）。
