# 应用编排（画布）设计（2026-10-25）

> 需求出处：2026-10-25 用户提出「给用户一个画布，自己画应用架构图（app、mq、db），平台按图出资源列表，
> 可选择平台部署或链接外部，部署流程化（db 先行、初始化 SQL），让用户尽量在一个地方配完」。
> 同轮确认：节点类型封闭（A）+ 自定义清单逃生口（C，后置）；**不做 CronJob**（平台不支持也不打算支持）；
> 画布创建的中间件与「中间件实例」页**同一张台账**；第一批**带中间件**（每个中间件节点可选建/接）；
> 失败**人工处理**，暂不自动回滚。
>
> 与既有模块的关系：复用 S3.6 的 `HelmRunner` / `ManifestInspector` / 异步操作形态、中间件商店的
> 适配器与实例台账、S0.2 的凭据与 Secret、S2.2/S2.4 的监控与暴露能力。
> **不引入** KubeVela / kro / Argo CD / Flux，也不自研 in-cluster operator（评估见 §1.1）。

---

## 0. 一句话

**应用 = 一张类型化的拓扑图**。图画在画布上，真相源是平台的 spec JSON；平台把图翻译成
「资源清单 + 分阶段部署流程」，用户只表达意图，K8s 资源由平台生成。

## 1. 范围

### 1.1 技术路线（已定）

| 决策 | 内容 | 理由 |
|---|---|---|
| D1 | **自研控制面**：无 CRD、无 in-cluster controller | 与「唯一写入原语 k8s-server」「台账即真相源」一致；多集群不需要每集群装一份并同步升级 |
| D2 | 不做外部 operator（KubeVela/kro/kapp/Argo/flux） | 均为第二写入方，绕过平台边界与审计；KubeVela 原生 helm 支持 2026-10 仍未 GA，稳定版只能走 FluxCD addon |
| D3 | 拓扑 spec JSON 是真相源，画布只是编辑器 | 便于以后加 CLI / 导入导出 / 模板 |
| D4 | 节点类型**封闭**，自定义清单节点（C）后置 | 封闭类型才能校验、注入、编排 |
| D5 | 一个应用 = **一个集群 + 一个命名空间** | 跨命名空间/跨集群不是本轮需求 |
| D6 | 画布创建的中间件实例与中间件商店**同一张台账** | 「把平台串起来」；实例页可见、标注来源，但由应用管理的实例只读 |
| D7 | 执行器 = 平台侧 stage 机，**不用 Tekton** | 需要长期等待、节点级重试、状态回显、与台账强耦合；Tekton 是 CI 引擎（且其 `when` 不能引用执行状态） |
| D8 | 失败**停住等人工重试**，不自动回滚 | 中间件回滚会连带丢数据；回滚只在「升级到坏版本」时由用户显式选择历史版本重放 |
| D9 | 组件渲染用**内置 chart 片段**（每类节点一份），中间件走既有适配器 | 单一路径产出 manifest → 合并 → SSA；复用 `helm template` 与 `ManifestInspector` |
| D10 | **不支持 CronJob**（节点目录里没有） | 用户 2026-10-25 明确：平台不支持，也不打算支持 |

### 1.2 v1 做

- 画布（节点、连线、校验、命名、预览）；
- 节点类型：服务（无状态/有状态）、后台进程、中间件（7 款）、外部服务；
- 三种中间件来源：平台创建 / 绑定已有实例 / 链接外部；
- 依赖注入（地址 + 凭据，密码只走 Secret）；
- 分阶段部署 + 节点状态回显 + 单节点重试；
- 初始化动作（MySQL/PostgreSQL SQL、Redis 命令、Kafka topic、ES index/template）；
- 版本快照（每次部署一条不可变 revision）与「回滚到某个 revision」；
- 引用守卫（被引用的中间件实例不能删）。

### 1.3 v1 不做（明确边界）

