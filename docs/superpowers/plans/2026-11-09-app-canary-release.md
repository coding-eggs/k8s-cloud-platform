# 应用入口灰度 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给网关暴露的无状态服务加入口灰度（权重 + header 分流，手动推进），附带探针 HTTP / TCP 类型。

**Architecture:** 灰度状态存在发布快照 `services.<key>.canary` 块里，不新建表。canary 渲染成一个额外节点 `<key>-canary`，
它的对象名是 `<slug>-<key>-canary`；stable 的路由同时写两个后端。四个操作（开始 / 调整 / 全量 / 回退）都是普通发布，
走现有执行器。执行阶段在 HEALTH 之后插入 ROUTE：路由等工作负载就绪后才下发。

**Tech Stack:** Spring Boot（platform-api / k8s-server）、fabric8、helm chart（`charts/app/workload/1.0.0`）、MyBatis XML、Vue 3 + Element Plus。

**Spec:** `docs/superpowers/specs/2026-11-09-app-canary-release-design.md`

## Global Constraints

- 在 main 上工作，不推送；只提交本计划改动的文件；提交信息结尾 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。
- k8s-server 零业务：它只做通用渲染 / 下发 / 查询，灰度规则全部在 platform-api。
- 一页一 controller；同类资源不抽公共基类；分页用 PageHelper；不加外键。
- 本仓没有 Flyway：SQL 文件手工重放；MySQL `--` 注释后面必须跟空白；新权限行必须显式绑定角色。
- 测试命令：`mvn -o -pl k8s-server,platform-api -am test -Dtest='<类名>' -Dsurefire.failIfNoSpecifiedTests=false`。
  全量：`mvn -o -pl '!platform-gateway' test`（platform-gateway 模块有既有失败，与本计划无关）。
  前端：`cd platform-web && npx vue-tsc --noEmit -p tsconfig.app.json`。
- 后缀常量：`AppCanary.SUFFIX = "-canary"`。默认值：副本 1、权重 10、header `x-canary: true`。
- 权重 0-100 的整数；header 只对 HTTP 服务有效。
- 发布 type：`CANARY_START` / `CANARY_UPDATE` / `CANARY_PROMOTE` / `CANARY_ABORT`。
- 执行阶段：`PRECHECK → RENDER → CONFLICT → APPLY → HEALTH → ROUTE → PRUNE → DONE`。
- 路由 kind：`HTTPRoute`、`TCPRoute`（常量 `AppReleaseExecutor.ROUTE_KINDS`）。

## Review Focus

1. **普通发布在灰度期间重新渲染灰度服务**：别的服务发布时，灰度服务按线上快照重新渲染，它的 canary 节点和带权重的路由必须原样保留，不能被删或重置成 100% stable。
2. **续跑幂等**：ROUTE 阶段在下发后、落库前崩溃，下一拍会重跑 ROUTE。重复下发路由必须无害。
3. **回滚到灰度期间的历史版本**：回滚目标快照里的 canary 块要被剥掉，否则回滚会悄悄重新拉起 canary。
4. **服务名撞后缀**：已有服务叫 `api`，用户新建一个 `api-canary`，会和灰度节点的对象名冲突。新建时必须拦住以 `-canary` 结尾的服务名。
5. **全量失败后回退**：台账已被改写成 canary 的配置，回退必须把它还原成线上快照里的 stable 配置，而且之后不应显示「有待发布改动」。

---

## 段一：探针类型、灰度模型、渲染

### Task 1: 探针类型 HTTP / TCP

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/app/AppServiceDesired.java`（白名单加 `probeType`；normalize 校验）
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/app/AppWorkloadValues.java:67-69`（probe 加 `type`）
- Modify: `k8s-server/src/main/resources/charts/app/workload/1.0.0/chart/templates/deployment.yaml:110-123`
- Modify: `k8s-server/src/main/resources/charts/app/workload/1.0.0/chart/templates/statefulset.yaml`（同样的探针块，如果有）
- Modify: `k8s-server/src/main/resources/charts/app/workload/1.0.0/chart/values.yaml`（`probe.type: http`）
- Modify: `platform-web/src/types.ts`（`AppServiceDesired.probeType?: 'http' | 'tcp'`）、`platform-web/src/views/ApplicationServiceView.vue:652-656`
- Test: `platform-api/src/test/java/com/coding/platformapi/app/AppServiceDesiredTest.java`、
  `k8s-server/src/test/java/com/coding/k8sserver/app/AppWorkloadValuesTest.java`、
  `k8s-server/src/test/java/com/coding/k8sserver/app/AppWorkloadChartRenderTest.java`

**Interfaces:**
- Produces：desired 字段 `probeType`，取值 `"http"` / `"tcp"`，缺省不存（= http）；chart values `workload.probe.type`。

