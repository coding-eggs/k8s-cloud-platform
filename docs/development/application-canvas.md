# 应用编排（画布）

> 设计：`docs/superpowers/specs/2026-10-25-app-orchestration-design.md`
> 计划①（本文覆盖）：`docs/superpowers/plans/2026-10-25-app-orchestration-batch1.md`

## 1. 一句话

**应用 = 一张类型化的拓扑图**。图画在画布上，真相源是平台的 spec JSON；平台把图翻译成
「资源清单 + 分阶段部署流程」。用户只表达意图，K8s 资源由平台生成。

节点类型：`SERVICE`（有端口，可建 Service）、`WORKER`（无端口）、`MIDDLEWARE`（6 款中间件，
三种来源）、`EXTERNAL`（集群外的数据库/消息队列）。中间件的依赖注入、初始化脚本与引用守卫
见 §4.1–§4.3。

## 2. 数据与真相源

| 表 | 作用 |
|---|---|
| `platform_application` | 应用台账；`spec_json` 是图定义（真相源） |
| `platform_application_revision` | 不可变版本快照：spec + 渲染清单 + **对象清单**（`objects_json`） |
| `platform_application_operation` | 部署操作；`current_step` 持久化，重启可续跑 |
| `platform_application_resource` | 对象台账：prune 的差集、冲突判定、删除应用时清理 |

**spec 是真相源**：任何集群状态都不反写 spec；资源页对被管理对象只读（见 §6）。
版本快照里的 `objects_json` 是 platform-api 判断「哪些对象是我的」的唯一依据 —— platform-api
不解析 YAML（它没有 yaml 依赖，也不需要）。

## 3. 节点与自动生成

| 节点 | 用户填 | 平台生成 |
|---|---|---|
| `SERVICE` | 镜像、端口、副本、环境变量、（可选）持久化/自动伸缩/监控/暴露方式 | Deployment（勾持久化则 StatefulSet + Headless Service）+ Service + ConfigMap + HPA/ServiceMonitor（勾选时） |
| `WORKER` | 镜像、副本、环境变量 | 只有 Deployment（无端口、无 Service、无探针） |
| `MIDDLEWARE` | 类型 + 来源（平台创建 / 绑定已有实例 / 链接外部）+ 形态 + 初始化脚本 | `CREATE` → 调中间件商店建实例并等 Ready；`BIND` → 只登记引用；`EXTERNAL` → 只产连接信息 |
| `EXTERNAL` | 协议、地址、端口、Secret 名与键 | 什么都不建，只产出注入用的连接信息 |

默认值：requests `100m/128Mi`、limits `1/1Gi`、副本 1、探针 HTTP GET `/`、HPA CPU 60%（1–10）、
ServiceMonitor `/metrics`。**探针可以在节点里关掉、路径可改** —— 非 HTTP 服务（gRPC/只监听 TCP）
必须关，否则 Pod 永远 NotReady、部署必然超时。对象名 = `<应用slug>-<节点key>`，slug 由应用名归一化（小写、连字符、≤30）。

## 3.1 画布交互

**视觉身份按类型区分**：服务=蓝、后台进程=紫、中间件=绿（徽标是该中间件品类码 MY/RD/KF/MQ/ES/NC/MV/PG）、
外部服务=橙 + **虚线边框**（它不在这个集群里）。左侧面板底部有同一份图例。

**端口方向就是规则**：被依赖的资源排在左边，所以连线的**出**口在卡片左侧、**入**口在右侧；
中间件和外部服务**只有入口**，从它们身上拖不出线 —— 比事后弹提示更早地表达了「只有计算节点能发起连线」。

**布局**：按依赖关系自动分层（被依赖的层在最左，使用者逐层向右），同列左边缘对齐、每列垂直居中。
**手动拖动过的节点会被钉住**，之后改图结构（加节点/连线/删边）也不会挪它；没钉住的节点跟着自动重排。
工具栏「自动整理」清空全部钉住并重新取景。

