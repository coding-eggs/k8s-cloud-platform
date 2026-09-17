# PodMonitor (B1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 PodMonitor（`monitoring.coreos.com/v1` CRD）全栈支持——后端 DTO/Converter/Operations/Controller + 前端列表页/编辑页，交互与 ServiceMonitor 完全一致。

**Architecture:** 全栈镜像 ServiceMonitor（platform-common DTO → k8s-core fabric8 通用 CRD converter+operations → k8s-server 边界 controller → platform-api 资源 controller+service → Vue3/Element Plus 列表+编辑页）。仅改 CRD 语义差异点：spec 端点键名 `podMetricsEndpoints`、`port`/`portNumber` 拆分、新增 `honorLabels`、无 `bearerTokenFile`、`podRef` 反查 Pod labels、discovery 前缀 `podMonitor/`。

**Tech Stack:** Java 21 / Spring Boot / Maven 多模块 / fabric8 GenericKubernetesResource + SSA；Vue 3 + TypeScript + Element Plus（vue-tsc 门控，无前端测试框架）。

**Spec:** [docs/superpowers/specs/2026-09-14-podmonitor-design.md](../specs/2026-09-14-podmonitor-design.md)

## Global Constraints

- Java 21；Maven reactor（root `pom.xml`，模块 platform-common/k8s-core/k8s-server/platform-api/platform-data/platform-auth）。构建命令一律从仓库根 `D:\coding\k8s-cloud-platform` 执行（Git Bash）。
- **不加新的外部依赖**（无代码生成、无新 CRD client 库）。platform-api 需补 `spring-boot-starter-test`（test scope，同 k8s-core 既有声明）。
- k8s-server 边界 controller **零业务逻辑**（用户钦定原则）；一页一 controller；所有 K8s 语义收在 k8s-core。
- 错误消息中文，走 `CloudPlatformException(EnumResponseType.*, msg)`。
- CRD spec 以 `Map<String,Object>` 读写（`GenericKubernetesResource.setAdditionalProperty("spec", ...)`）；update 必须走 **fetch-overlay + SSA**（`ServerSideApply.FIELD_MANAGER` + `forceConflicts()`），atomic list 未建模子字段靠 overlay 保留。
- 前端无 vitest：质量门 = `npm --prefix platform-web run type-check`；端到端需活后端（Task 9 人工清单）。
- **提交纪律**：工作区已有大量无关改动——**只 `git add` 计划里列出的路径，绝不 `git add -A`**。提交信息尾行按执行时会话的 attribution 规则（本计划编写时要求：`Co-Authored-By: Claude Haiku 4.5 (1M context) <noreply@anthropic.com>`；若执行模型不同，用其对应行）。
- 参照文件（实现时对照原文，不改动它们）：
  - `platform-common/src/main/java/com/coding/common/models/k8s/dto/ServiceMonitorDTO.java`
  - `platform-common/src/main/java/com/coding/common/models/k8s/dto/ServiceMonitorEndpointDTO.java`
  - `k8s-core/src/main/java/com/coding/k8score/converter/impl/monitoring/ServiceMonitorConverter.java`
  - `k8s-core/src/main/java/com/coding/k8score/operations/monitoring/ServiceMonitorOperations.java`
  - `platform-api/src/main/java/com/coding/platformapi/services/ServiceMonitorService.java`
  - `platform-api/src/main/java/com/coding/platformapi/controllers/resource/ServiceMonitorController.java`
  - `platform-api/src/main/java/com/coding/platformapi/models/ServiceMonitorKeyRequest.java`
  - `k8s-server/src/main/java/com/coding/k8sserver/controllers/namespace/ServiceMonitorController.java`
  - `platform-web/src/views/resource/ServiceMonitorView.vue`（224 行）
  - `platform-web/src/views/resource/ServiceMonitorEditorView.vue`（1176 行）

## File Structure

| 动作 | 文件 | 职责 |
|---|---|---|
| Create | `platform-common/.../dto/PodMonitorDTO.java` | spec 顶层字段 + 内部类（PodRef 等） |
| Create | `platform-common/.../dto/PodMonitorEndpointDTO.java` | endpoint 字段（含 honorLabels，无 bearerTokenFile） |
| Modify | `platform-common/.../k8s/ResourceType.java` | +`POD_MONITOR(PodMonitorDTO.class)` |
| Create | `k8s-core/.../converter/impl/monitoring/PodMonitorConverter.java` | DTO⇄GenericKubernetesResource；port 拆分；overlay |
| Create(Test) | `k8s-core/src/test/.../monitoring/PodMonitorConverterTest.java` | port 拆分/overlay/honorLabels 单测 |
| Create | `k8s-core/.../operations/monitoring/PodMonitorOperations.java` | fabric8 通用 CRD CRUD+SSA |
| Create(Test) | `k8s-core/src/test/.../monitoring/PodMonitorOperationsTest.java` | apiVersion 冒烟 |
| Modify | `k8s-core/.../factory/KubernetesOperationsFactory.java` | +POD_MONITOR case |
| Modify | `platform-api/pom.xml` | +spring-boot-starter-test(test) |
| Create | `platform-api/.../models/PodMonitorKeyRequest.java` | discovery 定位入参 |
| Create | `platform-api/.../services/PodMonitorService.java` | resolveSelector(podRef→labels)+discovery |
| Create(Test) | `platform-api/src/test/.../services/PodMonitorServiceTest.java` | service 单测（mockito） |
| Create | `platform-api/.../controllers/resource/PodMonitorController.java` | `/resource/podmonitors` |
| Create | `k8s-server/.../controllers/namespace/PodMonitorController.java` | 边界，零逻辑 |
| Modify | `platform-web/src/types.ts` | +K8sPmEndpoint/K8sPmMatchExpression/K8sPodMonitor |
| Modify | `platform-web/src/api/index.ts` | +podMonitorApi/podMonitorRelabelApi |
| Modify | `platform-web/src/router/index.ts` | +2 路由 |
| Modify | `platform-web/src/layouts/MainLayout.vue` | +菜单项 |
| Create | `platform-web/src/views/resource/PodMonitorView.vue` | 列表页 |
| Create | `platform-web/src/views/resource/PodMonitorEditorView.vue` | 编辑页 |

**Interfaces（跨任务契约，后续任务按此引用）**
- `PodMonitorDTO`：字段 `podMetricsEndpoints: List<PodMonitorEndpointDTO>`、`podRef: PodMonitorDTO.PodRef{name,namespace}`、`matchLabels/matchExpressions/namespaceSelector/jobLabel/podTargetLabels/sampleLimit/targetLimit/labelLimit/bodySizeLimit/attachMetadata/creationTime`；内部类 `MatchExpression/NamespaceSelector/PodRef/AttachMetadata`；`getApiPath()="/resources/podmonitors"`。
- `PodMonitorEndpointDTO`：`port/path/interval/scrapeTimeout/scheme/params/basicAuth/bearerTokenSecret/tlsConfig/honorLabels(Boolean)/relabelings/metricRelabelings` + 内部类 `SecretRef/BasicAuth/TlsConfig/Relabeling`。
- `ResourceType.POD_MONITOR`；`PodMonitorOperations implements NamespacedOperations<PodMonitorDTO>`；`KubernetesOperationsFactory` case。
- `PodMonitorService.create/update(PodMonitorDTO)`、`relabelLabels(String,String,String)`、`metricNames(String,String,String)`。
- TS：`K8sPodMonitor`（含 `podRef`、`podMetricsEndpoints`）、`K8sPmEndpoint`（含 `honorLabels`）、`K8sPmMatchExpression`、`K8sPmRelabeling`；`podMonitorApi`、`podMonitorRelabelApi{labels,metricNames}`；路由 name `podmonitors` / `podmonitor-editor`。

---

### Task 1: platform-common — DTO + ResourceType 枚举