- [ ] **Step 1: 写失败测试**
  - `AppServiceDesiredTest.probeTypeTcpIsKeptAndHttpIsTheDefault`：normalize `{port:80, probeType:"TCP"}` 后 `probeType == "tcp"`；`{port:80, probeType:"http"}` 后 desired **不含** `probeType`（缺省不存，避免旧数据变成待发布改动）；`probeType:"grpc"` 抛 `CloudPlatformException`，消息含「探针类型」。
  - `AppWorkloadValuesTest.tcpProbeTypeReachesValues`：params `probeType=tcp` → `workload.probe.type == "tcp"`；不传 → `"http"`。
  - `AppWorkloadChartRenderTest.tcpProbeRendersTcpSocket`：渲染出的 Deployment 容器 `readinessProbe.tcpSocket.port == "http"`，且没有 `httpGet`；HTTP 时仍是 `httpGet.path`。

- [ ] **Step 2: 运行，确认失败**
  Run: `mvn -o -pl k8s-server,platform-api -am test -Dtest='AppServiceDesiredTest,AppWorkloadValuesTest,AppWorkloadChartRenderTest' -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: 三个新测试 FAIL（字段被白名单丢弃 / values 无 type / 模板无 tcpSocket）。

- [ ] **Step 3: 实现**
  - `AppServiceDesired`：白名单加 `probeType`。normalize 时转小写；`http` 或空 → 移除；`tcp` → 保留；其他 → `bad("不支持的探针类型：...（可选 HTTP / TCP）")`。
  - `AppWorkloadValues`：probe map 加 `"type", "tcp".equals(params.get("probeType")) ? "tcp" : "http"`。
  - 模板：`{{- if eq .Values.workload.probe.type "tcp" }} tcpSocket: { port: http } {{- else }} httpGet: {...} {{- end }}`，readiness 和 liveness 两处都改。
  - 前端：探针开关下加「探针类型」单选 HTTP / TCP，TCP 时隐藏路径。暴露方式切到「网关 TCP」且用户没手动选过类型时，把 `probeType` 设成 `tcp`。

- [ ] **Step 4: 运行，确认通过**
  同 Step 2 的命令。Expected: PASS。再跑 `npx vue-tsc --noEmit -p tsconfig.app.json`，Expected: rc=0。

- [ ] **Step 5: 提交**
  `git commit -m "feat(app): probe type http/tcp for app services"`

### Task 2: 灰度模型 `AppCanary` 与待发布判定

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/app/AppCanary.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/app/AppPending.java:101`（`changedFields` 忽略 `canary` 键）
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/AppServiceConfigService.java:294`（`assertValidKey` 拒绝 `-canary` 结尾）
- Create: `platform-api/src/test/java/com/coding/platformapi/app/AppCanaryTest.java`、`platform-api/src/test/java/com/coding/platformapi/app/AppPendingTest.java`（目前不存在，新建）
- Test: `AppServiceConfigServiceTest.java`

**Interfaces:**
- Produces（`AppCanary`，全静态，final 类）：
  - `static final String KEY = "canary"; static final String SUFFIX = "-canary";`
  - `record Spec(Map<String,Object> desired, int replicas, int weight, Header header)`；`record Header(String name, String value)`
  - `static Spec of(Map<String,Object> serviceSnapshot)`：没有 canary 块返回 null
  - `static Map<String,Object> with(Map<String,Object> stable, Spec spec)`：返回新 map，stable 原样加 `canary` 块
  - `static Map<String,Object> without(Map<String,Object> serviceSnapshot)`：返回去掉 `canary` 块的新 map
  - `static AppSnapshot strip(AppSnapshot s)`：所有服务去掉 canary 块（回滚用）
  - `static List<String> activeKeys(AppSnapshot s)`：带 canary 块的服务 key，按字母序
  - `static String nodeKey(String key)`：`key + SUFFIX`
  - 快照里 canary 块的 JSON 形状：`{"desired":{...},"replicas":1,"weight":10,"header":{"name":"x-canary","value":"true"}}`，没开 header 时 `"header":null`。

- [ ] **Step 1: 写失败测试**
  - `AppCanaryTest.roundTripsThroughSnapshotJson`：`with(stable, spec)` 放进 `AppSnapshot`，`toJson` 再 `parse`，`of(...)` 得到相同的 weight / replicas / header / desired。
  - `AppCanaryTest.stripRemovesEveryCanaryBlock`、`activeKeysListsOnlyServicesInCanary`、`withoutLeavesStableUntouched`。
  - `AppPendingTest.canaryBlockIsNotAPendingChange`：线上 `{imageTag:1, canary:{...}}`、台账 `{imageTag:1}` → `compute` 返回空。
  - `AppServiceConfigServiceTest.createRejectsKeysEndingWithCanarySuffix`：创建 `api-canary` 抛异常，消息含「-canary」。

- [ ] **Step 2: 运行，确认失败**
  Run: `mvn -o -pl platform-api -am test -Dtest='AppCanaryTest,AppPendingTest,AppServiceConfigServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: `AppCanaryTest` 编译失败，其余新测试 FAIL。

- [ ] **Step 3: 实现**
  - `AppCanary` 按 Interfaces 实现。`of` 对数字字段用 `Number.intValue()`，缺省按 Global Constraints 的默认值兜底。
  - `AppPending.changedFields`：遍历键时跳过 `AppCanary.KEY`。
  - `assertValidKey`：`key.endsWith(AppCanary.SUFFIX)` → `error("服务名不能以 -canary 结尾（灰度版本保留后缀）")`。

