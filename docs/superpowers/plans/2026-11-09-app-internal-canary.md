# 应用服务内部灰度 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 灰度中的服务在应用开了 L7 网格时，集群内调用也按同一个权重分到 stable / canary；有网关暴露的服务可选外部流量是否参与。

**Architecture:** 在入口灰度之上加一条只在「灰度中 + 线上应用设置为 L7」时渲染的网格路由 `<slug>-<key>-mesh`（parentRef = stable Service，GAMMA）。
platform-api 决定渲不渲、网关路由带不带 canary 后端（`external`）；k8s-server 只多认两个通用参数（`protocol`、`meshRoute`）并透传给 chart。
指标按 reporter 分「内部调用 / 网关进来」两组，用 `destination_workload` 区分两个版本。

**Tech Stack:** Spring Boot 4 / Java（platform-api、k8s-server，JUnit5 + Mockito + AssertJ）、Helm chart（`charts/app/workload/1.0.0`）、Vue 3 + Element Plus + TS（platform-web，`vue-tsc`）。

**Spec:** `docs/superpowers/specs/2026-11-09-app-internal-canary-design.md`（扩展 `docs/superpowers/specs/2026-11-09-app-canary-release-design.md`）

## Global Constraints

- 在 main 上直接做，不推送；提交只含本计划改动的文件，结尾 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。
- 不碰工作区里其他会话的改动（各模块 `application.yaml`、`platform-web/vite.config.ts`）。
- k8s-server 零业务：只认通用参数、不判断网格 / 灰度语义（[[standing-architecture-principles]]）。
- 同类资源不抽公共基类；不加外键；无 SQL、无新端点、无新权限行（spec §7）。
- 网格路由只在「快照有灰度块 **且** 快照 `app` 设置 `mesh.mode = l7` 且有 waypoint」时渲染（spec §4.2）——没灰度的服务清单不得有任何变化（除 TCP 端口名，spec §4.4）。
- 协议判定（前后端同口径）：`expose = gateway-tcp` → TCP；否则 `probeType = tcp` → TCP；否则 HTTP。
- `external`：缺省（旧块）按 `true`；无网关暴露时存 `false`；非 L7 时只能为 `true`（spec §4.1 / §5.1）。
- 网格锁：有灰度时不能切入 / 切出 L7、不能换 waypoint；none / follow / l4 之间可切（spec §5.2）。
- 后端测试：`mvn -o -q -pl platform-api test -Dtest=<Class>`、`mvn -o -q -pl k8s-server test -Dtest=<Class>`；全量 `mvn -o -pl '!platform-gateway' test`。
- 前端验证只跑 `npx vue-tsc --noEmit -p tsconfig.app.json`（在 `platform-web/`），不做浏览器测试（用户约定）。
- 回复与文档中文，技术名词保留英文。

## Review Focus

1. **L7 应用、服务没有网关暴露**：只有网格路由，没有网关路由；开始灰度时 `external` 存 `false`，界面不出现开关。→ Task 3、Task 4 测试。
2. **非 L7、有网关暴露**：不渲染网格路由（与入口灰度清单一致）；`external=false` 被拒。→ Task 3、Task 4 测试。
3. **`external=false`**：网关路由与没灰度时一模一样（无 header 规则、无权重、单后端）。→ Task 3 测试。
4. **灰度中发布 `@app` 改网格**：切入 / 切出 L7 或换 waypoint 被拒（保存设置时、普通发布勾选 `@app` 时都拦）；none↔l4 放行。→ Task 5 测试。
5. **TCP 服务**：Service 端口名 `tcp`；网格路由是 TCPRoute 且没有 header 规则；没灰度的 HTTP 服务端口名仍是 `http`。→ Task 2、Task 3 测试。

---

