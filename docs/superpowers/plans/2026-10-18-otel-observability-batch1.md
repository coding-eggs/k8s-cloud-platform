# OpenTelemetry 可观测接入 · 批次一实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `otel-operator` 组件扩成「operator + Instrumentation + collector gateway + Tempo（管理集群）」的可观测采集栈，并给 Java / Python 工作负载提供「追踪 / 日志」开关。

**Architecture:** 复用 S3.6 的组件安装引擎（recipe + 两遍同名 ReleaseSpec + `extraObjects`），新增 Tempo chart 与 OTel CR 组装；工作负载侧新增一个与 `mesh-toggle` 同形的读-改-写端点，只改 PodTemplate 的注解/环境变量，不改规格。

**Tech Stack:** Java 21 / Spring Boot 4 / fabric8 / MyBatis / MySQL；Vue 3 + Element Plus + TypeScript；Helm 4.2.0；OTel operator 0.161.0、collector-contrib 0.161.0、Tempo。 

> 执行备注（2026-10-19）：Tempo 入库时发现 `grafana/tempo` 1.24.4 已标记 deprecated（Grafana charts
> 2026-01 迁移到 `grafana-community/helm-charts`），实际采用 **grafana-community/tempo 3.2.0（app 3.1.0）**；
> 其余按本计划执行。

**Spec:** `docs/superpowers/specs/2026-10-18-otel-observability-design.md`

## Global Constraints

- 组件 code 保持 `otel-operator`；release 名/命名空间固定为：`platform-otel-operator`（`opentelemetry-operator-system`）、`platform-tempo`（`monitor`）。
- 两遍同名 ReleaseSpec：第一遍只装 CRD + operator，第二遍（新 helm 进程）下发 `extraObjects`；顺序必须是 operator → Tempo → operator。
- collector 镜像固定 `ghcr.io/open-telemetry/opentelemetry-collector-releases/opentelemetry-collector-contrib:0.161.0`（不是 `-k8s` 发行版）。
- Instrumentation 的 exporter 端点用 **4318 + `http/protobuf`**（Python 注入镜像只带 HTTP exporter）；collector 之间、Istio、sidecar 用 gRPC 4317。
- 日志只做应用级：Java Logback/Log4j2、Python logging；**不做 Node，不做全容器 stdout 采集**。
- 凭证只以 Secret 引用形式出现；`values_snapshot` / `__platform` 里**不得出现密码**。
- 无数据库迁移；复用既有权限 `platform:cluster:component:manage` 与工作负载更新权限。
- 前端只提交改动过的字段（沿用现有 `initialXxx` + JSON 比较）。
- 不提交用户工作区里既有的三个改动（`PlatformApiApplication.java`、`RedisConnectionKeepAlive.java`、`ClusterView.vue`）。

## Review Focus

- **占位符误伤**：Tempo 的 `${MINIO_*}` 必须留给运行时；`ValueTemplate` 的非严格模式只能对 Tempo release 生效，其他 release 仍须对未知占位符硬失败。
- **两遍 release 的识别**：注入 `extraObjects` 必须命中**最后一个** `otel-operator` release；命中错会把 CR 塞进第一遍，重新踩 `no matches for kind`。
- **Secret 命名空间**：`platform-tempo-objstore` / `platform-otel-es` 必须落在 `monitor`，否则 Tempo/collector 的 Pod 挂不到。
- **日志开关关闭时**：gateway 不能渲染 `elasticsearch` exporter 和 logs 管道；打开时三条管道都要在。
- **工作负载开关的幂等与回滚**：重复打开不重复加 env/注解；关闭只移除平台管的注解（不动用户自己的 `OTEL_*` 之外的字段）；回滚到旧 revision 时接入配置一起回滚（预期行为）。

---

### Task 1: Tempo chart 入库

**Files:**
- Create: `k8s-server/src/main/resources/charts/components/tempo/1.24.4/chart/`（官方 chart 解包）
- Create: `k8s-server/src/main/resources/charts/components/tempo/1.24.4/provenance.yaml`

**Interfaces:**
- Produces: `ChartBundle.chartDir("tempo", "1.24.4")` 可用；后续 recipe 引用 `(chartCode=tempo, chartVersion=1.24.4)`。

- [ ] **Step 1: 下载并解包官方 chart**

```bash
cd k8s-server/src/main/resources/charts/components
mkdir -p tempo/1.24.4
curl -sL -o /tmp/tempo-1.24.4.tgz https://github.com/grafana/helm-charts/releases/download/tempo-1.24.4/tempo-1.24.4.tgz
tar -xzf /tmp/tempo-1.24.4.tgz -C tempo/1.24.4
mv tempo/1.24.4/tempo tempo/1.24.4/chart
```

（若本机走代理，curl 加 `-x http://127.0.0.1:7890`。）

- [ ] **Step 2: 写 provenance**

```yaml
# k8s-server/src/main/resources/charts/components/tempo/1.24.4/provenance.yaml
chart: tempo
version: 1.24.4
appVersion: 2.9.0
source: https://grafana.github.io/helm-charts
pullCommand: helm pull tempo --repo https://grafana.github.io/helm-charts --version 1.24.4
pulledAt: 2026-10-18
```

- [ ] **Step 3: 验证 chart 完整**

Run: `test -f k8s-server/src/main/resources/charts/components/tempo/1.24.4/chart/Chart.yaml && grep -q '^version: 1.24.4' k8s-server/src/main/resources/charts/components/tempo/1.24.4/chart/Chart.yaml && echo ok`
Expected: `ok`

- [ ] **Step 4: Commit**

```bash
git add k8s-server/src/main/resources/charts/components/tempo
git commit -m "chore(charts): 内置 grafana/tempo 1.24.4"
```

---

