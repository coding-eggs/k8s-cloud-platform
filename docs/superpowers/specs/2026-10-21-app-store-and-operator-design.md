# 应用商店与 Operator 管理设计（2026-10-21）

> ⚠️ **2026-10-22 已被取代**：通用 chart 商店、目录、来源搜索部分作废，改由
> [2026-10-22-middleware-store-design.md](2026-10-22-middleware-store-design.md) 定义的
> 「固定清单 + Operator 实例」方案取代。本文件仅保留作为 Helm 执行链与静态检查的历史设计依据。

> 需求出处：2026-10-21 用户提出「先做应用商店」，并在 brainstorming 中确认「**应用商店和 Operator 管理放在一起**」。
> 对应台账 S3.2（应用级交付）+ S3.3（Helm Chart 商店）+ S3.4（Operator 管理）三批，合为一个模块交付。
> 与 S3.6 的关系：**复用同一条 helm 执行链**（`HelmRunner` / 操作记录 / 异步轮询），不新建第二个写入实现。
> 与 S1.2 的关系：组件探测解决"基础设施组件看得见"；本模块解决"用户自己的应用装得上、看得见、升得动"。

---

## 已定决策（brainstorming 结论，2026-10-21）

| # | 决策 | 理由 |
|---|---|---|
| 1 | **一个模块、两个视图**：应用商店 + Operator 管理 | 底层引擎本来就是同一套；但产品面不同——商店按 chart 找，Operator 按「它管理了什么」找。2026-10-12 的「不能用 Helm 商店替代 Operator 管理」仍然成立：这里合并的是模块与引擎，不是把 Operator 塞进 chart 列表 |
| 2 | chart 来源：**本地 Harbor（OCI）+ 公网 Artifact Hub 都接** | 用户确认部署环境两者都可用 |
| 3 | **租户可自助安装**（D6） | 用户选定。租户只能装**目录**里的 chart，装到自己被分配的命名空间 |
| 4 | **白名单目录 + 静态检查** | 租户边界只收窄到命名空间，而 chart 能创建集群级对象；目录钉住版本与 digest，静态检查兜住渲染结果 |
| 5 | **D1：统一 Helm**（复用 S3.6 `HelmRunner`），租户路径加 `--skip-crds --no-hooks` | 资源归属（`meta.helm.sh/*`）、hooks、`crds/`、`--rollback-on-failure` 都只在 Helm 里有；拦截靠"预渲染 + 检查"，不需要换写入路径 |
| 6 | **Operator v1 = 发现 + 详情 + 升级**；安装复用商店的 chart 安装向导；**manifest bundle 安装后置** | 用户选定。Operator 的差异化价值在「它提供哪些 CRD、各有多少实例」 |
| 7 | **operator 只做升级，不做卸载**；应用**做卸载**（`helm uninstall`） | 租户装了就要能删；Operator 卸载会连带 CRD 与 CR 实例，风险等级不同，v1 不开放 |
| 8 | 不在本批：`helm rollback` 按钮、跨集群批量安装、自动发现 chart 新版本、漂移修复 | YAGNI。回滚用历史 values 快照重放代替 |

---

## 1. 交付边界

**做**

1. 应用商店页：目录（管理员上架 / 租户可见）、来源搜索（Harbor、Artifact Hub）、安装向导（values 表单或 YAML）、安装进度；
2. 已安装应用：列表、详情（values / 渲染清单 / 操作历史）、升级、卸载；
3. Operator 管理页：已装 Operator 列表（探测）、详情（提供的 CRD + 每个 CRD 的实例数与健康）、升级；
4. chart 获取（OCI / 远程 repo）+ digest 校验；
5. 预渲染静态检查与装后复核；
6. 三张表的迁移、权限点、审计。

**不做**

- manifest bundle / 裸 YAML 安装（后置）；
- `helm rollback` 按钮、应用级自动版本发现、YAML 漂移自动修复；
- 多集群批量安装（一次一个集群）；
- 接管外部安装的 release（只读，与 S3.6 同一规则）；
- chart 评分、评论、README 富文本渲染。

---

## 2. 模块与导航

一个菜单 **「应用与 Operator」**，页面级带**集群选择器**（与组件 tab 同构；应用与 Operator 都是集群内实体）。

```
应用与 Operator
├── 应用商店
│   ├── 目录          —— 租户：仅可见 TENANT 条目；管理员：全部 + 上架/下架
│   ├── 来源搜索      —— 仅管理员：Harbor / Artifact Hub
│   └── 已安装        —— 本集群应用列表 → 详情 / 升级 / 卸载
└── Operator 管理
    └── 列表 → 详情（CRD + 实例数）→ 升级
```

