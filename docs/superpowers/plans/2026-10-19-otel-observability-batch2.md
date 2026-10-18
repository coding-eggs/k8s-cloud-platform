# OpenTelemetry 可观测接入 · 批次二实施计划（网格 tracing + 文件日志）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在批次一（traces/指标/应用日志 + Tempo + collector）之上补两件事：① 网格侧 tracing（ingress gateway + waypoint，ztunnel 不动）；② Nginx 等「写文件」应用的 per-Pod sidecar 日志采集（用户提供日志目录）。

**Architecture:** 网格侧只新增 Istio 的 `Telemetry` CR（`extensionProviders` 落在 istiod values）；文件日志复用 OTel operator 的 sidecar 注入（`sidecar.opentelemetry.io/inject`），由工作负载开关加 emptyDir 卷 + sidecar 注解，collector 只读该卷并转给已有 gateway。

**Tech Stack:** 同批次一；新增 Istio `Telemetry` CRD（`telemetry.istio.io/v1`），无新 chart。

**Spec:** `docs/superpowers/specs/2026-10-18-otel-observability-design.md`（§4.4、§5）

## Global Constraints

- ztunnel **不做任何 tracing 配置**（L4 不产生 span）。
- 网格 tracing 只对 **ingress gateway（`istio-system/external-gateway`）和 waypoint Gateway** 生效；
  用 `Telemetry.spec.targetRefs` 指向 Gateway，不改 Gateway 本身。
- 文件日志只做 **用户显式开启 + 提供日志目录 + 勾选确认写文件**；不采 stdout、不做全节点 DaemonSet。
- sidecar 的卷名固定 `platform-otel-logs`；应用容器挂到用户提供的目录，sidecar 挂到 `/var/log/app`。
- 新增 `/mesh/waypoints/tracing` 端点必须有权限行；`PermissionCrossCheckRunner` 缺行会在启动时 brick。
  **部署顺序：先重放 `V2026_10_19_1`，再起新构建。**
- 不修改用户工作区里既有的三个改动。

## Review Focus

- **Telemetry targetRefs**：必须指向 `kind: Gateway`（不是 Deployment）；ingress 与每个 waypoint 各一条。
- **采样率来源**：waypoint/ingress 的 `randomSamplingPercentage` 取 OTel 组件上次成功快照的 `samplingPercentage`，缺省 10。
- **权限行与绑定**：两个新端点都要有 `platform_permission` 行 + 显式角色绑定（admin / tenant_admin，GET 还给 tenant_member）。
- **sidecar 卷/挂载的幂等与清理**：重复打开不重复加；切回应用日志/关闭时移除平台卷、挂载和注解，不动用户同名卷（同名但不是 emptyDir → 拒绝）。
- **文件日志与应用日志互斥**：`logMode=file` 时不再写 Instrumentation 注解（除非 tracing=true）；`logMode=app` 时移除 sidecar 注解/卷。

---