- CronJob（D10）、自定义清单节点（C，放 v2）；
- 跨命名空间 / 跨集群应用、多环境（同一应用多套环境）；
- 蓝绿 / 灰度 / 切流（C 路线，另立项）；
- 应用间依赖（应用 A 引用应用 B）；
- HTTPRoute 暴露（依赖 S4.2「入口候选来源」未定，v1 只给 ClusterIP / NodePort）；
- 自动回滚、自动扩缩之外的运维动作（备份/恢复）。

---

## 2. 概念模型

```
Application（应用）
├── spec（JSON，真相源）
│   ├── nodes[]        节点：key / kind / params / provisionMode
│   ├── edges[]        边：from → to（语义固定为 USES）
│   └── settings       命名空间、标签、默认资源
├── revision（不可变快照）  spec + resolved（解析后的注入上下文）+ manifest
└── operation（异步部署）   阶段 + 每节点状态 + 日志引用
```

节点 `key` 是应用内唯一标识（小写、`-`、≤20 字符），K8s 对象名由 `<app-slug>-<node-key>` 生成。
一条边只有一个语义 **USES**：平台据此注入连接信息，并推断部署顺序（被用者先部署）。

## 3. 节点目录（v1）

| kind | 用户填 | 平台生成 |
|---|---|---|
| `SERVICE` | key、镜像、容器端口、副本数、环境变量、资源（可选）、持久化（勾选 + 大小）、自动伸缩（开关）、监控（开关）、暴露方式（不暴露 / ClusterIP / NodePort） | Deployment；**勾了持久化则 StatefulSet + Headless Service + PVC**；ClusterIP Service；ConfigMap（普通变量）；Secret（标记敏感的变量）；HPA（勾选时）；ServiceMonitor（勾选时） |
| `WORKER` | key、镜像、副本数、环境变量、资源 | Deployment（无端口、不建 Service、不可暴露） |
| `MIDDLEWARE` | key、code（mysql/redis/kafka/rabbitmq/postgresql/elasticsearch/nacos）、来源模式、形态、资源、存储、指标开关、初始化动作 | `CREATE` → 调中间件商店建实例（同一台账）+ 连接 Secret；`BIND` → 只登记引用；`EXTERNAL` → 只登记外部地址与凭据 |
| `EXTERNAL` | key、类型（HTTP/DB/MQ）、地址、端口、凭据引用（平台 Secret 或凭据管理里的一条） | 不建资源；产出一份连接上下文供注入 |

**不做** `CRONJOB`（D10）。`CUSTOM_MANIFEST` 为 v2 预留（原样 apply，不参与注入与编排）。

## 4. 自动生成规则（减少 K8s 配置的核心）

用户只在「高级」里才看得到具体资源；默认值如下（全部可在应用设置里覆盖一次、节点级再覆盖）：

| 项 | 默认 |
|---|---|
| 资源 | requests `100m / 128Mi`，limits `1 / 1Gi`（命名空间有 LimitRange 时以其为准） |
| 副本 | 1 |
| 探针 | HTTP GET `/`，端口=容器端口；失败阈值 3、初始延迟 10s |
| Service | 与容器端口同名同号的 ClusterIP |
| 环境变量 | 普通值 → ConfigMap；标记「敏感」→ Secret（或引用已有 Secret 的某个键） |
| HPA | 勾选才建，CPU 目标 60%，范围 1–10（可改） |
| PVC | 只在 `SERVICE` 勾持久化时建，默认 StorageClass，大小必填 |
| ServiceMonitor | 勾选才建，默认 `/metrics` + 容器端口（无 /metrics 时前端提示） |
| 标签 | `platform.coding/app=<appId>`、`platform.coding/app-node=<key>`、`platform.coding/managed=true`、`app.kubernetes.io/name=<app-slug>` |
| 注解 | `platform.coding/revision=<n>`（用于 prune 与回溯） |

所有自动产物在**部署前预览**里逐条列出、可展开 YAML、可整份导出。

## 5. 依赖与注入

标准变量名（`<KEY>` = 节点 key 大写、`-`→`_`）：