- [ ] **Step 4: 运行，确认通过**
  同 Step 2 的命令。Expected: PASS。

- [ ] **Step 5: 提交**
  `git commit -m "feat(app): canary block model in release snapshot"`

### Task 3: 渲染 canary 节点与加权路由

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/AppReleasePipeline.java:188-262`（`buildRequest`）
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/app/AppWorkloadValues.java:102-115`（`route` 透传 canary）
- Modify: `k8s-server/src/main/resources/charts/app/workload/1.0.0/chart/templates/route.yaml`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/AppReleasePipelineTest.java`、
  `k8s-server/src/test/java/com/coding/k8sserver/app/AppWorkloadValuesTest.java`、`AppWorkloadChartRenderTest.java`

**Interfaces:**
- Consumes: Task 2 的 `AppCanary.of / nodeKey`。
- Produces:
  - 渲染请求里，灰度中的服务 `X` 多出一个节点 `key = X-canary`，kind `SERVICE`，params 取自 canary desired。其中 `replicas = spec.replicas`、`autoscalingEnabled = false`、`expose = "none"`，不带 `route`、`nodePort`。env 和配置文件引用照常解析。
  - stable 节点的 `params.route` 多一个键 `canary = {service: "<slug>-X-canary", weight: <int>, header: {name, value} | null}`。
  - chart values：`workload.route.canary = {enabled, service, weight, headerName, headerValue}`。

- [ ] **Step 1: 写失败测试**
  - `AppReleasePipelineTest.canaryServiceRendersAnExtraNodeAndWeightedRoute`：快照中 `api` 带 canary（weight 20，header `x-canary:true`，canary desired 的 imageTag 是 `2`）→ 请求里有节点 `api` 与 `api-canary`；`api-canary` 的 `image` 以 `:2` 结尾，`replicas == 1`，`autoscalingEnabled == false`，没有 `route`；`api` 的 `route.canary.weight == 20`、`route.canary.service == "shop-api-canary"`。
  - `AppReleasePipelineTest.serviceWithoutCanaryRendersExactlyAsBefore`：没有 canary 块时，节点数和 route params 与改动前逐字相同（防止普通服务被无端滚动）。
  - `AppWorkloadValuesTest.routeCarriesCanaryBackend`：params.route 带 canary → values 里 `route.canary.enabled == true`、weight、headerName；不带 → `route.canary.enabled == false`。
  - `AppWorkloadChartRenderTest.httpRouteWithCanaryHasHeaderRuleFirstThenWeights`：渲染出的 HTTPRoute `rules[0].matches[0].headers[0].name == "x-canary"`，后端是 canary；`rules[1].backendRefs` 两项，权重为 80 / 20。
  - `AppWorkloadChartRenderTest.tcpRouteWithCanaryHasOnlyWeights`：TCPRoute 只有一条规则、两个后端，没有 matches。
  - `AppWorkloadChartRenderTest.routeWithoutCanaryIsUnchanged`：单一后端、无 weight 字段（和现在一样）。

- [ ] **Step 2: 运行，确认失败**
  Run: `mvn -o -pl k8s-server,platform-api -am test -Dtest='AppReleasePipelineTest,AppWorkloadValuesTest,AppWorkloadChartRenderTest' -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: 新测试 FAIL。

- [ ] **Step 3: 实现**
  - `buildRequest`：遍历服务时，先按现有逻辑生成 stable 节点。若 `AppCanary.of(desired)` 非空，用同一套逻辑（同样的 env 解析、配置文件解析、应用设置注入）再生成 canary 节点，再按 Produces 覆盖字段。抽一个私有方法 `node(key, desired, ...)`，供两处共用。
  - **stable 节点的 params 不能带 `canary` 块**：`NODE_EXCLUDED` 加 `AppCanary.KEY`，否则 configHash 一变 stable 会被无端滚动。
  - canary 节点的应用设置注入用 stable 的服务 key 取生效配置（`snapshot.effective(X)`），对象名用 `AppObjectNames.objectName(slug, X-canary)`。
  - 网关方式：stable 的 route map 在有 canary 时加 `canary` 键。route map 用 `LinkedHashMap`，现有的 `Map.of` 不能放 null header。
  - `AppWorkloadValues.route`：读 `r.get("canary")`；`weight` 截断到 0-100；kind 为 tcp 时忽略 header。
  - `route.yaml`：
    ```yaml
    rules:
      {{- $c := .Values.workload.route.canary }}
      {{- if and $c.enabled $c.headerName (ne .Values.workload.route.kind "tcp") }}
      - matches:
          - headers:
              - name: {{ $c.headerName | quote }}
                value: {{ $c.headerValue | quote }}
        backendRefs:
          - name: {{ $c.service | quote }}
            port: {{ .Values.workload.route.port }}
      {{- end }}
      - backendRefs:
          - name: {{ include "app.name" . | quote }}
            port: {{ .Values.workload.route.port }}
            {{- if $c.enabled }}
            weight: {{ sub 100 $c.weight }}
          - name: {{ $c.service | quote }}
            port: {{ .Values.workload.route.port }}
            weight: {{ $c.weight }}
            {{- end }}
    ```
    values.yaml 给 `route.canary` 一个 `enabled: false` 的默认块，否则访问字段会报 nil。