### Task 2: `ValueTemplate` 非严格模式 + `ReleaseSpec` 开关

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/components/ReleaseSpec.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/components/ValueTemplate.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/components/ValueTemplateTest.java`

**Interfaces:**
- Produces: `ReleaseSpec(..., boolean preserveUnknownPlaceholders)`，并保留原 6 参构造（默认 false）。
- Produces: `ValueTemplate.resolve(Object value, Map<String,String> context, boolean preserveUnknown)`；原 2 参方法委托 `false`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void unknownPlaceholdersArePreservedWhenTheReleaseOptsOut() {
    Map<String, Object> in = Map.of("tempo", Map.of(
            "access_key", "${MINIO_ACCESS_KEY}",
            "bucket", "${tempo.minio.bucket}"));

    Object out = ValueTemplate.resolve(in,
            Map.of("tempo.minio.bucket", "thanos"), true);

    assertThat(out).isEqualTo(Map.of("tempo", Map.of(
            "access_key", "${MINIO_ACCESS_KEY}",
            "bucket", "thanos")));
}

@Test
void aKnownPlaceholderResolvingToBlankStillFailsInPreserveMode() {
    assertThatThrownBy(() -> ValueTemplate.resolve(
            Map.of("bucket", "${tempo.minio.bucket}"),
            Map.of("tempo.minio.bucket", ""), true))
            .isInstanceOf(CloudPlatformException.class)
            .hasMessageContaining("tempo.minio.bucket");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl platform-api -am test -Dtest=ValueTemplateTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（`resolve` 没有三参重载 / `ReleaseSpec` 没有新字段）。

- [ ] **Step 3: 实现**

```java
public record ReleaseSpec(String releaseName, String namespace, String chartCode, String chartVersion,
                          Map<String, Object> contractValues, Map<String, Object> editableValues,
                          boolean preserveUnknownPlaceholders) {

    public ReleaseSpec(String releaseName, String namespace, String chartCode, String chartVersion,
                       Map<String, Object> contractValues, Map<String, Object> editableValues) {
        this(releaseName, namespace, chartCode, chartVersion, contractValues, editableValues, false);
    }
}
```

`ValueTemplate`：

```java
public static Object resolve(Object value, Map<String, String> context) {
    return resolve(value, context, false);
}

