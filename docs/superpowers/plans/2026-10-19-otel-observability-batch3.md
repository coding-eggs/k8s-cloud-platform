# OpenTelemetry 批次三实施计划（多集群标签 / 保留期 / TLS / 自研 trace 查看）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在批次一/二之上补齐多集群消费侧：所有信号统一打 `cluster.name`、Tempo 保留期进表单、Tempo exporter 加 TLS 模式，并提供平台自研的 trace 查询/瀑布图页面（数据源 = 管理集群的 Tempo HTTP API）。

**Architecture:** 业务集群仍只填 Tempo 地址（可达性由管理员保证）；平台-api 直接查询管理集群的 Tempo `:3200`（`platform.tempo.query-url`），后端把 Tempo 的 OTLP JSON 归一化成 DTO，前端用列表 + 瀑布图展示。权限按平台管理面（admin）收口。

**Tech Stack:** 同前；新增 Spring `RestClient` 调 Tempo HTTP API；前端新增一个页面（无新依赖）。

**Spec:** `docs/superpowers/specs/2026-10-18-otel-observability-design.md`

## Global Constraints

- 不实现 Tempo 的对外暴露/TLS 终止（管理员负责）；平台只提供 collector 侧的 **TLS 模式**选择。
- trace 查看页 **仅平台管理员**（page + API 权限码都只绑 admin）；租户按命名空间过滤留作后续。
- 新增 `/traces/**` 端点必须有权限行（`PermissionCrossCheckRunner` 启动检查）；**先重放迁移再起构建**。
- Tempo 查询走管理集群内部地址 `http://platform-tempo.monitor.svc:3200`（可配置）。
- 不修改用户工作区里既有的三个改动。

## Review Focus

- **TLS 三态**：`plaintext`（`insecure: true`）/ `tls`（校验证书）/ `tls-skip-verify`（自签）——不要混用 `insecure` 与 `insecure_skip_verify`。
- **保留期值**：Tempo 的 `block_retention` 是 Go duration（`168h`），不是 `7d`；表单只给合法值。
- **cluster.name upsert**：gateway 的 `resource` processor 要给 traces/metrics/logs 三条管道都加。
- **Tempo 响应解析**：OTLP JSON 的 resource attributes 是键值数组；service.name/cluster.name 要从 resource 层取，不是 span 层。
- **页面权限**：trace 页仅 admin；API 行与 Page 行都要显式绑定 admin。

---