**坐标存哪**：钉住的坐标写进 `spec.settings.layout`（`{节点key: {x, y}}`）。它是纯画布信息，
`AppSpecValidator` 不读、渲染和部署也不用 —— 但仍然随 spec 一起进版本快照。

**边**：smoothstep + 箭头（箭头指向被使用的一方）；选中后按 Delete/Backspace 删除。
节点不能用 Delete 删（避免误删后连带丢配置），移除节点走检查器里的确认框。

> 坑：Vue Flow 的自定义节点**必须**渲染 `<Handle>`，否则节点上没有任何连接点、`@connect` 永远不触发。
> 节点尺寸靠 ResizeObserver 量，量不到尺寸时 `handleBounds` 为空，连线同样不可用 ——
> 在无渲染的环境（后台标签页/headless 预览）里会看到这个现象，真浏览器不会。

## 4. 部署阶段机

```
PRECHECK → MIDDLEWARE → INIT → RENDER → CONFLICT → APPLY → PRUNE → HEALTH → DONE
```

| 阶段 | 做什么 | 失败表现 |
|---|---|---|
| PRECHECK | 应用未删、版本存在、图合法（至少一个节点、命名空间非空） | 直接 FAILED，不写集群 |
| MIDDLEWARE | 逐个中间件节点：CREATE 建实例并等 Ready（15 分钟超时）、BIND 校验同命名空间同租户、EXTERNAL 取节点参数 | 停在该节点；已建实例保留，重试继续 |
| INIT | 按节点的初始化脚本跑一次性 Job（失败即整体失败，日志进操作记录） | 停在该节点 |
| RENDER | 用解析出的连接信息做注入，调 k8s-server 渲染 + 静态检查 | 有问题就 FAILED |
| CONFLICT | 读集群：要写的对象已存在但**不在本应用历史台账**里 → 拒绝 | 「对象已存在且不属于本应用」 |
| APPLY | SSA 写一份合并清单（fieldManager=platform-app），落对象台账 | 写入失败 |
| PRUNE | 删「上一版本有、本版本无」的对象；**PVC 默认跳过** | 清理失败 |
| HEALTH | 现读副本就绪；按节点回显状态；超时（默认 30 分钟）判失败 | 停在 HEALTH 等下一拍 |

- 推进器：`ApplicationOperationWorker`，3 秒一拍（`platform.application.worker-delay-ms`），
  **一次只推进一条活操作** —— 并发推进会与 PRUNE 的差集打架。假定 platform-api 单副本。
- **凭据**：worker 线程没有请求上下文，k8s-server 对匿名调用一律拒绝。发起部署时把当次请求的
  Authorization 记在内存（`ApplicationDeployCredentials`），worker 用 `runAs` 带过去；
  **重启后凭据没了 → 操作以「凭据已失效，请重新部署」失败**（不把用户 token 写进数据库）。
- 阶段进度是持久化的，但「重启续跑」只对**同一进程内**的中断成立（token 未过期时）。
- **PRUNE 的基线是上次成功部署的版本**（`app.current_revision`），不是 `rev-1` ——
  中间失败的版本没有台账行，用 rev-1 会把它们当基线、差集永远算空。
- 失败**停住等人工重试**（不自动回滚）：重试从失败阶段继续；已成功阶段不重跑。
- 升级 = 新 revision；**回滚 = 复制历史版本的 spec 建一个新 revision**（`action=ROLLBACK`），
  版本历史里能看出来；`current_revision` 只在部署成功后前移。
- revision 状态：`PENDING → RENDERED → APPLIED → DEPLOYED / FAILED`。

## 4.1 依赖注入

连线语义只有一种：**「谁使用谁」**（只有服务和后台进程可以发起连线）。平台按目标节点产出变量：

| 目标 | 注入的变量 |
|---|---|
| MySQL / PostgreSQL / Redis / RabbitMQ / Milvus | `<节点KEY>_<协议>_HOST`、`_PORT`、`_USER`、`_PASSWORD`（后两个是 `secretKeyRef`） |
| Kafka | `<节点KEY>_KAFKA_BOOTSTRAP` |
| Elasticsearch / Nacos | `<节点KEY>_<协议>_URL` |