| 依赖类型 | 注入 |
|---|---|
| MySQL / PostgreSQL | `<KEY>_HOST` `<KEY>_PORT` `<KEY>_USER` `<KEY>_PASSWORD`(secretKeyRef) `<KEY>_DATABASE` |
| Redis | `<KEY>_HOST` `<KEY>_PORT` `<KEY>_PASSWORD`(secretKeyRef) |
| Kafka | `<KEY>_BOOTSTRAP` `<KEY>_USER`(可选) `<KEY>_PASSWORD`(secretKeyRef) |
| RabbitMQ | `<KEY>_HOST` `<KEY>_PORT` `<KEY>_USER` `<KEY>_PASSWORD`(secretKeyRef) |
| Elasticsearch | `<KEY>_URL` `<KEY>_USER`(可选) `<KEY>_PASSWORD`(secretKeyRef) |
| EXTERNAL | 按其类型给上面同一组；地址来自节点参数，凭据来自引用的 Secret |

**注入上下文的来源**：`CREATE` 节点来自建实例时生成的连接 Secret；`BIND` 节点来自中间件商店的
`/middleware/instance/connection`（返回地址 + Secret 键名，口令不回传）；`EXTERNAL` 节点来自节点参数里的
地址与凭据引用。

**硬规矩**：密码/令牌**不进入** spec、revision 快照、渲染清单、日志；只以 `secretKeyRef` 形式出现。
用户在组件的环境变量里选「来自节点 X」即可，不用手写这些名字。

## 6. 渲染与落盘

```
spec ──解析──▶ 注入上下文（每节点的连接信息）
      ──渲染──▶ SERVICE/WORKER：内置 chart 出 manifest（MIDDLEWARE 节点此阶段只产出
                「创建/绑定计划 + 连接上下文」，真正创建实例在执行阶段）
      ──合并──▶ 单份多文档 YAML（按 apiVersion/kind/namespace/name 去重，冲突即失败）
      ──校验──▶ ManifestInspector（禁集群级对象 / hostPath / 特权 / 未声明 CRD）
      ──执行──▶ 中间件节点先建实例（适配器）→ SSA apply 组件清单（fieldManager=platform-app）
      ──清理──▶ prune：上一 revision 有、本 revision 无的对象
```

- 内置 chart 放在 `k8s-server/src/main/resources/charts/app/<kind>/<version>/chart`，一 kind 一份，附组件级 README；
- **prune 保护**：PVC 不随节点参数变化删除（只有节点被移除且用户勾「连数据删除」才删）；中间件 CR 由适配器删除，不走 prune；
- 每个被 apply 的对象写入 `platform_application_resource`（含 uid），用于状态现读、prune 与资源页反查。

## 7. 部署流程

```
DRAFT → PRECHECK → MIDDLEWARE → INIT → COMPONENTS → EXPOSE → HEALTH → READY
                        └──────────── 任一步失败 → FAILED（记录 failedStep / failedNode）
```

| 阶段 | 做什么 | 失败处理 |
|---|---|---|
| PRECHECK | 配额、StorageClass、镜像可达、名称冲突、引用完整性、循环依赖、节点数上限（20） | 直接失败，不写集群 |
| MIDDLEWARE | 逐个 `CREATE`/`BIND`/`EXTERNAL` 节点落位；`CREATE` 走中间件商店创建并等 Ready | 停在该节点；已创建的可「重试」续跑 |
| INIT | 每个中间件节点执行初始化动作（一次性 Job，幂等） | 停在 Job，日志可看 |
| COMPONENTS | 按依赖序分批 apply；每批等可用（Deployment/StatefulSet 可用副本数达标） | 停在失败节点，可单节点重试 |
| EXPOSE | NodePort / ClusterIP 的最终校验（v1 不做 HTTPRoute） | 同上 |
| HEALTH | 汇总所有节点状态 + 引用的中间件 Ready | — |

- 超时：每节点默认 5 分钟（中间件 15 分钟），可配；
- 重试：从失败阶段续跑，已成功节点默认跳过；「整体重来」需显式确认；
- 状态回显：画布节点显示 等待 / 创建中 / 就绪 / 失败 / 跳过；点开看该节点的资源与日志；
- 升级：改图 → 预览差异 → 新 revision → 从 PRECHECK 重新走，COMPONENTS 阶段 apply 并 prune；
- 回滚：选历史 revision → 以其 spec + resolved 重新部署（新 revision 记录「回滚自 rev N」）。