public static Object resolve(Object value, Map<String, String> context, boolean preserveUnknown) {
    // Map / List 递归时把 preserveUnknown 传下去
    // resolveString 里：context 没有该 key 时
    //   preserveUnknown ? 原样保留 ${key} : 抛 CloudPlatformException
    // 已知 key 的空值仍然抛错（两个模式一致）
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl platform-api -am test -Dtest=ValueTemplateTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS。

- [ ] **Step 5: 适配所有 `new ReleaseSpec(...)` 调用**

Run: `mvn -pl platform-api -am test -DskipTests -q`
Expected: 编译通过（6 参构造保留）。

- [ ] **Step 6: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/components/ReleaseSpec.java \
        platform-api/src/main/java/com/coding/platformapi/components/ValueTemplate.java \
        platform-api/src/test/java/com/coding/platformapi/components/ValueTemplateTest.java
git commit -m "feat(components): ReleaseSpec 支持保留未知占位符（Tempo 运行时展开用）"
```

---

### Task 3: `OtelRecipe` 与 recipe 目录

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/components/OtelRecipe.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/components/ComponentRecipe.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/components/ComponentRecipeCatalog.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/components/ComponentRecipeCatalogTest.java`

**Interfaces:**
- Produces: `OtelRecipe(boolean instrumentation, boolean tempoInstall, boolean tempoRemoteWrite, String tempoEndpointDefault)`。
- Produces: `ComponentRecipe.otel()`（可空；非空 = OTel 组件）。
- Produces: recipe `otel-operator`：
  - MANAGEMENT：`[operator pass1, tempo, operator pass2]`
  - BUSINESS：`[operator pass1, operator pass2]`
  - `releases().get(0).chartVersion()` = `0.126.0`（版本语义不变）。

- [ ] **Step 1: 写失败测试**

```java
@Test
void theOtelRecipeHasThreeReleasesOnManagementAndTwoOnBusiness() {
    var mgmt = catalog.recipeOf("otel-operator", "MANAGEMENT");
    assertThat(mgmt.releases()).extracting(ReleaseSpec::releaseName)
            .containsExactly("platform-otel-operator", "platform-tempo", "platform-otel-operator");
    assertThat(mgmt.releases().get(1).chartCode()).isEqualTo("tempo");
    assertThat(mgmt.releases().get(1).chartVersion()).isEqualTo("1.24.4");
    assertThat(mgmt.releases().get(1).preserveUnknownPlaceholders()).isTrue();

    var biz = catalog.recipeOf("otel-operator", "BUSINESS");
    assertThat(biz.releases()).extracting(ReleaseSpec::releaseName)
            .containsExactly("platform-otel-operator", "platform-otel-operator");
    assertThat(biz.otel().tempoInstall()).isFalse();
    assertThat(biz.otel().tempoRemoteWrite()).isTrue();
}

@Test
void theOtelCollectorImageIsContrib() {
    var pass2 = catalog.recipeOf("otel-operator", "BUSINESS").releases().get(1);
    Map<String, Object> manager = (Map<String, Object>) pass2.contractValues().get("manager");
    Map<String, Object> image = (Map<String, Object>) manager.get("collectorImage");
    assertThat(image).containsEntry("repository",
            "ghcr.io/open-telemetry/opentelemetry-collector-releases/opentelemetry-collector-contrib");
    assertThat(image).containsEntry("tag", "0.161.0");
}
```

再加一条 Tempo values 断言：

```java
@Test
void theTempoReleaseKeepsCredentialPlaceholdersForRuntimeExpansion() {
    var tempo = catalog.recipeOf("otel-operator", "MANAGEMENT").releases().get(1);
    String values = tempo.contractValues().toString();
    assertThat(values).contains("${MINIO_ACCESS_KEY}").contains("${MINIO_SECRET_KEY}")
            .contains("${tempo.minio.bucket}").contains("${tempo.minio.prefix}");
    assertThat(tempo.contractValues().toString()).contains("config.expand-env");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl platform-api -am test -Dtest=ComponentRecipeCatalogTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（`OtelRecipe` / `otel()` 不存在）。

- [ ] **Step 3: 实现**

`OtelRecipe.java`：

```java
package com.coding.platformapi.components;

/** OTel 组件的表单开关；null 字段只对非 OTel 组件有意义。 */
public record OtelRecipe(boolean instrumentation, boolean tempoInstall,
                         boolean tempoRemoteWrite, String tempoEndpointDefault) {
}
```

`ComponentRecipe` 增加一个字段并保留全部旧构造：

```java
public record ComponentRecipe(String profile, List<ReleaseSpec> releases, List<String> requires,
                              boolean platformAlerting, boolean thanosRemoteWrite, boolean thanosInstall,
                              boolean calicoNetwork, String thanosReceiveDefault, boolean tektonProfile,
                              OtelRecipe otel) {
    // 原有 9 参构造委托 this(..., null)
}
```

`ComponentRecipeCatalog`：

```java
private static final Map<String, Object> OTEL_PASS1 = Map.of(
        "manager", Map.of("collectorImage", Map.of(
                "repository", "ghcr.io/open-telemetry/opentelemetry-collector-releases/opentelemetry-collector-contrib",
                "tag", "0.161.0")));

private static final ReleaseSpec OTEL_OPERATOR_PASS1 = new ReleaseSpec(
        "platform-otel-operator", "opentelemetry-operator-system", "otel-operator", "0.126.0",
        OTEL_PASS1, Map.of());

private static final ReleaseSpec OTEL_OPERATOR_PASS2 = new ReleaseSpec(
        "platform-otel-operator", "opentelemetry-operator-system", "otel-operator", "0.126.0",
        OTEL_PASS1, Map.of());

private static final ReleaseSpec TEMPO = new ReleaseSpec(
        "platform-tempo", "monitor", "tempo", "1.24.4",
        Map.of("tempo", Map.of(
                "storage", Map.of("trace", Map.of(
                        "backend", "s3",
                        "s3", Map.of(
                                "bucket", "${tempo.minio.bucket}",
                                "prefix", "${tempo.minio.prefix}",
                                "endpoint", "${tempo.minio.endpoint}",
                                "access_key", "${MINIO_ACCESS_KEY}",
                                "secret_key", "${MINIO_SECRET_KEY}",
                                "insecure", true),
                        "wal", Map.of("path", "/var/tempo/wal"))),
                "extraArgs", Map.of("config.expand-env", "true"),
                "extraEnvFrom", List.of(Map.of("secretRef", Map.of("name", "platform-tempo-objstore"))),
                "resources", Map.of(
                        "requests", Map.of("cpu", "200m", "memory", "512Mi"),
                        "limits", Map.of("cpu", "1", "memory", "2Gi"))),
                "persistence", Map.of("enabled", true, "storageClassName", "nfs-client", "size", "20Gi")),
        Map.of(), true);
```

recipe 两条：

```java
Map.entry("otel-operator", Map.of(
        BUSINESS, new ComponentRecipe(BUSINESS,
                List.of(OTEL_OPERATOR_PASS1, OTEL_OPERATOR_PASS2), List.of("cert-manager"),
                false, false, false, false, null, false,
                new OtelRecipe(true, false, true, "")),
        MANAGEMENT, new ComponentRecipe(MANAGEMENT,
                List.of(OTEL_OPERATOR_PASS1, TEMPO, OTEL_OPERATOR_PASS2), List.of("cert-manager"),
                false, false, false, false, null, false,
                new OtelRecipe(true, true, true, "platform-tempo.monitor.svc:4317"))))
```

注意：`TEMPO` 的 `preserveUnknownPlaceholders` 为 true（7 参构造）；两遍 operator 为 false。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl platform-api -am test -Dtest=ComponentRecipeCatalogTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS（包括既有的「chart 必须存在」用例）。

- [ ] **Step 5: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/components/OtelRecipe.java \
        platform-api/src/main/java/com/coding/platformapi/components/ComponentRecipe.java \
        platform-api/src/main/java/com/coding/platformapi/components/ComponentRecipeCatalog.java \
        platform-api/src/test/java/com/coding/platformapi/components/ComponentRecipeCatalogTest.java
git commit -m "feat(components): otel-operator recipe 扩展为采集栈（operator + Tempo + CR）"
```

---

### Task 4: `OtelObjects`（Instrumentation ×3 + gateway）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/components/OtelObjects.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/components/OtelObjectsTest.java`

**Interfaces:**
- Produces: `static List<Map<String,Object>> build(boolean logsEnabled)`，返回 4 个对象（3 个 Instrumentation + 1 个 `OpenTelemetryCollector`），字符串里使用占位符：
  `${cluster.name}`、`${otel.sampling}`（比例字符串，如 `0.1`）、`${tempo.endpoint}`、`${otel.es.endpoint}`、`${otel.es.username}`、`${otel.logs.index}`。
- gateway 名称/命名空间固定：`platform-otel-gateway` / `monitor`；service 名 `platform-otel-gateway-collector`。

- [ ] **Step 1: 写失败测试**

```java
class OtelObjectsTest {

    @Test
    void itBuildsThreeInstrumentationsAndOneGateway() {
        var objects = OtelObjects.build(true);
        assertThat(objects).hasSize(4);
        assertThat(objects).extracting(o -> ((Map<?, ?>) o.get("metadata")).get("name"))
                .containsExactly("platform-instrumentation", "platform-instrumentation-logs",
                        "platform-instrumentation-all", "platform-otel-gateway");
    }

    @Test
    void theTracingInstrumentationUsesTheHttpEndpointAndRatioPlaceholder() {
        var tracing = OtelObjects.build(false).get(0);
        Map<?, ?> spec = (Map<?, ?>) tracing.get("spec");
        assertThat(((Map<?, ?>) spec.get("exporter")).get("endpoint"))
                .isEqualTo("http://platform-otel-gateway-collector.monitor.svc:4318");
        assertThat(((Map<?, ?>) spec.get("env")).toString()).contains("http/protobuf");
        assertThat(((Map<?, ?>) spec.get("sampler")).get("argument")).isEqualTo("${otel.sampling}");
    }

    @Test
    void logsOffOmitsTheElasticsearchExporterAndTheLogsPipeline() {
        String off = OtelObjects.build(false).get(3).toString();
        assertThat(off).doesNotContain("elasticsearch").doesNotContain("logs:");
        String on = OtelObjects.build(true).get(3).toString();
        assertThat(on).contains("elasticsearch").contains("logs:");
    }

    @Test
    void theGatewayUsesTheContribImageAndAllThreePorts() {
        Map<?, ?> gateway = (Map<?, ?>) OtelObjects.build(true).get(3);
        Map<?, ?> spec = (Map<?, ?>) gateway.get("spec");
        assertThat(spec.get("image").toString()).endsWith("opentelemetry-collector-contrib:0.161.0");
        assertThat(spec.get("ports").toString()).contains("4317").contains("4318").contains("8889");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl platform-api -am test -Dtest=OtelObjectsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现**

按 spec §4.1 / §4.2 的 YAML 结构用 `Map.of` / `List.of` 构造。关键点：

```java
public final class OtelObjects {
    public static final String GATEWAY = "platform-otel-gateway";
    public static final String GATEWAY_SERVICE = "platform-otel-gateway-collector";
    public static final String CONTRIB_IMAGE =
            "ghcr.io/open-telemetry/opentelemetry-collector-releases/opentelemetry-collector-contrib:0.161.0";

    public static List<Map<String, Object>> build(boolean logsEnabled) { ... }

    private static Map<String, Object> instrumentation(String name, String sampler, boolean logs) { ... }
    private static Map<String, Object> gateway(boolean logsEnabled) { ... }
}
```

- tracing 版：`env` 只有 `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`；
- logs 版：`sampler.type=always_off`，`env` 加 `OTEL_LOGS_EXPORTER=otlp`，`java.env` 加两个 appender 开关；
- all 版：tracing 的 sampler + logs 的 env；
- gateway：`config.exporters.prometheus`、`otlp/tempo`（`${env:TEMPO_ENDPOINT}`）、`elasticsearch`（仅 logsEnabled）、`service.pipelines` 对应三条。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl platform-api -am test -Dtest=OtelObjectsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/components/OtelObjects.java \
        platform-api/src/test/java/com/coding/platformapi/components/OtelObjectsTest.java
git commit -m "feat(otel): Instrumentation 与 collector gateway 对象生成"
```

---

### Task 5: 请求 / 状态 DTO

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/models/SecretRef.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/TempoStorageConfig.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/EsConfig.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/OtelInstallRequest.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/ComponentApplyRequest.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/ComponentStatusDTO.java`

**Interfaces:**
- Produces: `ComponentApplyRequest.otel`（`OtelInstallRequest`，可空）。
- Produces: `ComponentStatusDTO.otelInstrumentation / tempoInstall / tempoRemoteWrite / defaultTempoEndpoint / tempoEndpoint / otelInstallCurrent`。

- [ ] **Step 1: 写 DTO**

```java
@Data @NoArgsConstructor @AllArgsConstructor
public class SecretRef {
    private String namespace;
    private String name;
}

@Data @NoArgsConstructor @AllArgsConstructor
public class TempoStorageConfig {
    private String endpoint;      // MinIO host:port
    private String bucket;
    private String prefix;
    private SecretRef credentialSecret;
    private boolean insecureSkipVerify;
}

@Data @NoArgsConstructor @AllArgsConstructor
public class EsConfig {
    private String endpoint;      // https://es-es-http.default.svc:9200
    private String username;      // elastic
    private SecretRef credentialSecret;
    private String passwordKey;   // ECK 的 key 是 elastic
    private boolean insecureSkipVerify;
    private String logsIndex;     // otel-logs
}

@Data @NoArgsConstructor @AllArgsConstructor
public class OtelInstallRequest {
    private String tempoEndpoint;
    private Integer samplingPercentage;   // 10 = 10%
    private Integer gatewayReplicas;      // 默认 1
    private Boolean logsEnabled;          // 默认 false
    private TempoStorageConfig tempoStorage;  // 仅 MANAGEMENT
    private EsConfig es;                      // logsEnabled 时必填
}
```

`ComponentApplyRequest` 增加 `private OtelInstallRequest otel;`，canonical 构造改为 11 参，所有旧构造委托并补 `null`。

`ComponentStatusDTO` 增加：

```java
private boolean otelInstrumentation;
private boolean tempoInstall;
private boolean tempoRemoteWrite;
private String defaultTempoEndpoint;
private String tempoEndpoint;
private OtelInstallRequest otelInstallCurrent;
```

- [ ] **Step 2: 编译**

Run: `mvn -pl platform-api -am test -DskipTests -q`
Expected: 编译失败（构造参数数量），按提示把旧构造补齐 `null` 后再次运行直到通过。

- [ ] **Step 3: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/models/
git commit -m "feat(otel): 安装请求与状态 DTO"
```

---

### Task 6: `ComponentInstallService` 解析 / 校验 / 快照

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/ComponentInstallServiceTest.java`

**Interfaces:**
- Consumes: `OtelRecipe`、`OtelInstallRequest`、`OtelObjects`。
- Produces: `${tempo.endpoint}`、`${tempo.minio.*}`、`${otel.sampling}`、`${otel.es.endpoint}`、`${otel.es.username}`、`${otel.logs.index}` 进入 `ValueTemplate` 上下文。
- Produces: `__platform.otel` 快照（含 Tempo/ES 配置与 Secret 引用，**不含密码**）。

- [ ] **Step 1: 写失败测试**

```java
@Test
void otelRequiresATempoEndpoint() {
    when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
    when(mapper.countRunning("c1", "otel-operator")).thenReturn(0);
    ComponentApplyRequest req = new ComponentApplyRequest(
            "c1", "otel-operator", "INSTALL", null, Map.of(), null, null, null, null, null);
    req.setOtel(new OtelInstallRequest());  // tempoEndpoint 空

    assertThatThrownBy(() -> service().apply(req, "admin"))
            .hasMessageContaining("Tempo");
    verify(mapper, never()).insert(any());
}

@Test
void otelSamplingAndReplicasAreClampedByValidation() {
    when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
    when(mapper.countRunning("c1", "otel-operator")).thenReturn(0);
    OtelInstallRequest otel = baseOtel();
    otel.setSamplingPercentage(0);

    ComponentApplyRequest req = new ComponentApplyRequest(
            "c1", "otel-operator", "INSTALL", null, Map.of(), null, null, null, null, null);
    req.setOtel(otel);

    assertThatThrownBy(() -> service().apply(req, "admin"))
            .hasMessageContaining("采样率");
}

@Test
void otelSnapshotStoresFormValuesButNoPasswords() {
    when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
    when(mapper.countRunning("c1", "otel-operator")).thenReturn(0);
    when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());

    OtelInstallRequest otel = baseOtel();
    ComponentApplyRequest req = new ComponentApplyRequest(
            "c1", "otel-operator", "INSTALL", null, Map.of(), null, null, null, null, null);
    req.setOtel(otel);
    service().apply(req, "admin");

    ArgumentCaptor<PlatformComponentOperation> captor = ArgumentCaptor.forClass(PlatformComponentOperation.class);
    verify(mapper).insert(captor.capture());
    String snapshot = captor.getValue().getValuesSnapshot();
    assertThat(snapshot).contains("tempoEndpoint").contains("otel.es.endpoint");
    assertThat(snapshot).doesNotContain("password").doesNotContain("secretKey");
}
```

（`baseOtel()` 在测试类里构造：`tempoEndpoint="platform-tempo.monitor.svc:4317"`、`samplingPercentage=10`、
`gatewayReplicas=1`、`logsEnabled=false`、`es` 用默认地址与 `default/es-es-elastic-user`。）

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl platform-api -am test -Dtest=ComponentInstallServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译/断言失败。

- [ ] **Step 3: 实现**

1. 常量与默认值：

```java
private static final int DEFAULT_SAMPLING = 10;
private static final int DEFAULT_REPLICAS = 1;
private static final String DEFAULT_ES_ENDPOINT = "https://es-es-http.default.svc:9200";
private static final String DEFAULT_ES_USERNAME = "elastic";
private static final String DEFAULT_ES_LOGS_INDEX = "otel-logs";
```

2. 在 `apply()` 里：

```java
OtelInstallRequest otel = recipe.otel() == null ? null
        : resolveOtel(req.getOtel(), previousPlatform.get("otel"), recipe.otel(), profile);