**Files:**
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/PodMonitorDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/PodMonitorEndpointDTO.java`
- Modify: `platform-common/src/main/java/com/coding/common/models/k8s/ResourceType.java`（枚举项在 `SERVICE_MONITOR(ServiceMonitorDTO.class),` 之后、`WORKLOAD` 之前插入）

**Interfaces:**
- Produces: `PodMonitorDTO` / `PodMonitorEndpointDTO` / `ResourceType.POD_MONITOR`（Task 2/3/4/5 消费）

DTO 为纯数据载体（Lombok `@Data`），无逻辑可测；门控是编译。

- [ ] **Step 1: 写 `PodMonitorDTO.java`**（镜像 ServiceMonitorDTO，差异：`podRef`、`podMetricsEndpoints`；javadoc 按 Pod 语义改写）

```java
package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * PodMonitor DTO（monitoring.coreos.com/v1 CRD）。
 * spec 顶层字段：selector（必填）+ podMetricsEndpoints（必填）+ namespaceSelector / jobLabel /
 * podTargetLabels / sampleLimit / targetLimit / labelLimit / bodySizeLimit / attachMetadata。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PodMonitorDTO extends BaseResources {

    /** 目标 Pod 选择器 matchLabels（spec.selector，必填）：由后端据 podRef 解析填充；查询时原样返回 */
    private Map<String, String> matchLabels;

    /** 目标 Pod 选择器 matchExpressions（spec.selector.matchExpressions）：与 matchLabels 并存，ANDed；支持按表达式选 Pod */
    private List<MatchExpression> matchExpressions;

    /** 绑定的目标 Pod（平台侧）：前端选择后据此反查其 labels 生成 matchLabels；非 K8s 字段，转发 k8s-server 前剥离、不落库 */
    private PodRef podRef;

    /** 命名空间选择（缺省=仅本命名空间） */
    private NamespaceSelector namespaceSelector;

    /** Prometheus job 名（缺省 = <namespace>-<name>） */
    private String jobLabel;

    /** 从目标 Pod 的标签透传到抓取目标的 label 列表 */
    private List<String> podTargetLabels;

    /** 每个 target 最大样本数 */
    private Integer sampleLimit;

    /** 每 scrape 最大 target 数 */
    private Integer targetLimit;

    /** 每个 target 最大 label 数 */
    private Integer labelLimit;

    /** 响应体大小上限，如 50MB（string） */
    private String bodySizeLimit;

    /** 附加元数据（node=true 时把 node 名加到目标标签） */
    private AttachMetadata attachMetadata;

    /** 抓取端点列表（spec.podMetricsEndpoints，必填；注意非 ServiceMonitor 的 endpoints） */
    private List<PodMonitorEndpointDTO> podMetricsEndpoints;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    /** spec.namespaceSelector：any=跨所有命名空间；matchNames=指定列表 */
    @Data
    public static class NamespaceSelector {
        private Boolean any;
        private List<String> matchNames;
    }

    /** 绑定的目标 Pod（name + namespace）：namespace 缺省取本资源所在命名空间 */
    @Data
    public static class PodRef {
        private String name;
        private String namespace;
    }

    /** spec.attachMetadata：node=true 附加 node 名标签 */
    @Data
    public static class AttachMetadata {
        private Boolean node;
    }

    /** spec.selector.matchExpressions[] 项：key + operator(In/NotIn/Exists/DoesNotExist) + values */
    @Data
    public static class MatchExpression {
        private String key;
        private String operator;
        private List<String> values;
    }

    @Override
    public String getApiPath() {
        return "/resources/podmonitors";
    }

}
```

- [ ] **Step 2: 写 `PodMonitorEndpointDTO.java`**（差异：无 `bearerTokenFile`（本 OAS 未见，靠 overlay 保留）；新增 `honorLabels`）

```java
package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * PodMonitor 抓取端点（monitoring.coreos.com/v1，spec.podMetricsEndpoints[] 项）。
 * 覆盖常用抓取参数 + 鉴权/TLS 常用项 + honorLabels + relabeling 结构化列表。
 * bearerTokenFile 未建模（本 OAS 未见），update 时靠 fetch-overlay 原样保留。
 */
@Data
public class PodMonitorEndpointDTO {

    /** 端口名或端口号（DTO 层单一字段；CRD 边界按 §converter 规则拆 port / portNumber） */
    private String port;

    /** 抓取路径，缺省 /metrics */
    private String path;

    /** 抓取间隔，如 30s */
    private String interval;

    /** 抓取超时，如 10s（须小于 interval） */
    private String scrapeTimeout;

    /** 抓取协议：http / https */
    private String scheme;

    /** 抓取 URL 附加参数（map[string][]string） */
    private Map<String, List<String>> params;

    /** Basic 鉴权（username/password 各引用一个 Secret） */
    private BasicAuth basicAuth;

    /** Bearer Token（引用 Secret 的 name/key） */
    private SecretRef bearerTokenSecret;

    /** TLS 常用项 */
    private TlsConfig tlsConfig;

    /** 目标标签与指标自带标签同名冲突时：true=以指标自带标签为准 */
    private Boolean honorLabels;

    /** 抓取前重打标规则 */
    private List<Relabeling> relabelings;

    /** 抓取后指标重打标规则 */
    private List<Relabeling> metricRelabelings;

    /** Secret 引用（name + key）：basicAuth.username / password、bearerTokenSecret 共用 */
    @Data
    public static class SecretRef {
        private String name;
        private String key;
    }

    /** Basic 鉴权 */
    @Data
    public static class BasicAuth {
        private SecretRef username;
        private SecretRef password;
    }

    /** TLS 常用项（insecureSkipVerify / serverName；ca/cert/keySecret 靠 overlay 保留） */
    @Data
    public static class TlsConfig {
        private Boolean insecureSkipVerify;
        private String serverName;
    }

    /** Relabeling / MetricRelabeling 单条规则 */
    @Data
    public static class Relabeling {
        /** 源标签列表（如 __meta_kubernetes_pod_name） */
        private List<String> sourceLabels;
        private String targetLabel;
        private String regex;
        private String replacement;
        private String separator;
        private Long modulus;
        /** replace / keep / drop / hashmod / labelmap / labeldrop / labelkeep */
        private String action;
    }

}
```

- [ ] **Step 3: `ResourceType.java` 加枚举项**。锚点（现第 36 行）：

```java
    SERVICE_MONITOR(ServiceMonitorDTO.class),
```

替换为：

```java
    SERVICE_MONITOR(ServiceMonitorDTO.class),
    POD_MONITOR(PodMonitorDTO.class),
```

并在文件顶部 import 区（`dto` 包 import 段）加 `import com.coding.common.models.k8s.dto.PodMonitorDTO;`（若该文件用通配 import 则跳过）。

- [ ] **Step 4: 编译门控**

```bash
mvn -q -pl platform-common -am compile
```
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add platform-common/src/main/java/com/coding/common/models/k8s/dto/PodMonitorDTO.java platform-common/src/main/java/com/coding/common/models/k8s/dto/PodMonitorEndpointDTO.java platform-common/src/main/java/com/coding/common/models/k8s/ResourceType.java
git commit -m "feat(podmonitor): add PodMonitorDTO + PodMonitorEndpointDTO + ResourceType enum"
```

---

### Task 2: k8s-core — PodMonitorConverter（TDD）

**Files:**
- Create: `k8s-core/src/test/java/com/coding/k8score/converter/impl/monitoring/PodMonitorConverterTest.java`
- Create: `k8s-core/src/main/java/com/coding/k8score/converter/impl/monitoring/PodMonitorConverter.java`

**Interfaces:**
- Consumes: Task 1 的 DTO
- Produces: `PodMonitorConverter`：`convert(PodMonitorDTO)→GenericKubernetesResource`、`revert(GenericKubernetesResource)→PodMonitorDTO`、`convertForUpdate(PodMonitorDTO, GenericKubernetesResource)→GenericKubernetesResource`、常量 `API_VERSION="monitoring.coreos.com/v1"`（Task 3 消费）

- [ ] **Step 1: 写失败测试**（覆盖 spec §3/§4.2 全部差异点：podMetricsEndpoints 键名、port/portNumber 拆分、honorLabels、overlay 保未建模 + port 对清理、tlsConfig 深合并）

