# 应用模块重做设计（去画布，以发布为中心）

状态：待评审。取代 `2026-10-25-app-orchestration-design.md`。

## 1. 目标与边界

应用是平台上**可发布的单位**。用户要能完成：把镜像跑起来、连上依赖、升级或回滚、看出哪里坏了。

**不做：** 画布编辑、灰度/蓝绿、多环境、自动发布、CronJob、纳管已有工作负载、按依赖分批部署、单服务重试。

**模块分工：**
- 流水线只产出镜像，Harbor 是 CI 和发布之间的唯一约定，两者不直接关联。
- 中间件实例的生命周期归中间件模块，应用只记录依赖关系。
- 资源页只读，并提供跳回所属应用的入口。

## 2. 四个核心决定

1. **变量引用代替连线。** 环境变量写 `${order-db.host}`，依赖关系由引用推导。
2. **中间件不进发布流程。** 添加依赖时就创建或绑定，发布只校验其就绪。
3. **待发布改动代替草稿和架构版本。** 修改只改期望配置，发布时按服务勾选。
4. **只有一种工作负载。** 填端口就是服务，不填就是后台进程。

## 3. 数据模型

沿用 `platform_application` 的身份字段，增加 `live_release_no`；`spec_json`、`current_revision` 不再使用。其余为新表，不加外键。

| 表 | 关键字段 | 规则 |
|---|---|---|
| `platform_app_service` | `app_id`、`service_key`、`desired_json`、`pending_delete`、`version` | `(app_id, service_key)` 唯一；key 创建后不可改；`version` 做乐观锁 |
| `platform_app_dependency` | `app_id`、`dep_key`、`type`、`ownership`、`instance_id`、`external_json`、`init_hash`、`init_status`、`init_log` | `(app_id, dep_key)` 唯一；`type`=MIDDLEWARE/EXTERNAL；`ownership`=CREATED/BOUND |
| `platform_app_release` | `app_id`、`release_no`、`snapshot_json`、`objects_json`、`manifest`、`type`、`source_release_no`、`note`、`status` | 不可变；`type`=NORMAL/ROLLBACK |
| `platform_app_execution` | `app_id`、`release_no`、`status`、`stage`、`message`、`lease_until`、`started_by` | 用生成列唯一键保证每个应用最多一条活动执行 |

继续使用：`platform_application_resource`（`revision` 存发布号）、`platform_application_reference`（删除守卫）。

**待发布改动**不单独存储：比较 `desired_json` 和线上快照中的同名服务，不同即有改动。

`desired_json` 与 `AppWorkloadValues` 现有参数对齐，并把镜像拆为 `imageRepo`、`imageTag`。

## 4. 变量引用

环境变量行有四种形式：

| 形式 | 示例 | 渲染结果 |
|---|---|---|
| 字面值 | `info` | ConfigMap，`configMapKeyRef` |
| 引用 | `${order-db.password}` | 地址类进 ConfigMap；凭据类用 `secretKeyRef` |
| 拼接 | `mysql://${order-db.host}:${order-db.port}/orders` | 容器 `env[].value` 直接写，凭据经 `$(VAR)` 展开 |
| Secret | 选 Secret 和键 | `secretKeyRef` |

**可引用字段：**
- 中间件和外部连接：`host`、`port`、`address`、`username`、`password`、`url`。按协议只暴露有值的字段。
- 有端口的服务：`host`、`port`、`url`。`host` 为 `<slug>-<key>`。

**规则：**
- 解析在 platform-api 完成，k8s-server 只接收结果，保持零业务逻辑。
- 连接信息只从 `MiddlewareService.connection().getInfo()` 取，不在别处拼接。
- 拼接串含凭据时，生成隐藏变量 `_REF_<n>`，并排在使用它的变量之前，因为 `$(VAR)` 只展开先定义的变量。
- 引用不存在的依赖或字段时，保存可以通过，但禁止发布。
- 依赖详情页的「使用方」和拓扑图都由引用反查，不单独存储。
- 「从依赖导入」一次生成多行引用，变量名可改。

## 5. 依赖生命周期

**添加：**
- **新建：** 先写依赖行占住 key，再调用 `MiddlewareService.create`，并写入 `instance_id` 和引用表。
- **绑定：** 校验同租户、同集群、同命名空间，写引用表。
- **外部连接：** 保存地址、端口、Secret 名和键。不接收明文口令。

**初始化脚本：**
- 不随发布执行，由依赖详情页手动触发，只在实例 READY 后可用。
- `AppInitService` 最长等 10 分钟，所以由后台线程执行，并把状态和日志写入依赖行。
- 已成功执行过时，再次执行要二次确认。
- 表结构变更由业务自己的迁移工具负责。

**移除：** 如果期望配置或线上快照仍有引用，则拒绝并列出引用方。CREATED 实例由用户选择保留或删除，默认保留；保留后清掉 `app_id` 来源，转由中间件模块管理。BOUND 实例只解除引用。

**实例页：** 显示来源应用并可跳转；由应用创建的实例不能在实例页删除。

## 6. 发布

**创建：** 快照 = 线上服务 + 勾选服务的期望配置 − 勾选的待删除服务。

**校验：**
- 快照内所有引用都能解析。
- 引用的中间件已 READY。
- 勾选的服务引用了未发布的服务时，提示一并勾选。

**阶段：**

```text
PRECHECK → RENDER → CONFLICT → APPLY → HEALTH → PRUNE
```

相比现状去掉 MIDDLEWARE 和 INIT，并把 PRUNE 移到 HEALTH 之后，新版本健康前不删旧对象。各阶段继续复用现有的 helm template、SSA 和冲突检查。