if (recipe.otel() != null && isBlank(otel.getTempoEndpoint())) {
    throw new CloudPlatformException(..., "请填写 Tempo 地址（collector 的 traces 导出目标）");
}
```

3. `resolveOtel`：表单 > 上次 `__platform.otel` > 默认；`samplingPercentage` 1..100、`gatewayReplicas` 1..10、logs 打开时 ES 地址/用户名/凭证/索引必填、MANAGEMENT 时 Tempo 存储四项必填；`tempoEndpoint` 默认取 `recipe.otel().tempoEndpointDefault()`。

4. `resolveValues` 增加 `otel` 参数，上下文加：

```java
context.put("tempo.endpoint", otel.tempoEndpoint);
context.put("tempo.minio.endpoint", storage.endpoint());
context.put("tempo.minio.bucket", storage.bucket());
context.put("tempo.minio.prefix", storage.prefix());
context.put("otel.sampling", samplingRatio(otel.getSamplingPercentage())); // 10 -> "0.1"
context.put("otel.es.endpoint", es.endpoint());
context.put("otel.es.username", es.username());
context.put("otel.logs.index", es.logsIndex());
```

5. 注入 `extraObjects`：在解析前找到**最后一个** `chartCode = "otel-operator"` 的 release，把 `OtelObjects.build(logsEnabled)` 放进它的 values（`extraObjects`）。

6. ⚠️ **两个转换方法必须带上新字段**：`mergeValues` 与 `resolveValues` 现在都用 6 参构造新建 `ReleaseSpec`，
   会静默把 `preserveUnknownPlaceholders` 丢成 false。两处都改成把 `r.preserveUnknownPlaceholders()` 传下去；
   `resolveValues` 调 `ValueTemplate.resolve(values, context, r.preserveUnknownPlaceholders())`。

7. `snapshotWithPlatformInputs` 增加：

```java
platform.put("otel", otelSnapshot(otel)); // 只放地址、用户名、索引、Secret 的 namespace/name
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl platform-api -am test -Dtest=ComponentInstallServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS（既有用例也全绿）。