```java
package com.coding.k8score.converter.impl.monitoring;

import com.coding.common.models.k8s.dto.PodMonitorDTO;
import com.coding.common.models.k8s.dto.PodMonitorEndpointDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PodMonitorConverterTest {

    private final PodMonitorConverter c = new PodMonitorConverter();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> specOf(GenericKubernetesResource res) {
        return (Map<String, Object>) res.getAdditionalProperties().get("spec");
    }

    private PodMonitorDTO dtoWithEndpoint(String port) {
        PodMonitorDTO dto = new PodMonitorDTO();
        dto.setName("pm1");
        dto.setNamespace("ns1");
        dto.setMatchLabels(Map.of("app", "demo"));
        PodMonitorEndpointDTO ep = new PodMonitorEndpointDTO();
        ep.setPort(port);
        ep.setPath("/metrics");
        ep.setInterval("30s");
        dto.setPodMetricsEndpoints(List.of(ep));
        return dto;
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_emits_podMetricsEndpoints_selector_and_kind() {
        GenericKubernetesResource res = c.convert(dtoWithEndpoint("9090"));
        assertThat(res.getApiVersion()).isEqualTo("monitoring.coreos.com/v1");
        assertThat(res.getKind()).isEqualTo("PodMonitor");
        Map<String, Object> spec = specOf(res);
        assertThat(spec).containsKey("selector").doesNotContainKey("endpoints");
        assertThat(((Map<String, Object>) spec.get("selector")).get("matchLabels"))
                .isEqualTo(Map.of("app", "demo"));
        List<Map<String, Object>> eps = (List<Map<String, Object>>) spec.get("podMetricsEndpoints");
        assertThat(eps).hasSize(1);
        assertThat(eps.get(0)).containsEntry("path", "/metrics").containsEntry("interval", "30s");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_numeric_port_becomes_portNumber_and_drops_port() {
        List<Map<String, Object>> eps =
                (List<Map<String, Object>>) specOf(c.convert(dtoWithEndpoint("9090"))).get("podMetricsEndpoints");
        assertThat(eps.get(0)).containsEntry("portNumber", 9090).doesNotContainKey("port");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_named_port_stays_string_port() {
        List<Map<String, Object>> eps =
                (List<Map<String, Object>>) specOf(c.convert(dtoWithEndpoint("metrics"))).get("podMetricsEndpoints");
        assertThat(eps.get(0)).containsEntry("port", "metrics").doesNotContainKey("portNumber");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_out_of_range_numeric_string_falls_back_to_port_string() {
        // 超端口范围的纯数字（如误输 999999）→ 按命名端口处理，避免 int 溢出
        List<Map<String, Object>> eps =
                (List<Map<String, Object>>) specOf(c.convert(dtoWithEndpoint("999999"))).get("podMetricsEndpoints");
        assertThat(eps.get(0)).containsEntry("port", "999999").doesNotContainKey("portNumber");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_honorLabels_emitted_only_when_true() {
        PodMonitorDTO dto = dtoWithEndpoint("metrics");
        assertThat(specOf(c.convert(dto)).get("podMetricsEndpoints") instanceof List).isTrue();
        List<Map<String, Object>> eps =
                (List<Map<String, Object>>) specOf(c.convert(dto)).get("podMetricsEndpoints");
        assertThat(eps.get(0)).doesNotContainKey("honorLabels");
        dto.getPodMetricsEndpoints().get(0).setHonorLabels(true);
        eps = (List<Map<String, Object>>) specOf(c.convert(dto)).get("podMetricsEndpoints");
        assertThat(eps.get(0)).containsEntry("honorLabels", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void revert_prefers_named_port_then_reads_portNumber() {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion("monitoring.coreos.com/v1");
        res.setKind("PodMonitor");
        res.setMetadata(new ObjectMetaBuilder().withName("pm1").withNamespace("ns1")
                .withCreationTimestamp("2026-01-01T00:00:00Z").build());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("selector", Map.of("matchLabels", Map.of("app", "demo")));
        Map<String, Object> epNum = new LinkedHashMap<>();
        epNum.put("portNumber", 9090);
        epNum.put("honorLabels", true);
        Map<String, Object> epNamed = new LinkedHashMap<>();
        epNamed.put("port", "metrics");
        epNamed.put("portNumber", 9090); // 理论互斥；共存时以 port 为准
        spec.put("podMetricsEndpoints", List.of(epNum, epNamed));
        res.setAdditionalProperty("spec", spec);

        PodMonitorDTO dto = c.revert(res);
        assertThat(dto.getName()).isEqualTo("pm1");
        assertThat(dto.getCreationTime()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(dto.getMatchLabels()).containsEntry("app", "demo");
        assertThat(dto.getPodMetricsEndpoints()).hasSize(2);
        assertThat(dto.getPodMetricsEndpoints().get(0).getPort()).isEqualTo("9090");
        assertThat(dto.getPodMetricsEndpoints().get(0).getHonorLabels()).isTrue();
        assertThat(dto.getPodMetricsEndpoints().get(1).getPort()).isEqualTo("metrics");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_preserves_unmodeled_keys_and_replaces_modeled() {
        // live endpoint：建模字段旧值 + 外部字段（authorization/proxyUrl/bearerTokenFile/tlsConfig.ca）
        Map<String, Object> liveTls = new LinkedHashMap<>();
        liveTls.put("insecureSkipVerify", true);
        liveTls.put("ca", Map.of("configMap", Map.of("name", "cm")));
        Map<String, Object> liveEp = new LinkedHashMap<>();
        liveEp.put("port", "metrics");
        liveEp.put("path", "/old");
        liveEp.put("honorLabels", true);
        liveEp.put("authorization", Map.of("type", "Bearer"));
        liveEp.put("proxyUrl", "http://p:1");
        liveEp.put("bearerTokenFile", "/var/run/secrets/t");
        liveEp.put("tlsConfig", liveTls);
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("podMetricsEndpoints", List.of(liveEp));
        GenericKubernetesResource live = new GenericKubernetesResource();
        live.setMetadata(new ObjectMetaBuilder().withName("pm1").withNamespace("ns1").build());
        live.setAdditionalProperty("spec", liveSpec);

        // dto：端口改数字、清空 honorLabels、未动 tls（null）；relabelings 新加一条
        PodMonitorDTO dto = dtoWithEndpoint("9091");
        dto.getPodMetricsEndpoints().get(0).setHonorLabels(false);
        PodMonitorEndpointDTO.Relabeling r = new PodMonitorEndpointDTO.Relabeling();
        r.setAction("keep");
        r.setSourceLabels(List.of("__meta_kubernetes_pod_name"));
        dto.getPodMetricsEndpoints().get(0).setRelabelings(List.of(r));

        List<Map<String, Object>> merged =
                (List<Map<String, Object>>) specOf(c.convertForUpdate(dto, live)).get("podMetricsEndpoints");
        assertThat(merged).hasSize(1);
        Map<String, Object> ep = merged.get(0);
        assertThat(ep).containsEntry("portNumber", 9091)
                .doesNotContainKey("port")                      // 命名→数字：旧 port 被建模列表清理
                .containsEntry("honorLabels", false)             // 显式 false 照常下发（putBool 只跳 null；false 与缺省语义等价，CRD 合法）
                .containsEntry("path", "/metrics")
                .containsEntry("authorization", Map.of("type", "Bearer")) // 外部字段原样保留
                .containsEntry("proxyUrl", "http://p:1")
                .containsEntry("bearerTokenFile", "/var/run/secrets/t");
        assertThat((Map<String, Object>) ep.get("tlsConfig"))
                .containsEntry("insecureSkipVerify", true)       // 深合并：dto tlsConfig=null → 线上整块保留
                .containsKey("ca");
        assertThat((List<Map<String, Object>>) ep.get("relabelings")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_blank_port_removes_both_port_and_portNumber() {
        // 注：UI 校验 port 必填，此为 overlay 兜底语义（spec §4.2）
        Map<String, Object> liveEp = new LinkedHashMap<>();
        liveEp.put("port", "metrics");
        liveEp.put("path", "/old");
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("podMetricsEndpoints", List.of(liveEp));
        GenericKubernetesResource live = new GenericKubernetesResource();
        live.setMetadata(new ObjectMetaBuilder().withName("pm1").withNamespace("ns1").build());
        live.setAdditionalProperty("spec", liveSpec);

        PodMonitorDTO dto = dtoWithEndpoint(null);
        List<Map<String, Object>> merged =
                (List<Map<String, Object>>) specOf(c.convertForUpdate(dto, live)).get("podMetricsEndpoints");
        assertThat(merged.get(0)).doesNotContainKey("port").doesNotContainKey("portNumber");
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**（`PodMonitorConverter` 不存在）

```bash
mvn -q -pl k8s-core -am test -Dtest=PodMonitorConverterTest
```
Expected: COMPILATION ERROR（cannot find symbol: PodMonitorConverter）

- [ ] **Step 3: 实现 converter**——先复制再改：

```bash
cp k8s-core/src/main/java/com/coding/k8score/converter/impl/monitoring/ServiceMonitorConverter.java k8s-core/src/main/java/com/coding/k8score/converter/impl/monitoring/PodMonitorConverter.java
```

对副本应用以下 **7 处**修改（其余 util 方法 putStr/putBool/asStr/asBool/asInt/asLong/asStringList/notBlank、matchExpressions/secretRef/relabeling 转换**逐字保留**）：

**(1)** 包内类名/类型整体重命名：`ServiceMonitorConverter`→`PodMonitorConverter`、`ServiceMonitorDTO`→`PodMonitorDTO`（含内部类 `ServiceMonitorDTO.` 前缀 → `PodMonitorDTO.`）、`ServiceMonitorEndpointDTO`→`PodMonitorEndpointDTO`（import 行同步）。类 javadoc 首行改为：

```java
/**
 * PodMonitorDTO ⇄ GenericKubernetesResource（monitoring.coreos.com/v1 CRD）。
 * 走 fabric8 通用 CRD API，不引代码生成依赖；spec 以 Map 结构读写。
 * <p>CRD 要求 spec.selector 与 spec.podMetricsEndpoints 均必填 → convert 始终输出两者（空也给空结构）。
 * <p>endpoint.port：纯数字（1–65535）→ CRD portNumber(int)；否则 → CRD port(string)。二者只写其一。
 */
```

**(2)** `convert`：`res.setKind("ServiceMonitor")` → `res.setKind("PodMonitor")`；`selector` 注释里「选 Service」→「选 Pod」。

**(3)** `convert` 端点输出行（原文 `spec.put("endpoints", toEndpointMaps(dto.getEndpoints()));` 及其上方注释）替换为：

```java
        // podMetricsEndpoints（必填）：始终输出，空也给空数组
        spec.put("podMetricsEndpoints", toEndpointMaps(dto.getPodMetricsEndpoints()));
```

**(4)** `revert`：读端点行（原文 `List<Map<String, Object>> endpoints = (List<Map<String, Object>>) spec.get("endpoints"); if (endpoints != null) { dto.setEndpoints(...) }`）替换为：

```java
        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) spec.get("podMetricsEndpoints");
        if (endpoints != null) {
            dto.setPodMetricsEndpoints(endpoints.stream().map(this::fromEndpointMap).toList());
        }
```

**(5)** `MODELED_ENDPOINT_KEYS` + `convertForUpdate`/`readEndpoints`：常量替换为（含 port **与** portNumber——overlay 循环据此双向清理；含 honorLabels；**无** bearerTokenFile，使其成为被保留的外部字段）：

```java
    /** endpoints 的建模字段（overlay 时据此 set/clear；不在此列表的 key 视为外部字段，原样保留——
     *  含 bearerTokenFile/authorization/oauth2/proxy*；tlsConfig 单独深合并，故不列入）。 */
    private static final List<String> MODELED_ENDPOINT_KEYS = List.of(
            "port", "portNumber", "path", "interval", "scrapeTimeout", "scheme",
            "params", "basicAuth", "bearerTokenSecret", "honorLabels",
            "relabelings", "metricRelabelings");