### Task 1: gateway 统一打 `cluster.name`

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/components/OtelObjects.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/components/OtelObjectsTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void theGatewayStampsClusterNameOnAllSignals() {
    String gateway = OtelObjects.build(true, 1, "plaintext", "168h").get(3).toString();
    assertThat(gateway).contains("resource=").contains("cluster.name").contains("${cluster.name}");
    // resource 处理器出现在三条管道
    assertThat(gateway).contains("processors=[memory_limiter, resource, k8sattributes, batch]");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl platform-api -am test -Dtest=OtelObjectsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 失败（`build` 参数/processor 不存在）。

- [ ] **Step 3: 实现**

`gateway` 的 `config.processors` 加：

```java
config.put("processors", Map.of(
        "memory_limiter", ...,
        "resource", Map.of("attributes", List.of(Map.of(
                "key", "cluster.name", "value", "${cluster.name}", "action", "upsert"))),
        "k8sattributes", ...,
        "batch", ...));
```

三条 pipeline 的 processors 改为 `[memory_limiter, resource, k8sattributes, batch]`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl platform-api -am test -Dtest=OtelObjectsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/components/OtelObjects.java \
        platform-api/src/test/java/com/coding/platformapi/components/OtelObjectsTest.java
git commit -m "feat(otel): gateway 统一 upsert cluster.name"
```

---

### Task 2: Tempo 保留期进表单

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/OtelInstallRequest.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ClusterComponentService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/ComponentStatusDTO.java`
- Modify: `platform-web/src/types.ts`
- Modify: `platform-web/src/components/cluster/ClusterComponentsPanel.vue`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/ComponentInstallServiceTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void tempoRetentionGoesIntoTheTempoReleaseValues() {
    // MANAGEMENT，带 tempoStorage；捕获 applyComponent 的 DTO
    // 断言 tempo release 的 values.tempo.retention == "336h"
}
```

- [ ] **Step 2: 实现**

- `OtelInstallRequest` 加 `String tempoRetention`（默认 `168h`）。
- `resolveOtel`：`firstNonBlank(requested, prev, "168h")`；校验 `^\d+[hm]$`。
- `resolveValues`：对 `chartCode=tempo` 的 release，复制 values 并把 `tempo.retention` 注入。
- 快照 `__platform.otel.tempoRetention`。
- 状态 DTO 的 `otelInstallCurrent` 自然带出。
- 前端表单：`el-select`（24h / 168h / 336h），预填 `otelInstallCurrent?.tempoRetention ?? '168h'`。

- [ ] **Step 3: 运行确认通过**

Run: `mvn -pl platform-api -am test -Dtest=ComponentInstallServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/models/OtelInstallRequest.java \
        platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java \
        platform-api/src/main/java/com/coding/platformapi/services/ClusterComponentService.java \
        platform-api/src/test/java/com/coding/platformapi/services/ComponentInstallServiceTest.java \
        platform-web/src/types.ts platform-web/src/components/cluster/ClusterComponentsPanel.vue
git commit -m "feat(otel): Tempo 保留期进安装表单（默认 168h）"
```

---

### Task 3: Tempo exporter TLS 模式

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/OtelInstallRequest.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/components/OtelObjects.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java`
- Modify: `platform-web/src/types.ts`
- Modify: `platform-web/src/components/cluster/ClusterComponentsPanel.vue`
- Test: `platform-api/src/test/java/com/coding/platformapi/components/OtelObjectsTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void tempoTlsModeControlsTheExporterTlsBlock() {
    assertThat(OtelObjects.build(true, 1, "plaintext", "168h").get(3).toString())
            .contains("insecure=true");
    assertThat(OtelObjects.build(true, 1, "tls", "168h").get(3).toString())
            .doesNotContain("insecure=true");
    assertThat(OtelObjects.build(true, 1, "tls-skip-verify", "168h").get(3).toString())
            .contains("insecure_skip_verify=true");
}
```

- [ ] **Step 2: 实现**

- `OtelInstallRequest` 加 `String tempoTlsMode`（`plaintext` / `tls` / `tls-skip-verify`，默认 `plaintext`）。
- `OtelObjects.build(logsEnabled, replicas, tlsMode, retention)`；`gateway` 的 `otlp/tempo`：
  - plaintext → `tls: {insecure: true}`
  - tls → 不含 `tls` 键
  - tls-skip-verify → `tls: {insecure_skip_verify: true}`
- `resolveOtel` 校验三值；快照。
- 前端「Tempo TLS」下拉：明文 gRPC（默认）/ TLS / TLS（跳过证书校验）。

- [ ] **Step 3: 运行确认通过**

Run: `mvn -pl platform-api -am test -Dtest=OtelObjectsTest,ComponentInstallServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/models/OtelInstallRequest.java \
        platform-api/src/main/java/com/coding/platformapi/components/OtelObjects.java \
        platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java \
        platform-api/src/test/java/com/coding/platformapi/components/OtelObjectsTest.java \
        platform-web/src/types.ts platform-web/src/components/cluster/ClusterComponentsPanel.vue
git commit -m "feat(otel): Tempo exporter 支持明文/TLS/跳过校验三种模式"
```

---

### Task 4: Tempo 查询客户端 + trace 端点

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/trace/TempoTraceClient.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/trace/TraceSearchRequest.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/trace/TraceSummaryDTO.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/trace/TraceDetailDTO.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/trace/SpanDTO.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/controllers/TraceController.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/trace/TempoTraceClientTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void searchBuildsATraceQlQueryWithClusterAndService() {
    // MockRestServiceServer 断言请求 URL 含 /api/search、q=...cluster.name...、limit
}

@Test
void traceDetailFlattensOtlpResourceAttributes() {
    // 给一段 Tempo /api/traces/{id} 的 OTLP JSON
    // 断言 SpanDTO.serviceName / clusterName 来自 resource attributes
}
```

- [ ] **Step 2: 实现**

- `TempoTraceClient`：`RestClient`（构造器注入；测试用 `MockRestServiceServer`），配置 `platform.tempo.query-url`（默认 `http://platform-tempo.monitor.svc:3200`）。
  - `search(cluster, service, start, end, limit)` → `GET /api/search?q={...}&start=&end=&limit=`；TraceQL：
    `{ resource.cluster.name = "x" && resource.service.name = "y" }`（缺省条件省略）。
  - `get(traceId)` → `GET /api/traces/{id}`，解析 OTLP JSON。
- `TraceController`：`POST /traces/search`、`GET /traces/{traceId}`；参数校验（时间范围、limit ≤ 200）。
- DTO：summary 含 traceId/rootService/rootName/startTimeUnixNano/durationMs；detail 的 span 含 spanId/parentSpanId/name/serviceName/clusterName/start/end/duration/status/attributes。
- 解析 resource attributes：`service.name`、`cluster.name`、`k8s.namespace.name`。

- [ ] **Step 3: 运行确认通过**

Run: `mvn -pl platform-api -am test -Dtest=TempoTraceClientTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/trace/ \
        platform-api/src/main/java/com/coding/platformapi/controllers/TraceController.java \
        platform-api/src/test/java/com/coding/platformapi/trace/
git commit -m "feat(trace): Tempo 查询客户端与 trace 端点"
```

---

### Task 5: trace 页面/接口权限迁移

**Files:**
- Create: `platform-data/src/main/resources/db/migration/V2026_10_19_2__trace_view_permissions.sql`
- Modify: `platform-api/src/test/java/com/coding/platformapi/components/ComponentLifecycleMigrationTest.java`

**权限码：**
- API：`platform:trace:search`（POST `/traces/search`）、`platform:trace:get`（GET `/traces/{traceId}`）
- Page：`platform:page:mesh.trace`（`/mesh/traces`）
- 三者**只绑 admin**（trace 可能跨租户；先只给平台管理员）。

- [ ] **Step 1: 写迁移 + 测试**

```sql
INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_trace_search', 'API', '/traces/search', 'POST', 'platform:trace:search', '查询 trace 列表'),
 ('perm_trace_get',    'API', '/traces/{traceId}', 'GET', 'platform:trace:get', '查看 trace 详情'),
 ('perm_page_trace',   'Page', '/mesh/traces', 'VIEW', 'platform:page:mesh.trace', '页面·链路追踪')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);
-- 三个都只绑 builtin_role_admin（显式绑定，不按 code 继承）
```

- [ ] **Step 2: 运行迁移测试**

Run: `mvn -pl platform-api -am test -Dtest=ComponentLifecycleMigrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add platform-data/src/main/resources/db/migration/V2026_10_19_2__trace_view_permissions.sql \
        platform-api/src/test/java/com/coding/platformapi/components/ComponentLifecycleMigrationTest.java
git commit -m "feat(trace): trace 查看页与端点的权限行（仅 admin）"
```

---

### Task 6: 前端「链路追踪」页面

**Files:**
- Create: `platform-web/src/views/mesh/TraceView.vue`
- Create: `platform-web/src/components/trace/TraceWaterfall.vue`
- Modify: `platform-web/src/api/index.ts`
- Modify: `platform-web/src/types.ts`
- Modify: `platform-web/src/pageCodes.ts`
- Modify: `platform-web/src/router/index.ts`
- Modify: `platform-web/src/layouts/MainLayout.vue`

- [ ] **Step 1: 类型/API**

```ts
export interface TraceSummary {
  traceId: string; rootService?: string | null; rootName?: string | null
  startTimeUnixNano?: string | null; durationMs?: number | null
}
export interface TraceSpan {
  spanId: string; parentSpanId?: string | null; name: string
  serviceName?: string | null; clusterName?: string | null
  startTimeUnixNano?: string | null; endTimeUnixNano?: string | null
  durationMs?: number | null; statusCode?: string | null
  attributes?: Record<string, string> | null
}
export interface TraceDetail { traceId: string; spans: TraceSpan[] }
```

`traceApi.search(body)` → `POST /traces/search`；`traceApi.get(traceId)` → `GET /traces/{id}`。

- [ ] **Step 2: 页面**

- 过滤条：集群（输入/下拉）、服务名、时间范围（15m / 1h / 6h / 24h）、limit；
- 结果表：时间、服务、入口 span、耗时、traceId（点击打开详情）；
- 详情：`TraceWaterfall`（按 parent 缩进、按开始时间偏移画条、显示服务/名称/耗时；点击 span 看属性）；
- 空态/错误态分开（Tempo 不可达 vs 没查到）。

- [ ] **Step 3: 路由/菜单/页面码**

- `pageCodes.mesh.trace = 'platform:page:mesh.trace'`
- 路由 `/mesh/traces`，`group: '服务网格'`，`requiresPerm: [pageCodes.mesh.trace]`
- 菜单「链路追踪」放在网格拓扑后面。

- [ ] **Step 4: 类型检查 + 构建**

Run: `cd platform-web && npm run type-check && npm run build-only`
Expected: 无错误。

- [ ] **Step 5: Commit**

```bash
git add platform-web/src/views/mesh/TraceView.vue platform-web/src/components/trace/TraceWaterfall.vue \
        platform-web/src/api/index.ts platform-web/src/types.ts platform-web/src/pageCodes.ts \
        platform-web/src/router/index.ts platform-web/src/layouts/MainLayout.vue
git commit -m "feat(web): 平台自研链路追踪页面（Tempo 查询 + 瀑布图）"
```

---

### Task 7: 全量验证与收尾

- [ ] **Step 1: 后端全量** — `mvn test` → BUILD SUCCESS
- [ ] **Step 2: 前端全量** — `npm run type-check && npm run build-only` → 无错误
- [ ] **Step 3: 迁移检查** — `V2026_10_19_2` 三条权限行 + admin 绑定；测试覆盖
- [ ] **Step 4: 文档/记忆** — spec 回写实现备注；memory 记录部署顺序
- [ ] **Step 5: Commit**