- [ ] **Step 5: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java \
        platform-api/src/test/java/com/coding/platformapi/services/ComponentInstallServiceTest.java
git commit -m "feat(otel): 安装参数解析、校验与快照"
```

---

### Task 7: 前置 Secret（Tempo objstore / ES 凭证）

**Files:**
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/TempoObjstoreRequestDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/OtelEsSecretRequestDTO.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/components/TempoObjstoreObjects.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/components/OtelEsSecretObjects.java`
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/controllers/cluster/ComponentInstallController.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/k8s/K8sComponentClient.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java`
- Tests: `k8s-server/src/test/java/com/coding/k8sserver/components/TempoObjstoreObjectsTest.java`、`OtelEsSecretObjectsTest.java`

**Interfaces:**
- Produces: `POST /components/tempo-objstore`：读来源 Secret，写 `monitor/platform-tempo-objstore`（key `MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY`）。
- Produces: `POST /components/otel-es-secret`：读来源 Secret 的指定 key，写 `monitor/platform-otel-es`（key `username`/`password`）。
- Produces: `K8sComponentClient.applyTempoObjstore(...)`、`applyOtelEsSecret(...)`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void tempoObjstoreCopiesOnlyTheTwoCredentials() {
    Secret source = new SecretBuilder().withData(Map.of(
            "access-key", b64("minio"), "secret-key", b64("minio-secret"))).build();

    Secret target = TempoObjstoreObjects.build(source, "monitor", "platform-tempo-objstore");

    assertThat(target.getData()).containsKeys("MINIO_ACCESS_KEY", "MINIO_SECRET_KEY");
    assertThat(target.getData()).doesNotContainKeys("access-key", "secret-key");
}

@Test
void esSecretCopiesTheEckPasswordUnderAStableKey() {
    Secret source = new SecretBuilder().withData(Map.of("elastic", b64("pw"))).build();

    Secret target = OtelEsSecretObjects.build(source, "elastic", "monitor", "platform-otel-es");

    assertThat(target.getStringData()).containsEntry("username", "elastic")
            .containsEntry("password", "pw");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl k8s-server -am test -Dtest=TempoObjstoreObjectsTest,OtelEsSecretObjectsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现**

- `TempoObjstoreObjects.build` 复用 `ThanosObjstoreObjects.findCredentials(source.getData())`；
- `OtelEsSecretObjects.build` 从 `source.getData().get(passwordKey)` 取 Base64，解码后放进 `stringData.password`；
- 两个对象的 labels：`platform.coding/managed=true`、`platform.coding/component=otel-operator`；
- Controller 两个端点：`accessResolver.assertClusterAccess` + 参数校验 + SSA（`fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply()`）；
- `K8sComponentClient` 两个方法，DTO 在 platform-common；
- `ComponentInstallService.run()` 在 helm 之前调用（`otel != null && recipe.otel().tempoInstall()` → tempo；`otel.logsEnabled()` → ES）。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl k8s-server -am test -Dtest=TempoObjstoreObjectsTest,OtelEsSecretObjectsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add platform-common/src/main/java/com/coding/common/models/k8s/dto/TempoObjstoreRequestDTO.java \
        platform-common/src/main/java/com/coding/common/models/k8s/dto/OtelEsSecretRequestDTO.java \
        k8s-server/src/main/java/com/coding/k8sserver/components/ \
        k8s-server/src/main/java/com/coding/k8sserver/controllers/cluster/ComponentInstallController.java \
        platform-api/src/main/java/com/coding/platformapi/k8s/K8sComponentClient.java \
        platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java \
        k8s-server/src/test/java/com/coding/k8sserver/components/
git commit -m "feat(otel): Tempo 对象存储与 ES 凭证 Secret 前置下发"
```