### Task 1: OTel sidecar collector CR

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/components/OtelObjects.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/components/OtelObjectsTest.java`

**Interfaces:**
- Produces: `OtelObjects.build(boolean, int)` 返回 **5** 个对象，最后一个是 `platform-otel-sidecar`（`mode: sidecar`）。
- 卷名固定 `platform-otel-logs`；sidecar 挂载 `/var/log/app`；`filelog include: [/var/log/app/*.log]`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void itAlsoBuildsTheSidecarCollector() {
    var objects = OtelObjects.build(true, 1);
    assertThat(objects).hasSize(5);
    assertThat(objects.get(4).get("metadata").toString())
            .contains("platform-otel-sidecar");
    String sidecar = objects.get(4).toString();
    assertThat(sidecar).contains("mode=sidecar")
            .contains("filelog").contains("/var/log/app/*.log")
            .contains("platform-otel-logs")
            .contains("K8S_POD_UID")
            .contains("platform-otel-gateway-collector.monitor.svc:4317");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl platform-api -am test -Dtest=OtelObjectsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 失败（只有 4 个对象）。

- [ ] **Step 3: 实现 `sidecar()`**

```java
private static Map<String, Object> sidecar() {
    Map<String, Object> config = Map.of(
        "receivers", Map.of("filelog", Map.of(
            "include", List.of("/var/log/app/*.log"),
            "start_at", "end",
            "include_file_path", true)),
        "processors", Map.of(
            "resource", Map.of("attributes", List.of(
                Map.of("key","k8s.pod.name","value","${env:K8S_POD_NAME}","action","upsert"),
                Map.of("key","k8s.namespace.name","value","${env:K8S_NAMESPACE}","action","upsert"),
                Map.of("key","k8s.pod.uid","value","${env:K8S_POD_UID}","action","upsert"))),
            "k8sattributes", Map.of("auth_type","serviceAccount"),
            "batch", Map.of("timeout","5s")),
        "exporters", Map.of("otlp", Map.of(
            "endpoint","platform-otel-gateway-collector.monitor.svc:4317",
            "tls", Map.of("insecure", true))),
        "service", Map.of("pipelines", Map.of("logs", Map.of(
            "receivers", List.of("filelog"),
            "processors", List.of("resource","k8sattributes","batch"),
            "exporters", List.of("otlp")))));
    return Map.of(
        "apiVersion","opentelemetry.io/v1beta1",
        "kind","OpenTelemetryCollector",
        "metadata", Map.of("name","platform-otel-sidecar","namespace",NAMESPACE),
        "spec", Map.of(
            "mode","sidecar",
            "image", CONTRIB_IMAGE,
            "env", List.of(
                Map.of("name","K8S_POD_NAME","valueFrom", Map.of("fieldRef", Map.of("fieldPath","metadata.name"))),
                Map.of("name","K8S_NAMESPACE","valueFrom", Map.of("fieldRef", Map.of("fieldPath","metadata.namespace"))),
                Map.of("name","K8S_POD_UID","valueFrom", Map.of("fieldRef", Map.of("fieldPath","metadata.uid")))),
            "volumeMounts", List.of(Map.of(
                "name","platform-otel-logs","mountPath","/var/log/app","readOnly", true)),
            "config", config));
}
```

`build(...)` 末尾追加 `sidecar()`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl platform-api -am test -Dtest=OtelObjectsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/components/OtelObjects.java \
        platform-api/src/test/java/com/coding/platformapi/components/OtelObjectsTest.java
git commit -m "feat(otel): sidecar collector CR（文件日志）"
```

---

### Task 2: istiod 声明 OTel provider

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/components/ComponentRecipeCatalog.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/components/ComponentRecipeCatalogTest.java`

**Interfaces:**
- Produces: istiod release values 含 `meshConfig.enableTracing=true` 与 `extensionProviders[platform-otel]`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void istioDeclaresTheOtelTracingProvider() {
    var istiod = catalog.recipeOf("istio", "BUSINESS").releases().stream()
            .filter(r -> r.releaseName().equals("istiod")).findFirst().orElseThrow();
    Map<String, Object> mesh = (Map<String, Object>) istiod.contractValues().get("meshConfig");
    assertThat(mesh).containsEntry("enableTracing", true);
    assertThat(mesh.get("extensionProviders").toString())
            .contains("platform-otel")
            .contains("platform-otel-gateway-collector.monitor.svc.cluster.local")
            .contains("4317");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl platform-api -am test -Dtest=ComponentRecipeCatalogTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 失败（`meshConfig` 为 null）。

- [ ] **Step 3: 实现**

在 `ISTIO_RELEASES` 的 istiod `contractValues` 里追加：

```java
"meshConfig", Map.of(
        "enableTracing", true,
        "extensionProviders", List.of(Map.of(
                "name", "platform-otel",
                "opentelemetry", Map.of(
                        "service", "platform-otel-gateway-collector.monitor.svc.cluster.local",
                        "port", 4317))))
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl platform-api -am test -Dtest=ComponentRecipeCatalogTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/components/ComponentRecipeCatalog.java \
        platform-api/src/test/java/com/coding/platformapi/components/ComponentRecipeCatalogTest.java
git commit -m "feat(istio): 声明 OTel tracing provider（ingress/waypoint 的 Telemetry 用）"
```

---

### Task 3: k8s-server Telemetry 对象与端点

**Files:**
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/OtelTelemetryRequestDTO.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/components/OtelTelemetryObjects.java`
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/controllers/cluster/ComponentInstallController.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/k8s/K8sComponentClient.java`
- Tests: `k8s-server/src/test/java/com/coding/k8sserver/components/OtelTelemetryObjectsTest.java`

**Interfaces:**
- Produces: `POST /components/telemetry`（enabled=true → SSA；false → 删除；CRD/目标 Gateway 缺失 → 返回 false）。
- Produces: `GET /components/telemetry?clusterId=&namespace=&name=` → `{enabled, samplingPercentage}`（用 `OtelTelemetryStateDTO`）。
- Produces: `K8sComponentClient.applyOtelTelemetry(...)`、`otelTelemetryState(...)`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void itTargetsTheGatewayAndTheOtelProvider() {
    var telemetry = OtelTelemetryObjects.build("team-a", "platform-tracing-wp",
            "waypoint", 10);
    assertThat(telemetry.getApiVersion()).isEqualTo("telemetry.istio.io/v1");
    assertThat(telemetry.getKind()).isEqualTo("Telemetry");
    String spec = telemetry.getAdditionalProperties().get("spec").toString();
    assertThat(spec).contains("gateway.networking.k8s.io").contains("Gateway")
            .contains("waypoint").contains("platform-otel").contains("10");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl k8s-server -am test -Dtest=OtelTelemetryObjectsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现**

`OtelTelemetryObjects`：

```java
public static final CustomResourceDefinitionContext CRD = ... // telemetry.istio.io/v1, Telemetry, telemetries, Namespaced
public static GenericKubernetesResource build(String namespace, String name,
                                              String gatewayName, int samplingPercentage) {
    // spec.targetRefs = [{group: gateway.networking.k8s.io, kind: Gateway, name: gatewayName}]
    // spec.tracing = [{providers: [{name: platform-otel}], randomSamplingPercentage: samplingPercentage}]
}
```

Controller：
- `POST /components/telemetry`：校验参数；CRD 不存在 → false；enabled=false → `client.genericKubernetesResources(CRD).inNamespace(ns).withName(name).delete()`（404 忽略）→ true；enabled=true → 检查 Gateway 存在（`GATEWAY_CRD`）→ 不存在 false；否则 SSA → true。
- `GET /components/telemetry`：读对象；返回 `{enabled, samplingPercentage}`（无对象 → `{false, null}`）。

`K8sComponentClient` 两个方法，返回 `Boolean` / `OtelTelemetryStateDTO`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl k8s-server -am test -Dtest=OtelTelemetryObjectsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add platform-common/src/main/java/com/coding/common/models/k8s/dto/OtelTelemetryRequestDTO.java \
        k8s-server/src/main/java/com/coding/k8sserver/components/OtelTelemetryObjects.java \
        k8s-server/src/main/java/com/coding/k8sserver/controllers/cluster/ComponentInstallController.java \
        platform-api/src/main/java/com/coding/platformapi/k8s/K8sComponentClient.java \
        k8s-server/src/test/java/com/coding/k8sserver/components/OtelTelemetryObjectsTest.java
git commit -m "feat(mesh): Telemetry CR 下发/查询（waypoint/ingress）"
```

---

### Task 4: 平台侧 waypoint tracing 端点 + 权限迁移 + ingress 后置

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/models/WaypointTracingRequest.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/controllers/MeshController.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/MeshService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java`
- Create: `platform-data/src/main/resources/db/migration/V2026_10_19_1__mesh_waypoint_tracing.sql`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/MeshServiceTracingTest.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/components/ComponentLifecycleMigrationTest.java`（补新 SQL 断言）

**Interfaces:**
- Produces: `POST /mesh/waypoints/tracing`（`tenant:gateway:update`）、`GET /mesh/waypoints/tracing`（`tenant:gateway:get`）。
- Produces: OTel/istio 安装成功后自动下发 ingress 的 `Telemetry`（缺失 Gateway/CRD 时跳过）。

- [ ] **Step 1: 写失败测试**

```java
@Test
void settingWaypointTracingUsesTheOtelSamplingSnapshot() {
    when(installService.otelSamplingPercentage("c1")).thenReturn(25);
    svc.setWaypointTracing(req(true));
    verify(client).applyOtelTelemetry("c1", "team-a", "platform-tracing-wp",
            "waypoint", 25, true);
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl platform-api -am test -Dtest=MeshServiceTracingTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败。

- [ ] **Step 3: 实现**

- `ComponentInstallService` 增加 `public int otelSamplingPercentage(String clusterId)`：读 `latest(clusterId, "otel-operator")` 的 `__platform.otel.samplingPercentage`，缺省 10。
- `MeshService` 注入 `K8sComponentClient`、`ComponentInstallService`；两个方法：`waypointTracing(...)` 读状态、`setWaypointTracing(...)` 下发（名称 `platform-tracing-<gateway>`，截断 63）。
- `MeshController` 两个端点，加 `@Operation`；权限行由迁移补齐。
- `ComponentInstallService.run()` 成功后（componentCode 为 `otel-operator` 或 `istio`）调 `applyOtelTelemetry("istio-system", "platform-tracing-ingress", "external-gateway", sampling, true)`，异常/缺 CRD 只 WARN。
- 迁移 SQL：两行 `platform_permission` + 三个角色绑定（admin/tenant_admin/set+get，tenant_member/get）。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl platform-api -am test -Dtest=MeshServiceTracingTest,ComponentLifecycleMigrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/models/WaypointTracingRequest.java \
        platform-api/src/main/java/com/coding/platformapi/controllers/MeshController.java \
        platform-api/src/main/java/com/coding/platformapi/services/MeshService.java \
        platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java \
        platform-data/src/main/resources/db/migration/V2026_10_19_1__mesh_waypoint_tracing.sql \
        platform-api/src/test/java/com/coding/platformapi/services/MeshServiceTracingTest.java \
        platform-api/src/test/java/com/coding/platformapi/components/ComponentLifecycleMigrationTest.java
git commit -m "feat(mesh): waypoint/ingress 链路追踪开关（Telemetry + 权限行）"
```

---

### Task 5: 前端 waypoint tracing 开关

**Files:**
- Modify: `platform-web/src/api/index.ts`
- Modify: `platform-web/src/views/resource/GatewayEditorView.vue`

**Interfaces:**
- Produces: `meshApi.waypointTracing(...)`、`meshApi.setWaypointTracing(...)`；waypoint 编辑页的「链路追踪」开关。

- [ ] **Step 1: API**

```ts
waypointTracing: (ctx: { tenantId: string; clusterId: string; namespace: string; gatewayName: string }) =>
  http.get<never, boolean>('/mesh/waypoints/tracing', { params: ctx }),
setWaypointTracing: (ctx: { tenantId: string; clusterId: string; namespace: string; gatewayName: string; enabled: boolean }) =>
  http.post<never, boolean>('/mesh/waypoints/tracing', ctx),
```

- [ ] **Step 2: 编辑器**

- `waypointMode` 时显示 `el-switch`「链路追踪」；
- 编辑态加载时 `meshApi.waypointTracing` 回填；
- `submit()` 在 Gateway 创建/更新成功后调用 `setWaypointTracing`（失败只提示，不回滚 Gateway）。

- [ ] **Step 3: 类型检查**

Run: `cd platform-web && npm run type-check`
Expected: 无错误。

- [ ] **Step 4: Commit**

```bash
git add platform-web/src/api/index.ts platform-web/src/views/resource/GatewayEditorView.vue
git commit -m "feat(web): waypoint 链路追踪开关"
```

---

### Task 6: 工作负载文件日志（sidecar）后端

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/WorkloadObservabilityRequest.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/WorkloadService.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/WorkloadServiceObservabilityTest.java`

**Interfaces:**
- Produces: `logMode`（`app` / `file`）、`logDirectory`、`confirmFileLogs`。
- 文件日志：注解 `sidecar.opentelemetry.io/inject: monitor/platform-otel-sidecar` + emptyDir 卷 `platform-otel-logs` + 应用容器挂载。

- [ ] **Step 1: 写失败测试**

```java
@Test
void fileLogsAddTheSidecarAnnotationAndVolume() {
    WorkloadDTO updated = apply(workload(), fileLogReq("/var/log/nginx", true));
    assertThat(updated.getPodTemplate().getAnnotations())
            .containsEntry("sidecar.opentelemetry.io/inject", "monitor/platform-otel-sidecar");
    assertThat(updated.getPodTemplate().getSpec().getVolumes())
            .anySatisfy(v -> assertThat(v.getName()).isEqualTo("platform-otel-logs"));
    assertThat(updated.getPodTemplate().getSpec().getContainers().get(0).getVolumeMounts())
            .anySatisfy(m -> assertThat(m.getMountPath()).isEqualTo("/var/log/nginx"));
}

@Test
void fileLogsRequireConfirmationAndADirectory() {
    assertThatThrownBy(() -> svc.observability(fileLogReq("/var/log/nginx", false)))
            .hasMessageContaining("确认");
    assertThatThrownBy(() -> svc.observability(fileLogReq("", true)))
            .hasMessageContaining("日志目录");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl platform-api -am test -Dtest=WorkloadServiceObservabilityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（字段不存在）。

- [ ] **Step 3: 实现**

- 请求字段：`logMode`、`logDirectory`、`confirmFileLogs`。
- `observability()`：
  - `fileLogs = req.isLogs() && "file".equalsIgnoreCase(req.getLogMode())`；
  - 文件日志：校验确认/目录；更新注解；`ensureVolume` + `ensureMount`；
  - 非文件日志：移除 sidecar 注解、平台卷、平台挂载；
  - `agentEnabled = req.isTracing() || (req.isLogs() && !fileLogs)` 才写 Instrumentation 注解。
- 同名平台卷存在但类型不是 emptyDir → 拒绝；不碰用户卷。
- 测试补齐：切回 app 模式清理、关闭清理。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl platform-api -am test -Dtest=WorkloadServiceObservabilityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/models/WorkloadObservabilityRequest.java \
        platform-api/src/main/java/com/coding/platformapi/services/WorkloadService.java \
        platform-api/src/test/java/com/coding/platformapi/services/WorkloadServiceObservabilityTest.java
git commit -m "feat(workload): 文件日志 sidecar 接入（用户提供日志目录）"
```

---

### Task 7: 前端工作负载弹窗支持文件日志

**Files:**
- Modify: `platform-web/src/components/workload/WorkloadObservabilityDialog.vue`
- Modify: `platform-web/src/views/resource/WorkloadDetailView.vue`
- Modify: `platform-web/src/api/index.ts`

**Interfaces:**
- UI：日志采集方式（应用日志 / 文件日志 / 关闭）；文件日志时显示「日志目录」+「确认写文件」。
- 打开时：有 sidecar 注解 → 文件模式，目录从 `platform-otel-logs` 的挂载反推。

- [ ] **Step 1: props / 类型**

对话框增加 `fileLogDirectory?: string`（父组件从 podTemplate 计算）；提交字段 `logMode`、`logDirectory`、`confirmFileLogs`。

- [ ] **Step 2: 表单**

- `logMode` 选择：`app`（Java/Python 日志框架）/ `file`（Nginx 等写文件）/ 关闭（两个开关都关）；
- 文件模式：`el-input` 日志目录（默认 `/var/log/nginx`）+ `el-checkbox` 确认；
- 文件模式时隐藏语言/应用日志说明（tracing 仍可选语言）。

- [ ] **Step 3: 类型检查 + 构建**

Run: `cd platform-web && npm run type-check && npm run build-only`
Expected: 无错误。

- [ ] **Step 4: Commit**

```bash
git add platform-web/src/components/workload/WorkloadObservabilityDialog.vue \
        platform-web/src/views/resource/WorkloadDetailView.vue \
        platform-web/src/api/index.ts
git commit -m "feat(web): 工作负载文件日志接入（目录 + 确认）"
```

---

### Task 8: 全量验证与收尾

**Files:**
- Modify: `docs/superpowers/specs/2026-10-18-otel-observability-design.md`（实现偏差回写）
- No production code unless a test fails.

- [ ] **Step 1: 后端全量**

Run: `mvn test`
Expected: BUILD SUCCESS。

- [ ] **Step 2: 前端**

Run: `cd platform-web && npm run type-check && npm run build-only`
Expected: 无错误。

- [ ] **Step 3: 迁移检查**

确认 `V2026_10_19_1` 含两条 API 权限行与三个角色绑定；`ComponentLifecycleMigrationTest` 覆盖。

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-10-18-otel-observability-design.md
git commit -m "docs(otel): 批次二实现备注"
```