```

`convertForUpdate` 内 `List<ServiceMonitorEndpointDTO> inEps = dto.getEndpoints()...` → `List<PodMonitorEndpointDTO> inEps = dto.getPodMetricsEndpoints()...`；`spec.put("endpoints", merged)` → `spec.put("podMetricsEndpoints", merged)`；`readEndpoints` 内 `((Map<String, Object>) specObj).get("endpoints")` → `.get("podMetricsEndpoints")`。方法 javadoc 里 `spec.endpoints` → `spec.podMetricsEndpoints`、「ServiceMonitor」→「PodMonitor」。

**(6)** `toEndpointMap` 中 `putStr(m, "port", e.getPort());` 一行替换为 `putPort(m, e.getPort());`；`putStr(m, "bearerTokenFile", e.getBearerTokenFile());` 一行删除；`putStr(m, "scheme", e.getScheme());` 之后加 `putBool(m, "honorLabels", e.getHonorLabels());`。并在「小工具」区新增（`putStr` 旁）：

```java
    /** 纯数字端口（1–65535）→ CRD portNumber(int)；其余（命名端口/越界数字串）→ CRD port(string)。二者只写其一。 */
    private static void putPort(Map<String, Object> m, String port) {
        if (!notBlank(port)) {
            return;
        }
        String p = port.trim();
        if (p.matches("\\d{1,5}")) {
            int n = Integer.parseInt(p);
            if (n >= 1 && n <= 65535) {
                m.put("portNumber", n);
                return;
            }
        }
        m.put("port", p);
    }
```

**(7)** `fromEndpointMap` 中 `ep.setPort(asStr(e.get("port")));` 替换为 port→portNumber 回退读取；`ep.setBearerTokenFile(...)` 行删除；`ep.setScheme(...)` 后加 honorLabels：

```java
        String named = asStr(e.get("port"));
        if (named != null && !named.isBlank()) {
            ep.setPort(named); // 命名端口优先
        } else {
            Integer num = asInt(e.get("portNumber"));
            if (num != null) {
                ep.setPort(String.valueOf(num));
            }
        }
```

```java
        ep.setHonorLabels(asBool(e.get("honorLabels")));
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -q -pl k8s-core -am test -Dtest=PodMonitorConverterTest
```
Expected: `Tests run: 8, Failures: 0` BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add k8s-core/src/main/java/com/coding/k8score/converter/impl/monitoring/PodMonitorConverter.java k8s-core/src/test/java/com/coding/k8score/converter/impl/monitoring/PodMonitorConverterTest.java
git commit -m "feat(podmonitor): converter with port/portNumber split + fetch-overlay"
```

---

### Task 3: k8s-core — PodMonitorOperations + factory 注册

**Files:**
- Create: `k8s-core/src/test/java/com/coding/k8score/operations/monitoring/PodMonitorOperationsTest.java`
- Create: `k8s-core/src/main/java/com/coding/k8score/operations/monitoring/PodMonitorOperations.java`
- Modify: `k8s-core/src/main/java/com/coding/k8score/factory/KubernetesOperationsFactory.java`（case + import）

**Interfaces:**
- Consumes: Task 2 `PodMonitorConverter`、Task 1 DTO
- Produces: `PodMonitorOperations implements NamespacedOperations<PodMonitorDTO>`（factory 供 k8s-server 反射获取）

- [ ] **Step 1: 写冒烟测试**（CRUD 流需活 apiserver，仿 `CoreV1NodeOperationsTest` 只测纯方法）

```java
package com.coding.k8score.operations.monitoring;

import com.coding.k8score.converter.impl.monitoring.PodMonitorConverter;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PodMonitorOperationsTest {

    @Test
    void apiVersion_is_monitoring_v1() {
        PodMonitorOperations ops = new PodMonitorOperations(mock(KubernetesClient.class), new PodMonitorConverter());
        assertThat(ops.apiVersion()).isEqualTo("monitoring.coreos.com/v1");
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

```bash
mvn -q -pl k8s-core -am test -Dtest=PodMonitorOperationsTest
```
Expected: COMPILATION ERROR（PodMonitorOperations 不存在）

- [ ] **Step 3: 实现 operations**——复制后定点改：

```bash
cp k8s-core/src/main/java/com/coding/k8score/operations/monitoring/ServiceMonitorOperations.java k8s-core/src/main/java/com/coding/k8score/operations/monitoring/PodMonitorOperations.java
```

对副本改：类名/类型 `ServiceMonitorOperations`→`PodMonitorOperations`、`ServiceMonitorDTO`→`PodMonitorDTO`、`ServiceMonitorConverter`→`PodMonitorConverter`（import 同步）；CRD 常量块替换为：

```java
    private static final CustomResourceDefinitionContext CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup("monitoring.coreos.com")
            .withVersion("v1")
            .withKind("PodMonitor")
            .withPlural("podmonitors")
            .withScope("Namespaced")
            .build();
```

类 javadoc 首行改 `PodMonitor 操作（monitoring.coreos.com/v1 CRD，fabric8 通用 CRD API）。`；`create`/`update` 形参名 `sm`→`pm`（局部改名，逻辑逐字保留）。

- [ ] **Step 4: factory 注册**。锚点（`KubernetesOperationsFactory.java` 现 114 行附近）：

```java
            case SERVICE_MONITOR -> new ServiceMonitorOperations(client, new ServiceMonitorConverter());
```

替换为：

```java
            case SERVICE_MONITOR -> new ServiceMonitorOperations(client, new ServiceMonitorConverter());
            case POD_MONITOR -> new PodMonitorOperations(client, new PodMonitorConverter());
```

并加 import：`com.coding.k8score.operations.monitoring.PodMonitorOperations`、`com.coding.k8score.converter.impl.monitoring.PodMonitorConverter`（对齐同包既有 import 位置）。

- [ ] **Step 5: 跑测试 + 全模块编译**

```bash
mvn -q -pl k8s-core -am test -Dtest=PodMonitorOperationsTest
mvn -q -pl k8s-server -am compile
```
Expected: 均 BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add k8s-core/src/main/java/com/coding/k8score/operations/monitoring/PodMonitorOperations.java k8s-core/src/test/java/com/coding/k8score/operations/monitoring/PodMonitorOperationsTest.java k8s-core/src/main/java/com/coding/k8score/factory/KubernetesOperationsFactory.java
git commit -m "feat(podmonitor): operations + factory registration"
```

---

### Task 4: platform-api — PodMonitorService（TDD）+ 测试基建

**Files:**
- Modify: `platform-api/pom.xml`（`</dependencies>` 前加 test 依赖）
- Create: `platform-api/src/test/java/com/coding/platformapi/services/PodMonitorServiceTest.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/PodMonitorKeyRequest.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/services/PodMonitorService.java`

**Interfaces:**
- Consumes: `K8sResourceClient.get(PodDTO)/create/update(PodMonitorDTO)`、`K8sClusterMapper.selectByPrimaryKey(String)`、`PromDiscoveryClient.scrapePools/labelSets/targetRefs/metricNames`
- Produces: `PodMonitorService.create(PodMonitorDTO)→PodMonitorDTO`、`update(...)`、`relabelLabels(String clusterId, String namespace, String name)→Map<String,List<String>>`、`metricNames(String,String,String)→List<String>`（Task 5 controller 消费）

- [ ] **Step 1: platform-api/pom.xml 加测试依赖**（在末个 `</dependency>` 与 `</dependencies>` 之间插入，同 k8s-core/pom.xml:30-34 的声明）：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: 写失败测试**