---

### Task 8: 后置 ServiceMonitor

**Files:**
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/OtelServiceMonitorRequestDTO.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/components/OtelServiceMonitorObjects.java`
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/controllers/cluster/ComponentInstallController.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/k8s/K8sComponentClient.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/components/OtelServiceMonitorObjectsTest.java`

**Interfaces:**
- Produces: `POST /components/otel-servicemonitor`：CRD 不存在返回 `{created=false}`；存在则 SSA ServiceMonitor。
- Produces: `ComponentInstallService.run()` 在 helm 成功后调用；失败只记 WARN，不把操作置 FAILED。

- [ ] **Step 1: 写失败测试**

```java
@Test
void theMonitorSelectsTheOperatorGeneratedServiceAndThePrometheusPort() {
    var sm = OtelServiceMonitorObjects.build("monitor");
    Map<?, ?> spec = (Map<?, ?>) sm.get("spec");
    assertThat(spec.get("selector").toString())
            .contains("app.kubernetes.io/name").contains("platform-otel-gateway-collector");
    assertThat(spec.get("endpoints").toString()).contains("prometheus");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl k8s-server -am test -Dtest=OtelServiceMonitorObjectsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败。

- [ ] **Step 3: 实现**

- builder 返回 `GenericKubernetesResource`（`apiVersion: monitoring.coreos.com/v1`，`kind: ServiceMonitor`）；
- controller：`client.apiextensions().v1().customResourceDefinitions().withName("servicemonitors.monitoring.coreos.com").get() == null` → 返回 skipped；
- `run()` 中仅当 `componentCode = otel-operator` 且 helm 成功时调用；`catch (Exception e) { log.warn(...) }`，继续写 SUCCESS。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl k8s-server -am test -Dtest=OtelServiceMonitorObjectsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add platform-common/src/main/java/com/coding/common/models/k8s/dto/OtelServiceMonitorRequestDTO.java \
        k8s-server/src/main/java/com/coding/k8sserver/components/OtelServiceMonitorObjects.java \
        k8s-server/src/main/java/com/coding/k8sserver/controllers/cluster/ComponentInstallController.java \
        platform-api/src/main/java/com/coding/platformapi/k8s/K8sComponentClient.java \
        platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java \
        k8s-server/src/test/java/com/coding/k8sserver/components/OtelServiceMonitorObjectsTest.java
git commit -m "feat(otel): 后置下发 collector ServiceMonitor（CRD 缺失时跳过）"
```

---

### Task 9: 组件状态投影（flags + 当前值）

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ClusterComponentService.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/ClusterComponentServiceTest.java`

**Interfaces:**
- Produces: `/cluster/components` 每行带 `otelInstrumentation`、`tempoInstall`、`tempoRemoteWrite`、`defaultTempoEndpoint`、`tempoEndpoint`、`otelInstallCurrent`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void theOtelRowCarriesItsFlagsAndCurrentValues() {
    PlatformComponentOperation op = new PlatformComponentOperation();
    op.setComponentCode("otel-operator");
    op.setStatus("SUCCESS");
    op.setValuesSnapshot("{\"__platform\":{\"otel\":{"
            + "\"tempoEndpoint\":\"tempo.example:4317\","
            + "\"samplingPercentage\":25,\"gatewayReplicas\":2,\"logsEnabled\":true,"
            + "\"es\":{\"endpoint\":\"https://es:9200\",\"username\":\"elastic\","
            + "\"credentialSecret\":{\"namespace\":\"default\",\"name\":\"es-es-elastic-user\"},"
            + "\"passwordKey\":\"elastic\",\"insecureSkipVerify\":true,\"logsIndex\":\"otel-logs\"}}}}");
    PlatformComponentOperationMapper ops = mock(PlatformComponentOperationMapper.class);
    when(ops.selectLatestSuccesses("c1")).thenReturn(List.of(op));

    K8sComponentClient client = mock(K8sComponentClient.class);
    when(client.probe(anyString(), any())).thenReturn(aligned(List.of()));
    ClusterService management = mock(ClusterService.class);
    when(management.requireProfile("c1")).thenReturn("MANAGEMENT");
    ClusterComponentService svc = new ClusterComponentService(new ClusterComponentCatalog(),
            capability(DEFAULT_GROUPS), client, cache(), management,
            new com.coding.platformapi.components.ComponentRecipeCatalog(), ops);

    ComponentStatusDTO otel = find(svc.status("c1"), "otel-operator");

    assertThat(otel.isOtelInstrumentation()).isTrue();
    assertThat(otel.isTempoInstall()).isTrue();
    assertThat(otel.isTempoRemoteWrite()).isTrue();
    assertThat(otel.getDefaultTempoEndpoint()).isEqualTo("platform-tempo.monitor.svc:4317");
    assertThat(otel.getTempoEndpoint()).isEqualTo("tempo.example:4317");
    assertThat(otel.getOtelInstallCurrent().getSamplingPercentage()).isEqualTo(25);
    assertThat(otel.getOtelInstallCurrent().getEs().getCredentialSecret().getName())
            .isEqualTo("es-es-elastic-user");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl platform-api -am test -Dtest=ClusterComponentServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 断言失败（字段为默认 false/null）。

- [ ] **Step 3: 实现**

`setFlags` 里：

```java
OtelRecipe otel = recipe.otel();
s.setOtelInstrumentation(otel != null && otel.instrumentation());
s.setTempoInstall(otel != null && otel.tempoInstall());
s.setTempoRemoteWrite(otel != null && otel.tempoRemoteWrite());
s.setDefaultTempoEndpoint(otel == null ? null : otel.tempoEndpointDefault());
```

`applyEffectivePlatformInputs` 里：

```java
if (s.isTempoRemoteWrite() && platform.get("otel") instanceof Map<?, ?> otelMap) {
    s.setTempoEndpoint((String) otelMap.get("tempoEndpoint"));
    s.setOtelInstallCurrent(otelInstallOf(otelMap));
}
```

`otelInstallOf` 把快照 Map 转回 `OtelInstallRequest`（Secret 引用保留 namespace/name）。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl platform-api -am test -Dtest=ClusterComponentServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/ClusterComponentService.java \
        platform-api/src/test/java/com/coding/platformapi/services/ClusterComponentServiceTest.java
git commit -m "feat(otel): 组件状态投影 OTel 表单开关与当前值"
```