- [ ] **Step 4: 运行，确认通过**
  同 Step 2 的命令。Expected: PASS。

- [ ] **Step 5: 提交**
  `git commit -m "feat(app): render canary node and weighted gateway route"`

---

## 段二：执行阶段、灰度操作、锁

### Task 4: 执行阶段 ROUTE（路由在健康之后下发）

**Files:**
- Modify: `platform-common/src/main/java/com/coding/common/models/app/AppApplyRequestDTO.java`（加 `Set<String> onlyKinds`、`Set<String> skipKinds`，都可空）
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/app/AppApplyService.java:37`（按 kind 过滤清单后再下发）
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/AppReleaseExecutor.java`
  （`apply` 跳过路由；`health` 不看路由对象；新增 `route` 阶段；阶段中文名在 `:477` 附近加 `ROUTE → "路由"`）
- Modify: 前端阶段名映射（在 `platform-web/src` 里搜 `PRUNE` 找到位置，加 `ROUTE: '路由'`）
- Test: `k8s-server/src/test/java/com/coding/k8sserver/app/AppApplyServiceTest.java`、
  `platform-api/src/test/java/com/coding/platformapi/services/AppReleaseExecutorTest.java`

**Interfaces:**
- Produces：`AppReleaseExecutor.ROUTE_KINDS = Set.of("HTTPRoute", "TCPRoute")`。阶段流转为 `HEALTH → ROUTE → PRUNE`；下线仍是 `PRECHECK → PRUNE`。
- `AppApplyRequestDTO`：`onlyKinds` 非空时只下发这些 kind；`skipKinds` 非空时跳过这些 kind；过滤后为空就什么都不做、直接返回成功。

- [ ] **Step 1: 写失败测试**
  - `AppApplyServiceTest.skipKindsDropsRoutesFromTheManifest`、`onlyKindsKeepsOnlyRoutes`、`emptyAfterFilterIsANoop`：断言传给 `ManifestApplier.applyServerSide` 的清单里有 / 没有 `kind: HTTPRoute`；过滤后为空时不调用 applier。
  - `AppReleaseExecutorTest.applySkipsRoutesAndHealthIgnoresThem`：APPLY 发出的请求 `skipKinds == ROUTE_KINDS`；HEALTH 时 Deployment READY、HTTPRoute 状态 MISSING → 仍然进入 `ROUTE`。
  - `AppReleaseExecutorTest.routeStageAppliesOnlyRoutesThenPrunes`：ROUTE 发出 `onlyKinds == ROUTE_KINDS` 的请求，下一阶段是 `PRUNE`；日志 stage 为 `ROUTE`。
  - `AppReleaseExecutorTest.routeStageWithoutRoutesGoesStraightToPrune`：清单里没有路由对象 → 不调用 apply，直接 `PRUNE`。
  - `AppReleaseExecutorTest.routeStageIsIdempotentOnResume`（Review Focus 2）：同一执行连续跑两次 ROUTE，两次都只发 onlyKinds 请求、都进 PRUNE，不报错。

- [ ] **Step 2: 运行，确认失败**
  Run: `mvn -o -pl k8s-server,platform-api -am test -Dtest='AppApplyServiceTest,AppReleaseExecutorTest' -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: 新测试 FAIL（字段不存在 / 阶段不存在）。

- [ ] **Step 3: 实现**
  - k8s-server：`ManifestApplier.parse(manifest)` 得到对象列表，按 kind 过滤后用 fabric8 `Serialization.asYaml` 逐个序列化，以 `---` 拼接。副本交接（`handOverReplicas`）用过滤后的清单。
  - 执行器：`apply` 请求设 `skipKinds = ROUTE_KINDS`。`health` 计算 `objects` 时过滤掉 kind 属于 `ROUTE_KINDS` 的对象，全部就绪时返回 `"ROUTE"`（原来是 `"PRUNE"`）。
  - 新增 `route(execution)`：下线直接 `PRUNE`；清单里没有路由对象也直接 `PRUNE`；否则 `client.apply(onlyKinds = ROUTE_KINDS)`，日志「已下发 N 个路由」，返回 `PRUNE`。只确认下发成功，不读 Istio 状态（顶层 Programmed 不可信，见 spec §11）。
  - `advance` 的 switch 加 `case "ROUTE" -> route(execution);`，阶段中文名加 `"ROUTE" -> "路由"`。

- [ ] **Step 4: 运行，确认通过**
  同 Step 2 的命令。Expected: PASS。原有断言 `HEALTH → PRUNE` 的测试要一并改成 `HEALTH → ROUTE`。

- [ ] **Step 5: 提交**
  `git commit -m "feat(app): ROUTE stage applies routes after workloads are healthy"`

### Task 5: 灰度操作 `AppCanaryService`

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/services/AppCanaryService.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/AppCanaryRequest.java`
  （`appId, serviceKey, Map<String,Object> desired, Integer replicas, Integer weight, Boolean headerEnabled, String headerName, String headerValue, String note`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/AppReleaseService.java:216`（`launch`、`requireNoRunningExecution`、`liveSnapshot` 改为包可见，供 `AppCanaryService` 复用）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/AppCanaryServiceTest.java`