**初始化动作**（每中间件类型一个，与适配器并列实现）：

| code | 动作 |
|---|---|
| mysql / postgresql | SQL 文本（粘贴或上传 ≤1MB），以 Job 执行；内容不进日志，只记摘要 |
| redis | 命令序列（每行一条），Job 执行 |
| kafka | topic 列表（名称/分区/副本） |
| elasticsearch | index / index template 的 JSON |
| rabbitmq / nacos | v1 不支持初始化动作（表单里不出现） |

## 8. 数据模型

```sql
platform_application(
  id, tenant_id, cluster_id, namespace, name, display_name, description,
  status, spec_json MEDIUMTEXT, current_revision, created_by, created_at, updated_at,
  deleted_at, active_key /* 生成列：软删后可重建同名 */
)

platform_application_revision(
  id, app_id, revision, spec_json, resolved_json, manifest MEDIUMTEXT,
  status, message, created_by, created_at
)

platform_application_operation(
  id, app_id, revision, action, /* DEPLOY / UPGRADE / DELETE / RETRY */
  status, current_step, node_states_json, message, started_at, finished_at, created_by
)

platform_application_resource(
  id, app_id, revision, api_version, kind, namespace, name, uid, node_id, orphaned_at
)

platform_application_reference(
  id, app_id, node_id, ref_type, /* MIDDLEWARE_INSTANCE / SECRET */
  ref_id, created_at
)
```

另：`platform_middleware_instance` 加 `app_id`、`app_node_id`（可空）——用于实例页标注来源与删除守卫。

## 9. 接口

**platform-api `/application/**`**（全部需要权限行，否则 `PermissionCrossCheckRunner` 拒启）

| 端点 | 权限 | 说明 |
|---|---|---|
| `POST /application/list` | read | 按租户/分配表收窄 |
| `POST /application/get` | read | 含 spec、当前 revision、最近一次操作 |
| `POST /application/create` | write | 只落 spec，不部署 |
| `POST /application/update` | write | 改 spec（DRAFT 状态） |
| `POST /application/delete` | write | 删应用；中间件「保留/删除」由参数显式选择，默认保留 |
| `POST /application/preview` | read | 解析 + 渲染 + 校验 + 差异，**不写集群** |
| `POST /application/deploy` | write | 异步部署（新 revision） |
| `POST /application/operation/get` | read | 轮询阶段与节点状态 |
| `POST /application/revision/list` · `/get` | read | 版本历史与详情 |
| `POST /application/options` | read | 节点类型、命名空间（分配表）、中间件目录、默认资源 |

**k8s-server `/application/**`**（UPSTREAM 边界，渲染与落盘；归属校验在 platform-api）

| 端点 | 说明 |
|---|---|
| `POST /application/render` | 逐节点渲染并合并，返回 manifest + 校验结果（不写集群） |
| `POST /application/apply` | SSA apply 合并后的 manifest（fieldManager=platform-app） |
| `POST /application/prune` | 按资源台账删除上一 revision 多余对象（PVC 需显式） |
| `POST /application/status` | 现读被跟踪对象的就绪状态 |
| `POST /application/init` | 运行初始化 Job 并等待完成 |

中间件节点复用既有 `/middleware/instance/create|get|connection` 与 Operator 状态端点。

## 10. 权限与审计

- 权限码：`tenant:application:read`、`tenant:application:write`；页面码 `tenant:page:application`
  （注意：`tenant:page:app` 已被中间件商店占用，不复用）；
- 管理员可代操作（与中间件商店同形：`resolveNamespacedAccessOrPlatform`）；
- 审计动作：`APPLICATION_CREATE` / `APPLICATION_DEPLOY` / `APPLICATION_DELETE` /
  `APPLICATION_ROLLBACK` / `APPLICATION_INIT_RUN`（SQL 只记长度与哈希，不记内容）；