**执行器：**
- 每轮取所有活动执行，用有界线程池各推进一步，等待时不阻塞。
- 用 `lease_until` 条件更新抢占，防止同一执行被并发推进。
- 凭据仍是发起人 token，只存内存。缺失或返回 401 时进入 `INTERRUPTED`，用户点「继续」后用当前 token 接着跑。
- 执行失败不自动回滚。
- 重新执行从失败阶段继续，发布号不变。

**回滚：** 复制旧快照生成新发布，走相同的差异确认。没有未发布编辑的服务，其期望配置同步为旧快照；有编辑的保留，继续显示为待发布。旧版本引用的实例已删除时，在校验阶段拦截。

**扩缩容：** 走一次只含副本数的快捷发布。**重启**是运行操作，不产生版本。

**删除应用：** 列出将删除的对象，逐个询问 CREATED 实例是否删除，并清理引用表。

## 7. 必须同批修复的现有问题

这些问题出自阅读代码，开工时先用测试复现：

1. **改环境变量不滚动 Pod：** 字面值走 `configMapKeyRef`，Pod 模板没有配置哈希，只改 ConfigMap 不会重启。Deployment 和 StatefulSet 的 Pod 模板都要加 `platform.coding/config-hash`。哈希只算环境变量内容，不含发布号，避免未改动的服务被重启。
2. **健康判断会误判：** Deployment 只看 `available >= want`，旧副本满员时也算就绪，要增加 `updatedReplicas >= want`。StatefulSet 没有版本判断，要比较 `currentRevision` 和 `updateRevision` 并检查 `observedGeneration`。
3. **私有镜像拉不下来：** chart 没有 `imagePullSecrets`，接 Harbor 前要补上，并使用租户的 Harbor 凭据。
4. **ES 地址协议写死：** 依赖解析里 `url` 固定 `http://`，而实例默认开 TLS。`url` 要按实际协议生成。

## 8. 页面

**路由：** `/applications` 列表；`/applications/:id` 应用页；`/applications/:id/services/:key` 服务详情。

**应用页：**

```text
order-service   运行正常 · 线上 #12            [发布 · 2 项改动]
服务     api     1.4.2  3/3 正常   ● 有新版本 1.4.3
         worker  1.4.2  2/2 正常   ● 有待发布改动
依赖     order-db  MySQL  运行正常  被 api、worker 使用
         cache     Redis  运行正常  被 api 使用
[+ 添加服务] [+ 添加依赖]          发布记录 | 拓扑 | 事件
```

**服务详情：** 概览、配置（基本/环境变量/资源与命令/高级）、Pod 与日志、监控。

**发布对话框：** 按服务勾选 → 看差异（镜像、变量、新增和删除的对象）→ 填说明 → 确认后进入进度页。

**发布记录：** 进度、失败原因、Pod 和日志入口，以及「重新执行 / 继续 / 回滚」。刷新后通过活动执行恢复进度。

**拓扑：** 由引用生成的只读图，复用节点卡片和分层布局，点击跳转详情。

**流程：**
- **首次上线：** 部署应用 → 选镜像和 tag → 填端口和副本 → 可选添加依赖并导入变量 → 发布 → 显示访问地址。
- **日常发版：** 服务行提示新 tag → 发布 → 看差异 → 确认。
- **失败处理：** 看日志 → 重新执行、发布修复版本或回滚。

**新版本提示：** 比较 Harbor 最新 tag 的推送时间和线上 tag；查询失败或非 Harbor 镜像时不提示，且允许手填 tag。

## 9. 接口与权限

继续用 `ApplicationController`，路径都在 `/application/` 下：

- 服务：`service/create`、`service/update`、`service/remove`、`service/restart`
- 依赖：`dependency/add`、`dependency/update`（2026-11-03 补：只改外部连接）、`dependency/remove`、`dependency/init`
- 发布：`overview`、`release/preview`、`release/create`、`release/list`、`release/get`、`release/rollback`、`execution/active`、`execution/resume`

沿用 `tenant:application:read/write` 两个码。新权限行要显式绑定 admin、tenant_admin、tenant_member，读写范围与现有规则一致。

## 10. 实施顺序

1. **后端：** 新表、引用解析、执行器、第 7 节的修复。
2. **闭环：** 应用页、服务详情、发布和回滚，在真集群跑通。
3. **依赖：** 新建和绑定、变量选择器与导入、外部连接、初始化、实例页标注。
4. **联动：** Harbor 选 tag、新版本提示、`imagePullSecrets`、资源页跳转。
5. **收尾：** 只读拓扑、配置文件挂载、删除画布代码和旧服务。

对外域名不在本次范围，第一版提供 NodePort。租户如何使用 `external-gateway` 要先单独定。

## 11. 上线

- 不迁移旧应用。上线前查询 `platform_application`：有未删除的应用就先用旧版本删除，确认由其创建的中间件实例如何处理。
- 新表迁移脚本要先手工重放，再启动新构建（本仓没有 Flyway）。

## 12. 验收

用真集群验证：
- 重复发布不新建中间件实例，也不重跑初始化脚本。
- 只改环境变量会滚动 Pod，未勾选的服务不重启。
- 镜像拉取失败时，发布停在 HEALTH，旧 Pod 继续服务。
- 重启 platform-api 后执行进入「已中断」，点继续能完成。
- 一个应用卡住时，其他应用仍能发布。
- 回滚后线上配置与旧快照一致。
- 口令不出现在快照、清单和接口响应中。

## 13. 实现者可自行决定

线程池大小、轮询间隔、隐藏变量具体命名、差异展示样式、`init_log` 的截断长度。