`<节点KEY>` = 节点 key 大写、连字符变下划线。**凭据永远只给 Secret 名 + 键**，渲染清单里没有口令。

## 4.2 初始化动作

节点上填的脚本/命令按中间件类型以**一次性 Job** 执行（脚本正文放 ConfigMap，Job 只引用它）：

| 类型 | payload 形状 |
|---|---|
| mysql / postgresql | SQL 文本 |
| redis | 命令序列（每行一条） |
| kafka | 每行 `topic:分区:副本`（`--if-not-exists`，可重试） |
| elasticsearch | JSON 请求体（PUT 到 `$URL$PATH`） |

`backoffLimit: 0`：失败由平台重试（用户能看到日志），不让 K8s 反复跑 SQL。跑完 1 小时自动清理。

## 4.3 引用守卫

应用对实例的引用落 `platform_application_reference`。被引用的实例在「中间件实例」页删除时会被拒，
报错里列出引用它的应用名；要删先去画布里移除依赖。应用删除时引用一并清理。

## 5. 敏感值

**平台不接收明文敏感值**：
- `env` 里只放普通值 → 进 ConfigMap；
- 敏感值先在「Secret」页建好，节点里用「引用 Secret」（`secret: true` + Secret 名 + 键）→ 渲染成
  `valueFrom.secretKeyRef`；勾了引用却没填 Secret 名会直接报错。

这样 spec、revision 快照、渲染清单里都不会出现密码。

## 6. 与现有页面的边界

- 渲染出的对象都带 `platform.coding/managed=true` → 既有 `ManagedResourceGuard` 判成 COMPONENT，
  **工作负载/Service/ConfigMap/HPA/PVC 页面对它们只读**（前端置灰，直接调 API 也会被拒）。
- 带 `platform.coding/app` / `app-node` 标签 → 反查归属；目前资源页只显示「平台托管」，
  显示具体应用名留给计划②。
- 删除应用：先按台账把集群对象删掉（PVC 保留），再软删台账 —— 不允许出现「台账没了、对象还在」。

## 7. 权限

| 项 | 值 |
|---|---|
| 页面码 | `tenant:page:application` |
| 读 | `tenant:application:read`（列表/详情/预览/轮询/版本/选项） |
| 写 | `tenant:application:write`（建/改/删/部署） |

**归属校验**：除列表（管理员可看全部）外，每个端点都按应用归属过滤；轮询按「操作所属的应用」判。
**命名空间**：建应用/改图都过 `NamespaceAccessGuard`（分配表是安全边界，前端下拉只是提示）。
管理员在平台视图（无租户帽）下**不能建应用**，需先切到某个租户。

端点与权限行一一对应，由 `ApplicationPermissionMigrationTest` 单测钉住（漏一行应用启动即 fail-closed）。
审计动作：`application:create` / `application:update` / `application:delete` / `application:deploy`。

## 8. 部署步骤

1. 重放 `V2026_10_25_7__application.sql`（四张表 + 页面码 + 11 条权限行 + 角色绑定）；
   ⚠️ 先重放再起新构建，否则新端点没有权限行，platform-api 启动即失败；
2. 重建 `platform-common` / `platform-data` / `k8s-server` / `platform-api` / `platform-web`；
3. 冒烟：新建应用 → 加一个服务节点 → 预览（看到 Deployment/Service）→ 部署 → 画布节点变绿 →
   `kubectl -n <ns> get deploy,svc,cm` 能看到 `<slug>-<key>`；
4. 改镜像再部署 → 预览无新增删除 → 滚动；删节点再部署 → 预览显示将删除的对象 → 对象消失。

## 9. 还没做的

- 暴露节点（HTTPRoute）—— 等 S4.2「租户可挂哪些入口 Gateway」定案；
- 自定义清单节点（整块 YAML 的逃生口）；
- 预览里展示注入变量的摘要（当前只在部署时生效）。