```java
package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.common.models.k8s.dto.PodMonitorDTO;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.k8s.K8sResourceClient;
import com.coding.platformapi.metrics.PromDiscoveryClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PodMonitorServiceTest {

    private final K8sResourceClient k8s = mock(K8sResourceClient.class);
    private final K8sClusterMapper clusterMapper = mock(K8sClusterMapper.class);
    private final PromDiscoveryClient prom = mock(PromDiscoveryClient.class);
    private final PodMonitorService svc = new PodMonitorService(k8s, clusterMapper, prom);

    private PodMonitorDTO base() {
        PodMonitorDTO dto = new PodMonitorDTO();
        dto.setName("pm1");
        dto.setNamespace("ns1");
        dto.setTenantId("t1");
        dto.setClusterId("c1");
        return dto;
    }

    private PodDTO podWithLabels(Map<String, String> labels) {
        PodDTO p = new PodDTO();
        p.setName("p1");
        p.setNamespace("ns1");
        p.setLabels(labels);
        return p;
    }

    @Test
    void create_resolves_podRef_labels_into_matchLabels_and_strips_podRef() {
        when(k8s.get(any(PodDTO.class))).thenReturn(podWithLabels(Map.of("app", "demo")));
        when(k8s.create(any(PodMonitorDTO.class))).thenAnswer(i -> i.getArgument(0));
        PodMonitorDTO body = base();
        body.setMatchLabels(Map.of("stale", "x"));
        PodMonitorDTO.PodRef ref = new PodMonitorDTO.PodRef();
        ref.setName("p1");
        ref.setNamespace("ns1");
        body.setPodRef(ref);

        svc.create(body);

        ArgumentCaptor<PodMonitorDTO> cap = ArgumentCaptor.forClass(PodMonitorDTO.class);
        verify(k8s).create(cap.capture());
        assertThat(cap.getValue().getMatchLabels()).containsExactly(Map.entry("app", "demo"));
        assertThat(cap.getValue().getPodRef()).isNull();
    }

    @Test
    void create_pod_missing_throws_and_no_create() {
        when(k8s.get(any(PodDTO.class))).thenReturn(null);
        PodMonitorDTO body = base();
        PodMonitorDTO.PodRef ref = new PodMonitorDTO.PodRef();
        ref.setName("gone");
        body.setPodRef(ref);

        assertThatThrownBy(() -> svc.create(body)).isInstanceOf(CloudPlatformException.class);
        verify(k8s, never()).create(any(PodMonitorDTO.class));
    }

    @Test
    void create_pod_without_labels_throws() {
        when(k8s.get(any(PodDTO.class))).thenReturn(podWithLabels(Map.of()));
        PodMonitorDTO body = base();
        PodMonitorDTO.PodRef ref = new PodMonitorDTO.PodRef();
        ref.setName("p1");
        body.setPodRef(ref);

        assertThatThrownBy(() -> svc.create(body)).isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void create_without_podRef_keeps_matchLabels_as_is() {
        when(k8s.create(any(PodMonitorDTO.class))).thenAnswer(i -> i.getArgument(0));
        PodMonitorDTO body = base();
        body.setMatchLabels(Map.of("a", "b"));

        svc.create(body);

        verify(k8s, never()).get(any(PodDTO.class));
        ArgumentCaptor<PodMonitorDTO> cap = ArgumentCaptor.forClass(PodMonitorDTO.class);
        verify(k8s).create(cap.capture());
        assertThat(cap.getValue().getMatchLabels()).containsEntry("a", "b");
    }

    @Test
    void relabelLabels_only_scans_podMonitor_prefixed_pools() {
        K8sCluster cluster = new K8sCluster();
        cluster.setPrometheusUrl("http://prom:9090/");
        when(clusterMapper.selectByPrimaryKey("c1")).thenReturn(cluster);
        when(prom.scrapePools("http://prom:9090")).thenReturn(
                List.of("podMonitor/ns1/pm1/0", "serviceMonitor/ns1/pm1/0", "podMonitor/ns1/pm1/1"));
        when(prom.labelSets("http://prom:9090", "podMonitor/ns1/pm1/0"))
                .thenReturn(new PromDiscoveryClient.TargetLabelSets(Set.of("__address__"), Set.of("app")));
        when(prom.labelSets("http://prom:9090", "podMonitor/ns1/pm1/1"))
                .thenReturn(new PromDiscoveryClient.TargetLabelSets(Set.of("node"), Set.of("pod")));

        Map<String, List<String>> out = svc.relabelLabels("c1", "ns1", "pm1");

        verify(prom, never()).labelSets(anyString(), eq("serviceMonitor/ns1/pm1/0"));
        assertThat(out.get("relabeling")).containsExactly("__address__", "node");
        assertThat(out.get("metricRelabeling")).containsExactly("app", "pod");
    }

    @Test
    void relabelLabels_prom_unreachable_degrades_to_empty() {
        K8sCluster cluster = new K8sCluster();
        cluster.setPrometheusUrl("http://prom:9090");
        when(clusterMapper.selectByPrimaryKey("c1")).thenReturn(cluster);
        when(prom.scrapePools(anyString())).thenThrow(new RuntimeException("down"));

        Map<String, List<String>> out = svc.relabelLabels("c1", "ns1", "pm1");
        assertThat(out.get("relabeling")).isEmpty();
        assertThat(out.get("metricRelabeling")).isEmpty();
    }

    @Test
    void metricNames_builds_matchers_only_for_podMonitor_pools() {
        K8sCluster cluster = new K8sCluster();
        cluster.setPrometheusUrl("http://prom:9090");
        when(clusterMapper.selectByPrimaryKey("c1")).thenReturn(cluster);
        when(prom.scrapePools("http://prom:9090")).thenReturn(List.of("podMonitor/ns1/pm1/0"));
        when(prom.targetRefs("http://prom:9090", "podMonitor/ns1/pm1/0"))
                .thenReturn(List.of(new PromDiscoveryClient.TargetRef("j\\1", "inst")));
        when(prom.metricNames(eq("http://prom:9090"), anyList())).thenReturn(List.of("up"));

        assertThat(svc.metricNames("c1", "ns1", "pm1")).containsExactly("up");
        ArgumentCaptor<List<String>> mm = ArgumentCaptor.forClass(List.class);
        verify(prom).metricNames(eq("http://prom:9090"), mm.capture());
        assertThat(mm.getValue()).containsExactly("{job=\"j\\\\1\",instance=\"inst\"}");
    }
}
```

注：`K8sCluster` 的 setter 名以 `platform-data` 实体实际为准（`getPrometheusUrl()` 已在 ServiceMonitorService 使用；setter 若不同名，按实体生成器输出调整，仅测试内一处）。

- [ ] **Step 3: 跑测试确认编译失败**

```bash
mvn -q -pl platform-api -am test -Dtest=PodMonitorServiceTest
```
Expected: COMPILATION ERROR（PodMonitorService / PodMonitorKeyRequest 不存在）

- [ ] **Step 4: 写 `PodMonitorKeyRequest.java`**

```java
package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "PodMonitor 定位：集群 + 命名空间 + 名称（Prometheus discovery 查询用）")
public class PodMonitorKeyRequest {

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "命名空间名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String namespace;

    @Schema(description = "PodMonitor 名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;
}
```

- [ ] **Step 5: 写 `PodMonitorService.java`**——复制后定点改：

```bash
cp platform-api/src/main/java/com/coding/platformapi/services/ServiceMonitorService.java platform-api/src/main/java/com/coding/platformapi/services/PodMonitorService.java
```

对副本改：类名/类型名全量替换（`ServiceMonitorService`→`PodMonitorService`、`ServiceMonitorDTO`→`PodMonitorDTO`）；import `ServiceDTO`→`PodDTO`。三处语义改动：

**(a)** `resolveSelector` + `resolveFromService` 整块（原文 53–78 行两方法）替换为：

```java
    /**
     * 解析目标 Pod 绑定：有 podRef（name + namespace）→ 反查该 Pod 的 labels 覆盖 body.matchLabels；
     * 无 podRef → matchLabels 原样保留（编辑回填 / 未改动）。podRef 为平台侧字段，解析后剥离、绝不下发 k8s-server / 不落库。
     * 语义：选择器反映「创建时所选 Pod 的 labels」——同一工作负载的 Pod 共享模板 labels，故通常可泛化到该工作负载全部 Pod。
     */
    private void resolveSelector(PodMonitorDTO body) {
        PodMonitorDTO.PodRef ref = body.getPodRef();
        body.setPodRef(null); // 无论是否解析都剥离
        if (ref == null || !StringUtils.hasText(ref.getName())) {
            return;
        }
        body.setMatchLabels(resolveFromPod(body, ref.getName(), ref.getNamespace()));
    }

    /** 绑定 Pod：取该 Pod 的 metadata.labels 作为选择器；无 labels 则无法生成 */
    private Map<String, String> resolveFromPod(PodMonitorDTO ctx, String name, String namespace) {
        PodDTO pq = new PodDTO();
        pq.setTenantId(ctx.getTenantId());
        pq.setClusterId(ctx.getClusterId());
        pq.setNamespace(StringUtils.hasText(namespace) ? namespace : ctx.getNamespace());
        pq.setName(name);
        PodDTO pod = k8s.get(pq);
        if (pod == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "Pod「" + name + "」不存在");
        }
        Map<String, String> labels = pod.getLabels();
        if (labels == null || labels.isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "Pod「" + name + "」无 labels，无法生成选择器");
        }
        return new LinkedHashMap<>(labels);
    }
```

**(b)** 两处 pool 前缀（`relabelLabels` 与 `metricNames` 各一处，原文 `String prefix = "serviceMonitor/" + namespace + "/" + name + "/";`）替换为：

```java
            String prefix = "podMonitor/" + namespace + "/" + name + "/";
```

**(c)** javadoc/日志措辞：类头 javadoc、方法 javadoc 与两处 `log.warn("拉取 ServiceMonitor ...` 中「ServiceMonitor」→「PodMonitor」、`sm=` → `pm=`。其余逻辑（emptyLabelSets / toMatcher / escape / create / update）逐字保留。

- [ ] **Step 6: 跑测试确认通过**

```bash
mvn -q -pl platform-api -am test -Dtest=PodMonitorServiceTest
```
Expected: `Tests run: 7, Failures: 0` BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add platform-api/pom.xml platform-api/src/main/java/com/coding/platformapi/models/PodMonitorKeyRequest.java platform-api/src/main/java/com/coding/platformapi/services/PodMonitorService.java platform-api/src/test/java/com/coding/platformapi/services/PodMonitorServiceTest.java
git commit -m "feat(podmonitor): service layer (podRef resolve + discovery prefix) with unit tests"
```

---

### Task 5: 双 controller — platform-api 资源面 + k8s-server 边界

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/controllers/resource/PodMonitorController.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/controllers/namespace/PodMonitorController.java`

**Interfaces:**
- Consumes: Task 4 `PodMonitorService`、`PodMonitorKeyRequest`；Task 1 DTO/枚举
- Produces: HTTP `/resource/podmonitors/**`（platform-api，Task 8/9 前端消费）；`/resources/podmonitors/**`（k8s-server 边界）

- [ ] **Step 1: 写 platform-api controller**——复制后定点改（**无新逻辑**，端点集与 SM 完全一致）：