页面码：`tenant:page:app`（商店，管理员与租户都可达——管理员持 tenant 族码）、`platform:page:operator`（Operator 视图仅管理员）。

---

## 3. 数据模型

### 3.1 `platform_app_catalog`（上架条目）

| 列 | 说明 |
|---|---|
| `id` | `cat_<uuid>` |
| `source` | `HARBOR` / `ARTIFACT_HUB` |
| `chart_ref` | OCI：`oci://<harbor>/<project>/<chart>`；HTTP repo：repo URL |
| `chart_name` | 上游 chart 名（寻址用） |
| `version` | 钉死；不支持"latest" |
| `digest` | tgz/OCI manifest 的 sha256，**安装时必须逐字节一致** |
| `display_name` / `description` | 目录展示 |
| `values_schema_json` | 上架时从 chart 里抽出的 `values.schema.json`（无则 null） |
| `default_values_json` | chart 自带的 `values.yaml`（表单初始值） |
| `visible_to` | `TENANT` / `ADMIN`（租户只能看见 `TENANT`） |
| `created_by` / `created_at` | 审计 |

> 下架 = **逻辑删除**（`deleted_at`）：已装应用靠 `platform_app` 里的 chart 快照继续升级/卸载，不受影响。

### 3.2 `platform_app`（应用实例）

| 列 | 说明 |
|---|---|
| `id` | `app_<uuid>` |
| `cluster_id` / `namespace` | 目标位置；namespace 必须在该租户分配表内 |
| `release_name` | 平台生成：`app-<8 hex>`；与 chart 名无关，避免租户之间撞名 |
| `catalog_id` | 空 = 管理员直装（非目录来源） |
| `chart_ref` / `chart_name` / `version` / `digest` | 安装时的**事实快照**（catalog 后续下架不影响已装应用） |
| `values_json` | 最终生效的 values（升级补齐、回滚重放的依据） |
| `app_kind` | `APP` / `OPERATOR`（后者用于 Operator 视图归属） |
| `display_name` | 用户填写，默认为 chart 名 |
| `status` | `INSTALLING` / `RUNNING` / `FAILED` / `UNINSTALLED` |
| `created_by` / `created_at` / `updated_at` | |

### 3.3 `platform_app_operation`（操作记录）

与 `platform_component_operation` **同形但不共用**（领域不同；本仓不抽公共基类）：

`id, cluster_id, app_id, action(INSTALL/UPGRADE/UNINSTALL), from_version, to_version, values_snapshot, status(PENDING/RUNNING/SUCCESS/FAILED/UNKNOWN), message, log_tail, operator, started_at, finished_at`

并发保护沿用生成列 + 唯一键：`running_lock = CONCAT(cluster_id,'|',namespace,'|',release_name)`，仅 PENDING/RUNNING 有值。

### 3.4 迁移 `V2026_10_21_1__app_store_and_operator.sql`

- 三张 `CREATE TABLE IF NOT EXISTS`（含 `running_lock` 生成列）；
- 权限行（显式 id + ODKU）：
  | 端点族 | code |
  |---|---|
  | `/app/**` 读（目录/列表/详情/预览） | `tenant:app:read` |
  | `/app/**` 写（安装/升级/卸载） | `tenant:app:install` |
  | `/app/catalog/**` 管理（上架/下架/来源搜索） | `platform:app:manage` |
  | `/operator/**`（列表/详情/升级） | `platform:app:manage` |
- **共用端点用 `tenant:` 码并显式绑 admin**（`permission-multi-code-per-endpoint` 的不变量）：
  读/装是"管理员与租户共用能力"，所以不另建 `platform:app:read` —— 管理员本来就持 tenant 族码。
  `platform:app:manage` 只给**管理员专属**能力（目录治理、Operator 治理）。
- 角色绑定：`builtin_role_admin` 绑全部四个码；`tenant_admin` 绑 `tenant:app:read` + `tenant:app:install`；
  `tenant_member` 只绑 `tenant:app:read`。
- 页面码：`tenant:page:app`（admin + tenant_admin + tenant_member）、`platform:page:operator`（admin）。
- ⚠️ 本仓没有 Flyway，**先重放迁移再起新构建**（`PermissionCrossCheckRunner` 会拒启无行端点）。

---

## 4. 执行链

