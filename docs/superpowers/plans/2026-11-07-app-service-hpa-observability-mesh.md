# 应用服务 HPA / 可观测 / 网格 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 应用服务的 HPA 修好补全，OTel 接入与 ambient/waypoint 进 desired 随发布下发，组件未装不让开启，服务页给状态与跳转。

**Architecture:** platform-api 把 desired 里的 `otel` / `mesh` 翻译成通用渲染参数（Pod 注解 / Pod label / Service label / 额外 env / 日志卷），k8s-server 只透传给 chart。门禁统一读 `ClusterComponentService.flags`。

**Tech Stack:** Spring Boot + JUnit5/Mockito（platform-api、k8s-server）、Helm chart、Vue 3 + Element Plus + TS。

**Spec:** `docs/superpowers/specs/2026-11-07-app-service-hpa-observability-mesh-design.md`

## Global Constraints

- 直接在 main 上提交，不推送；只提交自己改的文件；提交信息末尾 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。
- k8s-server 零业务：OTel / 网格的取值翻译全在 platform-api。
- 不加接口、不加权限行、不加 SQL 迁移。
- 旧快照没有新键 → 渲染结果与现在**逐字相同**（不产生待发布改动、不滚动）。
- `OTEL_SERVICE_NAME` = 工作负载名 `<slug>-<key>`（`AppObjectNames.objectName(slug, key)`）。
- 门禁：只有「从关到开」才拦；关闭 / 「跟随」/ `none` 永远放行；回滚只警告。
- ⚠️ `WorkloadService.java` 正被会话 [统一组件门禁](#local_42bb43d7-99b6-4817-9f24-048b93c8c99e) 修改：Task 3 开工前确认它已提交，先 `git log -3 -- platform-api/src/main/java/com/coding/platformapi/services/WorkloadService.java`。它若新增了后端「查旗标并抛错」的公共方法，Task 6 改用它。

## Review Focus

- 存量开着 HPA、没存 `targetCpu` 的服务：仍按 60 渲染 CPU 指标（Task 1 测试）。
- 存量服务只改镜像：不因新校验被拒（Task 2 测试 `unrelatedSaveSkipsHpaChecks`）。
- 组件旗标读不到（UNKNOWN）：开启被拒，关闭放行（Task 6 测试）。
- 已开启服务所在集群卸了组件：未勾选也照常发布、预览有警告（Task 6 测试）。
- 没有 `otel` / `mesh` 的服务渲染出的清单与改动前完全一样（Task 4 / Task 5 测试）。

---

### Task 1: HPA 渲染修复 + 内存指标（k8s-server）

**Files:**
- Modify: `k8s-server/src/main/resources/charts/app/workload/1.0.0/chart/templates/deployment.yaml`、`statefulset.yaml`、`hpa.yaml`、`values.yaml`
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/app/AppWorkloadValues.java`（`autoscaling()`）
- Test: `k8s-server/src/test/java/com/coding/k8sserver/app/AppWorkloadValuesTest.java`、`AppWorkloadChartRenderTest.java`

**Interfaces:** Produces 参数 `targetMemory`（Integer，可空）→ values `workload.autoscaling.targetMemory`（0 = 不加内存指标）。

- [ ] **Step 1: 写失败测试**
  - `AppWorkloadValuesTest.autoscalingDefaultsTargetCpu60AndNoMemory`：params 只有 `autoscalingEnabled=true` → `targetCpu==60`、`targetMemory==0`。
  - `AppWorkloadChartRenderTest.hpaEnabledOmitsReplicas`（Deployment 与 StatefulSet 各一例）：渲染结果里工作负载没有 `spec.replicas`；HPA `metrics` 只有 cpu 一项。
  - `AppWorkloadChartRenderTest.hpaWithMemoryAddsSecondMetric`：`targetMemory=70` → metrics 有 cpu 与 memory（`averageUtilization: 70`）。
  - `AppWorkloadChartRenderTest.hpaDisabledKeepsReplicas`：`replicas=3` 时仍写 `replicas: 3`。
- [ ] **Step 2: 跑测试确认失败** — `cd k8s-server && mvn -q test -Dtest=AppWorkloadValuesTest,AppWorkloadChartRenderTest`，期望上述用例 FAIL（本机无 helm 时 render 测试会 skip，则至少 values 测试 FAIL）。
- [ ] **Step 3: 实现** — `autoscaling()` 加 `"targetMemory", num(params.get("targetMemory"), 0)`；两个工作负载模板 `{{- if not .Values.workload.autoscaling.enabled }}replicas: …{{- end }}`；`hpa.yaml` 内存项 `if gt targetMemory 0`；`values.yaml` 加 `targetMemory: 0` 注释。
- [ ] **Step 4: 跑测试确认通过**（同上命令全绿），再 `helm lint` chart 目录，结论 `0 chart(s) failed`。
- [ ] **Step 5: 提交** `fix(app-chart): stop releases from resetting HPA replicas; optional memory target`

### Task 2: desired 规范化（HPA 校验、otel、mesh）

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/app/AppServiceDesired.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/app/AppServiceDesiredTest.java`

**Interfaces:**
- Produces `AppServiceDesired.normalize(Map)` 新接受：`targetMemory`（1–100）、`otel`（Map：`language` java|python、`tracing` bool、`logs` bool、`logMode` app|file、`logFile` 绝对文件路径、`sampling` 1–100 或缺省）、`mesh`（Map：`dataplaneMode` ambient|none 或缺省、`useWaypoint` 名字|none 或缺省）。
- Produces `public static void validateHpaChange(Map<String,Object> before, Map<String,Object> after)`：HPA 相关键（`autoscalingEnabled/minReplicas/maxReplicas/targetCpu/targetMemory`）有变化且开启时校验 `1 ≤ min ≤ max`；无变化直接返回。`before` 可为 null（新建）。
- 常量 `public static final Set<String> OTEL_RESERVED_ENV = Set.of("OTEL_SERVICE_NAME","OTEL_TRACES_SAMPLER","OTEL_TRACES_SAMPLER_ARG")`。

- [ ] **Step 1: 写失败测试**（`AppServiceDesiredTest`）
  - `otelNormalizesAndRejectsUnknownField`：`otel.foo` → 抛异常；合法输入原样保留；`tracing=false && logs=false` → 输出不含 `otel` 键。
  - `otelAgentRequiresLanguage`：`tracing=true` 无 `language` → 抛；`logs=true, logMode=file` 无 language → 放行。
  - `otelFileModeRequiresFilePath`：`logFile` 为目录（以 `/` 结尾）或相对路径 → 抛。
  - `otelReservedEnvRejected`：开 tracing 且 env 有 `OTEL_SERVICE_NAME` → 抛，消息含变量名。
  - `meshNormalizes`：`dataplaneMode=foo` → 抛；两键都缺省 → 输出不含 `mesh`；worker（无端口）设 `useWaypoint` → 抛。
  - `validateHpaChangeMinMax`：开启且 `min=5,max=2` → 抛。
  - `unrelatedSaveSkipsHpaChecks`：before/after HPA 键相同（且 `min>max` 的脏数据）只改 `imageTag` → 不抛。
- [ ] **Step 2: 跑测试确认失败** — `cd platform-api && mvn -q test -Dtest=AppServiceDesiredTest`
- [ ] **Step 3: 实现** — `targetMemory` 进 `PASSTHROUGH`；`otel` / `mesh` 进 `CORE` 并各写一个私有规范化方法（白名单字段，未知拒绝，空对象不写出）；normalize 内不做 HPA 区间校验（交给 `validateHpaChange`）。
- [ ] **Step 4: 跑测试确认通过**
- [ ] **Step 5: 提交** `feat(app): desired accepts otel / mesh blocks and memory target`

### Task 3: 抽出 OTel 注入纯函数（与工作负载页共用）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/app/OtelInjection.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/WorkloadService.java`（`observability()` 改为调用它，行为不变）
- Test: `platform-api/src/test/java/com/coding/platformapi/app/OtelInjectionTest.java`；回归 `WorkloadServiceObservabilityTest`

**Interfaces:**
- Produces `public record OtelInjection.Plan(Map<String,String> podAnnotations, Map<String,String> env, String logMountDir)`（`logMountDir` 为 null = 不需要日志卷）。
- Produces `public static Plan OtelInjection.plan(String language, boolean tracing, boolean logs, String logMode, String logFile, Integer sampling, String serviceName)`：校验与现 `observability()` 相同（同样的异常文案）；`env` 含 `OTEL_SERVICE_NAME`（agent 开启时）与 sampler 两个 env（tracing 且 sampling 非空时）。
- Produces 常量 `OtelInjection.LOG_VOLUME = "platform-otel-logs"`、`INJECT_KEYS`（java/python 注解键，供「先清掉所有语言注解」使用）。

- [ ] **Step 1: 写失败测试**（`OtelInjectionTest`）：
  - `tracingOnlyJava` → 注解 `instrumentation.opentelemetry.io/inject-java = monitor/platform-instrumentation`，env 只有 `OTEL_SERVICE_NAME=svc`。
  - `tracingAndAppLogs` → 值 `monitor/platform-instrumentation-all`。
  - `fileLogsOnly`（`/var/log/nginx/access.log`）→ 注解 `sidecar.opentelemetry.io/inject=monitor/platform-otel-sidecar`、`platform.coding/otel-log-file=/var/log/app/access.log`，无 inject-* 注解，`logMountDir=/var/log/nginx`。
  - `sampling20` → env `OTEL_TRACES_SAMPLER=parentbased_traceidratio`、`OTEL_TRACES_SAMPLER_ARG=0.2`；tracing=false 时这两个 env 不出现。
  - `samplingOutOfRange` / `unsupportedLanguage` → 抛 `BEAN_VALIDATION_EXCEPTION`。
- [ ] **Step 2: 跑测试确认失败** — `mvn -q test -Dtest=OtelInjectionTest`
- [ ] **Step 3: 实现** `OtelInjection`，把 `WorkloadService` 里对应常量与计算搬过去；`observability()` 用 `Plan` 写注解 / env / 卷，保留它自己的「读线上对象、清旧注解、目标容器」逻辑。
- [ ] **Step 4: 跑测试** — `mvn -q test -Dtest=OtelInjectionTest,WorkloadServiceObservabilityTest` 全绿。
- [ ] **Step 5: 提交** `refactor(otel): extract OtelInjection shared by workload page and apps`

### Task 4: chart 通用注入参数（k8s-server）

**Files:**
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/app/AppWorkloadValues.java`
- Modify: chart `templates/deployment.yaml`、`statefulset.yaml`、`service.yaml`、`values.yaml`
- Test: `AppWorkloadValuesTest`、`AppWorkloadChartRenderTest`

**Interfaces:**
- Consumes 参数（platform-api 在 Task 5 生成，均可缺省）：`podAnnotations` Map<String,String>、`podLabels` Map<String,String>、`serviceLabels` Map<String,String>、`extraEnv` List<{name,value}>、`logMountDir` String。
- Produces values：`workload.podAnnotations` / `podLabels` / `serviceLabels`（缺省 `{}`）、`workload.extraEnv`（缺省 `[]`，排在 `inlineEnv` 之后写进容器 `env[].value`）、`workload.logVolume`（`{enabled, mountDir}`，卷名 `platform-otel-logs` emptyDir，挂到主容器 `mountDir`）。`configHash` 计算**不变**。

- [ ] **Step 1: 写失败测试**
  - `AppWorkloadValuesTest.injectionParamsPassThrough`：四类参数原样进 values；缺省时为空集合、`logVolume.enabled=false`。
  - `AppWorkloadValuesTest.configHashIgnoresInjection`：加注入参数前后 `configHash` 相同。
  - `AppWorkloadChartRenderTest.noInjectionRendersUnchanged`：无注入参数时三种形态（service / worker / service+persistence）渲染结果与改动前的基线 YAML 相同（先在改模板前生成基线写进测试资源）。
  - `AppWorkloadChartRenderTest.injectionRendered`：Pod 模板注解同时有 `platform.coding/config-hash` 与注入注解；Pod label 含注入项且 selector 不变；Service label 含 `istio.io/use-waypoint`；容器 env 末尾有 extraEnv；有 emptyDir 卷与挂载。
- [ ] **Step 2: 跑测试确认失败**
- [ ] **Step 3: 实现** values 映射与模板（注解块合并 config-hash 与 podAnnotations；`app.selector` 不动，podLabels 只加在 template.metadata.labels）。
- [ ] **Step 4: 跑测试确认通过** + `helm lint`
- [ ] **Step 5: 提交** `feat(app-chart): generic pod annotations / labels, service labels, extra env, log volume`

### Task 5: 发布渲染翻译 otel / mesh（platform-api）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/app/AppServiceInjection.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/AppReleasePipeline.java`（`buildRequest`，`NODE_EXCLUDED` 加 `otel`、`mesh`）
- Test: `platform-api/src/test/java/com/coding/platformapi/app/AppServiceInjectionTest.java`、`AppReleaseServiceTest`（或现有 pipeline 测试）

**Interfaces:**
- Consumes `OtelInjection.plan(...)`（Task 3）、desired 结构（Task 2）。
- Produces `public static Map<String,Object> AppServiceInjection.params(Map<String,Object> desired, String workloadName)`：返回要并进 node params 的键（只返回非空项，空 desired → 空 Map）：
  - `otel` → `podAnnotations`、`extraEnv`（由 Plan.env 转 `[{name,value}]`，按 name 排序保证稳定）、`logMountDir`；
  - `mesh.dataplaneMode` → `podLabels{"istio.io/dataplane-mode": 值}`；
  - `mesh.useWaypoint` → `serviceLabels{"istio.io/use-waypoint": 值}`（`none` 原样写）。

- [ ] **Step 1: 写失败测试**（`AppServiceInjectionTest`）：`emptyWhenNoBlocks`、`otelTracingJava`（注解 + `OTEL_SERVICE_NAME=<workloadName>`）、`meshAmbientAndWaypoint`、`meshNoneWritten`；以及 pipeline 测试 `buildRequestOmitsRawOtelMeshKeys`（node params 里没有 `otel` / `mesh`，有翻译后的键）与 `buildRequestUnchangedWithoutBlocks`（无新键时 params 与改动前相同）。
- [ ] **Step 2: 跑测试确认失败**
- [ ] **Step 3: 实现**；`workloadName` = `AppObjectNames.objectName(app.getSlug(), key)`。
- [ ] **Step 4: 跑测试确认通过** — `mvn -q test -Dtest='AppServiceInjectionTest,AppRelease*Test'`
- [ ] **Step 5: 提交** `feat(app): render otel / mesh settings into workload params`

### Task 6: 组件门禁与 waypoint 校验（保存 + 发布）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/app/AppServiceFeatures.java`（纯函数）
- Create: `platform-api/src/main/java/com/coding/platformapi/services/AppFeatureGate.java`
- Modify: `AppServiceConfigService.java`（create / update 调门禁与 `validateHpaChange`）、`AppReleaseService.java`（`preview` 加 problems / warnings，`create` 加 problems；`rollback` **不加**）
- Test: `AppServiceFeaturesTest`、`AppFeatureGateTest`、`AppServiceConfigServiceTest`、`AppReleaseServiceTest`

**Interfaces:**
- Produces `AppServiceFeatures.required(Map desired) -> Set<String>`：生效中的旗标码（`autoscalingEnabled`→`hpa-resource-metrics`；有端口且 `metricsEnabled`→`servicemonitor`；`otel` 的 tracing 或 app 日志→`otel-collection`；`otel.logs`→`otel-logs`；`dataplaneMode=ambient` 或 `useWaypoint` 为具体名字→`ambient-mesh`）。
- Produces `AppServiceFeatures.newlyEnabled(Map before, Map after) -> Set<String>`：`required(after) − required(before)`，另外 `useWaypoint` 从名字 A 换成名字 B 也算 `ambient-mesh`。`before` 为 null = 空。
- Produces `AppFeatureGate.assertCanEnable(String clusterId, Map before, Map after)`：对 `newlyEnabled` 每个码查 `ClusterComponentService.flags(clusterId)`，缺失或 `!available()` → 抛 `BEAN_VALIDATION_EXCEPTION`，消息「集群未就绪：<reason>」（旗标缺失时 reason =「组件状态未知」）。
- Produces `AppFeatureGate.assertWaypoint(String tenantId, String clusterId, String namespace, Map after)`：`useWaypoint` 是名字时，必须在 `GatewayService.waypointRefs(...)` 里且 `canHandleService` 为真，否则抛。
- Produces `AppFeatureGate.Report release(PlatformApplication app, AppSnapshot live, AppSnapshot next, Collection<String> selected, String tenantId)`，`record Report(List<String> problems, List<String> warnings)`：勾选服务的 `newlyEnabled(live, next)` 不可用 → problem；其余生效中但不可用 → warning；waypoint 不存在：勾选的 → problem、其余 → warning。每条带「服务 <key>：」前缀。

- [ ] **Step 1: 写失败测试**
  - `AppServiceFeaturesTest`：`requiredMapsEachSwitch`、`noneAndFollowRequireNothing`、`waypointRenameIsNewlyEnabled`、`disablingIsNotNewlyEnabled`。
  - `AppFeatureGateTest`：`enableRejectedWhenFlagUnavailable`、`enableRejectedWhenFlagMissing`（flags 里没有该码）、`disableAlwaysAllowed`、`waypointMustHandleService`（`waypoint-for=workload` 的候选被拒）、`releaseWarnsForUnselectedLiveFeature`、`releaseBlocksSelectedNewlyEnabled`。
  - `AppServiceConfigServiceTest.updateCallsGateWithStoredDesired`（mock gate，断言 before = 库里旧 desired）。
  - `AppReleaseServiceTest.rollbackIgnoresFeatureGate`（组件全不可用时 rollback 仍创建执行）。
- [ ] **Step 2: 跑测试确认失败**
- [ ] **Step 3: 实现**；`AppServiceConfigService` 里门禁在 `normalize` 之后、写库之前；`preview` 把 Report 并进现有 `problems` / `warnings`，`create` 把 problems 并进抛错列表。
- [ ] **Step 4: 跑测试确认通过** — `cd platform-api && mvn -q test`（全模块）
- [ ] **Step 5: 提交** `feat(app): component gate and waypoint checks for service features`

### Task 7: 服务配置页（HPA / 可观测 / 网格表单 + 门禁）

**Files:**
- Modify: `platform-web/src/types.ts`（`AppServiceDesired` 加 `targetMemory?`、`otel?: AppServiceOtel`、`mesh?: AppServiceMesh`）
- Create: `platform-web/src/components/app-release/appFeatures.ts`（与 `AppServiceFeatures` 同口径的 `requiredFlags(desired): string[]`，Task 8 回滚警告也用）
- Create: `platform-web/src/components/app-release/AppServiceOtelForm.vue`、`AppServiceMeshForm.vue`
- Modify: `platform-web/src/views/ApplicationServiceView.vue`（「高级」页签 HPA 区；新「可观测」「服务网格」子页签）

**Interfaces:**
- Consumes `useClusterComponents(clusterId)` 的 `available(code)` / `reasonOf(code)` / `probed`；waypoint 候选用 `/gateways/list` 已有 API + 与 Service 编辑器相同的 `waypoint-for` 过滤（找 `ServiceEditorView.vue:135` 附近现成实现复用，不复制判定）。
- 两个表单组件：`v-model` 为 `AppServiceOtel | undefined` / `AppServiceMesh | undefined`，props `available: Record<string, boolean>`、`reasons: Record<string, string>`、`isService: boolean`、`readonly: boolean`。

- [ ] **Step 1: 实现门禁规则** — 开关置灰条件 =「`!probed` 或 `!available(code)`」且**当前值为关**（已开启的始终可关），tooltip 显示 `reasonOf(code)`，未探测时文案「组件状态未知」。HPA 开启时副本数输入只读并提示「副本数由 HPA 在 min–max 间调整」；新增「目标内存 %」（可空）。暴露指标开关同样门禁（`servicemonitor`）。
- [ ] **Step 2: 可观测子页签** — 字段与 Task 2 的 `otel` 一致；文件日志模式才出现日志文件；采样率只在追踪开启时可填；服务名只读显示 `<slug>-<key>`。
- [ ] **Step 3: 网格子页签** — `dataplaneMode` 三项（跟随命名空间 / 纳入 ambient / 排除）；`useWaypoint` 仅服务显示，选项「跟随命名空间 / 不使用（none）/ 候选名字」，当前值不在候选里保留 disabled 回显项；注意 EP `value=""` 坑（见记忆 b3：跟随项同时补同文案 placeholder）。保存时缺省值不写键。
- [ ] **Step 4: 验证** — `cd platform-web && npm run build`（含 vue-tsc）通过。
- [ ] **Step 5: 提交** `feat(app-web): HPA / observability / mesh settings on service page`

### Task 8: 运行态展示、跳转与回滚警告

**Files:**
- Create: `platform-web/src/components/app-release/AppServiceRuntimeRows.vue`（概览里的「自动扩缩容」「可观测」「服务网格」三行）
- Modify: `ApplicationServiceView.vue`（概览插入组件；HPA 开启时「副本数」显示实时值）
- Modify: `platform-web/src/views/observability/LogsView.vue`（读 `route.query.workload` / `pod` 预填 `filters`）、`platform-web/src/views/mesh/TraceView.vue`（读 `route.query.service` 预填 `filters.serviceName`）
- Modify: `platform-web/src/components/app-release/AppRollbackDialog.vue`（目标快照里每个服务 `requiredFlags` 不可用 → 列警告，不阻止确认）

**Interfaces:** Consumes `hpaApi.get`（名字 `<slug>-<key>`）、`requiredFlags`（Task 7）、路由名 `observability-logs`、`mesh-traces`、`mesh-topology`（`?focus=<ns>/<workload>`）。

- [ ] **Step 1: 概览三行** — 只对**线上快照**里开启的项显示。HPA：当前 / 期望副本、各指标 当前值 / 目标、`ScalingActive=False` 或 `AbleToScale=False` 的 reason/message、「查看 HPA」；读失败或无权限时整行不显示。可观测：已接入的语言 / 追踪 / 日志 +「历史日志」「链路」按钮（无对应页权限则隐藏按钮）。网格：配置值 +「在网格拓扑里看」。
- [ ] **Step 2: 两个页面的 query 参数** — 进页时有参数就覆盖对应 filter 并立即查询；无参数行为不变。
- [ ] **Step 3: 回滚警告** — 用 `release/get` 已拿到的目标快照 + `useClusterComponents`，每条「服务 <key>：<功能> 依赖的组件当前不可用（<reason>），回滚后不生效」。
- [ ] **Step 4: 验证** — `npm run build` 通过。
- [ ] **Step 5: 提交** `feat(app-web): runtime rows, deep links to logs / traces / topology, rollback warnings`

### Task 9: 文档

**Files:**
- Modify: `docs/development/application-release.md`（新增 §1.7「HPA / 可观测 / 服务网格」：desired 键、门禁规则、回滚只警告、HPA 不写 replicas 的切换行为；§5 验收清单追加 spec §9 各条）
- Modify: `k8s-server/src/main/resources/charts/app/workload/README.md`（values 表加注入参数与 `targetMemory`；产物对照表 HPA 行注明不写 replicas）

- [ ] **Step 1: 写文档**；上线步骤写明：**不需要 SQL**，重建 k8s-server → platform-api → platform-web，k8s-server 先部署（新 platform-api 发的注入参数旧 k8s-server 不认，会被静默忽略）。
- [ ] **Step 2: 标注真跑项** — spec §3.1 两条「切到不写 replicas 那次发布副本是否重置」「scale 子资源 manager」写进验收清单，实测后回填结论。
- [ ] **Step 3: 提交** `docs(app): HPA / observability / mesh for app services`
