# 应用模块重做 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 去掉画布，把应用重做成「服务 + 依赖 + 发布记录」，发布可续跑、可回滚。

**Architecture:** platform-api 持有模型、引用解析和执行器；k8s-server 只负责渲染、下发、状态。旧服务并存到 Task 13 才删除，每个阶段都能独立验证。

**Tech Stack:** Java 21 / Spring Boot / MyBatis XML、fabric8、Helm chart、Vue 3 + Element Plus。

**Spec:** `docs/superpowers/specs/2026-11-01-application-release-redesign-design.md`

## Global Constraints

- k8s-server 零业务逻辑；一页一 controller（继续 `ApplicationController`）；不加外键。
- 平台不接收明文口令；口令不进快照、清单、接口响应。
- 连接信息只取自 `MiddlewareService.connection().getInfo()`。
- 本仓没有 Flyway：`db/migration` 脚本幂等，手工重放；新权限行显式绑定角色；MySQL 注释 `--` 后跟空白。
- 失败不自动回滚；发起人 token 只存内存。
- 前端没有单测运行器，验证 = `npm run build` + 浏览器实测（见 `ui-preview-verification` 记忆）。
- 后端验证命令：`mvn -q test -pl <module> -am -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`。

## Review Focus

1. 发布途中用户继续改配置：快照不受影响，改动显示为待发布（Task 6）。
2. 发布期间依赖实例被转为 FAILED 或被删：PRECHECK 拒绝并指出依赖名（Task 6）。
3. 同一变量名手填和隐藏变量冲突、`_REF_` 前缀被用户占用：保存时报错（Task 2）。
4. 回滚到引用已删除实例的旧版本：校验拦截，线上不变（Task 7）。
5. 执行卡住时 platform-api 重启：进入 INTERRUPTED，不会永远 RUNNING（Task 5）。

---

## 阶段 1：后端地基

### Task 1: 迁移与数据层

**Files:**
- Create: `platform-data/src/main/resources/db/migration/V2026_11_01_1__application_release.sql`（Task 13 已改名为 `V2026_11_02_10__application_release.sql`，权限三份为 `_11` / `_12` / `_13`）
- Create（各含 model / mapper / XML，放 `com.coding.data.*.system` 与 `resources/mapper/system`）：`PlatformAppService`、`PlatformAppDependency`、`PlatformAppRelease`、`PlatformAppExecution`
- Test: `platform-api/src/test/java/com/coding/platformapi/app/ApplicationReleaseMigrationTest.java`（仿 `ApplicationMigrationTest`）

**Interfaces:**
- Produces: 表与字段见 spec §3；`platform_application` 加 `live_release_no int NOT NULL DEFAULT 0`，`spec_json` 改可空。
- Produces: `platform_app_execution.active_key` 生成列 = `IF(status IN ('PENDING','RUNNING','INTERRUPTED'), app_id, NULL)`，唯一键。`FAILED` 不占活动位：新发布可覆盖，重新执行时把 FAILED 改回 PENDING。
- Produces mapper：`PlatformAppServiceMapper.selectByApp/selectByKey/insert/updateWithVersion(row):int`；`PlatformAppDependencyMapper.selectByApp/selectByKey/insert/updateById/deleteById`；`PlatformAppReleaseMapper.selectByNo/selectByApp/selectMaxNo/insert/updateById`；`PlatformAppExecutionMapper.selectActive():List/selectActiveByApp/selectLatestByApp/insert/updateById/claim(id, now, leaseUntil):int`。

- [ ] 写 `ApplicationReleaseMigrationTest`：断言脚本含四张表、`uk_app_service`、`uk_app_dep`、`uk_app_release_no`、活动唯一键，且为幂等写法。
- [ ] 运行，确认 FAIL。
- [ ] 写迁移、model、mapper、XML；`claim` 条件为 `lease_until IS NULL OR lease_until < now`。
- [ ] 运行，确认 PASS；`mvn -q compile -pl platform-data -am` 通过。
- [ ] 提交 `feat(app): 发布模型的表与数据层`。