```bash
cp platform-api/src/main/java/com/coding/platformapi/controllers/resource/ServiceMonitorController.java platform-api/src/main/java/com/coding/platformapi/controllers/resource/PodMonitorController.java
```

改：类名 `ServiceMonitorController`→`PodMonitorController`、类型/变量 `ServiceMonitorDTO`→`PodMonitorDTO`、`smService`→`pmService`、`ServiceMonitorKeyRequest`→`PodMonitorKeyRequest`（import 同步）；`@RequestMapping("/resource/servicemonitors")`→`@RequestMapping("/resource/podmonitors")`；`@Tag(name = "资源管理-ServiceMonitor", description = "命名空间内 ServiceMonitor")`→`@Tag(name = "资源管理-PodMonitor", description = "命名空间内 PodMonitor")`；类 javadoc 与 `@Operation` summary 中「ServiceMonitor」→「PodMonitor」、「目标 Service」→「目标 Pod」；`create` 内注释 `serviceRef`→`podRef`。

- [ ] **Step 2: 写 k8s-server 边界 controller**——复制后定点改：

```bash
cp k8s-server/src/main/java/com/coding/k8sserver/controllers/namespace/ServiceMonitorController.java k8s-server/src/main/java/com/coding/k8sserver/controllers/namespace/PodMonitorController.java
```

改（整文件语义即 4 处）：类名/构造器名→`PodMonitorController`、`ServiceMonitorDTO`→`PodMonitorDTO`、`ResourceType.SERVICE_MONITOR`→`ResourceType.POD_MONITOR`、`@RequestMapping("/resources/servicemonitors")`→`@RequestMapping("/resources/podmonitors")`、两处 `@Tag` 文案「ServiceMonitor」→「PodMonitor」+ import 同步。**零业务逻辑**（基类已含双模访问+分配表边界）。

- [ ] **Step 3: 编译两模块**

```bash
mvn -q -pl platform-api -am compile
mvn -q -pl k8s-server -am compile
```
Expected: 均 BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/controllers/resource/PodMonitorController.java k8s-server/src/main/java/com/coding/k8sserver/controllers/namespace/PodMonitorController.java
git commit -m "feat(podmonitor): platform-api + k8s-server boundary controllers"
```

---

### Task 6: 前端基础设施 — types / api / 菜单（router 在 Task 8）

**Files:**
- Modify: `platform-web/src/types.ts`（在 `K8sServiceMonitor` 接口结束处、`/** HPA 指标目标...` 之前插入）
- Modify: `platform-web/src/api/index.ts`（在 `serviceMonitorRelabelApi` 块之后、`/** HPA ...` 之前插入）
- Modify: `platform-web/src/layouts/MainLayout.vue`（现 164–167 行 ServiceMonitor 菜单项之后）

**Interfaces:**
- Produces: `K8sPodMonitor/K8sPmEndpoint/K8sPmMatchExpression/K8sPmRelabeling/K8sPmSecretRef`、`podMonitorApi`、`podMonitorRelabelApi`（Task 7/8 消费）

- [ ] **Step 1: types.ts 追加**（注意 `K8sPmSecretRef` 被导出接口引用，一并 `export`）

```ts
/** PodMonitor endpoint Secret 引用（与后端 PodMonitorEndpointDTO.SecretRef 同构） */
export interface K8sPmSecretRef { name?: string | null; key?: string | null }

/** PodMonitor Relabeling/MetricRelabeling 单条规则（与后端 PodMonitorEndpointDTO.Relabeling 同构） */
export interface K8sPmRelabeling {
  sourceLabels?: string[] | null
  targetLabel?: string | null
  regex?: string | null
  replacement?: string | null
  separator?: string | null
  modulus?: number | null
  /** replace / keep / drop / hashmod / labelmap / labeldrop / labelkeep */
  action?: string | null
}

/** PodMonitor 抓取端点（spec.podMetricsEndpoints[] 项；port 数字/名字由后端拆 portNumber） */
export interface K8sPmEndpoint {
  /** 端口名或端口号（单一字段） */
  port?: string | null
  path?: string | null
  interval?: string | null
  scrapeTimeout?: string | null
  /** http / https */
  scheme?: string | null
  params?: Record<string, string[]> | null
  basicAuth?: { username?: K8sPmSecretRef | null; password?: K8sPmSecretRef | null } | null
  bearerTokenSecret?: K8sPmSecretRef | null
  tlsConfig?: { insecureSkipVerify?: boolean | null; serverName?: string | null } | null
  /** 标签冲突时以指标自带标签为准（PodMonitor 特有建模） */
  honorLabels?: boolean | null
  relabelings?: K8sPmRelabeling[] | null
  metricRelabelings?: K8sPmRelabeling[] | null
}

/** PodMonitor selector 表达式（spec.selector.matchExpressions[] 项） */
export interface K8sPmMatchExpression {
  key?: string | null
  /** In / NotIn / Exists / DoesNotExist */
  operator?: string | null
  values?: string[] | null
}

/** PodMonitor（monitoring.coreos.com/v1 CRD） */
export interface K8sPodMonitor {
  name: string
  namespace: string
  labels?: Record<string, string> | null
  /** spec.selector.matchLabels（必填）：由后端据 podRef 解析填充；查询时原样返回 */
  matchLabels?: Record<string, string> | null
  matchExpressions?: K8sPmMatchExpression[] | null
  /** 绑定的目标 Pod（平台侧）：选择后据此反查 labels 生成 matchLabels；非 K8s 字段、不落库 */
  podRef?: { name: string; namespace: string } | null
  namespaceSelector?: { any?: boolean | null; matchNames?: string[] | null } | null
  jobLabel?: string | null
  podTargetLabels?: string[] | null
  sampleLimit?: number | null
  targetLimit?: number | null
  labelLimit?: number | null
  bodySizeLimit?: string | null
  attachMetadata?: { node?: boolean | null } | null
  /** spec.podMetricsEndpoints（必填；注意非 ServiceMonitor 的 endpoints） */
  podMetricsEndpoints?: K8sPmEndpoint[] | null
  creationTime?: string | null
}
```

（`K8sPmSecretRef` 若不导出会被 `--isolatedModules`/vue-tsc 报未使用？——它被导出接口引用，必须一并导出：改为 `export interface K8sPmSecretRef`。）

- [ ] **Step 2: api/index.ts 追加**（置于 `serviceMonitorRelabelApi` 定义之后）

```ts
/** PodMonitor /resource/podmonitors（CRD，集群未装 Prometheus Operator 时透传错误） */
export const podMonitorApi = makeResourceApi<K8sPodMonitor>('podmonitors')

/** PodMonitor Prometheus discovery 定位（对应后端 PodMonitorKeyRequest：clusterId + namespace + name） */
type PmDiscoveryCtx = { clusterId: string; namespace: string; name: string }

/** PodMonitor Relabeling/MetricRelabeling 的 sourceLabels 候选（编辑态；pool 前缀 podMonitor/{ns}/{name}/，不可达返回空） */
export const podMonitorRelabelApi = {
  labels: (ctx: PmDiscoveryCtx) =>
    http.post<never, { relabeling: string[]; metricRelabeling: string[] }>('/resource/podmonitors/relabel-labels', ctx),
  /** MetricRelabeling regex 的 __name__ 候选：scoped 到本 PM 活跃 target；无 target/不可达返回空 */
  metricNames: (ctx: PmDiscoveryCtx) =>
    http.post<never, string[]>('/resource/podmonitors/metric-names', ctx),
}
```

并在文件顶部 types import 列表加 `K8sPodMonitor`（该 import 为具名列表，如 `K8sServiceMonitor,` 行旁加一行）。

- [ ] **Step 3: MainLayout.vue 追加菜单**（锚点：ServiceMonitor 的 `el-menu-item` 结束标签之后。**本任务不动 router**——路由会指向尚不存在的 .vue 文件，导致 type-check 红灯，故并入 Task 8 Step 8）

```html
        <el-menu-item index="/resources/podmonitors">
          <el-icon><DataLine /></el-icon>
          <template #title>PodMonitor</template>
        </el-menu-item>