**Interfaces:**
- Consumes：`AppCanary`（Task 2）；`AppReleaseService.launch(app, authorization, plannedLive, snapshotJson, type, sourceNo, note, operator, writes)`。
- Produces（都返回执行 id `Long`，参数末尾是 `String operator, String tenantId`）：
  - `start(AppCanaryRequest req, ...)`
  - `update(AppCanaryRequest req, ...)`：字段为 null 表示不改
  - `promote(String appId, String serviceKey, String note, ...)`
  - `abort(String appId, String serviceKey, String note, ...)`

- [ ] **Step 1: 写失败测试**（依赖的 mock 写法照抄 `AppReleaseServiceTest` 的 setUp）
  - `startAddsCanaryBlockToTheLiveSnapshot`：线上 `api` 为 gateway-http；start 后插入的发布 type 是 `CANARY_START`，快照 `services.api.canary.weight == 10`、`replicas == 1`、`header.name == "x-canary"`，canary desired 的 imageTag 为请求值，其余字段等于 stable。
  - `startRejects`（每种情况一个 `assertThatThrownBy`）：服务未上线；暴露方式不是网关；有状态服务；已在灰度；canary desired 的 port / expose 与 stable 不同；weight 为 101；TCP 服务开 header。
  - `updateChangesOnlyGivenFields`：只传 weight 50 → 快照 canary.weight 50，desired 与 header 不变，type 为 `CANARY_UPDATE`。
  - `promoteWritesCanaryDesiredBackToStableAndDropsCanary`：快照 `services.api` 等于 canary desired、没有 canary 块，type 为 `CANARY_PROMOTE`；`services.updateWithVersion` 写入的 desired 等于 canary desired。
  - `abortRestoresStableRowFromLiveSnapshot`（Review Focus 5）：台账 desired 已是 canary 配置（模拟全量失败后），abort 后台账写回线上快照的 stable desired，快照里没有 canary 块，type 为 `CANARY_ABORT`；之后 `AppPending.compute(...)` 对 `api` 返回空。
  - `abortWithMatchingRowDoesNotWriteTheRow`：台账与线上一致时不调用 `updateWithVersion`。
  - `promoteAndAbortRejectWhenNotInCanary`。
  - `operationsRejectWhileAnExecutionIsRunning`。

- [ ] **Step 2: 运行，确认失败**
  Run: `mvn -o -pl platform-api -am test -Dtest='AppCanaryServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现**
  - 公共前置：`AppGuard.require`；取当前请求凭据（同 `AppReleaseService.create`）；`requireNoRunningExecution`；`plannedLive`；`live = liveSnapshot(app)`；`live.services().get(key)` 为空 → 「服务未上线」。
  - start：canary desired = `AppServiceDesired.normalize(req.desired)`；请求没给 desired 时取 stable 的副本。按 spec §6.2 校验。有状态的判据要与 k8s-server 选 StatefulSet 的判据一致，实现时到 `AppWorkloadValues` 核对（预期是 `persistenceEnabled == true`），ledger 记下核对结果。快照 = live 的副本，`api` 换成 `AppCanary.with(stable, spec)`；再调 `pipeline.problems(app, next)`，有问题就拦。
  - update：在已有 spec 上覆盖非 null 字段，校验同 start。
  - promote：next 里 `api = AppCanary.without(canaryDesired)`；`writes` 里把台账行 desired 写成 canary desired（`updateWithVersion`，冲突报「配置冲突」）。
  - abort：next 里 `api = AppCanary.without(liveService)`；`writes` 里若台账 desired 与 `without(liveService)` 不同（用 `AppPending.changedFields` 判断）就写回。
  - type 用 Global Constraints 的四个常量。note 为空时自动生成，例如「灰度 api：权重 10%」「全量 api」。

- [ ] **Step 4: 运行，确认通过**
  同 Step 2 的命令。Expected: PASS。

- [ ] **Step 5: 提交**
  `git commit -m "feat(app): canary start/update/promote/abort as releases"`

### Task 6: 灰度期间的锁与回滚剥离

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/AppServiceConfigService.java`（update / remove 拒绝灰度中的服务）
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/AppReleaseService.java`
  （`create` 勾选灰度中的服务 → 拒；`plan()` 保留未勾选服务的 canary 块；`rollback` / `offline` 有灰度时拒；`rollback` 目标快照 `AppCanary.strip`）
- Test: `AppServiceConfigServiceTest.java`、`AppReleaseServiceTest.java`

**Interfaces:**
- Consumes：`AppCanary.activeKeys(liveSnapshot)`、`AppCanary.strip`。

- [ ] **Step 1: 写失败测试**
  - `AppServiceConfigServiceTest.updateAndRemoveRejectServiceInCanary`：线上快照 `api` 带 canary → update / remove 抛异常，消息含「灰度中」。
  - `AppReleaseServiceTest.createRejectsSelectingAServiceInCanary`。
  - `AppReleaseServiceTest.releasingAnotherServiceKeepsTheCanaryBlock`（Review Focus 1）：线上 `api` 带 canary、`web` 有改动，只勾 `web` 发布 → 新快照里 `api` 的 canary 块与线上逐字相同。
  - `AppReleaseServiceTest.rollbackAndOfflineRejectWhileAnyServiceIsInCanary`。
  - `AppReleaseServiceTest.rollbackToASnapshotWithCanaryStripsIt`（Review Focus 3）：目标发布的快照带 canary、线上没有 → 回滚插入的快照没有 canary 块。
  - `AppReleaseServiceTest.overviewPendingIgnoresCanary`：灰度中的服务在概览里 `pending == null`。
  - `AppReleasePipelineTest.ingressReconcileIgnoresCanaryNodes`：PRECHECK 分配网关端口时只看真实服务 key，不会为 `api-canary` 申请端口。

- [ ] **Step 2: 运行，确认失败**
  Run: `mvn -o -pl platform-api -am test -Dtest='AppServiceConfigServiceTest,AppReleaseServiceTest,AppReleasePipelineTest' -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: 新测试 FAIL。如果某条因现有代码恰好已满足而直接通过（例如 `plan()` 本来就整份取线上），保留为回归测试，并在 ledger 记一条 Ruling。