```
platform-api                        k8s-server
  │ 权限 + 目录 + 分配表校验            │
  ├──── POST /chart/preview ────────▶│ 拉 chart → helm template → 逐对象分析
  │◀─── 清单 + violations ───────────┤
  ├──── POST /chart/apply ──────────▶│ 再拉一次（同一 digest）→ 再检查 → helm upgrade --install
  │◀─── 操作记录 + log_tail ──────────┤ 装后 helm get manifest 复核 → 越界标记
```

### 4.1 ChartFetcher（k8s-server 新增）

- OCI：`helm pull oci://… --version <v> --untar --untardir <tmp>`；
- HTTP repo：`helm pull <chart> --repo <url> --version <v> --untar`；
- 下载后计算 **sha256**，与目录里的 `digest` 比较，不一致**直接失败**（防"上架后源被替换"）；
- 临时目录在请求结束时删除；chart 体积上限 `platform.chart.max-size`（默认 50MB），超限拒绝；
- 超时与代理走 `HelmRunner` 同一套环境变量配置；
- `HelmRunner` 新增两个能力：`apply(..., InstallOptions)`（叠加 `--skip-crds` / `--no-hooks`）与
  `manifest(kubeconfig, release, ns)`（`helm get manifest`，装后复核用）。

### 4.2 静态检查（预渲染，hard fail）

输入是 `helm template` 的 stdout。逐文档判定（顺序即短路顺序）：

| # | 条件 | 结果 |
|---|---|---|
| 1 | 同一 chart 内出现重复 `apiVersion/kind/namespace/name` | 拒（`DUPLICATE_OBJECT`） |
| 2 | 对象带 `metadata.annotations["helm.sh/hook"]` | 租户拒（`HOOK`）；管理员仅提示 |
| 3 | kind 为集群级（用 discovery 的 `namespaced=false` 判定，未知 kind **fail-closed**） | 租户拒（`CLUSTER_SCOPED`）；管理员提示 |
| 4 | namespaced 且显式 `metadata.namespace` ≠ 目标 ns | 一律拒（`CROSS_NAMESPACE`，含管理员） |
| 5 | `kind: Namespace` | 一律拒（租户安装不建命名空间） |
| 6 | 对象既无 `metadata.name` | 拒（`INVALID_OBJECT`） |

> 第 4 条对管理员也拒：跨 ns 会让"卸载是否删干净"变得不可预期（`helm uninstall` 会删它，但那不是管理员想要的意外）。

`namespaced` 判定：k8s-server 用 fabric8 的 API discovery 建 `group/version/kind → namespaced` 缓存（按 clusterId，
TTL 5 分钟）；未知 kind 缓存为空即视为**不可装**（fail-closed），并提示"该 chart 使用了集群未安装的 CRD"。

### 4.3 安装参数

| 参数 | 租户路径 | 管理员路径 |
|---|---|---|
| `--namespace <ns>` | 分配表内的 ns | 任意（`--create-namespace` 允许） |
| `--skip-crds` | ✅ | ❌ |
| `--no-hooks` | ✅ | ❌ |
| `--rollback-on-failure` / `--force-conflicts` / `--timeout 10m` | ✅（沿用 S3.6） | ✅ |
| labels | `platform.coding/managed=true`, `platform.coding/app=<appId>`, `platform.coding/tenant=<tenantId>` | 同左（tenant 为 `-`） |

### 4.4 装后复核（第三道闸）

安装成功后调用 `helm get manifest <release> -n <ns>`，对**实际落盘的清单**再跑一遍 §4.2 的检查：

- 任意越界（跨 ns / 集群级 / hook）→ `platform_app.status=FAILED` 的**警告位**置位（`needsReview=true`），
  应用详情页标红并展示对象清单；**不自动删除**（删除比报告更危险）；
- 同时写入操作记录 `message`，审计留痕。

### 4.5 状态机与升级

- `INSTALLING → RUNNING`（helm 成功 + 复核完成）/ `FAILED`；`RUNNING → UNINSTALLED`；
- **升级**：仅平台装过的应用（`platform_app` 有记录且 release 带 `platform.coding/managed=true`）。
  目标版本必须是同一 chart 在目录中的条目（租户）或任意来源（管理员）；
  升级 = 用新 chart 重新走 §4.2 检查 + `upgrade`，values 以**已存快照**为基线，用户在升级表单里只改差异项。
- **回滚（v1 不做按钮）**：若需要，用历史 `values_snapshot` + 当时的 chart 版本重新 upgrade，属于人工操作。

---

## 5. Operator 管理

### 5.1 判定（启发式，必须可解释）