### Task 2: 变量引用解析

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/app/AppEnvResolver.java`
- Modify: `platform-common/.../models/app/AppInjectDTO.java`（加 `inline` 布尔）
- Test: `platform-api/src/test/java/com/coding/platformapi/app/AppEnvResolverTest.java`

**Interfaces:**
- Produces: `record AppEnvRow(String name, String kind, String value, String secretName, String secretKey)`，`kind` = `LITERAL` / `REF` / `SECRET`（含 `${` 的 LITERAL 视作 REF）。
- Produces: `AppEnvResolver.resolve(List<AppEnvRow> rows, Map<String, ConnInfo> deps, Map<String, ServiceAddr> services) -> Result(List<AppInjectDTO> env, List<AppProblemDTO> problems)`。
- Produces: `AppEnvResolver.references(List<AppEnvRow>) -> Set<String>`（被引用的 key，供反查和拓扑）。
- Produces: `record ServiceAddr(String host, int port)`。

- [ ] 写测试：
  - `singleHostRef`：`${db.host}` → 普通值。
  - `passwordRef`：`${db.password}` → `secret(name, secretName, passwordKey)`。
  - `templateWithSecret`：`mysql://${db.username}:${db.password}@${db.host}/x` → 先出 `_REF_0`、`_REF_1`（secret），再出 `inline=true` 且 value 含 `$(_REF_0)`。
  - `unknownDep`、`unknownField`、`fieldWithoutValue`（Redis 无 username）→ ERROR problem，含依赖名。
  - `duplicateName`、`reservedPrefix`（`_REF_x`）→ ERROR。
  - `esTlsUrl`：host 已带 `https://` 时 url 不再加 `http://`。
  - `serviceRef`：`${api.url}` → `http://<host>:<port>`。
- [ ] 运行，确认 FAIL。
- [ ] 实现。字段映射：`address`=`host:port`，`url` 见上；只有含 `secretKeyRef` 的拼接串才走 inline，否则进 ConfigMap。
- [ ] 运行，确认 PASS。
- [ ] 提交 `feat(app): 环境变量引用解析`。

### Task 3: Chart 修复（滚动、拼接变量、拉取凭据）

**Files:**
- Modify: `k8s-server/src/main/resources/charts/app/workload/1.0.0/chart/templates/{deployment,statefulset}.yaml`
- Modify: `k8s-server/.../app/AppWorkloadValues.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/app/AppWorkloadValuesTest.java`、`AppRenderServiceTest.java`

**Interfaces:**
- Produces values：`workload.inlineEnv: [{name,value}]`、`workload.configHash: string`、`workload.imagePullSecrets: [string]`。
- Consumes: `AppInjectDTO.inline`（Task 2）。

- [ ] 写测试：环境变量值变化则 `configHash` 变、仅 revision 变化时 hash 不变；inline 条目进 `inlineEnv` 不进 ConfigMap；`imagePullSecrets` 参数透传。
- [ ] 运行，确认 FAIL。
- [ ] 实现。模板：Pod template annotation `platform.coding/config-hash`；`inlineEnv` 渲染在 `secretEnv` **之后**（`$(VAR)` 只展开先定义的）；有值时渲染 `imagePullSecrets`。
- [ ] 运行，确认 PASS；用 helm 渲染确认 YAML 合法（`AppRenderServiceTest` 若依赖 helm 二进制，按其现有方式跑）。
- [ ] 提交 `fix(app-chart): 改配置触发滚动，支持拼接变量与拉取凭据`。

### Task 4: 健康判断修复

**Files:**
- Modify: `k8s-server/.../app/AppStatusService.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/app/AppStatusServiceTest.java`

**Interfaces:**
- Produces: `deploymentState(int want, int available, int updated, boolean observed)`、`statefulSetState(int want, int ready, boolean revisionSettled, boolean observed)`。

- [ ] 写测试：`available=3, updated=1, want=3, observed` → PENDING；`updated=3` → READY；StatefulSet `currentRevision≠updateRevision` → PENDING。
- [ ] 运行，确认 FAIL。
- [ ] 实现；`revisionSettled` = `updateRevision` 等于 `currentRevision`，或 `updatedReplicas >= want`（滚动完成后部分 K8s 版本不更新 `currentRevision`，不能只比较 revision）。
- [ ] 运行，确认 PASS。
- [ ] 提交 `fix(app): 滚动中不再误判就绪`。

### Task 5: 执行器

**Files:**
- Create: `platform-api/.../services/AppReleaseExecutor.java`、`AppExecutionWorker.java`
- Modify: `ApplicationDeployCredentials.java`（如需按执行 id 复用）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/AppReleaseExecutorTest.java`、`AppExecutionWorkerTest.java`

**Interfaces:**
- Consumes: Task 1 mapper、`K8sAppClient`。
- Produces: `AppReleaseExecutor.advance(PlatformAppExecution)`，每次只走一个阶段；阶段 `PRECHECK → RENDER → CONFLICT → APPLY → HEALTH → PRUNE → DONE`；状态 `PENDING / RUNNING / INTERRUPTED / FAILED / SUCCESS`。
- Produces: `AppExecutionWorker.tick()`（`@Scheduled`，默认 3 秒，有界线程池）。
- Produces: `AppReleaseExecutor.resume(Long executionId, String operator)`：FAILED/INTERRUPTED → PENDING，从当前阶段继续并记下当前 token。
- PRECHECK 调用的 `AppReleasePrecheck` 接口由 Task 6 提供实现；本任务测试 mock。

- [ ] 写测试：
  - 每次 `advance` 只推进一个阶段，并落库 stage。
  - HEALTH 未就绪：stage 不变、不阻塞、不算失败；超过 `platform.application.timeout-minutes` 判 FAILED。
  - HEALTH 未过不执行 PRUNE；PRUNE 基线 = `live_release_no` 的资源台账。
  - 全部完成：`live_release_no` 更新、release 标 `DEPLOYED`。
  - 无凭据（模拟重启）或网关返回 401 → INTERRUPTED，而不是 FAILED。
  - `resume` 后从原阶段继续，发布号不变。
  - Worker：应用 A 卡 HEALTH 时，应用 B 同一轮被推进；`claim` 返回 0 时跳过。
- [ ] 运行，确认 FAIL。
- [ ] 实现；从 `ApplicationDeployService` 复制 render/conflict/apply/prune/health 的做法（旧服务暂留）。线程池默认 4，租约 2 分钟，每步结束清除租约。
- [ ] 运行，确认 PASS。
- [ ] 提交 `feat(app): 不阻塞、可续跑的发布执行器`。

### Task 6: 服务配置与发布

**Files:**
- Create: `platform-api/.../services/AppServiceConfigService.java`、`AppReleaseService.java`
- Create: `platform-api/.../models/` 下对应 Request / View（`AppReleasePreviewView`、`AppOverviewView` 等）
- Modify: `ApplicationController.java`、`AuditAction.java`
- Create: `db/migration/V2026_11_01_2__application_release_permissions.sql`
- Test: `AppServiceConfigServiceTest.java`、`AppReleaseServiceTest.java`

**Interfaces:**
- Consumes: `AppEnvResolver`（Task 2）、`AppReleaseExecutor`（Task 5）。
- Produces 接口（spec §9）：`service/create|update|remove`、`overview`、`release/preview|create|list|get`、`execution/active|resume`。
- Produces: `AppReleaseService.preview(appId, selectedKeys, images, tenantId) -> AppReleasePreviewView`、`create(同参 + note, operator, tenantId) -> executionId`。
- Produces: `overview` 现读 `client.status`，返回服务状态、镜像、`pendingKeys`、活动执行。
- 权限：读接口绑 `tenant:application:read`，写接口绑 `tenant:application:write`，都显式绑 admin / tenant_admin；tenant_member 只绑读，与 `V2026_10_25_7` 一致。

- [ ] 写测试：
  - 校验 service key（DNS 标签），创建后不可改；`version` 不符 → 冲突错误。
  - 待发布：desired 与线上一致时不算改动。
  - 快照 = 线上 + 勾选项 − 勾选删除；未勾选的改动发布后仍为待发布。
  - **Review 1**：发布中再改 desired，快照不变。
  - 勾选服务引用未发布服务 → 错误列出需一并勾选的 key。
  - **Review 2**：引用的中间件非 READY → PRECHECK 失败，消息含依赖名。
  - 有活动执行时拒绝新发布；不同租户拒绝访问。
  - 快照 JSON 不含口令字段。
- [ ] 运行，确认 FAIL。
- [ ] 实现；差异比较服务级字段（镜像、副本、变量、资源）和渲染对象增删。`AppWorkloadValues` 接收 `imageRepo:imageTag` 拼成的 `image`。
- [ ] 运行，确认 PASS。
- [ ] 提交 `feat(app): 服务配置与发布接口`。

### Task 7: 回滚

**Files:**
- Modify: `AppReleaseService.java`、`ApplicationController.java`
- Test: `AppReleaseServiceTest.java`

**Interfaces:**
- Produces: `AppReleaseService.rollback(appId, sourceReleaseNo, note, operator, tenantId) -> executionId`，接口 `release/rollback`。

- [ ] 写测试：
  - 新发布 `type=ROLLBACK`，`source_release_no` 正确，快照等于源快照。
  - 无未发布编辑的服务，desired 同步为旧快照；有编辑的保留并显示待发布。
  - 旧快照有、当前已移除的服务，重建 service 行。
  - **Review 4**：旧版本引用已删除实例 → 拒绝，线上不变。
- [ ] 运行，确认 FAIL。
- [ ] 实现。
- [ ] 运行，确认 PASS。
- [ ] 提交 `feat(app): 回滚`。

## 阶段 2：发布闭环（前端）

### Task 8: 应用页、服务配置页

**Files:**
- Create: `platform-web/src/views/ApplicationDetailView.vue`、`ApplicationServiceView.vue`
- Create: `platform-web/src/components/app-release/AppEnvEditor.vue`
- Modify: `platform-web/src/views/ApplicationListView.vue`、`src/router/index.ts`、`src/api/index.ts`、`src/types.ts`

**Interfaces:**
- Consumes: Task 6 接口。
- Produces 路由：`application-detail`（`applications/:id`）、`application-service`（`applications/:id/services/:key`），均 `nav: 'hidden'`、`pageCodes.application`。

- [ ] 实现列表（去掉「打开画布」，名称进应用页，显示运行状态和线上发布号）、应用页（spec §8 布局）、服务页四个页签。Pod/日志/监控复用现有工作负载页组件或跳转。
- [ ] `npm run build` 通过。
- [ ] 浏览器实测：新建应用 → 添加服务 → 显示待发布 → 刷新后仍在；并发保存提示冲突。
- [ ] 提交 `feat(web): 应用页与服务配置页`。

### Task 9: 发布对话框与发布记录

**Files:**
- Create: `platform-web/src/components/app-release/AppReleaseDialog.vue`、`AppReleaseHistory.vue`、`AppExecutionPanel.vue`
- Modify: `ApplicationDetailView.vue`

- [ ] 实现：勾选 → 差异 → 说明 → 确认；进度面板轮询 `execution/active`；失败和中断时显示「重新执行 / 继续 / 回滚」；有活动执行时禁用发布。
- [ ] `npm run build` 通过。
- [ ] 真集群实测 spec §12 中的：只改变量滚动 Pod、未勾选服务不重启、镜像错误停在 HEALTH 且旧 Pod 继续服务、重启 platform-api 后可继续、回滚一致。
- [ ] 提交 `feat(web): 发布、进度与回滚`。

## 阶段 3：依赖

### Task 10: 依赖后端

**Files:**
- Create: `platform-api/.../services/AppDependencyService.java`
- Modify: `MiddlewareService.java`（实例视图带来源、删除守卫）、`ApplicationController.java`、`AppReleaseService.java`（接入依赖连接信息）
- Test: `AppDependencyServiceTest.java`、`MiddlewareServiceTest.java`

**Interfaces:**
- Produces 接口：`dependency/add|remove|init`。
- Produces: `AppDependencyService.connections(appId, tenantId) -> Map<String, ConnInfo>`，供 Task 6 的解析和 PRECHECK 使用。

- [ ] 写测试：
  - `dep_key` 占位后再创建实例：同 key 重复添加只成功一次，`middleware.create` 只调一次。
  - **发布多次都不调用 `middleware.create`，也不调用 `client.init`。**
  - 绑定校验租户、集群、命名空间，写引用表。
  - 仍被 desired 或线上快照引用时拒绝移除，消息列出服务。
  - 移除 CREATED：选删除则调用 `middleware.delete`；选保留则清 `app_id`、`app_node_id`。
  - 初始化：非 READY 拒绝；成功记录 hash；再次执行无确认标记时拒绝；运行中状态在重启后可恢复为失败。
  - 外部连接拒绝任何口令字段。
  - 由应用创建的实例，实例页删除被拒。
- [ ] 运行，确认 FAIL。
- [ ] 实现；初始化走独立线程 + `K8sServerGateway.runAs`。删除应用（新逻辑）同时清引用表。
- [ ] 运行，确认 PASS。
- [ ] 提交 `feat(app): 依赖生命周期`。

### Task 11: 依赖前端

**Files:**
- Create: `platform-web/src/components/app-release/AppDependencyDialog.vue`、`AppDependencyDetail.vue`、`AppRefPicker.vue`
- Modify: `AppEnvEditor.vue`、`ApplicationDetailView.vue`、`MiddlewareInstanceView.vue`

- [ ] 实现：添加依赖（新建/绑定/外部）、变量选择器、「从依赖导入」、使用方反查、初始化入口；实例页显示来源应用并隐藏删除。
- [ ] `npm run build` 通过。
- [ ] 真集群实测：新建 MySQL → 导入变量 → 发布 → 应用连通；再发布两次实例数不变。
- [ ] 提交 `feat(web): 依赖管理与变量引用`。

## 阶段 4：联动

### Task 12: Harbor 选 tag 与新版本提示

**Files:**
- Modify: `AppReleaseService.java`（overview 附新版本、渲染时带 pull secret）、`ApplicationServiceView.vue`、`AppReleaseDialog.vue`，资源页加「所属应用」链接
- Test: `AppReleaseServiceTest.java`

**Interfaces:**
- Consumes: `RegistryController /browse`（`BrowseResult`）；拉取 Secret 名沿用 `RegistryProjectController /pull-secret/sync` 所同步的 Secret，开工时读取该实现确认。

- [ ] 写测试：Harbor 有更新 tag 时返回 `newTag`；Harbor 查询异常或非 Harbor 镜像时 `newTag` 为空且 overview 不报错；渲染请求带 `imagePullSecrets`。
- [ ] 运行，确认 FAIL。
- [ ] 实现；手填 tag 始终可用。资源页按 `platform.coding/app` 标签显示跳转。
- [ ] 运行，确认 PASS；`npm run build` 通过；真集群拉取私有镜像成功。
- [ ] 提交 `feat(app): 接入 Harbor 与资源页跳转`。

## 阶段 5：收尾

### Task 13: 拓扑、配置挂载、删除旧代码

**Files:**
- Create: `platform-web/src/components/app-release/AppTopology.vue`（用 `AppNodeCard`、`appCanvasLayout`，迁入本目录）
- Delete: `ApplicationCanvasView.vue`、`app-canvas/` 其余文件；`ApplicationDeployService`、`ApplicationMiddlewareService`、`ApplicationPreviewService`、`ApplicationOperationWorker`、`AppDependencyResolver`、`AppSpecValidator` 及其测试和旧接口
- Modify: chart（配置文件挂载）、`AppWorkloadValues.java`、`ApplicationServiceView.vue`
- Create: `docs/development/application-release.md`；更新 `docs/task-ledger-2026-10-12.md` 的 S3.2

- [ ] 写测试：`configFiles: [{path, content}]` 渲染为 ConfigMap 卷并计入 `configHash`；`content` 不允许引用 Secret。
- [ ] 运行，确认 FAIL。
- [ ] 实现挂载和只读拓扑（节点跳详情，禁拖拽连线）。
- [ ] 删除旧代码和旧接口，`grep` 确认无残留引用。
- [ ] `mvn test` 全绿；`npm run build` 通过。
- [ ] 按 spec §11 写上线步骤，迁移顺序 `V2026_11_02_10` → `_11` → `_12` → `_13`（已改名，见 docs/development/application-release.md §3）；按 spec §12 完成全部真集群验收。
- [ ] 提交 `refactor(app): 移除画布与旧部署链路`。