```

（图标复用已 import 的 `DataLine`，不新增图标 import。）

- [ ] **Step 4: 类型门控**

```bash
npm --prefix platform-web run type-check
```
Expected: 通过（types/api 未被引用也合法；菜单 index 指向未注册路由不报错——vue-router 不校验字符串）

- [ ] **Step 5: Commit**

```bash
git add platform-web/src/types.ts platform-web/src/api/index.ts platform-web/src/layouts/MainLayout.vue
git commit -m "feat(podmonitor): frontend types, api client, sidebar menu"
```

---

### Task 7: 前端列表页 PodMonitorView.vue

**Files:**
- Create: `platform-web/src/views/resource/PodMonitorView.vue`（复制 ServiceMonitorView.vue 后按下表改）

**Interfaces:**
- Consumes: `podMonitorApi`、`K8sPodMonitor`（Task 6）
- Produces: 页面组件（Task 8 的 router 引用）

- [ ] **Step 1: 复制底稿**

```bash
cp platform-web/src/views/resource/ServiceMonitorView.vue platform-web/src/views/resource/PodMonitorView.vue
```

- [ ] **Step 2: 对副本逐处修改**（锚点→替换；其余逐字不动）

| # | 锚点（原文） | 改为 |
|---|---|---|
| 1 | `import { serviceMonitorApi } from '@/api'` | `import { podMonitorApi } from '@/api'` |
| 2 | `import type { K8sServiceMonitor } from '@/types'` | `import type { K8sPodMonitor } from '@/types'` |
| 3 | 全文 7 处 `K8sServiceMonitor`（list ref、goEdit、onDelete、openDetail 参数等） | `K8sPodMonitor` |
| 4 | `list.value = await serviceMonitorApi.list(ctxParams.value)` | `podMonitorApi.list(...)` |
| 5 | `router.push({ name: 'servicemonitor-editor' })` 与 `{ name: 'servicemonitor-editor', query: ... }` | `'podmonitor-editor'`（两处） |
| 6 | `` `确认删除 ServiceMonitor「${row.name}」？...` `` | `` `确认删除 PodMonitor「${row.name}」？Prometheus 将停止抓取对应目标。` `` |
| 7 | `await serviceMonitorApi.delete(...)` / `getYaml(...)` | `podMonitorApi.delete(...)` / `podMonitorApi.getYaml(...)` |
| 8 | `<PageHeader title="ServiceMonitor" ...>` 与按钮 `创建 ServiceMonitor` | `PodMonitor` / `创建 PodMonitor` |
| 9 | EmptyState `该命名空间下暂无 ServiceMonitor` 及描述行 | `该命名空间下暂无 PodMonitor`；描述改 `PodMonitor 由 Prometheus Operator 消费（monitoring.coreos.com/v1 CRD），直接抓取无 Service 的 Pod；集群未安装时操作会提示错误。` |
| 10 | 端点列 `{{ (row.endpoints ?? []).length }} 个` | `{{ (row.podMetricsEndpoints ?? []).length }} 个` |
| 11 | 抽屉标题 `` `ServiceMonitor · ${detail?.name ?? ''}` `` | `` `PodMonitor · ${detail?.name ?? ''}` `` |
| 12 | 抽屉端点表 `:data="detail?.endpoints ?? []"` | `:data="detail?.podMetricsEndpoints ?? []"` |
| 13 | 文件/组件注释「ServiceMonitor」 | 「PodMonitor」 |

- [ ] **Step 3: 类型门控**——此时 router 尚未引用本文件，跑既有检查确认新文件自身合法：

```bash
npm --prefix platform-web run type-check
```
Expected: 通过

- [ ] **Step 4: Commit**

```bash
git add platform-web/src/views/resource/PodMonitorView.vue
git commit -m "feat(podmonitor): list view (mirror ServiceMonitorView)"
```

---

### Task 8: 前端编辑页 PodMonitorEditorView.vue + 路由注册

**Files:**
- Create: `platform-web/src/views/resource/PodMonitorEditorView.vue`（复制 1176 行底稿后逐处改）
- Modify: `platform-web/src/router/index.ts`（Task 6 遗留的 router 步骤在此完成）

**Interfaces:**
- Consumes: `podMonitorApi/podMonitorRelabelApi/podApi`、`K8sPod/K8sPodMonitor/K8sPm*`、`ContainerPort`（`@/types/workload`）
- Produces: 路由 `podmonitor-editor` 目标组件

- [ ] **Step 1: 复制底稿**

```bash
cp platform-web/src/views/resource/ServiceMonitorEditorView.vue platform-web/src/views/resource/PodMonitorEditorView.vue
```

- [ ] **Step 2: 类型/导入层修改**

L5 原文：

```ts
import { serviceMonitorApi, serviceApi, serviceMonitorRelabelApi } from '@/api'
```
→

```ts
import { podMonitorApi, podApi, podMonitorRelabelApi } from '@/api'
```

L6 原文：

```ts
import type { K8sService, K8sServiceMonitor, K8sServicePort, K8sSmEndpoint, K8sSmMatchExpression, K8sSmRelabeling } from '@/types'
```
→

```ts
import type { K8sPod, K8sPodMonitor, K8sPmEndpoint, K8sPmMatchExpression, K8sPmRelabeling } from '@/types'
import type { ContainerPort } from '@/types/workload'
```

全文类型替换：`K8sSmRelabeling`→`K8sPmRelabeling`（fromRelabeling 参数、assembleRelabelings 局部与返回）、`K8sSmEndpoint`→`K8sPmEndpoint`（fromEndpoint、assembleEndpoint、`NonNullable<K8sSmEndpoint['basicAuth']>`→`NonNullable<K8sPmEndpoint['basicAuth']>`、`['tlsConfig']` 同）、`K8sServiceMonitor`→`K8sPodMonitor`（submit body）、`K8sSmMatchExpression`→`K8sPmMatchExpression`。

- [ ] **Step 3: EndpointRow 增删**（差异：去 bearerTokenFile、加 honorLabels）

L66 行 `  bearerTokenFile: string // 透传保留（UI 不编辑），避免 atomic list 整体替换时丢失` **删除**；其下加 `  honorLabels: boolean // 标签冲突时以指标自带标签为准（PodMonitor 特有）`。
L82 `newEndpoint` 返回对象中 `bearerTokenFile: '',` 删除、加 `honorLabels: false,`。

- [ ] **Step 4: form 模型改名**

L95–96：`matchLabels` 注释「后端据 serviceRef」→「后端据 podRef」；`serviceRef` 行整体：

```ts
  podRef: null as { name: string; namespace: string } | null, // 选择的目标 Pod（覆盖选择器）
```

L97 `matchExpressions` 注释「选 Service」→「选 Pod」；L106：

```ts
  podMetricsEndpoints: [newEndpoint()] as EndpointRow[],
```

- [ ] **Step 5: Pod 下拉与端口选项**（替换 L109–156 的 Service 加载块）。原 `// ---------- 目标 Service 下拉...` 至 watch 行为止，改为：

```ts
// ---------- 目标 Pod 下拉：按命名空间集合查询 Pod 列表，供选择后由后端解析成选择器 ----------
const podOptions = ref<{ name: string; namespace: string; ports: ContainerPort[] }[]>([])
const podLoading = ref(false)

/** 需查询的命名空间：选了 matchNames 就查那些（多命名空间），否则仅本命名空间 */
const targetNamespaces = computed<string[]>(() => {
  const ns = cleanList(form.nsMatchNames)
  return ns.length ? ns : [state.namespace!]
})

async function loadPods(): Promise<void> {
  if (!ready.value) return
  podLoading.value = true
  try {
    const lists = await Promise.all(
      targetNamespaces.value.map((ns) =>
        podApi.list({ tenantId: state.tenantId!, clusterId: state.clusterId!, namespace: ns }).catch(() => [] as K8sPod[]),
      ),
    )
    podOptions.value = lists.flat().map((p) => ({
      name: p.name,
      namespace: p.namespace,
      // 常规容器的声明端口（init 容器的端口不作为抓取目标）
      ports: (p.containerDetails ?? []).filter((c) => c.init !== true).flatMap((c) => c.ports ?? []),
    }))
  } finally {
    podLoading.value = false
  }
}

// el-select 绑定值：`namespace/name`
const podSelectValue = computed<string | ''>({
  get: () => (form.podRef ? `${form.podRef.namespace}/${form.podRef.name}` : ''),
  set: (v) => {
    if (!v) { form.podRef = null; return }
    const idx = v.indexOf('/')
    form.podRef = { namespace: v.slice(0, idx), name: v.slice(idx + 1) }
  },
})

/** 当前所选 Pod 的容器端口（供端点 Port 下拉）：有名字用名字，否则用端口号 */
const portOptions = computed(() => {
  const ref = form.podRef
  if (!ref) return [] as { value: string; label: string }[]
  const pod = podOptions.value.find((p) => p.name === ref.name && p.namespace === ref.namespace)
  return (pod?.ports ?? []).map((p) => ({
    value: p.name ? String(p.name) : String(p.containerPort),
    label: p.name ? `${p.name}（${p.containerPort}/${p.protocol ?? 'TCP'}）` : `${p.containerPort}/${p.protocol ?? 'TCP'}`,
  }))
})

// 上下文就绪或命名空间集合变化时重新拉取 Pod 列表（immediate：SPA 内跳转时 ready 可能已为 true）
watch([() => ready.value, () => targetNamespaces.value.join('|')], ([r]) => { if (r) void loadPods() }, { immediate: true })
```

注：`cleanList` 在文件后面才定义（原文件同样如此——函数声明提升，`const cleanList` 不行）。**原文件 L115 已引用 `cleanList`（L303 `function cleanList` 为函数声明、提升合法），保持 `function` 定义不动即可。**

- [ ] **Step 6: 回填/组装/提交层**

`fromEndpoint`（L190–220）：`base.bearerTokenFile = e.bearerTokenFile ?? ''` 删除；`base.port = e.port ?? ''` 之后加 `base.honorLabels = e.honorLabels === true`。

`loadDetail`（L222–250）：`serviceMonitorApi.get`→`podMonitorApi.get`；`form.serviceRef = null // ...重新选择 Service 才覆盖`→`form.podRef = null // 编辑时仅回显选择器；重新选择 Pod 才覆盖`；`const eps = (d.endpoints ?? [])...`→`const eps = (d.podMetricsEndpoints ?? [])...`；`form.endpoints = eps.length ? eps : [newEndpoint()]`→`form.podMetricsEndpoints = ...`。

`loadDiscoveryLabels`/`loadMetricNames`（L263/L276）：`serviceMonitorRelabelApi`→`podMonitorRelabelApi`（各一处），注释「本 SM」→「本 PodMonitor」。