- 新表、新权限行、新页面码各一条迁移；端点与权限行的对应关系用测试钉住。

## 11. 前端

- 路由 `/applications`（列表）、`/applications/:id`（画布）；
- 画布复用 `@vue-flow/core`（与流水线编辑器同一套库），左侧节点面板、中间画布、右侧节点检查器；
- 连线只有一种（USES），拖线时按类型给候选（中间件/外部 → 服务、服务 → 服务）；
- 部署抽屉：阶段时间线 + 每节点状态 + 资源清单/YAML/日志；
- 预览弹窗：资源清单、与上一版本的差异、图校验问题（循环依赖、孤立节点、缺端口等）；
- 画布上的中间件节点标注「平台创建 / 已有实例 / 外部」，并与中间件实例页互相跳转。

## 12. 与现有模块的边界

1. **资源页只读**：带 `platform.coding/app` 的对象在「工作负载 / Service / ConfigMap / Secret / HPA /
   PVC / ServiceMonitor」页只读，并提供「去应用里查看」跳转（复用 `ManagedResourceGuard` 判定思路）；
2. **禁止双写**：应用管的对象不能在资源页修改；反向也不提供「把已有工作负载纳入应用」（v1 不做导入）；
3. **中间件实例**：画布创建的实例出现在实例页（同一台账），标注「由应用 X 创建」，实例页对其只读；
   被 `BIND` 的应用引用的实例不能删（引用守卫，报错列出引用方）；
4. **删除应用**：先删生成资源与 prune，中间件默认保留（可选一并删除，需二次确认并说明数据不可恢复）。

## 13. 风险与开放问题

| # | 风险/问题 | 处理 |
|---|---|---|
| 1 | 封闭节点类型覆盖不到用户真实架构 | 每类节点支持「高级参数」；v2 补自定义清单节点；节点数上限 20 防止画布失控 |
| 2 | prune 误删（尤其 PVC） | PVC 白名单保护 + 删除需显式勾选；prune 前展示 diff |
| 3 | 初始化 SQL 的安全与体量 | ≤1MB、审计只记摘要、Job 使用中间件凭据 Secret、禁止在日志回显内容 |
| 4 | 应用与中间件实例页面的编辑冲突 | 实例页对应用管理的实例只读（与资源页同规则） |
| 5 | **HTTPRoute 暴露依赖 S4.2**（租户可挂的入口 Gateway 候选未定） | v1 只做 ClusterIP/NodePort；入口候选定了再补 |
| 6 | 大应用渲染/apply 的耗时与幂等 | 单次 apply 合并清单；阶段可续跑；资源台账保证 prune 幂等 |
| 7 | 多集群差异（1.35 / 1.37） | 渲染前按集群探测 API 版本（沿用 S3.6 的 `--kube-version/--api-versions` 探测） |

## 14. 分批

| 批 | 内容 | 完成标准 |
|---|---|---|
| **①a** | spec 模型 + 画布（服务/后台进程）+ 渲染/预览/apply/prune + 部署阶段（无中间件）+ 状态回显 | 能画「前端 + 后端」并一键部署、看到节点状态 |
| **①b** | 中间件节点三模式 + 依赖注入 + 初始化动作 + 引用守卫 + 实例页来源标注 | 能画「应用 + MySQL」整套起起来，初始化 SQL 跑通 |
| ② | 暴露（待 S4.2）、自定义清单节点、导入已有资源、并行/失败策略增强 | — |

> 用户要求「第一批带中间件」：①a 与 ①b 同属第一次交付，拆两段只是为了让「渲染闭环」先可验证。

## 15. 验收要点

- 预检能挡住：循环依赖、引用不存在的实例、命名冲突、配额不足、无 StorageClass；
- 注入的密码只以 `secretKeyRef` 出现在清单里（可 grep 渲染结果验证）；
- 部署中途杀掉 platform-api，重启后操作可续跑（状态从台账恢复）；
- 资源页对被管理对象只读；删被引用的中间件实例被拒并列出引用方；
- 升级时 prune 只删该删的，PVC 保留。