1. 取集群全部 CRD，按 `spec.group` 分组，排除 `*.k8s.io`；
2. 对每个 group，在全部命名空间的 Deployment 里找候选控制器，命中任一即算：
   - Deployment 名包含 group 的第一段（`cert-manager.io` → `cert-manager`）；
   - 任一容器镜像的仓库名包含该段；
   - Deployment 的 `app.kubernetes.io/name` 等于该段；
3. 展示**匹配依据**（"名称匹配" / "镜像匹配" / "标签匹配"）；多个候选都列出，不擅自选一个；
4. 一个都匹配不上 → 状态写「未识别到控制器」，仍展示 CRD 与实例数（这是事实，不隐藏）。

**已知代价**：会漏（命名不含组名前缀的 Operator）会重（宽泛前缀）。v1 接受，靠"显示依据"让用户自己判断；后续可加手工映射表。

### 5.2 详情

- Operator 基本信息：控制器 Deployment（ns/副本/就绪/镜像版本）、匹配依据；
- **它提供的 CRD 列表**：每行 CRD 名、kind、scope、实例数、异常实例数（`status.phase`/`status.conditions` 可读时）；
- 实例数实现：按需 `list`（detail 视图懒加载），每 CRD 上限 500 条并显示「≥500」；结果缓存 30s；
- 若该 Operator 由平台安装（`platform_app.app_kind=OPERATOR` 且 release 匹配）→ 显示归属与「升级」；
  外部安装 → 只读，并写明"平台不接管"。

### 5.3 升级

复用应用升级链路（chart 来源 = 该 Operator 的 chart，目标版本由用户选择）。外部安装的 Operator 不提供升级。

---

## 6. 端点与权限

### 6.1 k8s-server（新增 controller，PLATFORM 边界）

| 端点 | 说明 |
|---|---|
| `POST /chart/inspect` | 拉 chart → 返回 digest、`values.schema.json`、默认 values（上架用） |
| `POST /chart/preview` | 拉 chart → `helm template` → 返回清单 + 分析结果（对象、scope、violations） |
| `POST /chart/apply` | 安装/升级（含静态检查 + 装后复核），写操作记录 |
| `POST /chart/uninstall` | `helm uninstall`（写操作记录） |
| `POST /operators/list` | Operator 探测列表 |
| `POST /operators/detail` | 单个 Operator 的 CRD 与实例数（懒加载） |

> 规则：k8s-side 只做"事实与边界"（渲染、namespaced 判定、命名空间归属校验、helm 执行）；
> "能不能装这个 chart"（目录、权限、租户）在 platform-api 判定。`/chart/apply` 的 `strict` 布尔由 api 侧给出。

### 6.2 platform-api（对前端）

| 端点 | 权限 |
|---|---|
| `POST /app/catalog/list` | `tenant:app:read`（租户只返回 `visible_to=TENANT`） |
| `POST /app/catalog/search` | `platform:app:manage`（Harbor / Artifact Hub，带 5 分钟缓存与静默降级） |
| `POST /app/catalog/create` / `/delete` | `platform:app:manage`（上架时调 `/chart/inspect` 存 schema 与 digest） |
| `POST /app/preview` | `tenant:app:read`（预览不落盘） |
| `POST /app/install` / `/upgrade` / `/uninstall` | `tenant:app:install` |
| `POST /app/list` / `/get` | `tenant:app:read`（租户按分配表收窄；管理员看全部） |
| `POST /app/operations/get` | `tenant:app:read`（轮询进度） |
| `POST /operator/list` / `/get` / `/upgrade` | `platform:app:manage` |

审计：安装/升级/卸载/上架/下架全部 `@Audit`。

---

## 7. 前端

- `AppStoreView.vue`：集群选择器 + 三个 tab（目录 / 来源搜索〔管理员〕/ 已安装）。
  - 安装向导：chart 信息 → 命名空间（租户只能选分配表内的）→ values（有 schema 生成表单，否则 YAML 编辑器）→
    **预览**（渲染清单 + violations 列表，红色阻断项/黄色提示项）→ 提交 → 轮询操作记录；
  - 已安装：列表（名称/ns/chart/版本/状态）→ 详情抽屉（values、渲染清单、操作历史、卸载）。
- `OperatorView.vue`：列表（组名、控制器 ns/就绪、匹配依据）→ 详情（CRD 表 + 实例数，点击展开实例列表）。
- 复用：`YamlBlock`（清单/values 展示）、`ResourceFormValues` 那套控件形态（schema → 表单）、操作轮询（与组件 tab 同一写法）。
- 新页面码进 `pageCodes.ts`；路由 `meta.requiresPerm`；菜单按页面码显隐。