### Task 1: 模型小件——`external`、协议判定、L7 判定

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/app/AppCanary.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/app/AppServiceDesired.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/app/AppSettings.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/app/AppCanaryTest.java`、`AppServiceDesiredTest.java`、`AppSettingsTest.java`

**Interfaces:**
- Produces:
  - `AppCanary.Spec(Map<String,Object> desired, int replicas, int weight, Header header, boolean external)`（新增第 5 个分量）；
    `of()` 读 `external`，缺省 `true`；`with()` 写 `external`。现有四参调用点（`AppCanaryService`、`AppReleasePipelineTest`、`AppCanaryServiceTest` 等）全部补参。
  - `static String AppServiceDesired.serviceProtocol(Map<String,Object> desired)` → `"HTTP"` / `"TCP"`；`null` 入参 → `"HTTP"`。
  - `static boolean AppSettings.isL7(Map<String,Object> settings)`：`"l7".equals(meshMode(settings)) && waypoint(settings) != null`。

- [ ] **Step 1: 写失败测试**

```java
// AppCanaryTest
@Test void externalDefaultsToTrueForOldBlocks() {
    Map<String, Object> svc = new LinkedHashMap<>(Map.of("imageTag", "1"));
    svc.put("canary", Map.of("desired", Map.of("imageTag", "2"), "replicas", 1, "weight", 10));
    assertThat(AppCanary.of(svc).external()).isTrue();
}
@Test void externalRoundTrips() {
    Map<String, Object> out = AppCanary.with(Map.of("imageTag", "1"),
            new AppCanary.Spec(Map.of("imageTag", "2"), 1, 10, null, false));
    assertThat(AppCanary.of(out).external()).isFalse();
}
// AppServiceDesiredTest
@Test void serviceProtocol() {
    assertThat(AppServiceDesired.serviceProtocol(Map.of("expose", "gateway-tcp"))).isEqualTo("TCP");
    assertThat(AppServiceDesired.serviceProtocol(Map.of("probeType", "tcp"))).isEqualTo("TCP");
    assertThat(AppServiceDesired.serviceProtocol(Map.of("expose", "gateway-http"))).isEqualTo("HTTP");
    assertThat(AppServiceDesired.serviceProtocol(Map.of("port", 8080))).isEqualTo("HTTP");
    assertThat(AppServiceDesired.serviceProtocol(null)).isEqualTo("HTTP");
}
// AppSettingsTest
@Test void isL7() {
    assertThat(AppSettings.isL7(Map.of("mesh", Map.of("mode", "l7", "waypoint", "wp")))).isTrue();
    assertThat(AppSettings.isL7(Map.of("mesh", Map.of("mode", "l4")))).isFalse();
    assertThat(AppSettings.isL7(Map.of())).isFalse();
    assertThat(AppSettings.isL7(null)).isFalse();
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -q -pl platform-api test -Dtest='AppCanaryTest,AppServiceDesiredTest,AppSettingsTest'`
Expected: 编译失败（`external()` / `serviceProtocol` / `isL7` 不存在）。

- [ ] **Step 3: 实现**，并把所有四参 `AppCanary.Spec` 构造补成五参（`grep -rn "AppCanary.Spec(" platform-api/src`）。
  `AppCanaryService.start/update` 本任务先传 `true` / `cur.external()`（Task 4 改成真实逻辑）；测试里的构造传 `true`。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -q -pl platform-api test -Dtest='AppCanaryTest,AppServiceDesiredTest,AppSettingsTest,AppCanaryServiceTest,AppReleasePipelineTest'`
Expected: PASS。

- [ ] **Step 5: Commit** — `feat(app): canary external flag, service protocol and L7 helpers`

### Task 2: k8s-server——`protocol` 端口名与 `meshRoute` 模板

**Files:**
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/app/AppWorkloadValues.java`
- Modify: `k8s-server/src/main/resources/charts/app/workload/1.0.0/chart/templates/service.yaml`
- Create: `k8s-server/src/main/resources/charts/app/workload/1.0.0/chart/templates/route-mesh.yaml`
- Modify: `k8s-server/src/main/resources/charts/app/workload/1.0.0/chart/values.yaml`、`k8s-server/src/main/resources/charts/app/workload/README.md`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/app/AppWorkloadValuesTest.java`、`AppWorkloadChartRenderTest.java`

**Interfaces:**
- Consumes（Task 3 生成的通用参数；k8s-server 只透传、不判断网格 / 灰度语义）：
  - `params.protocol`：`"tcp"` / 其他（缺省 = http）。
  - `params.meshRoute = {kind: http|tcp, canary: {service, weight, header: {name, value} | null}}`；缺省或形状不对 = 不渲染。
- Produces（chart values）：
  - `workload.expose.portName`：`protocol = tcp` → `"tcp"`，否则 `"http"`。
  - `workload.meshRoute = {enabled, kind, port, canary: {enabled, service, weight, headerName, headerValue}}`；
    canary 解析复用现有 `canary(raw, tcp)`（权重截 0-100、tcp 丢 header）；worker 或缺省 → `{enabled: false}`。
- Chart：
  - `service.yaml`：端口 `name: {{ .Values.workload.expose.portName | default "http" }}`；`targetPort: http` 不变（容器端口名不动）。
  - `route-mesh.yaml`：`{{- if and (eq .Values.workload.mode "service") .Values.workload.meshRoute.enabled }}`；
    kind / apiVersion 取法与 `route.yaml` 相同；`metadata.name: <app.name>-mesh`；
    `parentRefs: [{group: "", kind: Service, name: <app.name>, port: <meshRoute.port>}]`；
    rules 与 `route.yaml` 同形（仅 http 时 header 规则在前；然后 stable `100-weight` / canary `weight`）。

- [ ] **Step 1: 写失败测试**

```java
// AppWorkloadValuesTest（expose()/meshRoute() 是测试内取 workload.expose / workload.meshRoute 的小助手）
@Test void portNameFollowsProtocol() {
    assertThat(expose(values(Map.of("image", "x", "port", 80, "protocol", "tcp")))).containsEntry("portName", "tcp");
    assertThat(expose(values(Map.of("image", "x", "port", 80)))).containsEntry("portName", "http");
}
@Test void meshRouteParsedAndClamped() {
    Map<String, Object> mr = Map.of("kind", "http", "canary", Map.of("service", "shop-api-canary", "weight", 150,
            "header", Map.of("name", "x-canary", "value", "true")));
    Map<String, Object> v = meshRoute(values(Map.of("image", "x", "port", 80, "meshRoute", mr)));
    assertThat(v).containsEntry("enabled", true).containsEntry("kind", "http").containsEntry("port", 80);
    assertThat((Map<String, Object>) v.get("canary")).containsEntry("weight", 100).containsEntry("headerName", "x-canary");
}
@Test void meshRouteAbsentOrTcpDropsHeader() {
    assertThat(meshRoute(values(Map.of("image", "x", "port", 80)))).containsEntry("enabled", false);
    Map<String, Object> tcp = Map.of("kind", "tcp", "canary",
            Map.of("service", "s-canary", "weight", 10, "header", Map.of("name", "x", "value", "y")));
    assertThat((Map<String, Object>) meshRoute(values(Map.of("image", "x", "port", 80, "meshRoute", tcp))).get("canary"))
            .containsEntry("headerName", "");
}
```

AppWorkloadChartRenderTest（都 `assumeTrue(HelmProbe.available(), "本机没有 helm，跳过")`，用现有 `render` / `find` / `at`）：
- `meshRouteRendersServiceParentWithHeaderThenWeights`：port 8080、meshRoute http、weight 20、header `x-canary: true` →
  HTTPRoute `shop-api-mesh` 的 `parentRefs[0]` = `{group: "", kind: Service, name: shop-api, port: 8080}`；
  `rules[0]` 的 header match = `{name: x-canary, value: "true"}`、backendRefs = `[{name: shop-api-canary, port: 8080}]`；
  `rules[1].backendRefs` = `[{shop-api, 8080, weight 80}, {shop-api-canary, 8080, weight 20}]`。
- `tcpMeshRouteHasNoHeaderRule`：meshRoute tcp → TCPRoute `shop-api-mesh`，rules 只有一条加权规则。
- `noMeshRouteByDefault`：无 meshRoute → 没有名为 `shop-api-mesh` 的对象。
- `tcpProtocolNamesServicePortTcp`：protocol tcp → Service `ports[0].name = tcp`、`targetPort = http`；缺省 → `http`。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -q -pl k8s-server test -Dtest='AppWorkloadValuesTest,AppWorkloadChartRenderTest'`
Expected: 新测试 FAIL（缺 `portName` / `meshRoute`；chart 里没有 `shop-api-mesh`）。

- [ ] **Step 3: 实现** values 解析、`service.yaml`、`route-mesh.yaml`、`values.yaml` 默认块（`expose.portName: http`、`meshRoute.enabled: false` + 空 canary）、README 一段。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -q -pl k8s-server test`
Expected: PASS。若本机无 helm，chart 测试被跳过——报告里写明「chart 渲染未实测」。

- [ ] **Step 5: Commit** — `feat(k8s-server): service port name by protocol and mesh route template`

### Task 3: platform-api 渲染——`protocol`、网格路由、`external`

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/AppReleasePipeline.java`（`buildRequest` / `node` / `canaryBackend`）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/AppReleasePipelineTest.java`

**Interfaces:**
- Consumes: Task 1 的 `Spec.external()`、`serviceProtocol`、`isL7`；Task 2 的参数形状。
- Produces（节点 params，k8s-server 透传）：
  - `protocol: "tcp"`：只在 `serviceProtocol(desired) = TCP` 且有端口时放；HTTP **不放这个键**（没灰度的 HTTP 服务 params 完全不变）。stable 与 canary 节点各按自己的 desired 放。
  - stable 节点 `meshRoute = {kind: http|tcp, canary: canaryBackend(...)}`：只在 `canary != null && AppSettings.isL7(snapshot.app())` 时放。
  - 网关路由 `route.canary`：只在 `canary != null && canary.external()` 时放（`external=false` → 网关路由与没灰度时相同）。
  - `meshRoute` 的 canary 后端与网关路由同一个 `canaryBackend(app, key, canary)`（header 对 TCP 由 k8s-server 丢弃，这里不特判）。

- [ ] **Step 1: 写失败测试**（照现有 `canaryServiceRendersAnExtraNodeAndWeightedRoute` 的构造方式；`l7App()` = `Map.of("mesh", Map.of("mode","l7","waypoint","wp"))` 放进 `AppSnapshot` 第三参）

```java
@Test void l7CanaryAddsMeshRouteOnStableNode() {
    // api：port 8080，无网关暴露，灰度中（weight 20, header x-canary:true, external false），快照 app = l7App()
    // → stable 节点 params.meshRoute = {kind: "http", canary: {service: "<slug>-api-canary", weight: 20, header: {...}}}
    //   stable 节点没有 params.route；canary 节点没有 meshRoute
}
@Test void nonL7CanaryHasNoMeshRoute() {
    // 同一个灰度，网关 HTTP 暴露，快照 app = Map.of() → stable 无 meshRoute；route.canary 存在（与入口灰度一致）
}
@Test void externalFalseKeepsGatewayRoutePlain() {
    // 网关 HTTP 暴露 + l7App() + external=false → route 无 canary 键；meshRoute 存在
}
@Test void tcpServicesCarryProtocolParamHttpDoNot() {
    // 服务 a：expose gateway-tcp；服务 b：probeType tcp；服务 c：普通 HTTP
    // → a、b 的 params.protocol = "tcp"；c 的 params 不含 "protocol"
}
@Test void noCanaryNoMeshRouteEvenWithL7() {
    // l7App()，没有灰度块 → 任何节点都没有 meshRoute（Review Focus：没灰度的服务清单不变）
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -q -pl platform-api test -Dtest=AppReleasePipelineTest`
Expected: 新测试 FAIL（无 `meshRoute` / `protocol`；`externalFalse...` 仍带 canary）。

- [ ] **Step 3: 实现**。`protocol` 放在 `node(...)` 里（stable / canary 共用）；`meshRoute` 与 `route.canary` 的条件写在 `buildRequest` 的灰度分支里。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -q -pl platform-api test -Dtest='AppReleasePipelineTest,AppReleaseServiceTest,AppCanaryServiceTest'`
Expected: PASS。

- [ ] **Step 5: Commit** — `feat(app): render mesh canary route for L7 apps and honor external flag`

### Task 4: 灰度操作的前提与 `external`；概览带出 `external`

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/AppCanaryService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/AppCanaryRequest.java`（加 `Boolean external`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/AppOverviewView.java`（`Canary` 记录加 `boolean external`）及其构造处（`canaryRow`）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/AppCanaryServiceTest.java`、概览所在测试（`grep -rln "canaryRow\|ServiceRow(" platform-api/src/test`）

**Interfaces:**
- Consumes: Task 1 全部；`AppSettings.isL7(c.live.app())`（**线上快照**的应用设置，spec §5.1）。
- Produces:
  - 能否灰度：`isL7 || gatewayProtocol(stable) != null`，且无状态。否则报
    「服务 X 不能灰度：应用未开启 L7 网格（内部调用分流需要），服务也没有网关暴露」；有状态报原文案不变。
  - `validate` 新增：`serviceProtocol(canary) == serviceProtocol(stable)`，否则「灰度版本的协议必须与线上一致」；
    header 只对 `serviceProtocol(stable) = HTTP`（原来看 `gatewayProtocol`），TCP 报「服务 X 是 TCP 服务，不支持 header 直达」。
  - `external` 决定（start / update 同一函数 `resolveExternal(Boolean requested, Boolean current, boolean exposed, boolean l7)`）：
    - 无网关暴露 → `false`（忽略请求值）；
    - 有网关暴露：取 `requested`，为 null 取 `current`，再为 null 取 `true`；
    - 结果为 `false` 且非 L7 → 报「应用未开启 L7 网格时外部流量必须参与灰度（否则没有任何流量分到灰度版本）」。
  - `start` 的 header 默认开启条件改为 `serviceProtocol(stable) = HTTP`（原 `gatewayProtocol`）。
  - 概览 `Canary` 记录多 `external`（前端开关回显用）。

- [ ] **Step 1: 写失败测试**（`AppCanaryServiceTest`；`liveWithApp(services, appSettings)` 是新小助手：同 `live(...)` 但 `AppSnapshot` 第三参给设置）

```java
@Test void l7AppCanStartCanaryOnUnexposedService() {
    liveWithApp(Map.of("api", svc("1", null)), L7);
    row("api", svc("1", null));
    canary.start(start("2"), "bob", "t1");   // start("2") 里的 desired 改为跟随 stable 的 expose（见下注）
    AppCanary.Spec s = AppCanary.of(AppSnapshot.parse(inserted().getSnapshotJson()).services().get("api"));
    assertThat(s.external()).isFalse();
    assertThat(s.header()).isNotNull();       // HTTP 服务默认开 header
}
@Test void unexposedServiceWithoutL7IsRejected() {
    live(Map.of("api", svc("1", null)));
    assertThatThrownBy(() -> canary.start(start(null), "bob", "t1")).hasMessageContaining("L7");
}
@Test void externalFalseRejectedWithoutL7() {
    live(Map.of("api", svc("1", "gateway-http")));
    row("api", svc("1", "gateway-http"));
    AppCanaryRequest r = start(null); r.setExternal(false);
    assertThatThrownBy(() -> canary.start(r, "bob", "t1")).hasMessageContaining("外部流量必须参与");
}
@Test void externalFalseAllowedWithL7AndKeptOnUpdate() {
    // liveWithApp(gateway-http, L7)；start external=false → external false；
    // 再把线上设为该快照，update 只改 weight → external 仍 false
}
@Test void canaryProtocolMustMatchStable() {
    // L7，stable 普通 HTTP；desired 带 probeType tcp → 报「协议必须与线上一致」
}
@Test void tcpServiceRejectsHeader() {
    // L7，stable probeType tcp（无网关）；headerEnabled true → 报「不支持 header 直达」；不传 → header 为 null
}
```

注：现有 `start(tag)` 助手固定 `expose = gateway-http`；未暴露的用例传 `null` desired（复制 stable）或新增 `start(tag, expose)` 重载，二选一，别改动现有用例语义。
另把现有 `startRejects` 里「不是网关暴露」那一断言改成断言新文案（含 `L7`）——这是本任务唯一改动的旧断言。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -q -pl platform-api test -Dtest=AppCanaryServiceTest`
Expected: 新测试 FAIL（未暴露服务被拒为「不是网关暴露」；无 `setExternal`）。

- [ ] **Step 3: 实现**前提、`resolveExternal`、`validate` 改动、`AppCanaryRequest.external`、概览 `external`。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -q -pl platform-api test -Dtest='AppCanaryServiceTest,AppReleaseServiceTest,AppServiceConfigServiceTest'`
Expected: PASS。

- [ ] **Step 5: Commit** — `feat(app): canary allowed on L7 apps without gateway, external switch`

### Task 5: 网格锁（保存设置 + 普通发布勾选 `@app`）

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/app/AppSettings.java`（加 `l7Waypoint`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/AppServiceConfigService.java`（`updateSettings`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/AppReleaseService.java`（普通发布校验，`selected` 循环处）
- Test: `AppSettingsTest`、`AppServiceConfigServiceTest`、`AppReleaseServiceTest`

**Interfaces:**
- Produces:
  - `static String AppSettings.l7Waypoint(Map<String,Object> settings)`：L7 时返回 waypoint，否则 `null`。
    「网格变化会影响灰度」= `!Objects.equals(l7Waypoint(before), l7Waypoint(after))`（none / follow / l4 互切都是 null→null，放行）。
  - `updateSettings`：任一服务在灰度（线上快照**或**进行中执行的快照里 `AppCanary.activeKeys` 非空，口径同 `requireNotInCanary`）
    且上面的判断为真 → 报「有服务在灰度（X、Y），先全量或回退再改网格」。比较对象：保存前的期望设置 vs 新设置。
  - 普通发布：勾选了 `@app`、`AppCanary.activeKeys(live)` 非空、且 `l7Waypoint(live.app()) ≠ l7Waypoint(settings)` → errors 加同一文案。

- [ ] **Step 1: 写失败测试**

```java
// AppSettingsTest
@Test void l7Waypoint() {
    assertThat(AppSettings.l7Waypoint(Map.of("mesh", Map.of("mode", "l7", "waypoint", "wp")))).isEqualTo("wp");
    assertThat(AppSettings.l7Waypoint(Map.of("mesh", Map.of("mode", "l4")))).isNull();
    assertThat(AppSettings.l7Waypoint(null)).isNull();
}
```
- `AppServiceConfigServiceTest`：
  - `settingsCannotLeaveL7WhileCanary`：线上快照 `api` 带灰度块，期望设置 L7(wp) → 保存 L4，报错含「先全量或回退」；
  - `settingsCannotEnterL7OrSwitchWaypointWhileCanary`：none → L7(wp) 报错；L7(wp) → L7(wp2) 报错；
  - `settingsMayToggleNonL7ModesWhileCanary`：none → L4 成功；仅改 `tracing` 成功；
  - `settingsLockAlsoSeesActiveExecution`：线上无灰度、进行中执行的快照有灰度 → none → L7 报错。
- `AppReleaseServiceTest`：`releasingAppSettingsThatChangeL7IsRejectedDuringCanary`（勾选 `@app`，线上 L7(wp) + 灰度，期望 L4 → 报错）；
  `releasingAppSettingsTracingOnlyIsAllowedDuringCanary`（只改 tracing → 不含该错误）。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -q -pl platform-api test -Dtest='AppSettingsTest,AppServiceConfigServiceTest,AppReleaseServiceTest'`
Expected: 新测试 FAIL（`l7Waypoint` 不存在 / 未拦截）。

- [ ] **Step 3: 实现**。`updateSettings` 里「是否有灰度」抽成私有方法 `activeCanaryKeys(app)`，与 `requireNotInCanary` 共用快照读取（同一个类内，不跨类抽基类）。

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: PASS。

- [ ] **Step 5: Commit** — `feat(app): lock mesh L7/waypoint changes while a canary is active`

### Task 6: 指标——按来源分组、按 `destination_workload` 分版本

**Files:**
- Rename: `platform-api/src/main/java/com/coding/platformapi/metrics/labels/ServiceNameLabels.java` → `DestinationWorkloadLabels.java`（字段 `@JsonProperty("destination_workload") String workload`；原类只被灰度使用，直接替换）
- Modify: `platform-api/src/main/java/com/coding/platformapi/metrics/MetricQuery.java`（六条 `CANARY_*`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/metrics/MetricsService.java`（`canaryCompare`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/AppCanaryMetricsView.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/AppCanaryService.java`（`metrics`）
- Test: `platform-api/src/test/java/com/coding/platformapi/metrics/MetricsServiceCanaryTest.java`、`AppCanaryServiceTest`

**Interfaces:**
- 查询模板：过滤改为 `cluster_name="%1$s",destination_workload_namespace="%2$s",destination_workload=~"%3$s",reporter="%5$s"`，
  `sum by (destination_workload)`（延迟 `sum by (le, destination_workload)`）；`%4$s` 仍是时间窗。
- `Map<String, Map<String, Double>> canaryCompare(String clusterId, String namespace, String stableWl, String canaryWl,
  String window, boolean tcp, String reporter)`——多一个 `reporter` 参数，其余返回形状不变。
- `public static final String MetricsService.REPORTER_WAYPOINT = "waypoint"`、`REPORTER_GATEWAY = "source"`。
- `AppCanaryMetricsView(String window, String protocol, Group internal, Group external)`，`record Group(Map<String,Double> stable, Map<String,Double> canary)`；
  `internal` 在非 L7 时为 `null`；`external` 在无网关暴露时为 `null`。
- `AppCanaryService.metrics`：协议用 `serviceProtocol`；工作负载名 = `AppObjectNames.objectName(slug, key)` / `objectName(slug, nodeKey(key))`（与 Deployment 名一致）。

- [ ] **Step 1: 写失败测试**
  - `MetricsServiceCanaryTest`：把现有 `row(name, value)` 改成写 `destination_workload`；新增
    `queriesFilterByReporterAndWorkload`：`canaryCompare(..., "waypoint")` 发出的每条 PromQL 都含 `reporter="waypoint"` 与 `destination_workload=~"shop-api|shop-api-canary"`，且不含 `destination_service_name`。
  - `AppCanaryServiceTest`：
    - `metricsReturnsInternalOnlyForUnexposedL7Service`：`internal` 非 null、`external` 为 null，且 `metrics.canaryCompare` 只以 `"waypoint"` 被调一次（Mockito verify）；
    - `metricsReturnsExternalOnlyWithoutL7`：网关暴露、非 L7 → `internal` null、只以 `"source"` 调用；
    - 现有 `metricsRejectsServiceNotInCanaryAndNormalizesWindow` 按新签名调整 stub（`any()` 多一个参数）。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -q -pl platform-api test -Dtest='MetricsServiceCanaryTest,AppCanaryServiceTest'`
Expected: 编译失败 / 断言失败（无 reporter 参数、视图无分组）。

- [ ] **Step 3: 实现**。

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: PASS。

- [ ] **Step 5: Commit** — `feat(app): canary metrics split by reporter and destination workload`

### Task 7: 前端

**Files:**
- Modify: `platform-web/src/types.ts`（`AppServiceCanary.external`、`AppCanaryRequest.external`、`AppCanaryMetrics` 改分组）
- Modify: `platform-web/src/components/app-release/releaseUtil.ts`（加 `serviceProtocol(d)`、`isL7(settings)`，口径同后端）
- Modify: `platform-web/src/views/ApplicationDetailView.vue`（`canaryStartBlocked`、给抽屉传 `l7`）
- Modify: `platform-web/src/components/app-release/AppCanaryDrawer.vue`（透传 `l7`；协议改用 `serviceProtocol`）
- Modify: `platform-web/src/components/app-release/AppCanaryStartDialog.vue`
- Modify: `platform-web/src/components/app-release/AppCanaryPanel.vue`
- Modify: `platform-web/src/components/app-release/AppSettingsPanel.vue` 或 `AppMeshForm.vue`（灰度中锁网格）

**Interfaces:**
- Consumes: 后端 `overview.settings.live`（线上应用设置）、`row.canary.external`、`row.ingress`（有网关暴露）、新指标结构
  `{window, protocol, internal: {stable, canary} | null, external: {stable, canary} | null}`。
- 行为（文案取自 spec §8）：
  - 服务列表「开启灰度」：`!l7 && !row.ingress` 时置灰，tooltip「应用未开启 L7 网格（在应用设置里开启并选择 waypoint），且服务没有网关暴露」；其余原有置灰原因保留（去掉「只有网关暴露的服务能灰度」）。
  - 开始对话框：`l7 && exposed` 时显示「外部流量参与灰度」开关（默认开）并随请求发 `external`；`!l7` 时顶部 `el-alert`「应用未开启 L7 网格，内部调用不分流，只分网关进来的流量」；
    header 说明加「内部调用需调用方把这个请求头往下传」（仅 l7 时）。`protocol` 来自 `serviceProtocol(stable)`（不再只看 expose），header 区仅 HTTP。
  - 管理抽屉（`AppCanaryPanel`）：
    - 顶部提示按范围三选一：`l7 && exposed && external` →「内部调用 + 网关进来」；`l7 && !(exposed && external)` →「仅内部调用」；`!l7` →「仅网关进来（未开启 L7 网格，内部调用不分流）」。
    - `l7 && exposed` 时流量区多开关 `external`，纳入 `routeDirty` / 「还原」/ 「应用」（`appCanaryApi.update` 带 `external`）。
    - 指标区：`internal`、`external` 各一张表（标题「内部调用」「网关进来」），为 null 的不显示；标橙规则每组各算。
  - 应用设置：有服务在灰度（`overview.services.some(s => s.canary)`）时，当前线上为 L7 → 网格整块只读；否则 L7 选项禁用；提示「有服务在灰度中，先全量或回退」。实现时看 `AppSettingsPanel` 现有 props，缺的数据由 `ApplicationDetailView` 传入。

- [ ] **Step 1: 改类型与工具函数**，跑 `npx vue-tsc --noEmit -p tsconfig.app.json`（`platform-web/`）。
  Expected: FAIL，报错集中在使用旧 `AppCanaryMetrics.stable/canary` 的 `AppCanaryPanel.vue`（这是本任务的「红」：类型先行，编译器列出所有要改的点）。

- [ ] **Step 2: 按上面的行为改各组件**。

- [ ] **Step 3: 跑类型检查确认通过**

Run: `cd platform-web && npx vue-tsc --noEmit -p tsconfig.app.json`
Expected: rc=0。

- [ ] **Step 4: Commit** — `feat(app-web): internal canary scope, external switch, grouped metrics, mesh lock`

### Task 8: 文档与记忆

**Files:**
- Modify: `docs/development/application-release.md`（§1.8 加「内部灰度」小节：前提三分法、网格路由、`external`、网格锁、指标分组；§3 部署段：k8s-server 先部署、无 SQL；§5 清单：spec §9 七条；§6 限制：TCPRoute in waypoint 待验、header 透传）
- Modify: `docs/superpowers/specs/2026-11-09-app-internal-canary-design.md`（状态改「已实现（未部署）」+ 实现出入列表）
- Modify: `C:\Users\wsail\.claude\projects\D--coding-k8s-cloud-platform\memory\app-canary-release.md`（补内部灰度要点与待核对项；MEMORY.md 索引行同步）

- [ ] **Step 1: 写文档**；`git diff --stat` 只含上述文件。
- [ ] **Step 2: 全量回归**

Run: `mvn -o -pl '!platform-gateway' test` 与 `cd platform-web && npx vue-tsc --noEmit -p tsconfig.app.json`
Expected: BUILD SUCCESS；rc=0。

- [ ] **Step 3: Commit** — `docs(app): internal canary usage and deployment notes`