- [ ] **Step 3: 实现**
  - 配置服务：读线上快照（与 `isPublished` 相同的取法），`AppCanary.of(live.get(key)) != null` → `error("服务 " + key + " 灰度中，先全量或回退")`。
  - `plan()`：未勾选的服务原样取线上（包括 canary 块）；勾选的服务在 `activeKeys` 中 → 记入 errors。
  - rollback / offline：`!AppCanary.activeKeys(live).isEmpty()` → `error("有服务正在灰度（" + keys + "），先全量或回退")`；rollback 的 target 先 `AppCanary.strip`。

- [ ] **Step 4: 运行，确认通过**
  同 Step 2 的命令。Expected: PASS。

- [ ] **Step 5: 提交**
  `git commit -m "feat(app): lock services in canary; rollback strips canary"`

### Task 7: 概览带灰度信息

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/AppOverviewView.java`
  （`ServiceRow` 末尾加 `Canary canary`；新增 `record Canary(int weight, String headerName, String headerValue, String image, int replicas, String state)`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/AppReleaseService.java:575-612`（组装 canary；state 取 `states` 里 `<slug>-<key>-canary` 的状态）
- Modify: `platform-web/src/types.ts`（`AppServiceRow.canary?: AppServiceCanary | null`）
- Test: `AppReleaseServiceTest.java`