---

## 8. 租户隔离与安全（汇总）

1. **目录白名单**：租户只能安装 `visible_to=TENANT` 的条目；chart 版本与 digest 钉死；
2. **分配表**：目标 ns 必须属于该租户（`platform_tenant_namespace`），由 platform-api 校验（权威）；
3. **静态检查**（§4.2）：跨 ns、集群级对象、hook、Namespace 一律拒；
4. **参数收紧**：租户路径 `--skip-crds --no-hooks`；
5. **装后复核**（§4.4）；
6. **审计**：安装人、chart、版本、ns、values（脱敏后）全部留痕；
7. **镜像拉取**：chart 里的镜像若来自 Harbor 私有项目，租户 ns 需要对应的 imagePullSecret —— v1 在安装向导里提示，不做自动注入（后置）。

---

## 9. 测试

**单测（platform-api）**
- 目录可见性：租户看不到 `ADMIN` 条目；下架后已装应用仍可查；
- 租户 ns 校验：不在分配表 → 拒；管理员不受限；
- 权限码与页面码存在性（`PermissionCrossCheckRunner` 覆盖）；
- release 名生成与并发锁（同 ns 同名第二次安装 → 冲突拒绝）。

**单测（k8s-server）**
- 静态检查表驱动：跨 ns / 集群级 / hook / Namespace / 未知 kind（fail-closed）/ 正常；
- `helm template` 输出解析（多文档、空文档、注释）；
- ChartFetcher：digest 不一致 → 拒；超限 → 拒；
- helm 参数拼装：租户 vs 管理员（`--skip-crds` / `--no-hooks` 只在租户路径）；
- Operator 判定：名称/镜像/标签三种命中与"未识别"。

**真集群（coding-cluster）**
1. 管理员从 Harbor 装一个简单 chart → 出现在已安装与（若为 operator）Operator 视图；
2. 管理员上架一个 chart → 租户从目录装到自己的 ns → 租户只能看见自己的应用；
3. 升级到目标版本；卸载；
4. 反例：跨 ns 的 chart、含 CRD/ClusterRole 的 chart、带 hook 的 chart → 租户被拒且提示准确；
5. 装后复核：人为构造越界（测试用 chart）→ 详情标红。

**前端**：`vue-tsc` + 浏览器实测安装向导（表单/YAML 两条路径）与 violations 展示。

---

## 10. 部署

1. 先重放 `V2026_10_21_1__app_store_and_operator.sql`（否则新端点无权限行，服务拒启）；
2. 重建 **platform-data + platform-common + k8s-server + platform-api + platform-web**；
3. k8s-server 需要：helm 4.2.0（已有）+ 访问 Harbor 与 Artifact Hub 的网络（或代理环境变量）；
4. 新增配置：
   - `platform.chart.harbor.*`（复用 S0.2 的 Harbor 连接）
   - `platform.chart.artifacthub.base-url`（默认 `https://artifacthub.io`）
   - `platform.chart.max-size`（默认 50MB）、`platform.chart.discovery-ttl`（默认 5m）
5. 上线后管理员先上架 1~2 个 chart 作为冒烟。

---

## 11. 风险

| 风险 | 处理 |
|---|---|
| Operator 判定是启发式的，会漏会重 | 详情页显示匹配依据；不擅自选一个；判不出就明说 |
| Artifact Hub 的 chart 在外部 repo，可用性不受控 | 拉取失败明确报错，不重试到超时；来源搜索静默降级为空 |
| 租户升级时新版本引入越界对象 | 升级与安装走**同一套**静态检查，不是只查首装 |
| 静态检查依赖 discovery，集群不可达时无法判定 namespaced | fail-closed：无法判定即拒绝，并提示原因（不给"先装上再说"的口子） |
| `helm uninstall` 不删 CRD / PVC | 卸载确认框明确写出；应用详情保留已卸载记录 |
| 租户镜像拉取凭据 | v1 提示用户配置 imagePullSecret，自动注入后置 |

---

## 12. 与既有规范的对照

- **k8s-server 零业务逻辑**：只做渲染、边界判定、helm 执行；目录/权限/租户判定在 api 侧；
- **一页一 controller**：`AppController`（catalog + apps）、`OperatorController` 各一；
- **不抽公共基类**：`platform_app_operation` 与 `platform_component_operation` 各写一份；
- **权限行显式绑定**：新码必须显式绑角色（不按 code 继承）；
- **前端页面码唯一来源**：`pageCodes.ts`。