`REPLACE_TOKENS`（L311–317）：`{ token: 'monitor', label: 'ServiceMonitor 名', ... }`→`label: 'PodMonitor 名'`；`{ token: 'service', label: '目标 Service 名（需已绑定）', ph: '{{service}}', resolve: () => form.serviceRef?.name ?? '' }`→`{ token: 'pod', label: '目标 Pod 名（需已绑定）', ph: '{{pod}}', resolve: () => form.podRef?.name ?? '' }`。

`RELABEL_REGEX_PRESETS`：`__meta_kubernetes_pod_label_(.*)` 条目保留、desc「Pod 自定义 label」不动（对 PodMonitor 正是主用法）；`__meta_kubernetes_service_label_(.*)` 的 desc 改为「Service label（Pod 发现也带）」（一行改动）。

`assembleEndpoint`（L400–437）：`if (e.bearerTokenFile.trim()) ep.bearerTokenFile = e.bearerTokenFile.trim()` 删除；tls 块之后加：

```ts
  if (e.honorLabels) ep.honorLabels = true
```

`submit`（L439–500）：
- L446–447：错误文案「请选择要抓取的目标 Service」→「请选择要抓取的目标 Pod」。
- L449–454 循环：`form.endpoints`→`form.podMetricsEndpoints`（两处），提示「至少需要一个抓取端点（spec.endpoints，Port 必填）」→「（spec.podMetricsEndpoints，Port 必填）」。
- L456：`const body: K8sServiceMonitor = { name, namespace: state.namespace!, labels: form.labels, matchLabels: form.matchLabels, endpoints }` → `const body: K8sPodMonitor = { name, namespace: state.namespace!, labels: form.labels, matchLabels: form.matchLabels, podMetricsEndpoints: endpoints }`。
- L457：`body.serviceRef`→`body.podRef`（两处：条件与赋值）。
- L488/L491：`serviceMonitorApi.update/create`→`podMonitorApi.update/create`；L494/L502 `'/resources/servicemonitors'`→`'/resources/podmonitors'`。
- L504：pageTitle `'编辑/创建 ServiceMonitor'`→`'编辑/创建 PodMonitor'`。

- [ ] **Step 7: 模板层**（区块结构与 SM 一致，全部按 Pod 语义改写）

| 锚点（原文） | 改为 |
|---|---|
| L517 EmptyState「再创建或编辑 ServiceMonitor。」 | 「再创建或编辑 PodMonitor。」 |
| L523 `` `ServiceMonitor · ${form.name}` `` | `` `PodMonitor · ${form.name}` `` |
| L543–552 目标 Service 表单项（label「目标 Service」、FieldHelp、`svcSelectValue`、`:loading="svcLoading"`、placeholder、`v-for="s in svcOptions"` 的 option 块） | 全部换 Pod 版：label「目标 Pod」；FieldHelp tip「选择要抓取的目标 Pod；后端据其 labels 自动生成选择器（spec.selector）。编辑时重新选择即可覆盖。」；`v-model="podSelectValue"`、`:loading="podLoading"`、placeholder「选择目标 Pod（必填）」、`v-for="p in podOptions"` `:key="\`p.namespace/p.name\`"` `:label="targetNamespaces.length > 1 ? \`\${p.name} · \${p.namespace}\` : p.name"` `:value="\`\${p.namespace}/\${p.name}\`"` |
| L555 选择器 FieldHelp「由所选 Service 的 labels 决定…重新选择 Service…」 | 「由所选 Pod 的 labels 决定，不可手改；重新选择 Pod 才会覆盖。」 |
| L559–560 `form.serviceRef?.name` 分支与「尚未选择（创建时必选目标 Service）」 | `form.podRef?.name`；「将使用 Pod「…」的 labels 生成」；「尚未选择（创建时必选目标 Pod）」 |
| L563 matchExpressions FieldHelp「选 Service」 | 「选 Pod」 |
| L589 卡片标题 `抓取端点（endpoints）` | `抓取端点（podMetricsEndpoints）` |
| L590/593/859 `form.endpoints`（v-for、splice、push） | `form.podMetricsEndpoints`（三处） |
| L605 Port FieldHelp「目标 Service 的端口…从所选 Service 选择」 | 「目标 Pod 的容器端口（名字或端口号）；从所选 Pod 选择，也可手输。提交时纯数字→portNumber，命名→port。」 |
| L606 placeholder 三元 `form.serviceRef ? '选择端口' : '先选择目标 Service'` | `form.podRef ? '选择端口' : '先选择目标 Pod'` |
| L645 折叠项 `title="鉴权 / TLS"` | `title="鉴权 / TLS / 标签行为"` |
| L668–678 TLS 模板块**之后**（仍在该 collapse-item 内）插入 honorLabels 开关 | 见下方代码块 |
| L867 podTargetLabels FieldHelp「从目标 Service 的标签…」 | 「从目标 Pod 的标签透传到抓取目标的 label 列表。」 |

honorLabels 插入块（L678 `</template>` 之后）：

```html
                  <label class="sw"><el-switch v-model="e.honorLabels" /> honorLabels <FieldHelp tip="抓取目标自带标签与指标样本同名标签冲突时：开=以指标自带标签为准（丢弃抓取目标标签）；关（默认）=以抓取目标标签为准。" /></label>
```

- [ ] **Step 8: router 注册**（Task 6 遗留；锚点：第 40 行 servicemonitor-editor 路由行之后）

```ts
        { path: 'resources/podmonitors', name: 'podmonitors', component: () => import('@/views/resource/PodMonitorView.vue'), meta: { title: 'PodMonitor', group: '资源管理', context: 'full' } },
        { path: 'resources/podmonitors/editor', name: 'podmonitor-editor', component: () => import('@/views/resource/PodMonitorEditorView.vue'), meta: { title: 'PodMonitor 编辑', group: '资源管理', context: 'full' } },
```

- [ ] **Step 9: 类型门控 + 构建**

```bash
npm --prefix platform-web run type-check
```
Expected: 通过（无 vue-tsc 错误）

- [ ] **Step 10: Commit**

```bash
git add platform-web/src/views/resource/PodMonitorEditorView.vue platform-web/src/router/index.ts
git commit -m "feat(podmonitor): editor page (pod target + honorLabels) + routes"
```

---

### Task 9: 全量验证 + 后端重启联调

**Files:** 无新增（验证轮）

- [ ] **Step 1: 后端全量编译 + 全部单测**

```bash
mvn -q -T1C compile
mvn -q -pl k8s-core,platform-api -am test
```
Expected: BUILD SUCCESS，测试全绿（含既有 NodeConverter/Quantity 等测试不回归）

- [ ] **Step 2: 前端全量构建**

```bash
npm --prefix platform-web run build
```
Expected: 成功（type-check + vite build）

- [ ] **Step 3: 人工 E2E 清单**（需活集群 + Prometheus Operator；对照 spec §9）
1. UI 创建：选目标 Pod + 端口 → `kubectl get podmonitor -o yaml` 核 `spec.selector.matchLabels`=该 Pod labels、`spec.podMetricsEndpoints[0].portNumber`（数字端口）或 `port`（命名端口）。
2. 编辑存量带 `authorization`/`proxyUrl`/`bearerTokenFile`/`tlsConfig.ca` 的 PodMonitor（`kubectl edit` 手工塞入）→ 平台改 path 保存 → 上述未建模字段仍在。
3. 列表 / 详情抽屉（端点数、YAML tab）/ 删除全通；未装 Operator 的集群上打开页面 → 拦截器提示、列表空。
4. Prometheus 可达：编辑态 Relabeling sourceLabels 候选只来自 `podMonitor/<ns>/<name>/` pool（可用 `curl $PROM/api/v1/scrape_pools` 对照前缀）；MetricRelabeling regex 的指标名候选非空。
5. honorLabels 开关：创建开→CRD 见 `honorLabels: true`；编辑关→字段消失。
6. 目标 Pod 无 labels（如裸 `kubectl run` 不带标签）→ 创建被拒、错误中文。
7. 与 ServiceMonitor 并存：同 ns 各建一个，列表/编辑互不串数据。

任一不过：回读对应任务修复，重跑该任务测试后再回本清单。

- [ ] **Step 4: 收尾提交**（若 Step 3 暴露小修）

```bash
git add -u
git commit -m "fix(podmonitor): integration polish from manual E2E"
```

（无改动则跳过。）

---

## Self-Review 记录（已执行）

1. **Spec 覆盖**：§4.1/4.2 DTO→T1；§3 port 拆分/键名/honorLabels/overlay→T2；§5.1 operations+factory→T3；§5.2 platform-common（枚举）→T1；§5.3 platform-api→T4/T5；§5.4 边界→T5；§6.1 resolveSelector、§6.2 discovery 前缀→T4；§7.1/7.2/7.3 前端→T6/T7/T8；§8 边界（404 透传/Prometheus 降级/pod 无 labels）→T4 测试 + T7 catch 保留；§9 验收→T9 清单逐条映射。无缺口。
2. **占位符扫描**：无 TBD/「类似 Task N」；所有复制+定点改均给出锚点原文与完整替换体。
3. **类型一致性**：`podMetricsEndpoints`/`podRef`/`honorLabels`/`POD_MONITOR`/路由 name 在各任务间已对表核对（Task1↔2↔4↔6↔8）；`podMonitorRelabelApi.labels/metricNames` 与 T4 controller 端点路径一致；T6 Step3 的 router 已挪入 T8 避免红灯。