- [ ] **Step 1: 写失败测试** `overviewCarriesCanaryWeightImageAndState`：线上 `api` 带 canary（weight 30，镜像 tag 2），状态查询返回 `shop-api-canary` Deployment READY → 该行 `canary().weight() == 30`、`image` 以 `:2` 结尾、`state == "READY"`；不在灰度的行 `canary() == null`。
- [ ] **Step 2: 运行，确认失败**
  Run: `mvn -o -pl platform-api -am test -Dtest='AppReleaseServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: 编译失败。
- [ ] **Step 3: 实现**：两处 `ServiceRow` 构造补参数。发布清单本来就包含 canary 对象，`liveStates` 返回的 `states` 直接可用。
- [ ] **Step 4: 运行，确认通过**（同 Step 2），再跑 `npx vue-tsc --noEmit -p tsconfig.app.json`。
- [ ] **Step 5: 提交** `git commit -m "feat(app): overview shows canary state"`

---

## 段三：指标、接口与权限、前端、收尾

### Task 8: 灰度指标查询

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/metrics/MetricQuery.java`（在服务网格拓扑段后新增「灰度对比」段）
- Create: `platform-api/src/main/java/com/coding/platformapi/metrics/labels/ServiceNameLabels.java`（`@JsonProperty("destination_service_name") String serviceName`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/metrics/MetricsService.java`（新方法 `canaryCompare`）
- Create: `platform-api/src/main/java/com/coding/platformapi/models/AppCanaryMetricsView.java`
- Modify: `AppCanaryService`（`metrics(appId, serviceKey, window, tenantId)`）
- Test: `platform-api/src/test/java/com/coding/platformapi/metrics/MetricsServiceCanaryTest.java`、`AppCanaryServiceTest.java`

**Interfaces:**
- 新查询（`%s` 依次为 ① 集群名 ② 命名空间 ③ 服务名正则 `stable|canary` ④ 时间窗），全部 `sum by (destination_service_name)`：
  - `CANARY_REQUESTS`：`rate(istio_requests_total{cluster_name="%s",destination_service_namespace="%s",destination_service_name=~"%s"}[%s])`
  - `CANARY_ERRORS`：同上加 `response_code=~"5.."`
  - `CANARY_LATENCY_P50` / `CANARY_LATENCY_P99`：`histogram_quantile(0.5|0.99, sum by (le, destination_service_name)(rate(istio_request_duration_milliseconds_bucket{...}[%s])))`
  - `CANARY_TCP_CONNECTIONS`：`rate(istio_tcp_connections_opened_total{...}[%s])`
  - `CANARY_TCP_BYTES`：`rate(istio_tcp_sent_bytes_total{...}[%s]) + rate(istio_tcp_received_bytes_total{...}[%s])`，两侧都按 by 聚合
  - 不加 `reporter` 过滤：本集群只有 `source` 与 `waypoint`（见 MetricQuery 里拓扑段的注释）。只要求 `destination_service_name` 匹配，网关上报的那份自然包含在内。真集群核对见 Task 11。
- `MetricsService.canaryCompare(String clusterId, String namespace, String stableSvc, String canarySvc, String window, boolean tcp)` → `Map<String, Map<String, Double>>`。外层 key `stable` / `canary`；内层 key 为 `rps, errorRate, p50, p99`（HTTP）或 `cps, bytes`（TCP）；查不到的为 null。
- `AppCanaryMetricsView(String window, String protocol, Map<String,Double> stable, Map<String,Double> canary)`
- window 只接受 `5m`、`15m`、`1h`，其他值按 `15m` 处理。

- [ ] **Step 1: 写失败测试**
  - `MetricsServiceCanaryTest.httpCompareSplitsByServiceName`：mock `ThanosQueryClient.query`，按 sql 内容返回两条带 `destination_service_name` 的结果 → stable / canary 的 rps 分开；errorRate = 5xx / 总量，总量为 0 时为 null；sql 里含 `cluster_name="c-name"`、`destination_service_name=~"shop-api|shop-api-canary"`。
  - `MetricsServiceCanaryTest.tcpCompareReturnsConnectionsAndBytes`。
  - `MetricsServiceCanaryTest.missingSeriesIsNullNotZero`。
  - `AppCanaryServiceTest.metricsRejectsServiceNotInCanaryAndNormalizesWindow`：不在灰度 → 抛错；`window="7d"` → 传给 `canaryCompare` 的是 `15m`。

- [ ] **Step 2: 运行，确认失败**
  Run: `mvn -o -pl platform-api -am test -Dtest='MetricsServiceCanaryTest,AppCanaryServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: 编译失败。

- [ ] **Step 3: 实现**
  集群名取法照抄 `MetricsService` 里拓扑查询的现有写法。即时查询用 `client.query(sql, null, ServiceNameLabels.class)`。服务名用 `AppObjectNames.objectName`。

- [ ] **Step 4: 运行，确认通过**（同 Step 2）。

- [ ] **Step 5: 提交** `git commit -m "feat(app): canary vs stable gateway metrics"`