---

### Task 10: 前端组件表单

**Files:**
- Modify: `platform-web/src/types.ts`
- Modify: `platform-web/src/api/index.ts`
- Modify: `platform-web/src/composables/useComponentOperations.ts`
- Modify: `platform-web/src/views/ClusterDetailView.vue`
- Modify: `platform-web/src/components/cluster/ClusterComponentsPanel.vue`

**Interfaces:**
- Produces: `K8sOtelInstall`、`K8sSecretRef`、`K8sTempoStorage`、`K8sEsConfig`；`K8sComponentStatus` 新字段。
- Produces: `componentApply` / `operations.apply` 支持 `otel?: K8sOtelInstall`。

- [ ] **Step 1: 类型**

```ts
export interface K8sSecretRef { namespace: string; name: string }
export interface K8sTempoStorage {
  endpoint: string; bucket: string; prefix: string;
  credentialSecret: K8sSecretRef; insecureSkipVerify: boolean;
}
export interface K8sEsConfig {
  endpoint: string; username: string; credentialSecret: K8sSecretRef;
  passwordKey: string; insecureSkipVerify: boolean; logsIndex: string;
}
export interface K8sOtelInstall {
  tempoEndpoint: string; samplingPercentage: number; gatewayReplicas: number;
  logsEnabled: boolean; tempoStorage?: K8sTempoStorage; es?: K8sEsConfig;
}
```

`K8sComponentStatus` 增加：`otelInstrumentation?`、`tempoInstall?`、`tempoRemoteWrite?`、`defaultTempoEndpoint?`、`tempoEndpoint?`、`otelInstallCurrent?: K8sOtelInstall`。

- [ ] **Step 2: 表单**

`ClusterComponentsPanel.vue` 的 `ApplyDialog` 增加：

```ts
showOtel: boolean
otel: K8sOtelInstall
initialOtel: string
```

打开弹窗时：

```ts
const otel: K8sOtelInstall = {
  tempoEndpoint: (editing ? row.tempoEndpoint ?? row.defaultTempoEndpoint : row.defaultTempoEndpoint) ?? '',
  samplingPercentage: row.otelInstallCurrent?.samplingPercentage ?? 10,
  gatewayReplicas: row.otelInstallCurrent?.gatewayReplicas ?? 1,
  logsEnabled: row.otelInstallCurrent?.logsEnabled ?? false,
  tempoStorage: row.otelInstallCurrent?.tempoStorage ?? {
    endpoint: '', bucket: 'thanos', prefix: 'tempo',
    credentialSecret: { namespace: 'monitor', name: '' }, insecureSkipVerify: true,
  },
  es: row.otelInstallCurrent?.es ?? {
    endpoint: 'https://es-es-http.default.svc:9200', username: 'elastic',
    credentialSecret: { namespace: 'default', name: 'es-es-elastic-user' },
    passwordKey: 'elastic', insecureSkipVerify: true, logsIndex: 'otel-logs',
  },
}
// 打开弹窗时同 applyDialog 的其它 initialXxx 一样，记下初始 JSON 用于“只提交改动”
// initialOtel: JSON.stringify(otel)
```

表单区块（`el-form-item`）按 spec §6.2 的表格逐项渲染：

- Tempo 地址（必填）
- 采样率（`el-input-number` 1..100）、Gateway 副本数（1..10）、日志采集（`el-switch`）
- MANAGEMENT 且 `row.tempoInstall`：MinIO 地址、桶、前缀、凭证 Secret（namespace + name 两个输入）、跳过 TLS
- 日志打开时：ES 地址、用户名、凭证 Secret、password key、跳过 TLS、日志索引

- [ ] **Step 3: 提交**

`confirmApply` 里：

```ts
otel: d.showOtel && JSON.stringify(d.otel) !== d.initialOtel ? { ...d.otel } : undefined,
```

`useComponentOperations.apply` / `clusterApi.componentApply` 的 payload 类型加 `otel?: K8sOtelInstall`。

- [ ] **Step 4: 类型检查**

Run: `cd platform-web && npm run type-check`
Expected: 无错误。

- [ ] **Step 5: Commit**

```bash
git add platform-web/src/types.ts platform-web/src/api/index.ts \
        platform-web/src/composables/useComponentOperations.ts \
        platform-web/src/views/ClusterDetailView.vue \
        platform-web/src/components/cluster/ClusterComponentsPanel.vue
git commit -m "feat(web): OTel 组件安装/编辑表单（Tempo、MinIO、ES、采样）"
```

---

### Task 11: 工作负载「可观测接入」后端

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/models/WorkloadObservabilityRequest.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/controllers/WorkloadController.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/WorkloadService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/audit/AuditAction.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/WorkloadServiceObservabilityTest.java`

**Interfaces:**
- Produces: `POST /workloads/{name}/observability`，body：
  `{ tenantId, clusterId, namespace, kind?, language, tracing, logs, serviceName?, containerName? }`。
- 行为：读写线上工作负载，只改 PodTemplate 的注解与主容器 env；`language` 只接受 `java` / `python`；
  `containerName` 空 = 第一个容器。

- [ ] **Step 1: 写失败测试**

```java
@Test
void tracingAndLogsSelectTheAllInstrumentation() {
    var existing = workloadWithTemplate();
    when(k8s.get(any())).thenReturn(existing);
    when(k8s.update(any())).thenAnswer(inv -> inv.getArgument(0));

    svc.observability(req("java", true, true, "movie"));

    Map<String, Object> ann = existing.getPodTemplate().getAnnotations();
    assertThat(ann).containsEntry("instrumentation.opentelemetry.io/inject-java",
            "monitor/platform-instrumentation-all");
    assertThat(ann).doesNotContainKey("instrumentation.opentelemetry.io/inject-python");
    assertThat(envOf(existing, "OTEL_SERVICE_NAME")).isEqualTo("movie");
}