### Task 9: 接口、权限 SQL、审计

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/controllers/ApplicationController.java`（在 `/release/offline` 之后加 5 个端点）
- Create: `platform-data/src/main/resources/db/migration/V2026_11_09_2__app_canary.sql`
- Create: `platform-api/src/test/java/com/coding/platformapi/services/AppCanaryPermissionMigrationTest.java`
  （读 SQL 文件断言，写法照抄 `components/ComponentPermissionMigrationTest.java`：用相对路径 `../platform-data/...` 读迁移文件）

**Interfaces:**

| 端点 | 方法 | 码 | 服务方法 |
|---|---|---|---|
| `/application/canary/start` | POST | `tenant:application:write` | `AppCanaryService.start` |
| `/application/canary/update` | POST | `tenant:application:write` | `update` |
| `/application/canary/promote` | POST | `tenant:application:write` | `promote` |
| `/application/canary/abort` | POST | `tenant:application:write` | `abort` |
| `/application/canary/metrics` | POST | `tenant:application:read` | `metrics` |

请求体：前四个用 `AppCanaryRequest`；metrics 用 `{appId, serviceKey, window}`，可复用 `AppCanaryRequest` 加一个 `window` 字段。operator、tenantId 的取法照抄同一 controller 的 `/release/create`。

- [ ] **Step 0: 写失败测试** `AppCanaryPermissionMigrationTest`：SQL 文件存在；5 个端点路径各有一行，code 与上表一致；回填绑定语句分别引用 `perm_app_release_create` 与 `perm_app_overview`。先跑一次，确认因文件不存在而 FAIL。
- [ ] **Step 1: 写 SQL**（头部注释格式照 `V2026_11_08_2__listenerset_readonly.sql`：部署顺序、重新登录、手工重放、`--` 后空格）
  - 5 行 `platform_permission`：id `perm_app_canary_start|update|promote|abort|metrics`，domain `API`，ODKU。
  - 绑定：新权限行不会因为 code 相同就自动继承绑定，所以按「持有同 code 旧行的角色」回填。写端点按持有 `perm_app_release_create` 的角色，metrics 按持有 `perm_app_overview` 的角色；id 用 `CONCAT('rp_cny_', SUBSTRING(MD5(CONCAT(role_id,'|',perm_id)),1,32))`，ODKU。
- [ ] **Step 2: 加 controller 端点**，审计照同 controller 写接口的现有做法（若是注解就加同样的注解）。
- [ ] **Step 3: 编译并跑全量后端**（`AppCanaryPermissionMigrationTest` 此时应通过）
  Run: `mvn -o -pl '!platform-gateway' test`
  Expected: BUILD SUCCESS，0 失败。
- [ ] **Step 4: 提交** `git commit -m "feat(app): canary endpoints and permission rows (V2026_11_09_2)"`

### Task 10: 前端灰度页签

**Files:**
- Modify: `platform-web/src/api/index.ts`（`appCanaryApi`：start / update / promote / abort / metrics）
- Modify: `platform-web/src/types.ts`（`AppServiceCanary`、`AppCanaryRequest`、`AppCanaryMetrics`）
- Create: `platform-web/src/components/app-release/AppCanaryPanel.vue`（灰度中的面板）
- Create: `platform-web/src/components/app-release/AppCanaryStartDialog.vue`（开始 / 修改灰度版本配置共用）
- Modify: `platform-web/src/views/ApplicationServiceView.vue`（加「灰度」页签；灰度中禁用其他页签的保存，提示原因）
- Modify: `platform-web/src/views/ApplicationDetailView.vue`（状态旁加标签「灰度中 N%」）
- Modify: `platform-web/src/components/app-release/AppReleaseDialog.vue`（灰度中的服务不可勾选，显示原因）
- Modify: `platform-web/src/components/app-release/AppReleaseHistory.vue`（四个新 type 的中文名，带服务名）

**界面规格**（spec §9）：
- 未灰度：一段说明 + 「开始灰度」按钮。不满足前提时按钮置灰，tooltip 说明原因：没上线 / 不是网关暴露 / 有状态服务。
- 开始弹窗：默认复制 stable，镜像 tag 置顶；端口和暴露方式只读；副本数（默认 1）；初始权重（默认 10）；「header 直达」开关（仅 HTTP），默认 `x-canary: true`。探针关闭时显示提示「探针已关闭：就绪只代表进程已启动」。镜像与 stable 相同时显示提示，但不拦截。
- 灰度中：
  - 权重滑块 0-100，加 0 / 10 / 25 / 50 / 100 快捷档，「应用」按钮发 update。
  - header 规则可开关和修改。
  - 指标表两列 stable / canary：HTTP 显示「请求/秒、错误率、P50、P99」，TCP 显示「新建连接/秒、字节/秒」。时间窗 5m / 15m / 1h，30 秒刷新。canary 错误率比 stable 高 1 个百分点以上时数字标橙。没有数据显示「暂无数据」。
  - 两个版本的 Pod：复用服务页现有 Pod 列表，按 `<slug>-<key>` 和 `<slug>-<key>-canary` 分别过滤。
  - 一行说明：「集群内其他服务调用的仍是 stable，canary 只接网关进来的流量」。
  - 「修改灰度版本配置」「全量」「回退」三个按钮，全量和回退要二次确认。回退确认文案：「回到灰度开始前：stable 恢复为灰度前的配置，删除灰度版本」。
  - 有执行进行中时，复用现有执行面板显示进度，按钮禁用。
- 弹窗、抽屉内的样式用非 scoped + 独有前缀 `app-cny-`（Element Plus 弹窗 scoped CSS 坑）。

- [ ] **Step 1: 实现上述改动**
- [ ] **Step 2: 类型检查**
  Run: `cd platform-web && npx vue-tsc --noEmit -p tsconfig.app.json`
  Expected: rc=0。
- [ ] **Step 3: 提交** `git commit -m "feat(app-web): canary tab, start dialog, metrics panel"`

### Task 11: 文档、部署说明、验收清单

**Files:**
- Modify: `docs/development/application-release.md`（新增「§1.8 入口灰度」：模型、四个操作、ROUTE 阶段、锁、指标；部署节加 `V2026_11_09_2` 与「k8s-server 先部署」；验收清单加 spec §10 的 5 条；已知限制加「canary 只接网关流量」「有状态服务不能灰度」「指标 label 待真集群核对」）
- Modify: `docs/superpowers/specs/2026-11-09-app-canary-release-design.md`（文首状态改为「已实现（未部署）」，附提交范围；实现与设计有出入的地方逐条列出）
- Memory：`C:\Users\wsail\.claude\projects\D--coding-k8s-cloud-platform\memory\` 新建 `app-canary-release.md` 并在 `MEMORY.md` 加一行（待部署；先重放 V2026_11_09_2；k8s-server 先部署；指标 label 待核对）

- [ ] **Step 1: 写文档与记忆**
- [ ] **Step 2: 全量验证**
  Run: `mvn -o -pl '!platform-gateway' test` 与 `cd platform-web && npx vue-tsc --noEmit -p tsconfig.app.json`
  Expected: 后端 BUILD SUCCESS、0 失败；前端 rc=0。
- [ ] **Step 3: 提交** `git commit -m "docs(app): canary release usage and deployment notes"`