@Test
void nodeIsRejected() {
    assertThatThrownBy(() -> svc.observability(req("nodejs", true, false, "x")))
            .hasMessageContaining("不支持");
}

@Test
void turningEverythingOffRemovesOnlyPlatformAnnotations() {
    var existing = workloadWithObservability("python", "platform-instrumentation");
    when(k8s.get(any())).thenReturn(existing);
    when(k8s.update(any())).thenAnswer(inv -> inv.getArgument(0));

    svc.observability(req("python", false, false, "x"));

    assertThat(existing.getPodTemplate().getAnnotations())
            .doesNotContainKey("instrumentation.opentelemetry.io/inject-python")
            .containsKey("user.example/keep");
}
```

（夹具：`workloadWithTemplate()` 返回带 `podTemplate.spec.containers[0]` 的 `WorkloadDTO`；
`workloadWithObservability(lang, name)` 在其上预置注入注解与 `user.example/keep`；
`envOf(dto, key)` 从主容器读 env 值；`req(...)` 构造 `WorkloadObservabilityRequest`。）

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl platform-api -am test -Dtest=WorkloadServiceObservabilityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败。

- [ ] **Step 3: 实现**

按 `meshToggle` 的读-改-写模式：

```java
private static final Map<String, String> INJECT_KEYS = Map.of(
        "java", "instrumentation.opentelemetry.io/inject-java",
        "python", "instrumentation.opentelemetry.io/inject-python");

public WorkloadDTO observability(WorkloadObservabilityRequest req) {
    WorkloadDTO query = dto(...); query.setKind(req.getKind());
    WorkloadDTO existing = k8s.get(query);
    // 校验 language ∈ java/python
    // annotations 先复制，移除所有 INJECT_KEYS 的值，再按组合写入
    // tracing/logs 都为 false → 不写注解
    // OTEL_SERVICE_NAME：写到主容器（containerName 或第一个容器）
    // annotations 为空 → null（与 meshToggle 的规整一致）
    return k8s.update(existing);
}
```

Instrumentation 名称：

```java
String name = tracing && logs ? "platform-instrumentation-all"
        : tracing ? "platform-instrumentation"
        : "platform-instrumentation-logs";
```

审计：`AuditAction.WORKLOAD_TOGGLE_OBSERVABILITY = "workload:toggle-observability"`，控制器加 `@Auditable`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl platform-api -am test -Dtest=WorkloadServiceObservabilityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/models/WorkloadObservabilityRequest.java \
        platform-api/src/main/java/com/coding/platformapi/controllers/WorkloadController.java \
        platform-api/src/main/java/com/coding/platformapi/services/WorkloadService.java \
        platform-api/src/main/java/com/coding/platformapi/audit/AuditAction.java \
        platform-api/src/test/java/com/coding/platformapi/services/WorkloadServiceObservabilityTest.java
git commit -m "feat(workload): 可观测接入开关（Instrumentation 注解 + OTEL_SERVICE_NAME）"
```

---

### Task 12: 前端工作负载开关

**Files:**
- Create: `platform-web/src/components/workload/WorkloadObservabilityDialog.vue`
- Modify: `platform-web/src/views/resource/WorkloadDetailView.vue`
- Modify: `platform-web/src/api/index.ts`
- Modify: `platform-web/src/types.ts`

**Interfaces:**
- Produces: `workloadApi.observability(name, ctx)`，body 与 Task 11 一致。
- UI：详情页头部「可观测」按钮 → 弹窗（语言、追踪、日志、服务名）。

- [ ] **Step 1: API 与类型**

```ts
observability: (name: string, ctx: {
  tenantId: string; clusterId: string; namespace: string; kind?: string;
  language: string; tracing: boolean; logs: boolean; serviceName?: string; containerName?: string;
}) => http.post<never, WorkloadDetail>(
  `/workloads/${encodeURIComponent(name)}/observability`, { ...ctx, name }),
```

- [ ] **Step 2: 弹窗组件**

`WorkloadObservabilityDialog.vue`：

- props: `visible`、`workloadName`、`kind`、`annotations`、`containers`；
- 语言：`java` / `python`；Node 选项 `disabled` 并注明「第一批不支持」；
- 追踪 / 日志：`el-switch`；「只开日志」时提示「机器人仍需注入 agent」；
- 服务名：默认工作负载名；
- 打开时从 `instrumentation.opentelemetry.io/inject-*` 注解回显语言与组合。

- [ ] **Step 3: 详情页接入**

`WorkloadDetailView.vue` 头部按钮区加：

```vue
<el-button size="small" @click="observabilityVisible = true">可观测</el-button>
```

并在成功后重新加载详情。

- [ ] **Step 4: 类型检查**

Run: `cd platform-web && npm run type-check`
Expected: 无错误。

- [ ] **Step 5: Commit**

```bash
git add platform-web/src/components/workload/WorkloadObservabilityDialog.vue \
        platform-web/src/views/resource/WorkloadDetailView.vue \
        platform-web/src/api/index.ts platform-web/src/types.ts
git commit -m "feat(web): 工作负载可观测接入弹窗"
```

---

### Task 13: 全量验证与收尾

**Files:**
- Modify: `docs/superpowers/specs/2026-10-18-otel-observability-design.md`（如有实现偏差，回写一句）
- No production code changes unless a test fails.

- [ ] **Step 1: 后端全量测试**

Run: `mvn test`
Expected: BUILD SUCCESS（全模块）。

- [ ] **Step 2: 前端构建**

Run: `cd platform-web && npm run type-check && npm run build-only`
Expected: 无类型错误、构建成功。

- [ ] **Step 3: 真集群预检（只读/服务端 dry-run）**

- 确认 `tempo` chart 能被 `ChartBundle` 找到（单测已覆盖）；
- 对 `platform-otel-operator` 的第二遍 release 做一次 `helm template`（用 Task 4 生成的 values），确认 `extraObjects` 能渲染出 4 个对象；
- 不执行真实安装（部署与验收由用户按 spec §8.1 做）。

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-10-18-otel-observability-design.md
git commit -m "docs(otel): 记录批次一实现偏差"
```
