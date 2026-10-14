# CI（Tekton）第 ① 片 —— 定义 · 运行 · 日志 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让租户在平台里连出一条自由 DAG 流水线、手动触发它跑在自己的命名空间里，跑完能看到每一步的状态与日志；集群里不留 `Completed` Pod，记录与日志进平台数据库。

**Architecture:** 五层沿用本仓既有形状，一行新范式都不发明。**k8s-core** 放纯逻辑（节点模型 + 渲染器，均可单测）与 `PipelineRunOperations`（用 `io.fabric8:tekton-client`，走 SSA 通道）；**k8s-server** 只加一个继承 `AbstractNamespacedResourceController` 的控制器（6 个标准端点白送）加三个非标准动作（cancel / watch / 扫描）；**platform-api** 放业务（画布校验、渲染编排、归档、保留清理）与审计；**platform-web** 加画布与运行页。归档由 **k8s-server 侧的按需 watch** 驱动，回调 platform-api 完成「抓日志 → 落库 → 删 PipelineRun」。

**Tech Stack:** Spring Boot 4.1.1 / Spring Framework 7 / Java 21 / MyBatis / MySQL / `io.fabric8:tekton-client:7.9.0`（与现有 `kubernetes-client:7.9.0` 同步发版）/ Vue 3 + Element Plus + TypeScript / `@vue-flow/core`

**Spec:** `docs/superpowers/specs/2026-10-15-ci-tekton-design.md`

## Global Constraints

- **fabric8 版本**：`tekton-client` 必须用 **`${fabric8.kubernetes-client.version}`（当前 7.9.0）**，与 `kubernetes-client` 同一版本号 —— 两者是同步发版的，不要另写版本。
- **不加外键**（本仓既定原则）。所有级联删除（run → 日志行、命名空间 → 流水线）靠代码，不靠 FK。
- **权限码必须是 `tenant:` 前缀** —— `RoleService.assertScopeMatches` 规定 TENANT 角色只能持 `tenant:` 码。
- **同一 code 对应多行是既有的 ANY-of 约定**（`RoleService`：「权限点 code 自 Task 4 起不再唯一」）—— 勾一个 code 后端会落该 code 的**全部**行。
- **`permission_id` 必须与 `platform_permission.id` 逐字一致**。本仓无外键，写错只留一条**悬空关联**（码在库里、角色没有它、菜单不显示、日志安静）。S0.2 踩过一次。**重放后必须做 join 对账，不能只数行数。**
- **MySQL 注释 `--` 后必须跟空白字符**，否则整个文件重放失败。
- **本仓没有 Flyway**，`db/migration/*.sql` 一律**手工重放**；启动日志永远没有 `Migrating schema`。
- **零新增配置项是不成立的**：`platform.pipeline.*` 有默认值，但 **`internal-token` 没有**，必须配。
- **前端收口一律用 `npm run build`**，不用 `npx vue-tsc --noEmit` —— S3.1 与 Docker Hub 两批都实测过：前者会**零报错**放过 `error TS2532` / `TS2345`。
- **出站地址只由凭据的 endpoint（或方言写死的官方地址）拼**；用户输入只用于匹配。Tekton 的 `PipelineRun` 是**集群内对象**，不经出站。
- 全量测试基线：**platform-common 19 · k8s-core 215 · k8s-server 20 · platform-api 490 = 744**。本批只增不减。
- ⚠️ `k8s-core` 的 215 依赖 `WorkloadConverterStaticIpTest.java` **正被有意删在工作区**（@HEAD 即 NPE，非本批引入，**不要提交这个删除**）。

## Review Focus

以下五类输入/条件，spec 隐含要求但没有任何现成的测试盖住。每条都已指派到拥有该代码的任务（见括注）：

1. **画布里连出了环**（A→B→A）—— 保存必须被拒（前端实时提示 + **后端也拒**），绝不能把一个环渲染成 Tekton 对象交给集群。**（Task 2）**
2. **同一个 run 被回调两次**（k8s-server 重投 / 平台重启后补投）—— 库里只应有一条记录，**但集群对象必须仍然尝试删**（「归档成功但删除失败」时直接 return 200 会让对象永远留着）。**（Task 14）**
3. **集群里有一条别人手工建的 `PipelineRun`**（没有平台标签）—— watch 不理它、归档不碰它、**绝不删它**。这是本批最容易造成事故的一处。**（Task 8 / Task 15）**
4. **一个节点引用了未被授权的 git / registry 连接 id**（绕过前端直接打接口）—— 必须**明确拒绝**，不能静默降级成「匿名去拉」。**（Task 11）**
5. **平台未配置 `internal-token`** —— 内部回调端点必须**拒绝一切请求**，而不是「没配就当放行」。**（Task 13）**

---

## 文件结构

**platform-common（新增）**

| 文件 | 职责 |
|---|---|
| `models/k8s/dto/PipelineDTO.java` | 流水线（平台自己的表，不是一个 K8s 对象） |
| `models/k8s/dto/PipelineRunDTO.java` | 一次运行（集群里的 `PipelineRun` 的投影） |
| `models/k8s/ResourceType.java`（改） | 加 `PIPELINE_RUN(PipelineRunDTO.class)` |

**k8s-core（新增）**

| 文件 | 职责 |
|---|---|
| `pipeline/PipelineNode.java` | 节点接口：`type()` / `formSchema()` / `render()` |
| `pipeline/NodeSchema.java` `NodeField.java` | 表单 schema（纯数据） |
| `pipeline/PipelineCanvas.java` | 画布模型（节点 + 连线）与其 JSON |
| `pipeline/NodeContext.java` `RenderedStep.java` | 渲染的入参/出参 |
| `pipeline/PipelineNodeRegistry.java` | 节点分派（**唯一真相源**） |
| `pipeline/PipelineRenderer.java` | 画布 → Tekton `PipelineSpec`，**纯函数** |
| `pipeline/nodes/GitCloneNode.java` `ShellNode.java` `ImagePushNode.java` | 三个内置节点 |
| `pipeline/BuildPreset.java` | 语言预设（**数据**，且只是默认值） |
| `operations/pipelinerun/PipelineRunOperations.java` | `NamespacedOperations<PipelineRunDTO>` + `cancel` + watch 开关 + 扫描 |
| `operations/pipelinerun/PipelineRunConverter.java` | Tekton 对象 ↔ DTO |
| `factory/KubernetesOperationsFactory.java`（改） | switch 加一个 case |

**k8s-server（新增）**

| 文件 | 职责 |
|---|---|
| `controllers/namespace/PipelineRunController.java` | 继承基类 + `/cancel` |
| `pipeline/PipelineRunWatchManager.java` | **活动 watch 台账** + 开/关 + 回调 + 待补集合 |
| `pipeline/PipelineRunTicker.java` | 节拍器：超时判定 + 待补重投（**只看台账，不打集群**） |
| `pipeline/PipelineRunStartupSweep.java` | 启动时扫一次「带平台标签且还在 running」 |

**platform-api（新增）**

| 文件 | 职责 |
|---|---|
| `services/PipelineService.java` | 流水线 CRUD + 画布校验 + 渲染预览 |
| `services/PipelineRunService.java` | run / cancel / list / get（编排 k8s-server 的原子调用） |
| `services/PipelineArchiveService.java` | 归档：抓快照 → 落库（事务）→ 删对象 |
| `services/PipelineRetentionService.java` | 每日清理 90 天前的 run 与日志 |
| `services/PipelineCapabilityService.java` | Tekton / StorageClass 的门控探测（本批最小版） |
| `controllers/PipelineController.java` | 用户端点 |
| `controllers/PipelineInternalController.java` | `/internal/pipeline-run/finished`（密钥鉴权） |
| `k8s/K8sClient.java`（改） | 加 pipeline 相关的原子调用 |
| `data/mapper/.../PlatformPipelineMapper.java` `PlatformPipelineRunMapper.java` | 两个 mapper |

**platform-data（新增）**

| 文件 | 职责 |
|---|---|
| `db/migration/V2026_10_15_1__pipeline.sql` | 三张表 + 一列 + 5 权限行 + 角色关联 |
| `mapper/system/PlatformPipelineMapper.xml` `PlatformPipelineRunMapper.xml` | |

**platform-web（新增/改）**

| 文件 | 职责 |
|---|---|
| `views/resource/PipelineListView.vue` | 流水线列表 |
| `views/resource/PipelineEditorView.vue` | 画布页 |
| `views/resource/PipelineRunView.vue` | 运行历史 + 运行详情 |
| `components/pipeline/NodePalette.vue` `NodePropsPanel.vue` `PipelineNodeVue.vue` | 画布三件套 |
| `composables/usePipelineNodeTypes.ts` | 拉 `/pipeline/node-types` 并缓存 |
| `types.ts` `api/index.ts` `pageCodes.ts` `apiCodes.ts` `router/index.ts` `layouts/MainLayout.vue`（改） | |

---

## Part A —— 纯逻辑（k8s-core，全部可单测）

### Task 1: 节点模型与画布模型

**Files:**
- Create: `k8s-core/src/main/java/com/coding/k8score/pipeline/{PipelineNode,NodeSchema,NodeField,PipelineCanvas,NodeContext,RenderedStep,PipelineNodeRegistry}.java`
- Test: `k8s-core/src/test/java/com/coding/k8score/pipeline/PipelineNodeRegistryTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `PipelineNode.type()/formSchema()/render(NodeContext)`；`NodeSchema(List<NodeField>)`；`NodeField(key,label,type,required,placeholder,help,defaultValue,options)`；`PipelineCanvas(List<CanvasNode> nodes, List<CanvasEdge> edges)`；`CanvasNode(id,type,config:Map<String,String>)`；`CanvasEdge(from,to)`；`NodeContext(nodeId,nodeName,workspaceName,canvas)`；`RenderedStep(List<StepSpec>)`；`PipelineNodeRegistry.of(String)` / `supports(String)` / `all()`

> **为什么一个内置节点一个类**：spec §2. 表单由 `formSchema()` 驱动（加字段不用写 Vue），
> 「字段 → taskSpec」用 Java 写（不发明 DSL）。

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.k8score.pipeline;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineNodeRegistryTest {

    /** 只为测试存在的节点 */
    static class FakeNode implements PipelineNode {
        @Override public String type() { return "fake"; }
        @Override public NodeSchema formSchema() {
            return new NodeSchema(List.of(NodeField.text("msg", "消息", false)));
        }
        @Override public RenderedStep render(NodeContext ctx) {
            return new RenderedStep(List.of());
        }
    }

    @Test
    void dispatchesByType() {
        PipelineNodeRegistry r = new PipelineNodeRegistry(List.of(new FakeNode()));
        assertThat(r.of("fake")).isInstanceOf(FakeNode.class);
        assertThat(r.supports("fake")).isTrue();
        assertThat(r.supports("nope")).isFalse();
    }

    @Test
    void unknownTypeFailsWithABusinessCodeNamingTheType() {
        PipelineNodeRegistry r = new PipelineNodeRegistry(List.of(new FakeNode()));
        assertThatThrownBy(() -> r.of("nope"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void duplicateTypeIsAProgrammingErrorNotLastOneWins() {
        // 两个节点类声明了同一个 type —— 静默"后者覆盖"会让一个节点在画布上可用、
        // 渲染时变成另一个。构建期就炸比运行时诡异好。
        assertThatThrownBy(() -> new PipelineNodeRegistry(List.of(new FakeNode(), new FakeNode())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fake");
    }

    @Test
    void canvasRoundTripsThroughJson() {
        PipelineCanvas c = new PipelineCanvas(
                List.of(new CanvasNode("n1", "fake", Map.of("msg", "hi"))),
                List.of(new CanvasEdge("n1", "n2")));
        String json = PipelineCanvas.toJson(c);
        assertThat(PipelineCanvas.fromJson(json)).isEqualTo(c);
    }

    @Test
    void malformedCanvasJsonIsRejectedNotSilentlyEmpty() {
        // 画布 JSON 是唯一真相源。解析成"空画布"会让用户以为自己的流水线没了
        assertThatThrownBy(() -> PipelineCanvas.fromJson("{not json"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("画布");
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
mvn -o -pl k8s-core -am test -Dtest=PipelineNodeRegistryTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: **编译失败**（这些类都不存在），这就是本步要的红。

- [ ] **Step 3: 建模型与注册表**

`NodeField.java`：

```java
package com.coding.k8score.pipeline;

import java.util.List;

/**
 * 一个表单字段。**纯数据** —— 前端据此渲染，渲染器据此校验。
 *
 * @param type TEXT / TEXTAREA / SELECT / IMAGE。IMAGE 在前端会挂 S3.1 的候选补全
 */
public record NodeField(String key, String label, FieldType type,
                        boolean required, String placeholder, String help,
                        String defaultValue, List<Option> options) {

    public enum FieldType { TEXT, TEXTAREA, SELECT, IMAGE }

    public record Option(String value, String label) { }

    public static NodeField text(String key, String label, boolean required) {
        return new NodeField(key, label, FieldType.TEXT, required, null, null, null, List.of());
    }

    public static NodeField image(String key, String label) {
        return new NodeField(key, label, FieldType.IMAGE, true, "如 maven:3.9-eclipse-temurin-21",
                "基础镜像。**带候选补全** —— 从已配的镜像仓库里挑", null, List.of());
    }

    public static NodeField select(String key, String label, List<Option> options, String def) {
        return new NodeField(key, label, FieldType.SELECT, true, null, null, def, options);
    }
}
```

`NodeSchema.java`：

```java
package com.coding.k8score.pipeline;

import java.util.List;

/** 一个节点的表单 schema。**非空且 key 不重复**由护栏测试钉住（spec §2.4）。 */
public record NodeSchema(List<NodeField> fields) {
}
```

`PipelineNode.java`：

```java
package com.coding.k8score.pipeline;

/**
 * 一个**内置**流水线节点。设计见 docs/superpowers/specs/2026-10-15-ci-tekton-design.md §2。
 *
 * <p><b>加一个节点 = 加一个类</b>，实现本接口并在构造器上标 {@code @Component}。
 * 表单由 {@link #formSchema()} 驱动（加字段不用写 Vue）；「字段 → taskSpec」用 Java 写
 * （不发明模板 DSL —— 那是选「一个节点一个类」而不是「纯声明式」的理由）。
 */
public interface PipelineNode {

    /** 节点的 type 标识，如 {@code "git"} / {@code "shell"} / {@code "image"}。全局唯一。 */
    String type();

    /** 表单 schema。前端据此渲染属性面板，渲染器据此做必填校验。 */
    NodeSchema formSchema();

    /** 把节点上填的值合成 Tekton 的 step。**纯函数**：无 IO、可单测。 */
    RenderedStep render(NodeContext ctx);
}
```

`PipelineCanvas.java`：

```java
package com.coding.k8score.pipeline;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * 画布：**流水线的唯一真相源**（spec §3.2）。集群里不留 {@code Pipeline} 对象，
 * 每次运行即时把它渲染成 {@code PipelineSpec}。
 */
public record PipelineCanvas(List<CanvasNode> nodes, List<CanvasEdge> edges) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** @param config 字段 key → 用户填的值（**全是字符串**，与表单一致） */
    public record CanvasNode(String id, String type, Map<String, String> config) { }

    public record CanvasEdge(String from, String to) { }

    public static String toJson(PipelineCanvas c) {
        return JSON.writeValueAsString(c);
    }

    public static PipelineCanvas fromJson(String json) {
        try {
            PipelineCanvas c = JSON.readValue(json, PipelineCanvas.class);
            if (c == null) {
                throw new CloudPlatformException(EnumResponseType.PIPELINE_CANVAS_INVALID, "画布为空");
            }
            return c;
        } catch (CloudPlatformException e) {
            throw e;
        } catch (Exception e) {
            // ⚠️ 不能"解析失败就当空画布" —— 那会让用户以为自己的流水线没了
            throw new CloudPlatformException(EnumResponseType.PIPELINE_CANVAS_INVALID,
                    "画布内容无法解析");
        }
    }
}
```

`NodeContext.java` / `RenderedStep.java`：

```java
package com.coding.k8score.pipeline;

/** 渲染一个节点时的上下文。 */
public record NodeContext(String nodeId, String nodeName, String workspaceName,
                          PipelineCanvas.CanvasNode node) {
    /** 取字段值；缺省回落到 schema 里的 defaultValue；都没有则返回空串。 */
    public String value(String key) {
        String v = node.config() == null ? null : node.config().get(key);
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        return "";
    }
}
```

```java
package com.coding.k8score.pipeline;

import io.fabric8.tekton.v1.Step;
import java.util.List;

/** 一个节点渲染出来的东西。目前只有 steps —— 将来要 results/volumes 时在这里加。 */
public record RenderedStep(List<Step> steps) {
}
```

`PipelineNodeRegistry.java`：

```java
package com.coding.k8score.pipeline;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点分派。**这是「有哪些节点」的唯一真相源** —— 调用方不要再手写一份 type 清单
 * （S3.1 终审删掉 {@code REGISTRY_TYPES} 时反对的就是那个形状）。
 */
@Component
public class PipelineNodeRegistry {

    private final Map<String, PipelineNode> byType = new HashMap<>();

    public PipelineNodeRegistry(List<PipelineNode> nodes) {
        for (PipelineNode n : nodes) {
            // ⚠️ 重复 type 是**编程错误**，不能"后者覆盖" —— 那会让一个节点在画布上可用、
            // 渲染时静默变成另一个。构建期就炸掉。
            if (byType.put(n.type(), n) != null) {
                throw new IllegalStateException("流水线节点 type 重复：" + n.type());
            }
        }
    }

    public PipelineNode of(String type) {
        PipelineNode n = byType.get(type);
        if (n == null) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_NODE_UNSUPPORTED,
                    "不支持的流水线节点类型：" + type);
        }
        return n;
    }

    public boolean supports(String type) {
        return type != null && byType.containsKey(type);
    }

    public List<PipelineNode> all() {
        return List.copyOf(byType.values());
    }
}
```

在 `EnumResponseType` 里加三个码（照该枚举里既有条目的写法）：
`PIPELINE_CANVAS_INVALID` / `PIPELINE_NODE_UNSUPPORTED` / `PIPELINE_INVALID`。

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -o -pl k8s-core -am test -Dtest=PipelineNodeRegistryTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add k8s-core/src/main/java/com/coding/k8score/pipeline/ platform-common/src/main/java/com/coding/common/exception/EnumResponseType.java k8s-core/src/test/java/com/coding/k8score/pipeline/
git commit -m "feat(pipeline): 节点模型与画布模型（加一个节点 = 加一个类）"
```

---

### Task 2: 渲染器（画布 → Tekton PipelineSpec）

**Files:**
- Create: `k8s-core/src/main/java/com/coding/k8score/pipeline/PipelineRenderer.java`
- Test: `k8s-core/src/test/java/com/coding/k8score/pipeline/PipelineRendererTest.java`

**Interfaces:**
- Consumes: Task 1 的全部
- Produces: `PipelineRenderer.render(PipelineCanvas, String workspaceClaimName)` → `PipelineSpec`

> **它为什么是纯函数**：与本仓的 `CredentialProbe` / `HarborDialect` 的静态规则、`RegistryTargetResolver` 同一条判断 ——
> 规则与 IO 分开，规则就测得动。这里没有任何 IO。

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.k8score.pipeline;

import io.fabric8.tekton.v1.PipelineSpec;
import io.fabric8.tekton.v1.PipelineTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineRendererTest {

    private final PipelineNodeRegistry registry = new PipelineNodeRegistry(List.of(new GitNode(), new EchoNode()));

    /** 两个只为测试存在的节点：git 产出一个 step，echo 产出一个 step */
    static class GitNode implements PipelineNode {
        @Override public String type() { return "git"; }
        @Override public NodeSchema formSchema() { return new NodeSchema(List.of(NodeField.text("repo", "仓库", true))); }
        @Override public RenderedStep render(NodeContext ctx) {
            return new RenderedStep(List.of(newStep("clone-" + ctx.nodeName())));
        }
    }
    static class EchoNode implements PipelineNode {
        @Override public String type() { return "echo"; }
        @Override public NodeSchema formSchema() { return new NodeSchema(List.of(NodeField.text("msg", "消息", false))); }
        @Override public RenderedStep render(NodeContext ctx) {
            return new RenderedStep(List.of(newStep("echo-" + ctx.nodeName())));
        }
    }
    private static io.fabric8.tekton.v1.Step newStep(String name) {
        io.fabric8.tekton.v1.Step s = new io.fabric8.tekton.v1.Step();
        s.setName(name);
        s.setImage("alpine:3");
        return s;
    }

    private static PipelineCanvas canvas(List<PipelineCanvas.CanvasNode> nodes, List<PipelineCanvas.CanvasEdge> edges) {
        return new PipelineCanvas(nodes, edges);
    }

    private static PipelineCanvas.CanvasNode node(String id, String type) {
        return new PipelineCanvas.CanvasNode(id, type, Map.of("repo", "x", "msg", "y"));
    }

    @Test
    void aSingleNodeBecomesOnePipelineTask() {
        PipelineSpec spec = PipelineRenderer.render(
                canvas(List.of(node("n1", "git")), List.of()), "ws");

        assertThat(spec.getTasks()).hasSize(1);
        assertThat(spec.getTasks().get(0).getName()).isEqualTo("n1");
    }

    @Test
    void anEdgeBecomesRunAfter() {
        PipelineSpec spec = PipelineRenderer.render(
                canvas(List.of(node("n1", "git"), node("n2", "echo")),
                        List.of(new PipelineCanvas.CanvasEdge("n1", "n2"))), "ws");

        PipelineTask second = spec.getTasks().stream()
                .filter(t -> t.getName().equals("n2")).findFirst().orElseThrow();
        assertThat(second.getRunAfter()).containsExactly("n1");
    }

    @Test
    void unconnectedNodesAreConcurrentNotBroken() {
        // 没有连线 = 并发，这是 Tekton 的默认语义。**不能被当成"没连上"而拒掉** ——
        // UI 上要给这个语义提示，渲染器则必须原样产出一个不带 runAfter 的 task
        PipelineSpec spec = PipelineRenderer.render(
                canvas(List.of(node("n1", "git"), node("n2", "echo")), List.of()), "ws");

        assertThat(spec.getTasks()).hasSize(2);
        assertThat(spec.getTasks()).allSatisfy(t -> assertThat(t.getRunAfter()).isNullOrEmpty());
    }

    @Test
    void forkThenJoinKeepsBothEdges() {
        PipelineSpec spec = PipelineRenderer.render(
                canvas(List.of(node("a", "git"), node("b", "echo"), node("c", "echo"), node("d", "echo")),
                        List.of(new PipelineCanvas.CanvasEdge("a", "b"),
                                new PipelineCanvas.CanvasEdge("a", "c"),
                                new PipelineCanvas.CanvasEdge("b", "d"),
                                new PipelineCanvas.CanvasEdge("c", "d"))), "ws");

        PipelineTask d = spec.getTasks().stream().filter(t -> t.getName().equals("d")).findFirst().orElseThrow();
        assertThat(d.getRunAfter()).containsExactlyInAnyOrder("b", "c");
    }

    @Test
    void aCycleIsRejectedNotRendered() {
        // ⭐ Review Focus #1：连成 A→B→A。绝不能让一个环走到集群去
        assertThatThrownBy(() -> PipelineRenderer.render(
                canvas(List.of(node("a", "git"), node("b", "echo")),
                        List.of(new PipelineCanvas.CanvasEdge("a", "b"),
                                new PipelineCanvas.CanvasEdge("b", "a"))), "ws"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("环");
    }

    @Test
    void selfLoopIsAlsoACycle() {
        assertThatThrownBy(() -> PipelineRenderer.render(
                canvas(List.of(node("a", "git")),
                        List.of(new PipelineCanvas.CanvasEdge("a", "a"))), "ws"))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void anEdgePointingAtAnUnknownNodeIsRejected() {
        assertThatThrownBy(() -> PipelineRenderer.render(
                canvas(List.of(node("a", "git")),
                        List.of(new PipelineCanvas.CanvasEdge("a", "ghost"))), "ws"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void anEmptyCanvasIsRejected() {
        assertThatThrownBy(() -> PipelineRenderer.render(canvas(List.of(), List.of()), "ws"))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void everyNodeGetsTheSharedWorkspaceWithoutTheUserKnowing() {
        PipelineSpec spec = PipelineRenderer.render(
                canvas(List.of(node("n1", "git"), node("n2", "echo")), List.of()), "ws");

        assertThat(spec.getWorkspaces()).extracting(w -> w.getName()).containsExactly("ws");
    }

    @Test
    void renderingIsDeterministic() {
        // 这是「历史运行天然带当时那一版」的前提 —— 同一份画布渲染两次必须字节等价
        PipelineCanvas c = canvas(List.of(node("n1", "git"), node("n2", "echo")), List.of());
        assertThat(PipelineRenderer.render(c, "ws")).isEqualTo(PipelineRenderer.render(c, "ws"));
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
mvn -o -pl k8s-core -am test -Dtest=PipelineRendererTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败（`PipelineRenderer` 不存在）。

- [ ] **Step 3: 实现渲染器**

```java
package com.coding.k8score.pipeline;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import io.fabric8.tekton.v1.PipelineSpec;
import io.fabric8.tekton.v1.PipelineTask;
import io.fabric8.tekton.v1.PipelineWorkspaceDeclaration;
import io.fabric8.tekton.v1.TaskSpec;
import io.fabric8.tekton.v1.WorkspaceDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 画布 → Tekton {@code PipelineSpec}。**纯函数、无 IO、可单测**。
 *
 * <p>三条硬规则（spec §3.1）：
 * <ol>
 *   <li><b>有向无环</b> —— 有环直接拒，绝不让它走到集群；</li>
 *   <li><b>连线 → {@code runAfter}</b>；</li>
 *   <li><b>没有连线 = 并发</b>（Tekton 的默认语义）—— <b>不是错误</b>，UI 要给提示。</li>
 * </ol>
 *
 * <p>⚠️ <b>每条 task 的 {@code taskSpec} 全部内联</b>（台账 S3.5.3 已定）——
 * 消掉跨命名空间引用与 ClusterTask 废弃那一堆问题。
 */
public final class PipelineRenderer {

    private PipelineRenderer() {
    }

    /**
     * @param workspaceClaimName 隐式共享 workspace 的名字。平台注入到**所有**节点，用户不感知
     */
    public static PipelineSpec render(PipelineCanvas canvas, String workspaceClaimName) {
        if (canvas == null || canvas.nodes() == null || canvas.nodes().isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID, "流水线至少要有一个节点");
        }
        Map<String, PipelineCanvas.CanvasNode> byId = new LinkedHashMap<>();
        for (PipelineCanvas.CanvasNode n : canvas.nodes()) {
            if (byId.put(n.id(), n) != null) {
                throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID, "节点 id 重复：" + n.id());
            }
        }
        List<PipelineCanvas.CanvasEdge> edges = canvas.edges() == null ? List.of() : canvas.edges();

        // 连线按 to 归组 —— 一个节点可以被多个上游指向（汇合）
        Map<String, Set<String>> runAfter = new LinkedHashMap<>();
        for (PipelineCanvas.CanvasEdge e : edges) {
            if (!byId.containsKey(e.from())) {
                throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                        "连线指向了不存在的节点：" + e.from());
            }
            if (!byId.containsKey(e.to())) {
                throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                        "连线指向了不存在的节点：" + e.to());
            }
            runAfter.computeIfAbsent(e.to(), k -> new LinkedHashSet<>()).add(e.from());
        }
        rejectCycles(byId.keySet(), edges);

        List<PipelineTask> tasks = new ArrayList<>();
        for (PipelineCanvas.CanvasNode n : canvas.nodes()) {
            PipelineNode impl = NODES.of(n.type());
            NodeContext ctx = new NodeContext(n.id(), n.id(), workspaceClaimName, n);
            RenderedStep rendered = impl.render(ctx);

            TaskSpec taskSpec = new TaskSpec();
            taskSpec.setSteps(rendered.steps());
            // 隐式共享 workspace：每个 task 都挂上，用户不用管
            WorkspaceDeclaration wd = new WorkspaceDeclaration();
            wd.setName(workspaceClaimName);
            taskSpec.setWorkspaces(List.of(wd));

            PipelineTask task = new PipelineTask();
            task.setName(n.id());                      // 节点 id 就是 task 名
            task.setTaskSpec(taskSpec);
            Set<String> after = runAfter.get(n.id());
            if (after != null && !after.isEmpty()) {
                task.setRunAfter(new ArrayList<>(after));
            }
            tasks.add(task);
        }

        PipelineWorkspaceDeclaration ws = new PipelineWorkspaceDeclaration();
        ws.setName(workspaceClaimName);
        PipelineSpec spec = new PipelineSpec();
        spec.setTasks(tasks);
        spec.setWorkspaces(List.of(ws));
        return spec;
    }

    /**
     * 环检测（DFS 三色）。**必须做** —— Tekton 侧对环的处理不是我们想要的失败形态，
     * 而且一个带环的画布根本表达不了「谁先跑」。
     */
    private static void rejectCycles(Set<String> ids, List<PipelineCanvas.CanvasEdge> edges) {
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (String id : ids) {
            adj.put(id, new ArrayList<>());
        }
        for (PipelineCanvas.CanvasEdge e : edges) {
            adj.get(e.from()).add(e.to());
        }
        Map<String, Integer> color = new LinkedHashMap<>();   // 0 白 1 灰 2 黑
        for (String id : ids) {
            color.put(id, 0);
        }
        for (String id : ids) {
            if (color.get(id) == 0 && hasCycle(id, adj, color)) {
                throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                        "流水线里有一个环（节点 " + id + " 回到了自己）—— 节点之间的连线必须是一条有向无环图");
            }
        }
    }

    private static boolean hasCycle(String id, Map<String, List<String>> adj, Map<String, Integer> color) {
        color.put(id, 1);
        for (String next : adj.getOrDefault(id, List.of())) {
            Integer c = color.get(next);
            if (c != null && c == 1) {
                return true;
            }
            if (c != null && c == 0 && hasCycle(next, adj, color)) {
                return true;
            }
        }
        color.put(id, 2);
        return false;
    }
}
```

⚠️ `NODES` 在上面是占位 —— 渲染器**不能是纯静态**却又要拿到 registry。改成实例方法：

```java
    private final PipelineNodeRegistry nodes;

    public PipelineRenderer(PipelineNodeRegistry nodes) {
        this.nodes = nodes;
    }
```
并把 `render` 与 `rejectCycles` 的调用改成实例方法（`nodes.of(...)`）。测试里相应地 `new PipelineRenderer(registry).render(...)`
—— **测试代码里已经把 registry 建出来了，改成实例调用即可**（把 9 处 `PipelineRenderer.render(` 改成 `renderer.render(`，其中 `renderer = new PipelineRenderer(registry)`）。
`rejectCycles` / `hasCycle` 保持 `private static`（它们不碰 registry）。

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -o -pl k8s-core -am test -Dtest=PipelineRendererTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add k8s-core/src/main/java/com/coding/k8score/pipeline/ k8s-core/src/test/java/com/coding/k8score/pipeline/
git commit -m "feat(pipeline): 渲染器 —— 画布到 PipelineSpec，纯函数（含环检测、隐式共享 workspace）"
```

---

### Task 3: 三个内置节点 + 语言预设 + 护栏

**Files:**
- Create: `k8s-core/src/main/java/com/coding/k8score/pipeline/BuildPreset.java`
- Create: `k8s-core/src/main/java/com/coding/k8score/pipeline/nodes/{GitCloneNode,ShellNode,ImagePushNode}.java`
- Test: `k8s-core/src/test/java/com/coding/k8score/pipeline/nodes/BuiltinNodesTest.java`

**Interfaces:**
- Consumes: Task 1 的接口
- Produces: 三个 `@Component` 节点，type 分别为 `git` / `shell` / `image`；`BuildPreset.all()` / `BuildPreset.of(String)`

> ⚠️ **预设只是默认值，不锁死任何字段**（用户 2026-10-15 明确要求）。
> 所以「改掉镜像后渲染出来的是改后的值」要正面钉住。

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.k8score.pipeline.nodes;

import com.coding.k8score.pipeline.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltinNodesTest {

    private static NodeContext ctx(String type, Map<String, String> config) {
        return new NodeContext("n1", "n1", "ws", new PipelineCanvas.CanvasNode("n1", type, config));
    }

    @Test
    void allThreeNodeTypesAreRegisteredAndTheirSchemasAreWellFormed() {
        // 护栏（spec §2.4）：type 非空、schema 非空、字段 key 不重复
        List<PipelineNode> all = List.of(new GitCloneNode(), new ShellNode(), new ImagePushNode());
        assertThat(all).extracting(PipelineNode::type).containsExactlyInAnyOrder("git", "shell", "image");
        for (PipelineNode n : all) {
            assertThat(n.formSchema().fields()).as(n.type() + " 的 schema").isNotEmpty();
            assertThat(n.formSchema().fields()).extracting(NodeField::key).doesNotHaveDuplicates();
        }
    }

    @Test
    void gitNodeRendersACloneStepIntoTheSharedWorkspace() {
        RenderedStep step = new GitCloneNode().render(ctx("git",
                Map.of("repo", "https://gitlab.example.com/g/r.git", "revision", "main", "dir", "src")));

        assertThat(step.steps()).hasSize(1);
        assertThat(step.steps().get(0).getScript()).contains("git");
        assertThat(step.steps().get(0).getScript()).contains("https://gitlab.example.com/g/r.git");
        // 源码写进共享 workspace —— 下游节点不用做任何事就能看到
        assertThat(step.steps().get(0).getWorkingDir()).contains("ws");
    }

    @Test
    void shellNodeDefaultDirIsTheWorkspaceRoot() {
        RenderedStep step = new ShellNode().render(ctx("shell",
                Map.of("image", "alpine:3", "command", "echo hi", "workDir", "")));
        assertThat(step.steps().get(0).getWorkingDir()).isEqualTo("/workspace");
    }

    @Test
    void shellNodeSplitsTheCommandOnNewlines() {
        RenderedStep step = new ShellNode().render(ctx("shell",
                Map.of("image", "alpine:3", "command", "echo a\necho b", "workDir", ".")));
        assertThat(step.steps().get(0).getScript()).isEqualTo("echo a\necho b");
    }

    @Test
    void shellNodeUsesThePresetDefaultsWhenFieldsAreEmpty() {
        // 选了 java-build 预设但没改任何字段 → 用的是预设的镜像与命令
        RenderedStep step = new ShellNode().render(ctx("shell",
                Map.of("preset", "java-build", "image", "", "command", "", "workDir", "")));
        assertThat(step.steps().get(0).getImage()).isEqualTo("maven:3.9-eclipse-temurin-21");
        assertThat(step.steps().get(0).getScript()).contains("mvn");
    }

    @Test
    void anOverriddenImageWinsOverThePreset() {
        // ⭐ 用户明确要求：预设只是默认值，改了就得生效
        RenderedStep step = new ShellNode().render(ctx("shell",
                Map.of("preset", "java-build", "image", "my-harbor.example.com/base/maven:3.9",
                        "command", "mvn -B verify", "workDir", ".")));
        assertThat(step.steps().get(0).getImage()).isEqualTo("my-harbor.example.com/base/maven:3.9");
        assertThat(step.steps().get(0).getScript()).isEqualTo("mvn -B verify");
    }

    @Test
    void everyPresetCarriesItsOwnImageAndCommand() {
        assertThat(BuildPreset.all()).isNotEmpty();
        for (BuildPreset p : BuildPreset.all()) {
            if ("custom".equals(p.key())) {
                continue;
            }
            assertThat(p.image()).as(p.key() + " 的镜像").isNotBlank();
            assertThat(p.command()).as(p.key() + " 的命令").isNotBlank();
        }
    }

    @Test
    void imageNodeTagSourceIsAThreeWayChoiceNotATemplate() {
        // spec §2.2：tag 由三选一下拉决定，**不发明 ${...} 模板语法**
        NodeSchema schema = new ImagePushNode().formSchema();
        NodeField tagSource = schema.fields().stream()
                .filter(f -> f.key().equals("tagSource")).findFirst().orElseThrow();
        assertThat(tagSource.options()).extracting(NodeField.Option::value)
                .containsExactlyInAnyOrder("git-sha", "run-id", "fixed");
        assertThat(schema.fields()).extracting(NodeField::key).doesNotContain("tagTemplate");
    }

    @Test
    void imageNodeRendersABuildahStep() {
        RenderedStep step = new ImagePushNode().render(ctx("image",
                Map.of("imageName", "app", "tagSource", "git-sha", "dockerfile", "Dockerfile", "context", ".")));
        assertThat(step.steps()).hasSize(1);
        assertThat(step.steps().get(0).getImage()).contains("buildah");
        assertThat(step.steps().get(0).getScript()).contains("buildah");
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
mvn -o -pl k8s-core -am test -Dtest=BuiltinNodesTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败。

- [ ] **Step 3: 实现预设与三个节点**

`BuildPreset.java`（**扁平列表**，不是「语言 × 动作」两段式 —— 那会让字段随选择变化）：

```java
package com.coding.k8score.pipeline;

import java.util.List;
import java.util.Optional;

/**
 * 语言预设：**数据，且只是默认值**（用户 2026-10-15 明确要求「预设节点只是预设，
 * 用户组建流水线的时候可以更改，如镜像，命令等」）。
 *
 * <p>所以没有预设的语言（Rust / PHP / Scala…）→ 选 {@code custom} 或任意一项、改掉镜像与命令即可，
 * <b>平台零代码改动</b>。「加一种语言」= 加**一行**。
 */
public record BuildPreset(String key, String label, String image, String command) {

    private static final List<BuildPreset> ALL = List.of(
            new BuildPreset("java-build", "Java · 构建", "maven:3.9-eclipse-temurin-21", "mvn -B -DskipTests package"),
            new BuildPreset("java-test", "Java · 测试", "maven:3.9-eclipse-temurin-21", "mvn -B test"),
            new BuildPreset("go-build", "Go · 构建", "golang:1.22", "go build ./..."),
            new BuildPreset("go-test", "Go · 测试", "golang:1.22", "go test ./..."),
            new BuildPreset("node-build", "Node · 构建", "node:20", "npm ci && npm run build"),
            new BuildPreset("node-test", "Node · 测试", "node:20", "npm test"),
            new BuildPreset("custom", "自定义", "", ""));

    public static List<BuildPreset> all() {
        return ALL;
    }

    /** @return 找不到返回 {@code custom}（而不是抛）—— 预设只是默认值，不是必填 */
    public static BuildPreset of(String key) {
        return ALL.stream().filter(p -> p.key().equals(key)).findFirst()
                .orElse(ALL.get(ALL.size() - 1));
    }

    public static List<NodeField.Option> options() {
        return ALL.stream().map(p -> new NodeField.Option(p.key(), p.label())).toList();
    }

    public Optional<String> imageIfSet() {
        return image.isBlank() ? Optional.empty() : Optional.of(image);
    }

    public Optional<String> commandIfSet() {
        return command.isBlank() ? Optional.empty() : Optional.of(command);
    }
}
```

`GitCloneNode.java`：

```java
package com.coding.k8score.pipeline.nodes;

import com.coding.k8score.pipeline.*;
import io.fabric8.tekton.v1.Step;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * git 克隆。**真特殊**的节点之一：接 S0.2 的 git 连接（凭据由平台渲染时注入成 workspace 或 secret）、
 * 把源码写进**共享 workspace**，下游节点不用做任何事就能看到。
 *
 * <p>⚠️ 凭据注入的落点在 Task A5 的 `PipelineRun` 组装里（推的是 docker config 与 git 凭据），
 * 本节点只负责「怎么克隆」这件事。
 */
@Component
public class GitCloneNode implements PipelineNode {

    static final String WS = "/workspace";

    @Override
    public String type() {
        return "git";
    }

    @Override
    public NodeSchema formSchema() {
        return new NodeSchema(List.of(
                NodeField.text("repo", "仓库地址", true),
                NodeField.text("revision", "分支 / commit", false),
                NodeField.text("dir", "目标目录（相对 workspace）", false)));
    }

    @Override
    public RenderedStep render(NodeContext ctx) {
        String repo = ctx.value("repo");
        String revision = ctx.value("revision");
        String dir = ctx.value("dir").isEmpty() ? "." : ctx.value("dir");

        StringBuilder script = new StringBuilder("set -e\n");
        script.append("git clone --depth 1 ");
        if (!revision.isEmpty()) {
            script.append("--branch ").append(shellQuote(revision)).append(' ');
        }
        script.append(shellQuote(repo)).append(' ').append(shellQuote(dir)).append('\n');

        Step step = new Step();
        step.setName("clone");
        step.setImage("alpine/git:2.45.2");
        step.setWorkingDir(WS);
        step.setScript(script.toString());
        return new RenderedStep(List.of(step));
    }

    /** 只加单引号并把内部的单引号转义 —— 不引入完整 shell 解析，够用且可预测。 */
    static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
```

`ShellNode.java`：

```java
package com.coding.k8score.pipeline.nodes;

import com.coding.k8score.pipeline.*;
import io.fabric8.tekton.v1.Step;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通用命令节点。**所有字段都可改** —— 预设只负责预填默认值。
 *
 * <p>「跑测试」就是一个预设；**「跳过测试」= 不连那个节点**（自由 DAG 天然支持，不做开关）。
 */
@Component
public class ShellNode implements PipelineNode {

    static final String WS = "/workspace";

    @Override
    public String type() {
        return "shell";
    }

    @Override
    public NodeSchema formSchema() {
        return new NodeSchema(List.of(
                NodeField.select("preset", "预设（只是默认值，下面都能改）", BuildPreset.options(), "custom"),
                NodeField.image("image", "镜像"),
                NodeField.text("command", "命令（可多行）", true),
                NodeField.text("workDir", "工作目录（相对 workspace）", false)));
    }

    @Override
    public RenderedStep render(NodeContext ctx) {
        BuildPreset preset = BuildPreset.of(ctx.value("preset"));
        // ⚠️ 用户填了就用用户的；没填才回落到预设 —— 这是「预设只是默认值」的落点
        String image = ctx.value("image");
        if (image.isEmpty()) {
            image = preset.imageIfSet().orElseThrow(() ->
                    new com.coding.common.exception.CloudPlatformException(
                            com.coding.common.exception.EnumResponseType.PIPELINE_INVALID,
                            "镜像必填（选一个预设，或自己填一个）"));
        }
        String script = ctx.value("command");
        if (script.isEmpty()) {
            script = preset.commandIfSet().orElseThrow(() ->
                    new com.coding.common.exception.CloudPlatformException(
                            com.coding.common.exception.EnumResponseType.PIPELINE_INVALID,
                            "命令必填（选一个预设，或自己填一段）"));
        }
        String workDir = ctx.value("workDir").isEmpty() ? "." : ctx.value("workDir");

        Step step = new Step();
        step.setName("run");
        step.setImage(image);
        step.setWorkingDir(WS + "/" + workDir.replaceAll("^\\./", ""));
        step.setScript(script);
        return new RenderedStep(List.of(step));
    }
}
```

`ImagePushNode.java`：

```java
package com.coding.k8score.pipeline.nodes;

import com.coding.k8score.pipeline.*;
import io.fabric8.tekton.v1.Step;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 构建并推送镜像。**真特殊**的节点之二：Buildah（per-Pod、不用共享构建服务）、
 * 推送凭据由平台注入、产出 digest。
 *
 * <p>⚠️ tag 由**三选一下拉**决定，不发明 {@code ${...}} 模板语法（spec §2.2）。
 * 真正的拼接在 Task A5 组装 PipelineRun 时做 —— 那里才知道 git sha 与 run 序号。
 */
@Component
public class ImagePushNode implements PipelineNode {

    static final String WS = "/workspace";

    @Override
    public String type() {
        return "image";
    }

    @Override
    public NodeSchema formSchema() {
        return new NodeSchema(List.of(
                NodeField.text("registryConnectionId", "目标仓库（被授权的 registry 连接）", true),
                NodeField.text("imageName", "镜像名（仓库路径，如 proj/app）", true),
                NodeField.select("tagSource", "tag 来源",
                        List.of(new NodeField.Option("git-sha", "git 短 sha"),
                                new NodeField.Option("run-id", "本次运行序号"),
                                new NodeField.Option("fixed", "自定义固定值")), "git-sha"),
                NodeField.text("fixedTag", "固定 tag（仅「自定义固定值」时用）", false),
                NodeField.text("dockerfile", "Dockerfile 路径", false),
                NodeField.text("context", "构建上下文", false)));
    }

    @Override
    public RenderedStep render(NodeContext ctx) {
        String dockerfile = ctx.value("dockerfile").isEmpty() ? "Dockerfile" : ctx.value("dockerfile");
        String context = ctx.value("context").isEmpty() ? "." : ctx.value("context");

        // ⚠️ 这里用占位符 `__IMAGE_REF__`，由 Task A5 在组装 PipelineRun 时替换成
        // `endpoint/仓库路径:tag`（那时才知道 git sha 与 run 序号）。
        // 用占位符而不是 ${...}，是为了让"这不是一门模板语言"这件事在代码里也看得见。
        String script = "set -e\n"
                + "buildah bud -f " + GitCloneNode.shellQuote(dockerfile)
                + " -t __IMAGE_REF__ " + GitCloneNode.shellQuote(context) + "\n"
                + "buildah push --digestfile /workspace/.image-digest __IMAGE_REF__\n";

        Step step = new Step();
        step.setName("build-and-push");
        step.setImage("quay.io/buildah/stable:v1.35");
        step.setWorkingDir(WS);
        step.setScript(script);
        return new RenderedStep(List.of(step));
    }

    /** Task A5 用它做替换 —— 占位符只有这一处，找得到。 */
    public static final String IMAGE_REF_PLACEHOLDER = "__IMAGE_REF__";
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -o -pl k8s-core -am test -Dtest=BuiltinNodesTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add k8s-core/src/main/java/com/coding/k8score/pipeline/ k8s-core/src/test/java/com/coding/k8score/pipeline/nodes/
git commit -m "feat(pipeline): 三个内置节点（git / shell / image）+ 语言预设只是默认值"
```

---

## Part B —— 接入 Tekton（k8s-core + k8s-server）

### Task 4: `PipelineRunDTO` + 转换器 + 工厂接线

**Files:**
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/PipelineRunDTO.java`
- Create: `k8s-core/src/main/java/com/coding/k8score/converter/impl/pipeline/PipelineRunConverter.java`
- Modify: `platform-common/src/main/java/com/coding/common/models/k8s/ResourceType.java`
- Modify: `k8s-core/src/main/java/com/coding/k8score/factory/KubernetesOperationsFactory.java`
- Test: `k8s-core/src/test/java/com/coding/k8score/converter/impl/pipeline/PipelineRunConverterTest.java`

**Interfaces:**
- Consumes: 无（Task 1-3 的渲染器在 Task 12 才用上）
- Produces: `PipelineRunDTO extends BaseResources`；`PipelineRunConverter.toDto(PipelineRun)` / `toTekton(PipelineRunDTO)`；`ResourceType.PIPELINE_RUN`

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.k8score.converter.impl.pipeline;

import com.coding.common.models.k8s.dto.PipelineRunDTO;
import io.fabric8.tekton.v1.PipelineRun;
import io.fabric8.tekton.v1.PipelineRunStatus;
import io.fabric8.tekton.v1.PipelineRunCondition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineRunConverterTest {

    private final PipelineRunConverter converter = new PipelineRunConverter();

    @Test
    void dtoApiPathIsTheControllerMapping() {
        assertThat(new PipelineRunDTO().getApiPath()).isEqualTo("/pipelineruns");
    }

    @Test
    void readsStatusAndTimesOffTheObject() {
        PipelineRun pr = new PipelineRun();
        pr.getMetadata().setName("r1");
        pr.getMetadata().setNamespace("ns1");
        PipelineRunStatus st = new PipelineRunStatus();
        PipelineRunCondition ok = new PipelineRunCondition();
        ok.setType("Succeeded");
        ok.setStatus("True");
        ok.setReason("Succeeded");
        st.setConditions(List.of(ok));
        st.setStartTime("2026-10-15T10:00:00Z");
        st.setCompletionTime("2026-10-15T10:03:00Z");
        pr.setStatus(st);

        PipelineRunDTO dto = converter.toDto(pr);

        assertThat(dto.getName()).isEqualTo("r1");
        assertThat(dto.getNamespace()).isEqualTo("ns1");
        assertThat(dto.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(dto.getStartTime()).isEqualTo("2026-10-15T10:00:00Z");
        assertThat(dto.getCompletionTime()).isEqualTo("2026-10-15T10:03:00Z");
    }

    @Test
    void runningHasNoConditionSucceededYetSoStatusIsRunning() {
        PipelineRun pr = new PipelineRun();
        pr.getMetadata().setName("r2");
        pr.setStatus(new PipelineRunStatus());     // conditions 为 null
        assertThat(converter.toDto(pr).getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void failedConditionMapsToFailed() {
        PipelineRun pr = new PipelineRun();
        pr.getMetadata().setName("r3");
        PipelineRunCondition bad = new PipelineRunCondition();
        bad.setType("Succeeded");
        bad.setStatus("False");
        bad.setReason("Failed");
        PipelineRunStatus st = new PipelineRunStatus();
        st.setConditions(List.of(bad));
        pr.setStatus(st);
        assertThat(converter.toDto(pr).getStatus()).isEqualTo("FAILED");
    }

    @Test
    void cancelledReasonAndTimeoutReasonAreDistinguished() {
        // 平台自己取消的与平台判定超时的，状态要能分开 —— 否则运行历史里看不出区别
        assertThat(statusOf("False", "Cancelled")).isEqualTo("CANCELLED");
        assertThat(statusOf("False", "PipelineRunTimeout")).isEqualTo("TIMEOUT");
    }

    private String statusOf(String condStatus, String reason) {
        PipelineRun pr = new PipelineRun();
        pr.getMetadata().setName("r");
        PipelineRunCondition c = new PipelineRunCondition();
        c.setType("Succeeded");
        c.setStatus(condStatus);
        c.setReason(reason);
        PipelineRunStatus st = new PipelineRunStatus();
        st.setConditions(List.of(c));
        pr.setStatus(st);
        return converter.toDto(pr).getStatus();
    }

    @Test
    void createDoesNotSendAStatusSubresource() {
        PipelineRunDTO dto = new PipelineRunDTO();
        dto.setName("r1");
        dto.setNamespace("ns1");
        dto.setPipelineRefName("p1");
        PipelineRun pr = converter.toTekton(dto);
        assertThat(pr.getMetadata().getName()).isEqualTo("r1");
        assertThat(pr.getStatus()).isNull();
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
mvn -o -pl k8s-core -am test -Dtest=PipelineRunConverterTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败。

- [ ] **Step 3: 建 DTO、转换器、接线**

`PipelineRunDTO.java`：

```java
package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 一次流水线运行（集群里的 {@code PipelineRun}，**命名空间级、租户域**）。
 * 端点走 k8s-server {@code /pipelineruns}（分配表三元组边界）。
 *
 * <p>⚠️ 本 DTO **只建模读出与取消需要的东西**。真正喂给集群的 {@code PipelineSpec} 是
 * Task 5 的 {@code PipelineRunOperations} 用渲染器现渲染、**内联**进 {@code spec.pipelineSpec} 的，
 * 不走这个 DTO（spec §3.2：集群里不留 {@code Pipeline} 对象）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PipelineRunDTO extends BaseResources {

    /** create 时用：内联的 PipelineSpec 的 JSON（由渲染器产出后序列化） */
    @Schema(description = "内联的 PipelineSpec（JSON）。create 时必填")
    private String pipelineSpecJson;

    /** 平台的流水线 id —— 用于运行历史列表按流水线过滤 */
    @Schema(description = "平台侧流水线 id")
    private String pipelineId;

    /** RUNNING / SUCCEEDED / FAILED / CANCELLED / TIMEOUT（只读，converter 填） */
    private String status;

    private String reason;

    private String startTime;

    private String completionTime;

    @Override
    public String getApiPath() {
        return "/pipelineruns";
    }
}
```

`PipelineRunConverter.java`：

```java
package com.coding.k8score.converter.impl.pipeline;

import com.coding.common.models.k8s.dto.PipelineRunDTO;
import io.fabric8.tekton.v1.PipelineRun;
import io.fabric8.tekton.v1.PipelineRunCondition;
import io.fabric8.tekton.v1.PipelineRunStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/** Tekton {@code PipelineRun} ↔ DTO。状态映射是**唯一有规则**的地方，所以它单测。 */
@Component
public class PipelineRunConverter {

    /**
     * Tekton 用 conditions 表达结果，平台用五个状态。映射：
     * <ul>
     *   <li>没有 condition（还没被 reconciler 处理）→ {@code RUNNING}</li>
     *   <li>{@code Succeeded=True} → {@code SUCCEEDED}</li>
     *   <li>{@code Succeeded=False} 且 reason 含 Cancelled → {@code CANCELLED}</li>
     *   <li>{@code Succeeded=False} 且 reason 含 Timeout → {@code TIMEOUT}</li>
     *   <li>其余 {@code Succeeded=False} → {@code FAILED}</li>
     * </ul>
     * ⚠️ 「平台取消」与「平台判超时」都要能看出来 —— 运行历史里它们对用户是两件事。
     */
    public PipelineRunDTO toDto(PipelineRun pr) {
        PipelineRunDTO dto = new PipelineRunDTO();
        if (pr == null) {
            return dto;
        }
        dto.setName(pr.getMetadata() == null ? null : pr.getMetadata().getName());
        dto.setNamespace(pr.getMetadata() == null ? null : pr.getMetadata().getNamespace());
        dto.setLabels(pr.getMetadata() == null ? null : pr.getMetadata().getLabels());

        PipelineRunStatus st = pr.getStatus();
        dto.setStatus(mapStatus(st));
        if (st != null) {
            dto.setStartTime(st.getStartTime());
            dto.setCompletionTime(st.getCompletionTime());
            dto.setReason(reasonOf(st));
        }
        return dto;
    }

    public PipelineRun toTekton(PipelineRunDTO dto) {
        PipelineRun pr = new PipelineRun();
        pr.getMetadata().setName(dto.getName());
        pr.getMetadata().setNamespace(dto.getNamespace());
        // ⚠️ create 不写 status —— 它是子资源，写了也会被忽略，还会掩盖真实的初始状态
        return pr;
    }

    static String mapStatus(PipelineRunStatus st) {
        String reason = reasonOf(st);
        String cond = conditionStatus(st);
        if (cond == null) {
            return "RUNNING";
        }
        if ("True".equals(cond)) {
            return "SUCCEEDED";
        }
        if (reason != null && reason.toLowerCase().contains("cancel")) {
            return "CANCELLED";
        }
        if (reason != null && reason.toLowerCase().contains("timeout")) {
            return "TIMEOUT";
        }
        return "FAILED";
    }

    private static String conditionStatus(PipelineRunStatus st) {
        PipelineRunCondition c = succeededCondition(st);
        return c == null ? null : c.getStatus();
    }

    private static String reasonOf(PipelineRunStatus st) {
        PipelineRunCondition c = succeededCondition(st);
        return c == null ? null : c.getReason();
    }

    private static PipelineRunCondition succeededCondition(PipelineRunStatus st) {
        if (st == null || st.getConditions() == null) {
            return null;
        }
        List<PipelineRunCondition> cs = st.getConditions();
        return cs.stream().filter(c -> "Succeeded".equals(c.getType())).findFirst().orElse(null);
    }
}
```

`ResourceType`：在枚举里加一行（照既有条目的写法，import 也补上）：
```java
    PIPELINE_RUN(PipelineRunDTO.class),
```

`KubernetesOperationsFactory`：在 `switch` 里加一个 case（照既有写法）：
```java
            case PIPELINE_RUN -> new PipelineRunOperations(client, new PipelineRunConverter());
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -o -pl k8s-core -am test -Dtest=PipelineRunConverterTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add platform-common/src/main/java/com/coding/common/models/k8s/ k8s-core/src/main/java/com/coding/k8score/ k8s-core/src/test/java/com/coding/k8score/converter/impl/pipeline/
git commit -m "feat(pipeline): PipelineRunDTO + 转换器 + ResourceType/factory 接线"
```

---

### Task 5: `PipelineRunOperations`（含 cancel 与「带标签」的硬边界）

**Files:**
- Create: `k8s-core/src/main/java/com/coding/k8score/operations/pipelinerun/PipelineRunOperations.java`
- Test: `k8s-core/src/test/java/com/coding/k8score/operations/pipelinerun/PipelineRunOperationsTest.java`

**Interfaces:**
- Consumes: Task 4 的 `PipelineRunDTO` / `PipelineRunConverter`
- Produces: `PipelineRunOperations implements NamespacedOperations<PipelineRunDTO>`；常量 `MANAGED_LABEL = "app.kubernetes.io/managed-by"`、`MANAGED_VALUE = "platform-system"`；`cancel(ns,name)`；`listManagedRunning()`；`createManaged(ns, name, PipelineSpec, Map<String,String> labels)`

> ⚠️ **「只碰平台自己创建的」是本任务的核心**（spec §4.1.1 / Review Focus #3）。
> 它必须体现在：`list` / `get` / `delete` / `cancel` 全都带标签过滤，
> 一个没有平台标签的 `PipelineRun` **看不见、改不了、删不掉**。

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.k8score.operations.pipelinerun;

import com.coding.common.exception.CloudPlatformException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 标签过滤是**纯字符串规则**，所以抽成静态方法单测；真正的 K8s 调用靠 §12.1 的部署后冒烟。
 */
class PipelineRunOperationsTest {

    @Test
    void theManagedSelectorIsExactlyOneLabelEqualsOneValue() {
        assertThat(PipelineRunOperations.MANAGED_SELECTOR)
                .isEqualTo("app.kubernetes.io/managed-by=platform-system");
    }

    @Test
    void aForeignRunIsRefusedByTheGuard() {
        // ⭐ Review Focus #3：集群里别人手工建的 PipelineRun（没有平台标签）
        // —— 归档不碰、**绝不删**。这条守卫是唯一的落点
        assertThatThrownBy(() -> PipelineRunOperations.assertManaged(Map.of("foo", "bar"), "r1"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("不是平台创建");
    }

    @Test
    void aManagedRunPassesTheGuard() {
        PipelineRunOperations.assertManaged(
                Map.of("app.kubernetes.io/managed-by", "platform-system"), "r1");
    }

    @Test
    void nullLabelsAreRefusedNotTreatedAsManaged() {
        // 缺标签当成"是平台的"是最危险的默认值 —— 必须拒绝
        assertThatThrownBy(() -> PipelineRunOperations.assertManaged(null, "r1"))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void createManagedAlwaysStampsTheLabelEvenIfCallerForgot() {
        Map<String, String> labels = PipelineRunOperations.managedLabels(Map.of("k", "v"));
        assertThat(labels)
                .containsEntry("app.kubernetes.io/managed-by", "platform-system")
                .containsEntry("k", "v");
    }

    @Test
    void managedLabelsOverwriteACallerSuppliedManagedBy() {
        // 调用方（平台自己的渲染层）不可能把标签写成别的值
        Map<String, String> labels = PipelineRunOperations.managedLabels(
                Map.of("app.kubernetes.io/managed-by", "someone-else"));
        assertThat(labels).containsEntry("app.kubernetes.io/managed-by", "platform-system");
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
mvn -o -pl k8s-core -am test -Dtest=PipelineRunOperationsTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败。

- [ ] **Step 3: 实现**

```java
package com.coding.k8score.operations.pipelinerun;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.k8score.converter.impl.pipeline.PipelineRunConverter;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.tekton.v1.PipelineRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tekton {@code PipelineRun} 操作（{@code tekton.dev/v1}，命名空间级、租户域）。
 *
 * <h2>⚠️ 本类最重要的不变量：只碰平台自己创建的 run</h2>
 * 集群里可能有**别人手工建的** {@code PipelineRun}（运维调试、别的 CI 系统）。
 * 平台会**删掉**归档完的 run —— 不带过滤的话就是删别人的东西，这是本批最容易造成事故的一处。
 * 所以 {@link #MANAGED_SELECTOR} 出现在每一个读与每一个写上，并且
 * {@link #assertManaged} 是最后一道闸（spec §4.1.1）。
 */
@Slf4j
@RequiredArgsConstructor
public class PipelineRunOperations implements NamespacedOperations<PipelineRunDTO> {

    public static final String MANAGED_LABEL = "app.kubernetes.io/managed-by";
    public static final String MANAGED_VALUE = "platform-system";
    public static final String MANAGED_SELECTOR = MANAGED_LABEL + "=" + MANAGED_VALUE;

    private final KubernetesClient client;
    private final PipelineRunConverter converter;

    @Override
    public String apiVersion() {
        return "tekton.dev/v1";
    }

    /** 调用方的标签 + 平台标签。**平台标签最后写** —— 调用方覆盖不了它。 */
    public static Map<String, String> managedLabels(Map<String, String> extra) {
        Map<String, String> out = new HashMap<>(extra == null ? Map.of() : extra);
        out.put(MANAGED_LABEL, MANAGED_VALUE);
        return out;
    }

    /** 不是平台创建的一律拒 —— 读、删、取消都必须先过这里。 */
    public static void assertManaged(Map<String, String> labels, String name) {
        if (labels == null || !MANAGED_VALUE.equals(labels.get(MANAGED_LABEL))) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                    "该 PipelineRun 不是平台创建的，平台不会碰它：" + name);
        }
    }

    @Override
    public List<PipelineRunDTO> list(String namespace, String labelSelector, String fieldSelector) {
        // 平台标签与调用方给的 selector 用逗号连接（K8s 的 AND 语义）
        String selector = labelSelector == null || labelSelector.isBlank()
                ? MANAGED_SELECTOR
                : MANAGED_SELECTOR + "," + labelSelector;
        return client.resources(PipelineRun.class).inNamespace(namespace)
                .withLabelSelector(selector)
                .list().getItems().stream().map(converter::toDto).toList();
    }

    /**
     * 集群级 list：**给启动扫描用** —— 找「带平台标签且还在 running」的 run。
     * 未实现会在接口默认实现里抛 {@code OPERATION_NOT_SUPPORTED}，本类覆写它。
     */
    @Override
    public List<PipelineRunDTO> listAll(String labelSelector, String fieldSelector) {
        return client.resources(PipelineRun.class).inAnyNamespace()
                .withLabelSelector(MANAGED_SELECTOR)
                .list().getItems().stream().map(converter::toDto).toList();
    }

    @Override
    public PipelineRunDTO get(String namespace, String name) {
        PipelineRun pr = client.resources(PipelineRun.class).inNamespace(namespace).withName(name).get();
        if (pr == null) {
            return null;
        }
        assertManaged(pr.getMetadata().getLabels(), name);
        return converter.toDto(pr);
    }

    @Override
    public void delete(String namespace, String name) {
        // 先 get 再删 —— 必须确认它带平台标签。级联：删 PipelineRun 会带走 TaskRun 与 **Pod**
        PipelineRun pr = client.resources(PipelineRun.class).inNamespace(namespace).withName(name).get();
        if (pr == null) {
            return;                                   // 幂等：已经没了就是成功
        }
        assertManaged(pr.getMetadata().getLabels(), name);
        client.resources(PipelineRun.class).inNamespace(namespace).withName(name).delete();
    }

    @Override
    public String yaml(String namespace, String name) {
        PipelineRun pr = client.resources(PipelineRun.class).inNamespace(namespace).withName(name).get();
        if (pr == null) {
            return null;
        }
        assertManaged(pr.getMetadata().getLabels(), name);
        return io.fabric8.kubernetes.client.utils.Serialization.asYaml(pr);
    }

    /**
     * 创建。**内联 {@code pipelineSpec}**（集群里不留 {@code Pipeline} 对象，spec §3.2）
     * 并**打上平台标签**（{@link #managedLabels}）。
     *
     * @param pipelineSpecJson 渲染器产出的 {@code PipelineSpec} 的 JSON
     */
    @Override
    public PipelineRunDTO create(PipelineRunDTO dto) {
        PipelineRun pr = new PipelineRun();
        pr.getMetadata().setName(dto.getName());
        pr.getMetadata().setNamespace(dto.getNamespace());
        pr.getMetadata().setLabels(managedLabels(dto.getLabels()));

        io.fabric8.tekton.v1.PipelineSpec spec =
                io.fabric8.kubernetes.client.utils.Serialization.unmarshal(dto.getPipelineSpecJson(),
                        io.fabric8.tekton.v1.PipelineSpec.class);
        io.fabric8.tekton.v1.PipelineRunSpec runSpec = new io.fabric8.tekton.v1.PipelineRunSpec();
        runSpec.setPipelineSpec(spec);
        pr.setSpec(runSpec);

        PipelineRun created = client.resources(PipelineRun.class).inNamespace(dto.getNamespace())
                .resource(pr).create();
        return converter.toDto(created);
    }

    @Override
    public PipelineRunDTO update(PipelineRunDTO resource) {
        // 流水线运行**不可编辑**（改了就不是同一次运行了）。要改就取消重跑。
        throw new CloudPlatformException(EnumResponseType.OPERATION_NOT_SUPPORTED,
                "流水线运行不支持编辑 —— 取消后重新触发一次");
    }

    /**
     * 取消：**patch {@code spec.status}**，不是删对象 —— 删了历史就没了（spec §4.2）。
     *
     * <p>⚠️ 具体取值（v1 里是 {@code "Cancelled"} 还是要用别的）**见 §11 待实测** ——
     * 全量测试绿不了这一条，只能在真集群上验。
     */
    public void cancel(String namespace, String name) {
        PipelineRun pr = client.resources(PipelineRun.class).inNamespace(namespace).withName(name).get();
        if (pr == null) {
            return;
        }
        assertManaged(pr.getMetadata().getLabels(), name);
        client.resources(PipelineRun.class).inNamespace(namespace).withName(name)
                .edit(p -> {
                    p.getSpec().setStatus("Cancelled");
                    return p;
                });
    }

    /** 一次运行的每个 TaskRun 名（归档时要按它去取 Pod 与日志）。 */
    public List<String> childTaskRunNames(String namespace, String runName) {
        PipelineRun pr = client.resources(PipelineRun.class).inNamespace(namespace).withName(runName).get();
        if (pr == null || pr.getStatus() == null || pr.getStatus().getChildReferences() == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        pr.getStatus().getChildReferences().forEach(ref -> {
            if (ref.getName() != null) {
                out.add(ref.getName());
            }
        });
        return out;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -o -pl k8s-core -am test -Dtest=PipelineRunOperationsTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add k8s-core/src/main/java/com/coding/k8score/operations/pipelinerun/ k8s-core/src/test/java/com/coding/k8score/operations/pipelinerun/
git commit -m "feat(pipeline): PipelineRunOperations —— 内联 pipelineSpec、取消、以及「只碰平台自己创建的」硬边界"
```

---

### Task 6: k8s-server 控制器（6 个标准端点 + 取消）

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/controllers/namespace/PipelineRunController.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/controllers/namespace/PipelineRunControllerTest.java`

**Interfaces:**
- Consumes: Task 5 的 `PipelineRunOperations.cancel`
- Produces: `POST /pipelineruns/list` `/list-all` `/` (create) `/` PUT (更新→拒) · `GET /pipelineruns/{name}` `/{name}/yaml` · `DELETE /pipelineruns/{name}` · **`POST /pipelineruns/{name}/cancel`**

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineRunControllerTest {

    @Test
    void declaresThePipelineRunResourceTypeAndUrlPrefix() {
        assertThat(PipelineRunController.class.getAnnotation(
                org.springframework.web.bind.annotation.RequestMapping.class).value())
                .containsExactly("/pipelineruns");
    }

    @Test
    void cancelIsDeclaredAsANonStandardEndpoint() throws Exception {
        // 基类给 6 个标准端点，cancel 是本类加的 —— 它必须真的存在
        assertThat(PipelineRunController.class.getMethod(
                "cancel", String.class, String.class, String.class, String.class)).isNotNull();
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
mvn -o -pl k8s-server -am test -Dtest=PipelineRunControllerTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败。

- [ ] **Step 3: 实现**

```java
package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.pipelinerun.PipelineRunOperations;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.components.ResourceAccessResolver.AccessContext;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 —— Tekton {@code PipelineRun}（{@code tekton.dev/v1}，租户域，边界=分配表三元组）。
 *
 * <p>6 个标准端点由 {@link AbstractNamespacedResourceController} 提供；本类只加一个
 * <b>取消</b>（patch {@code spec.status}，不是删对象 —— 删了历史就没了）。
 * update 在 {@link PipelineRunOperations} 里被明确拒掉：运行不可编辑。
 */
@Tag(name = "资源管理-流水线运行", description = "命名空间内 PipelineRun（租户域，边界=分配表）")
@RestController
@RequestMapping("/pipelineruns")
public class PipelineRunController extends AbstractNamespacedResourceController<PipelineRunDTO> {

    public PipelineRunController(KubernetesOperationsFactory operationsFactory,
                                 ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.PIPELINE_RUN;
    }

    /**
     * 取消一次运行。
     *
     * <p>⚠️ **平台侧的编排（先抓日志、再取消）在 platform-api 的 `PipelineRunService` 里** ——
     * 本端点只做「取消」这一个原子动作。顺序是那条链的全部依据，不该散在两层。
     */
    @PostMapping("/{name}/cancel")
    @Operation(summary = "取消一次流水线运行（patch spec.status，保留对象与历史）")
    public ResponseData<Void> cancel(@PathVariable String name,
                                     @RequestParam(required = false) String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        AccessContext ctx = resolve(tenantId, clusterId, namespace);
        TokenAware<PipelineRunDTO> ops = ops(ctx, clusterId);
        ((PipelineRunOperations) ops).cancel(namespace, name);
        return new ResponseData<>();
    }
}
```

⚠️ `ops(ctx, clusterId)` 返回的是 `NamespacedOperations<T>`，要调 `cancel` 得转型。
为避免两层都不安全的裸转型，**在 `NamespacedOperations<T>` 上加一个 default 方法**：

```java
    /**
     * 该资源是否支持取消。默认不支持 —— 与 {@link #listAll} 同一条判断：
     * 「不支持」必须**响亮**，不能静默变成 no-op。
     */
    default void cancel(String namespace, String name) {
        throw new CloudPlatformException(EnumResponseType.OPERATION_NOT_SUPPORTED,
                "本资源不支持取消（" + getClass().getSimpleName() + " 未实现 cancel）");
    }
```

于是控制器里是 `ops(ctx, clusterId).cancel(namespace, name);` —— **无转型**。
`PipelineRunOperations.cancel` 加 `@Override`。

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -o -pl k8s-server -am test -Dtest=PipelineRunControllerTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add k8s-server/src/main/java/com/coding/k8sserver/controllers/namespace/PipelineRunController.java k8s-core/src/main/java/com/coding/k8score/operations/ k8s-server/src/test/java/com/coding/k8sserver/controllers/namespace/
git commit -m "feat(pipeline): k8s-server 的 PipelineRun 控制器（6 标准端点 + 取消）"
```

---

### Task 7: 按需 watch 管理器 + 回调

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/pipeline/PipelineRunWatchManager.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/pipeline/PipelineRunCallbackClient.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/pipeline/PipelineRunWatchManagerTest.java`

**Interfaces:**
- Consumes: Task 5 的 `PipelineRunOperations`
- Produces: `watch(namespace, runName)` / `closeRun(namespace, runName)` / `activeRuns()`（台账快照）/ `pending()`（待补集合）；`PipelineRunCallbackClient.finished(clusterId, namespace, runName)` → `boolean`

> **为什么要「按需」而不是常驻 informer**：用户 2026-10-15 两次追问后的结论（spec §4.1）——
> 连接随运行结束而结束，**空闲时零连接**。

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.k8sserver.pipeline;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineRunWatchManagerTest {

    /** 只测**台账与超时判定** —— 真正的 watch 调用靠部署后冒烟。 */
    private final PipelineRunWatchManager mgr = new PipelineRunWatchManager(null, null);

    @Test
    void anOpenedRunShowsUpInTheLedgerWithItsStartTime() {
        Instant t0 = Instant.parse("2026-10-15T10:00:00Z");
        mgr.recordOpened("c1", "ns1", "r1", t0);

        assertThat(mgr.activeRuns()).singleElement().satisfies(r -> {
            assertThat(r.clusterId()).isEqualTo("c1");
            assertThat(r.namespace()).isEqualTo("ns1");
            assertThat(r.runName()).isEqualTo("r1");
            assertThat(r.startedAt()).isEqualTo(t0);
        });
    }

    @Test
    void aRunOverItsTimeoutIsReportedButNothingElseIs() {
        Instant now = Instant.parse("2026-10-15T10:31:00Z");
        mgr.recordOpened("c1", "ns1", "old", now.minus(Duration.ofMinutes(31)));
        mgr.recordOpened("c1", "ns1", "fresh", now.minus(Duration.ofMinutes(5)));

        // ⚠️ 只看台账、**不打集群** —— 这是本方法之所以不是"轮询"的全部理由
        assertThat(mgr.overdue(now, Duration.ofMinutes(30)))
                .extracting(PipelineRunWatchManager.ActiveRun::runName)
                .containsExactly("old");
    }

    @Test
    void closingARunRemovesItFromTheLedger() {
        mgr.recordOpened("c1", "ns1", "r1", Instant.now());
        mgr.recordClosed("c1", "ns1", "r1");
        assertThat(mgr.activeRuns()).isEmpty();
    }

    @Test
    void closingAnUnknownRunIsANoOpNotAnError() {
        // 幂等：回调成功与 watch 关闭可能以任意顺序发生
        mgr.recordClosed("c1", "ns1", "ghost");
        assertThat(mgr.activeRuns()).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
mvn -o -pl k8s-server -am test -Dtest=PipelineRunWatchManagerTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败。

- [ ] **Step 3: 实现**

`PipelineRunCallbackClient.java`：

```java
package com.coding.k8sserver.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * 把「某个 run 终结了」告诉 platform-api。
 *
 * <p>⚠️ 这是**回环调用**（同机 `127.0.0.1`），不跨网络边界。
 * 鉴权靠一个共享密钥 —— platform-api 是单体，没法只给这一个端点绑 127.0.0.1（spec §4.5）。
 */
@Slf4j
@Component
public class PipelineRunCallbackClient {

    private final RestClient http;
    private final String platformUrl;
    private final String token;

    public PipelineRunCallbackClient(
            @Value("${platform.pipeline.callback.url:http://127.0.0.1:8081}") String platformUrl,
            @Value("${platform.pipeline.archive.internal-token:}") String token) {
        this.platformUrl = platformUrl;
        this.token = token;
        HttpClient hc = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(hc);
        f.setReadTimeout(Duration.ofSeconds(30));     // 归档要抓日志，给宽一点
        this.http = RestClient.builder().requestFactory(f).build();
    }

    /** @return 是否被确认（2xx）。**false 会进「待补」集合**，由节拍器重投。 */
    public boolean finished(String clusterId, String namespace, String runName) {
        try {
            http.post()
                    .uri(platformUrl + "/internal/pipeline-run/finished")
                    .header("X-Internal-Token", token == null ? "" : token)
                    .body(Map.of("clusterId", clusterId, "namespace", namespace, "runName", runName))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("归档回调失败（进待补集合，由节拍器重投）run={}/{}", namespace, runName, e);
            return false;
        }
    }
}
```

`PipelineRunWatchManager.java`：

```java
package com.coding.k8sserver.pipeline;

import com.coding.k8score.operations.pipelinerun.PipelineRunOperations;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.tekton.v1.PipelineRun;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **活动 watch 台账**。设计见 spec §4.1 —— 只在有运行在跑时开一条 watch，结束就关，空闲时零连接。
 *
 * <p>台账同时是**超时判定的数据源**：{@link #overdue} 只看它，**不打集群** ——
 * 这正是它不算"轮询"的理由。
 */
@Slf4j
@Component
public class PipelineRunWatchManager {

    /** 一条活动中的 watch。{@code startedAt} 从集群对象的 creationTimestamp 来，用于超时判定。 */
    public record ActiveRun(String clusterId, String namespace, String runName, Instant startedAt) { }

    private final PipelineRunOperations ops;
    private final PipelineRunCallbackClient callback;

    /** key = clusterId + "/" + namespace + "/" + runName */
    private final Map<String, ActiveRun> active = new ConcurrentHashMap<>();
    private final Map<String, Watch> watches = new ConcurrentHashMap<>();
    /** 回调没被确认的（platform-api 重启/不可达）—— 节拍器会重投 */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    public PipelineRunWatchManager(PipelineRunOperations ops, PipelineRunCallbackClient callback) {
        this.ops = ops;
        this.callback = callback;
    }

    private static String key(String clusterId, String namespace, String runName) {
        return clusterId + "/" + namespace + "/" + runName;
    }

    public void recordOpened(String clusterId, String namespace, String runName, Instant startedAt) {
        active.put(key(clusterId, namespace, runName),
                new ActiveRun(clusterId, namespace, runName, startedAt));
    }

    public void recordClosed(String clusterId, String namespace, String runName) {
        String k = key(clusterId, namespace, runName);
        active.remove(k);
        Watch w = watches.remove(k);
        if (w != null) {
            w.close();                                // 连接随运行结束而结束
        }
    }

    public List<ActiveRun> activeRuns() {
        return new ArrayList<>(active.values());
    }

    /** 超过 {@code timeout} 还没结束的。**只看台账**。 */
    public List<ActiveRun> overdue(Instant now, Duration timeout) {
        return active.values().stream()
                .filter(r -> r.startedAt() != null && r.startedAt().plus(timeout).isBefore(now))
                .toList();
    }

    public Set<String> pending() {
        return new LinkedHashSet<>(pending);
    }

    /**
     * watch 收到终结事件 → 回调。**不确认就进待补集合**，由节拍器重投
     * （platform-api 重启期间的回调会以这种方式补上）。
     */
    void onTerminated(String clusterId, String namespace, String runName) {
        String k = key(clusterId, namespace, runName);
        if (callback.finished(clusterId, namespace, runName)) {
            pending.remove(k);
            recordClosed(clusterId, namespace, runName);
        } else {
            pending.add(k);
        }
    }

    /** 节拍器重投待补的。 */
    void retryPending() {
        for (String k : List.copyOf(pending)) {
            String[] parts = k.split("/", 3);
            if (parts.length != 3) {
                pending.remove(k);
                continue;
            }
            if (callback.finished(parts[0], parts[1], parts[2])) {
                pending.remove(k);
                recordClosed(parts[0], parts[1], parts[2]);
            }
        }
    }

    /** 台账快照，给启动扫描用（它要补开 watch）。 */
    public Map<String, ActiveRun> snapshot() {
        return new LinkedHashMap<>(active);
    }
}
```

⚠️ **`watch(...)` 真正的开启**（`client.resources(PipelineRun.class)...watch(...)`）与
`onTerminated` 的接线放在 `PipelineRunOperations.watchRun(...)` 里，由 Task 12 的创建路径调用 ——
本任务只交付**台账与回调**这两个可测的部分，watch 的实际开启与关闭在 Task 8 的启动扫描里一并接上
（它需要同样的接线）。**这是刻意的**：watch 的形态本身在 spec §11 里标了待实测。

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -o -pl k8s-server -am test -Dtest=PipelineRunWatchManagerTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add k8s-server/src/main/java/com/coding/k8sserver/pipeline/ k8s-server/src/test/java/com/coding/k8sserver/pipeline/
git commit -m "feat(pipeline): 按需 watch 的台账与回调（空闲时零连接）"
```

---

### Task 8: 节拍器（超时 + 待补）与启动扫描

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/pipeline/PipelineRunTicker.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/pipeline/PipelineRunStartupSweep.java`
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/K8SServerApplication.java`（若未开 `@EnableScheduling` 则加上）
- Test: `k8s-server/src/test/java/com/coding/k8sserver/pipeline/PipelineRunTickerTest.java`

**Interfaces:**
- Consumes: Task 7 的 `PipelineRunWatchManager`
- Produces: `PipelineRunTicker.tick(Instant now)`（**纯逻辑、可单测**，`@Scheduled` 只是它的调用者）；`PipelineRunStartupSweep.sweep()`

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.k8sserver.pipeline;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineRunTickerTest {

    /** 只测「每一拍该做什么决定」——真正的取消/重投动作由它调用的对象负责。 */
    static class RecordingTicker extends PipelineRunTicker {
        final List<String> cancelled = new ArrayList<>();
        final List<String> retried = new ArrayList<>();

        RecordingTicker(PipelineRunWatchManager mgr, Duration timeout) {
            super(mgr, timeout, null);
        }

        @Override
        void cancelOverdue(PipelineRunWatchManager.ActiveRun run) {
            cancelled.add(run.runName());
        }

        @Override
        void retryPending() {
            retried.add("tick");
        }
    }

    @Test
    void anOverdueRunIsCancelledAndPendingIsRetried() {
        PipelineRunWatchManager mgr = new PipelineRunWatchManager(null, null);
        Instant now = Instant.parse("2026-10-15T10:31:00Z");
        mgr.recordOpened("c1", "ns1", "old", now.minus(Duration.ofMinutes(31)));
        mgr.recordOpened("c1", "ns1", "fresh", now.minus(Duration.ofMinutes(1)));

        RecordingTicker t = new RecordingTicker(mgr, Duration.ofMinutes(30));
        t.tick(now);

        assertThat(t.cancelled).containsExactly("old");
        assertThat(t.retried).containsExactly("tick");     // 待补重投**每一拍都做**
    }

    @Test
    void nothingOverdueMeansNothingCancelledButStillRetries() {
        PipelineRunWatchManager mgr = new PipelineRunWatchManager(null, null);
        RecordingTicker t = new RecordingTicker(mgr, Duration.ofMinutes(30));
        t.tick(Instant.now());

        assertThat(t.cancelled).isEmpty();
        assertThat(t.retried).containsExactly("tick");
    }

    @Test
    void withNoActiveRunsTheTickTouchesNoCluster() {
        // ⭐ 这是「它不是轮询」的正面断言：台账空 → 一次集群调用都不发
        PipelineRunWatchManager mgr = new PipelineRunWatchManager(null, null);
        RecordingTicker t = new RecordingTicker(mgr, Duration.ofMinutes(30));
        t.tick(Instant.now());
        assertThat(mgr.activeRuns()).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
mvn -o -pl k8s-server -am test -Dtest=PipelineRunTickerTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败。

- [ ] **Step 3: 实现**

`PipelineRunTicker.java`：

```java
package com.coding.k8sserver.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 节拍器。每一拍干两件事：
 * <ol>
 *   <li><b>超时判定</b> —— 看台账里有没有跑太久的，有就让平台去取消（先抓日志再取消，见 platform-api 侧）</li>
 *   <li><b>重投待补</b> —— 上一拍回调没被确认的</li>
 * </ol>
 *
 * <p>⚠️ <b>它只看台账、不打集群</b>，而且台账空时它是空转 ——
 * 这是它不算「轮询集群」的全部理由（spec §4.2）。
 *
 * <p>设计成 {@code tick(Instant)} 是纯逻辑、{@code @Scheduled} 只是调用者，
 * 是为了让「每一拍该做什么决定」可单测。
 */
@Slf4j
@Component
public class PipelineRunTicker {

    private final PipelineRunWatchManager watch;
    private final Duration timeout;

    public PipelineRunTicker(PipelineRunWatchManager watch,
                             @Value("${platform.pipeline.run.timeout:30m}") Duration timeout,
                             @Value("${platform.pipeline.archive.enabled:true}") Boolean enabled) {
        this.watch = watch;
        this.timeout = timeout;
        this.enabled = enabled;
    }

    private final Boolean enabled;

    @Scheduled(fixedDelayString = "${platform.pipeline.run.timeout-check:30s}")
    public void scheduledTick() {
        if (!Boolean.TRUE.equals(enabled)) {
            return;
        }
        tick(Instant.now());
    }

    /** 可单测的入口。 */
    void tick(Instant now) {
        for (PipelineRunWatchManager.ActiveRun run : watch.overdue(now, timeout)) {
            cancelOverdue(run);
        }
        retryPending();
    }

    /** 平台判定超时 → 与取消同一条路（**先抓日志、再取消**）。真正的顺序在 platform-api 侧。 */
    void cancelOverdue(PipelineRunWatchManager.ActiveRun run) {
        log.warn("流水线运行超过 {} 未结束，交由平台取消：{}/{}",
                timeout, run.namespace(), run.runName());
        watch.onTerminatedAsTimeout(run.clusterId(), run.namespace(), run.runName());
    }

    void retryPending() {
        watch.retryPending();
    }
}
```

⚠️ `PipelineRunWatchManager` 需要补一个方法（Task 7 里加，本步一并补上）：

```java
    /**
     * 超时终结。与 {@link #onTerminated} 走同一条回调 / 待补路径，
     * 区别只在**语义**（platform-api 侧按 run 的结束原因记 {@code TIMEOUT}）。
     */
    void onTerminatedAsTimeout(String clusterId, String namespace, String runName) {
        onTerminated(clusterId, namespace, runName);
    }
```

`PipelineRunStartupSweep.java`：

```java
package com.coding.k8sserver.pipeline;

import com.coding.k8score.operations.pipelinerun.PipelineRunOperations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时扫一次：集群里还有**带平台标签且还在 running** 的 run 吗？有就补开 watch。
 *
 * <p>补的是「k8s-server 重启时正好有运行在跑」这个洞（spec §4.1.2）。
 * <b>这是初始化，不是轮询</b> —— 它只在启动时发生一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineRunStartupSweep {

    private final PipelineRunOperations ops;
    private final PipelineRunWatchManager watch;

    @EventListener(ApplicationReadyEvent.class)
    public void sweep() {
        try {
            // 集群级 list，一次调用 —— 走 listAll（已覆写，带平台标签过滤）
            ops.listAll(null, null).stream()
                    .filter(r -> "RUNNING".equals(r.getStatus()))
                    .forEach(r -> {
                        log.info("启动扫描：补盯一个还在跑的运行 {}/{}", r.getNamespace(), r.getName());
                        watch.recordOpened(null, r.getNamespace(), r.getName(), java.time.Instant.now());
                    });
        } catch (Exception e) {
            // ⚠️ 启动扫描失败**不能拦住应用启动** —— 它只是补漏，主路径是按需 watch
            log.warn("流水线运行启动扫描失败（不影响启动）", e);
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -o -pl k8s-server -am test -Dtest=PipelineRunTickerTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add k8s-server/src/main/java/com/coding/k8sserver/pipeline/ k8s-server/src/main/java/com/coding/k8sserver/K8SServerApplication.java k8s-server/src/test/java/com/coding/k8sserver/pipeline/
git commit -m "feat(pipeline): 节拍器（超时 + 待补，只看台账不打集群）与启动扫描"
```

---

## Part C —— platform-api：迁移、服务、归档

### Task 9: 迁移（三张表 + 一列 + 5 个码）

**Files:**
- Create: `platform-data/src/main/resources/db/migration/V2026_10_15_1__pipeline.sql`

**Interfaces:**
- Produces: `platform_pipeline` / `platform_pipeline_run` / `platform_pipeline_run_log` 三张表；`platform_credential.usable_by_pipelines` 列；13 行 API 权限 + 1 行 Page 权限；角色关联 39 行（13 × 3）

> ⚠️ **13 行 API 权限一个都不能少**：`PermissionCrossCheckRunner` 是 **fail-closed** ——
> 有端点没权限行，应用**直接拒启**。
> ⚠️ `/internal/pipeline-run/finished` **不进权限表** —— 它不是用户端点，
> 要加进 `ExemptPaths`（Task 13）。漏了的话一样拒启。

- [ ] **Step 1: 写迁移文件**

```sql
-- ============================================================================================
-- S3.5 第 ① 片：CI（Tekton）—— 三张表 + 一列 + 5 个权限码
-- ============================================================================================
-- 设计全文见 docs/superpowers/specs/2026-10-15-ci-tekton-design.md。
--
-- 范围：流水线定义（画布 JSON）+ 运行记录 + 运行日志 + 连接的「可在流水线里使用」开关。
--
-- 权限码（5 个，落在 14 行权限上）：
--   · tenant:pipeline:read    —— API，7 行：list / get / node-types / git-connections /
--                                preview / run/list / run/get
--   · tenant:pipeline:manage  —— API，3 行：create / update / delete
--   · tenant:pipeline:run     —— API，2 行：run / run/cancel
--   · tenant:pipeline:logs    —— API，1 行：run/logs。**单独成码**：日志是容器输出，
--                                可能含敏感内容（台账 S3.5.12 的「定义/触发/看日志分开授权」）
--   · tenant:page:pipeline    —— Page，1 行，页面可见性
--
-- ⚠️ 必须是 tenant: 前缀：RoleService.assertScopeMatches 规定 TENANT 角色只能持 tenant: 码。
--
-- ⚠️ 同一 code 对应多行是既有的 ANY-of 约定（RoleService「权限点 code 自 Task 4 起不再唯一」）。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启。
-- ⚠️ 重放后需重新登录或等权限缓存 TTL（30s）。
-- ⚠️ 本仓没有 Flyway，本文件是手工重放的；启动日志里永远不会出现 Migrating schema。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符，否则整个文件重放失败。
--
-- ⚠️⚠️ permission_id 必须与上面 platform_permission 的 id 逐字一致。本仓**不加外键**，
--    写错了不会报任何错，只留一条悬空关联 → 菜单不显示、路由跳回总览，且日志一片安静。
--    重放后**必须做 join 对账**，不能只数行数。
-- ============================================================================================

-- ===== 表 1：流水线（平台自己的表 —— 它不是一个 K8s 对象）=====
CREATE TABLE IF NOT EXISTS `platform_pipeline` (
  `id`          varchar(64)  NOT NULL COMMENT 'ULIDGenerator',
  `cluster_id`  varchar(64)  NOT NULL,
  `namespace`   varchar(253) NOT NULL COMMENT '命名空间级资源 —— 跟 Deployment/Service 一样',
  `name`        varchar(253) NOT NULL COMMENT '同命名空间内唯一（代码保证，本仓不加外键/唯一索引以外的约束）',
  `description` varchar(255)          DEFAULT NULL,
  `canvas_json` text         NOT NULL COMMENT '画布：节点 + 连线。**流水线的唯一真相源** —— 集群里不留 Pipeline 对象',
  `created_by`  varchar(64)           DEFAULT NULL,
  `created_at`  datetime              DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_pipeline`(`cluster_id` ASC, `namespace` ASC, `name` ASC) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC
  COMMENT='流水线（画布 JSON 是唯一真相源）';

-- ===== 表 2：运行记录 =====
CREATE TABLE IF NOT EXISTS `platform_pipeline_run` (
  `id`              varchar(64)  NOT NULL,
  `pipeline_id`     varchar(64)  NOT NULL,
  `cluster_id`      varchar(64)  NOT NULL,
  `namespace`       varchar(253) NOT NULL,
  `run_name`        varchar(253) NOT NULL COMMENT '集群里的 PipelineRun 名，**由平台先生成** —— 归档幂等键',
  `status`          varchar(32)  NOT NULL COMMENT 'RUNNING / SUCCEEDED / FAILED / CANCELLED / TIMEOUT',
  `reason`          varchar(255)          DEFAULT NULL,
  `triggered_by`    varchar(64)           DEFAULT NULL,
  `start_time`      varchar(64)           DEFAULT NULL,
  `completion_time` varchar(64)           DEFAULT NULL,
  `steps_json`      text                  DEFAULT NULL COMMENT '每个节点的状态与耗时',
  `created_at`      datetime              DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_run_name`(`run_name` ASC) USING BTREE,
  INDEX `idx_pipeline`(`pipeline_id` ASC) USING BTREE,
  INDEX `idx_completion`(`completion_time` ASC) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC
  COMMENT='流水线运行记录（集群对象归档后删掉，本表是运行历史的唯一来源）';

-- ===== 表 3：运行日志 =====
CREATE TABLE IF NOT EXISTS `platform_pipeline_run_log` (
  `id`         varchar(64) NOT NULL,
  `run_id`     varchar(64) NOT NULL,
  `node_name`  varchar(253) NOT NULL COMMENT '画布上的节点 id（= Tekton task 名 = step 容器名的一部分）',
  `content`    mediumtext             COMMENT '**单步上限 1 MB**，超出截断',
  `truncated`  tinyint     NOT NULL DEFAULT 0,
  `size_bytes` int         NOT NULL DEFAULT 0,
  `created_at` datetime             DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_run_node`(`run_id` ASC, `node_name` ASC) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC
  COMMENT='流水线运行日志（归档时抓下来；集群里的 Pod 随后被删）';

-- ===== platform_credential 加一列：允许在流水线里使用 =====
-- 默认 0（关）。S0.2 特意留着「租户侧没有凭据码」这条边界，CI 正面撞上了它 ——
-- 所以改成**逐条显式授权**，管理员看得见哪条连接被子租户引用（spec §5.3）。
ALTER TABLE `platform_credential`
  ADD COLUMN IF NOT EXISTS `usable_by_pipelines` tinyint NOT NULL DEFAULT 0
  COMMENT '1=允许租户在流水线的 git/推送节点里引用这条连接；默认 0（关）';

-- ===== 权限行：API 域 13 行 + Page 域 1 行 =====
INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_pipeline_list',      'API','/pipeline/list',        'POST','tenant:pipeline:read',   '流水线列表'),
 ('perm_pipeline_get',       'API','/pipeline/get',         'POST','tenant:pipeline:read',   '流水线详情（含画布 JSON）'),
 ('perm_pipeline_nodetypes', 'API','/pipeline/node-types',  'POST','tenant:pipeline:read',   '内置节点类型与表单 schema'),
 ('perm_pipeline_gitconns',  'API','/pipeline/git-connections','POST','tenant:pipeline:read','可在流水线里使用的 git 连接（窄投影）'),
 ('perm_pipeline_preview',   'API','/pipeline/preview',     'POST','tenant:pipeline:read',   '把画布渲染成 YAML（预览）'),
 ('perm_pipeline_runlist',   'API','/pipeline/run/list',    'POST','tenant:pipeline:read',   '运行历史'),
 ('perm_pipeline_runget',    'API','/pipeline/run/get',     'POST','tenant:pipeline:read',   '一次运行的详情'),
 ('perm_pipeline_create',    'API','/pipeline/create',      'POST','tenant:pipeline:manage', '新建流水线'),
 ('perm_pipeline_update',    'API','/pipeline/update',      'POST','tenant:pipeline:manage', '更新流水线'),
 ('perm_pipeline_delete',    'API','/pipeline/delete',      'POST','tenant:pipeline:manage', '删除流水线'),
 ('perm_pipeline_run',       'API','/pipeline/run',         'POST','tenant:pipeline:run',    '触发一次运行'),
 ('perm_pipeline_runcancel', 'API','/pipeline/run/cancel',  'POST','tenant:pipeline:run',    '取消一次运行'),
 ('perm_pipeline_runlogs',   'API','/pipeline/run/logs',    'POST','tenant:pipeline:logs',   '看运行日志（单独成码：日志可能含敏感内容）'),
 ('perm_page_pipeline',      'Page','/pipelines',           'view','tenant:page:pipeline',   '流水线页面可见性')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

-- ===== 角色关联：13 行 × 3 角色 = 39 行 =====
-- ⚠️ member 拿 read + logs + run，**不拿 manage**。理由（spec §5.1）：
--    run 只能跑**别人定义好的**流水线；要往流水线里塞任意命令需要 manage。
--    这条把「构建 Pod 能跑任意代码」的风险面收敛到了 manage 上。
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_tadmin_pl_list',   'builtin_role_tenant_admin','perm_pipeline_list'),
 ('rp_tadmin_pl_get',    'builtin_role_tenant_admin','perm_pipeline_get'),
 ('rp_tadmin_pl_nt',     'builtin_role_tenant_admin','perm_pipeline_nodetypes'),
 ('rp_tadmin_pl_gc',     'builtin_role_tenant_admin','perm_pipeline_gitconns'),
 ('rp_tadmin_pl_pv',     'builtin_role_tenant_admin','perm_pipeline_preview'),
 ('rp_tadmin_pl_rl',     'builtin_role_tenant_admin','perm_pipeline_runlist'),
 ('rp_tadmin_pl_rg',     'builtin_role_tenant_admin','perm_pipeline_runget'),
 ('rp_tadmin_pl_c',      'builtin_role_tenant_admin','perm_pipeline_create'),
 ('rp_tadmin_pl_u',      'builtin_role_tenant_admin','perm_pipeline_update'),
 ('rp_tadmin_pl_d',      'builtin_role_tenant_admin','perm_pipeline_delete'),
 ('rp_tadmin_pl_run',    'builtin_role_tenant_admin','perm_pipeline_run'),
 ('rp_tadmin_pl_cancel', 'builtin_role_tenant_admin','perm_pipeline_runcancel'),
 ('rp_tadmin_pl_logs',   'builtin_role_tenant_admin','perm_pipeline_runlogs'),
 ('rp_tadmin_pl_page',   'builtin_role_tenant_admin','perm_page_pipeline'),
 ('rp_tmember_pl_list',  'builtin_role_tenant_member','perm_pipeline_list'),
 ('rp_tmember_pl_get',   'builtin_role_tenant_member','perm_pipeline_get'),
 ('rp_tmember_pl_nt',    'builtin_role_tenant_member','perm_pipeline_nodetypes'),
 ('rp_tmember_pl_gc',    'builtin_role_tenant_member','perm_pipeline_gitconns'),
 ('rp_tmember_pl_pv',    'builtin_role_tenant_member','perm_pipeline_preview'),
 ('rp_tmember_pl_rl',    'builtin_role_tenant_member','perm_pipeline_runlist'),
 ('rp_tmember_pl_rg',    'builtin_role_tenant_member','perm_pipeline_runget'),
 ('rp_tmember_pl_run',   'builtin_role_tenant_member','perm_pipeline_run'),
 ('rp_tmember_pl_cancel','builtin_role_tenant_member','perm_pipeline_runcancel'),
 ('rp_tmember_pl_logs',  'builtin_role_tenant_member','perm_pipeline_runlogs'),
 ('rp_tmember_pl_page',  'builtin_role_tenant_member','perm_page_pipeline'),
 ('rp_admin_pl_list',    'builtin_role_admin','perm_pipeline_list'),
 ('rp_admin_pl_get',     'builtin_role_admin','perm_pipeline_get'),
 ('rp_admin_pl_nt',      'builtin_role_admin','perm_pipeline_nodetypes'),
 ('rp_admin_pl_gc',      'builtin_role_admin','perm_pipeline_gitconns'),
 ('rp_admin_pl_pv',      'builtin_role_admin','perm_pipeline_preview'),
 ('rp_admin_pl_rl',      'builtin_role_admin','perm_pipeline_runlist'),
 ('rp_admin_pl_rg',      'builtin_role_admin','perm_pipeline_runget'),
 ('rp_admin_pl_c',       'builtin_role_admin','perm_pipeline_create'),
 ('rp_admin_pl_u',       'builtin_role_admin','perm_pipeline_update'),
 ('rp_admin_pl_d',       'builtin_role_admin','perm_pipeline_delete'),
 ('rp_admin_pl_run',     'builtin_role_admin','perm_pipeline_run'),
 ('rp_admin_pl_cancel',  'builtin_role_admin','perm_pipeline_runcancel'),
 ('rp_admin_pl_logs',    'builtin_role_admin','perm_pipeline_runlogs'),
 ('rp_admin_pl_page',    'builtin_role_admin','perm_page_pipeline')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
```

- [ ] **Step 2: 重放并做 join 对账**

```bash
# 用 JDBC 工具执行本文件（本仓没有 mysql CLI），然后对账：
#   ① SELECT count(*) FROM platform_role_permission WHERE permission_id LIKE 'perm_pipeline%' OR permission_id='perm_page_pipeline';
#      → 必须是 39
#   ② join 对账：每一条 rp_%_pl_* 都能 join 到真实权限行
#      SELECT rp.id FROM platform_role_permission rp LEFT JOIN platform_permission p ON p.id = rp.permission_id
#      WHERE rp.id LIKE 'rp_%_pl_%' AND p.id IS NULL;   → 必须 0 行
#   ③ 全表悬空扫描（S0.2 的教训，别只数行数）
#      SELECT rp.id FROM platform_role_permission rp LEFT JOIN platform_permission p ON p.id = rp.permission_id
#      WHERE p.id IS NULL;                              → 必须 0 行
```
Expected: 39 行、两个悬空扫描都是 0。

- [ ] **Step 3: 提交**

```bash
git add platform-data/src/main/resources/db/migration/V2026_10_15_1__pipeline.sql
git commit -m "feat(pipeline): 迁移 —— 三张表 + usable_by_pipelines 列 + 5 个码（14 行权限 + 39 行角色关联）"
```

---

### Task 10: 流水线 CRUD 与节点元数据

> ### ⚠️ 本任务已按「渲染搬进 k8s-server」执行（2026-10-15 用户裁定，spec §7 的改定块）
>
> **下面 Step 1/3 里凡让 platform-api 碰 `PipelineNodeRegistry` / `PipelineRenderer` 的，一律作废。**
> 实际交付形状：
>
> | 层 | 交付 |
> |---|---|
> | **k8s-core** | `PipelineCanvasOperations`（`@Component`：`nodeTypes()` / `renderYaml(canvasJson)` / `render(canvasJson)`）+ `PipelineNodeTypeView` |
> | **k8s-server** | `PipelineRunController` 加 `POST /pipelineruns/node-types`、`POST /pipelineruns/render` |
> | **platform-api** | `K8sPipelineClient`（转发这两个动作）+ `PipelineService`（只做自己那张表的 CRUD + **调 k8s-server 的 render 来做「保存时就拒掉带环画布」**）+ `PipelineController` |
>
> 于是 platform-api 里**没有** `import com.coding.k8score.*`，pom 不动。
> Task 10 的测试相应改成「打桩 `K8sPipelineClient`」而不是「构造真渲染器」。
> Task 12 的 `run` 同样：发**画布 JSON**，不发渲染结果。

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/services/PipelineService.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/controllers/PipelineController.java`
- Create: `platform-data/src/main/java/com/coding/data/mapper/system/PlatformPipelineMapper.java` + `platform-data/src/main/resources/mapper/system/PlatformPipelineMapper.xml`
- Create: `platform-data/src/main/java/com/coding/data/models/system/PlatformPipeline.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/PipelineServiceTest.java`

**Interfaces:**
- Consumes: Task 1-3 的节点注册表与渲染器
- Produces: `PipelineService.list/get/create/update/delete/nodeTypes/preview`；`PipelineController` 的 8 个端点

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.k8score.pipeline.NodeField;
import com.coding.k8score.pipeline.PipelineNodeRegistry;
import com.coding.k8score.pipeline.PipelineRenderer;
import com.coding.k8score.pipeline.nodes.GitCloneNode;
import com.coding.k8score.pipeline.nodes.ImagePushNode;
import com.coding.k8score.pipeline.nodes.ShellNode;
import com.coding.platformapi.models.PipelineNodeTypeView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineServiceTest {

    private final PipelineNodeRegistry registry =
            new PipelineNodeRegistry(List.of(new GitCloneNode(), new ShellNode(), new ImagePushNode()));
    private final PipelineService svc = new PipelineService(null, registry, new PipelineRenderer(registry));

    @Test
    void nodeTypesExposeSchemaAndPresetsForTheFrontend() {
        // 前端靠它渲染属性面板 —— 加字段不用改前端，就是靠这条
        List<PipelineNodeTypeView> types = svc.nodeTypes();
        assertThat(types).extracting(PipelineNodeTypeView::type)
                .containsExactlyInAnyOrder("git", "shell", "image");

        PipelineNodeTypeView shell = types.stream()
                .filter(t -> t.type().equals("shell")).findFirst().orElseThrow();
        assertThat(shell.fields()).extracting(NodeField::key)
                .containsExactly("preset", "image", "command", "workDir");
        assertThat(shell.presets()).isNotEmpty();
    }

    @Test
    void anImageFieldIsMarkedSoTheFrontendCanAttachAutocomplete() {
        PipelineNodeTypeView shell = svc.nodeTypes().stream()
                .filter(t -> t.type().equals("shell")).findFirst().orElseThrow();
        assertThat(shell.fields()).filteredOn(f -> f.key().equals("image"))
                .singleElement()
                .satisfies(f -> assertThat(f.type()).isEqualTo(NodeField.FieldType.IMAGE));
    }

    @Test
    void previewRejectsACanvasWithACycle() {
        // ⭐ Review Focus #1 的另一半：前端实时提示之外，**后端也必须拒**
        String canvas = """
                {"nodes":[{"id":"a","type":"git","config":{"repo":"r"}},
                          {"id":"b","type":"shell","config":{"command":"c"}}],
                 "edges":[{"from":"a","to":"b"},{"from":"b","to":"a"}]}""";
        assertThatThrownBy(() -> svc.preview(canvas))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("环");
    }

    @Test
    void previewRejectsAnUnknownNodeType() {
        String canvas = """
                {"nodes":[{"id":"a","type":"nope","config":{}}],"edges":[]}""";
        assertThatThrownBy(() -> svc.preview(canvas))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void previewReturnsRenderedYamlContainingBothTaskNames() {
        String canvas = """
                {"nodes":[{"id":"clone","type":"git","config":{"repo":"https://g/r.git"}},
                          {"id":"build","type":"shell","config":{"preset":"custom","image":"alpine:3","command":"echo hi"}}],
                 "edges":[{"from":"clone","to":"build"}]}""";
        String yaml = svc.preview(canvas);
        assertThat(yaml).contains("clone").contains("build").contains("runAfter");
    }

    @Test
    void aPipelineNameMustBeDnsLabelish() {
        assertThatThrownBy(() -> svc.validateName("Bad_Name"))
                .isInstanceOf(CloudPlatformException.class);
        svc.validateName("good-name-1");
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
mvn -o -pl platform-api -am test -Dtest=PipelineServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败。

- [ ] **Step 3: 实现**

`PlatformPipeline.java`（照 `PlatformCredential` 的写法）、`PlatformPipelineMapper.java` + XML（`selectAll/selectById/selectByKey/insert/update/deleteById`）。

`PipelineNodeTypeView.java`：

```java
package com.coding.platformapi.models;

import com.coding.k8score.pipeline.BuildPreset;
import com.coding.k8score.pipeline.NodeField;

import java.util.List;

/** 一个内置节点暴露给前端的元数据。**前端靠它渲染属性面板** —— 加字段不用改前端。 */
public record PipelineNodeTypeView(String type, List<NodeField> fields, List<BuildPreset> presets) {
}
```

`PipelineService.java`：

```java
package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PipelineDTO;
import com.coding.k8score.pipeline.BuildPreset;
import com.coding.k8score.pipeline.PipelineCanvas;
import com.coding.k8score.pipeline.PipelineNode;
import com.coding.k8score.pipeline.PipelineNodeRegistry;
import com.coding.k8score.pipeline.PipelineRenderer;
import com.coding.platformapi.models.PipelineNodeTypeView;
import com.coding.data.mapper.system.PlatformPipelineMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 流水线定义。**画布 JSON 是唯一真相源**（spec §3.2）—— 集群里不留 {@code Pipeline} 对象，
 * 每次运行即时渲染并内联进 {@code PipelineRun}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineService {

    /** DNS_LABEL 的子集 —— 名字会进 PipelineRun 名（K8s 对象名），必须合法。 */
    private static final Pattern NAME = Pattern.compile("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");

    /** 隐式共享 workspace 的名字。**用户不感知** —— 平台注入到所有节点（spec §3.3）。 */
    public static final String WORKSPACE = "ws";

    private final PlatformPipelineMapper mapper;
    private final PipelineNodeRegistry nodes;
    private final PipelineRenderer renderer;

    /** 前端渲染属性面板用。**这是「加一个节点不用改前端」的落点。** */
    public List<PipelineNodeTypeView> nodeTypes() {
        return nodes.all().stream()
                .sorted(Comparator.comparing(PipelineNode::type))
                .map(n -> new PipelineNodeTypeView(n.type(), n.formSchema().fields(), BuildPreset.all()))
                .toList();
    }

    public void validateName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                    "流水线名只能是小写字母/数字/连字符，且以字母或数字开头结尾：" + name);
        }
    }

    /**
     * 把画布渲染成 YAML 给前端预览。**它同时是渲染器的主要自证**：
     * 平台渲染出来的东西用户能看见（spec §6）。
     */
    public String preview(String canvasJson) {
        PipelineCanvas canvas = PipelineCanvas.fromJson(canvasJson);
        return io.fabric8.kubernetes.client.utils.Serialization.asYaml(
                renderer.render(canvas, WORKSPACE));
    }

    // list / get / create / update / delete 照 CredentialService 的写法落到 mapper，
    // create/update 里都先 validateName + 跑一次 preview（**保存时就拒掉带环的画布**，
    // 而不是等用户点了运行才发现）。
}
```

`PipelineController.java`：8 个 `@PostMapping`（list/get/create/update/delete/node-types/preview + 见下），
`create/update/delete` 标 `@Auditable`。**每个端点的路径必须与 Task 9 的权限行逐字一致**。

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -o -pl platform-api -am test -Dtest=PipelineServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/ platform-data/src/main/java/com/coding/data/ platform-data/src/main/resources/mapper/system/PlatformPipelineMapper.xml platform-api/src/test/java/com/coding/platformapi/services/PipelineServiceTest.java
git commit -m "feat(pipeline): 流水线 CRUD、节点元数据、预览（保存时就拒掉带环的画布）"
```

---

### Task 11: 连接的逐条授权与窄投影

**Files:**
- Modify: `platform-data/src/main/java/com/coding/data/models/system/PlatformCredential.java`（加 `usableByPipelines`）
- Modify: `platform-data/src/main/resources/mapper/system/PlatformCredentialMapper.xml`（列 + 更新）
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/CredentialCreateRequest.java` `CredentialUpdateRequest.java` `CredentialView.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/CredentialService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/controllers/PipelineController.java`（加 `/git-connections`）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/CredentialServiceTest.java`

**Interfaces:**
- Produces: `CredentialService.pipelineUsableConnections(String type)` → `List<ConnectionView>`；`CredentialService.assertUsableByPipelines(String id, String expectedType)`

- [ ] **Step 1: 写失败的测试**

```java
    @Test
    void onlyConnectionsMarkedForPipelinesShowUp() {
        // ⭐ Review Focus #4 的一半：没被授权的连接**不出现**在下拉里
        when(mapper.selectAll()).thenReturn(List.of(
                storedWithPipelineFlag("c1", "git-a", "GIT", "https://a.example.com", true),
                storedWithPipelineFlag("c2", "git-b", "GIT", "https://b.example.com", false),
                storedWithPipelineFlag("c3", "harbor", "HARBOR", "harbor.example.com", true)));

        assertThat(svc.pipelineUsableConnections("GIT"))
                .extracting(ConnectionView::id).containsExactly("c1");
    }

    @Test
    void theProjectionCarriesOnlyThreeFields() {
        when(mapper.selectAll()).thenReturn(List.of(
                storedWithPipelineFlag("c1", "git-a", "GIT", "https://a.example.com", true)));
        ConnectionView v = svc.pipelineUsableConnections("GIT").get(0);
        // 只有 id/name/endpoint —— 与 S3.1 的 /registry/connections 同一形状
        assertThat(v.getClass().getDeclaredFields()).hasSize(3);
    }

    @Test
    void referencingAnUnauthorizedConnectionIsRefusedNotSilentlyAnonymous() {
        // ⭐ Review Focus #4：绕过前端直接打接口，也**不能**静默降级成"匿名去拉"
        when(mapper.selectById("c2")).thenReturn(
                storedWithPipelineFlag("c2", "git-b", "GIT", "https://b.example.com", false));

        assertThatThrownBy(() -> svc.assertUsableByPipelines("c2", "GIT"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("未授权");
    }

    @Test
    void aWrongTypeIsAlsoRefused() {
        when(mapper.selectById("c3")).thenReturn(
                storedWithPipelineFlag("c3", "harbor", "HARBOR", "harbor.example.com", true));

        assertThatThrownBy(() -> svc.assertUsableByPipelines("c3", "GIT"))
                .isInstanceOf(CloudPlatformException.class);
    }
```

- [ ] **Step 2-4: 跑红 → 实现 → 跑绿**

实现要点：

- `PlatformCredential` 加 `private Integer usableByPipelines;`；XML 的 `Base_Column_List` / `resultMap` / `insert` / `update` 四处都要带上它（**漏一处就是"改了不生效"的经典来源**）。
- `CredentialView` 加 `usableByPipelines`（**它不是凭据内容，可以回显** —— 它是个开关）。
- `CredentialService.assertUsableByPipelines(id, expectedType)`：查行 → 不存在 / 类型不符 / `usableByPipelines != 1` → 抛，文案含「未授权」。
- `pipelineUsableConnections(type)` 返回一个新的窄投影 record `CredentialService.ConnectionView(String id, String name, String endpoint)`。
- `PipelineController` 加 `/git-connections`（**只回 GIT 类型**）。

- [ ] **Step 5: 提交**

```bash
git add platform-data/src/main/ platform-api/src/main/java/com/coding/platformapi/ platform-api/src/test/java/com/coding/platformapi/services/CredentialServiceTest.java
git commit -m "feat(pipeline): 连接的逐条显式授权（usable_by_pipelines）+ 窄投影"
```

---

### Task 12: 触发（**先落库再创建**）与取消

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/services/PipelineRunService.java`
- Create: `platform-data/src/main/java/com/coding/data/mapper/system/PlatformPipelineRunMapper.java` + XML + `PlatformPipelineRun.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/controllers/PipelineController.java`（run / run-cancel / run-list / run-get）
- Modify: `platform-api/src/main/java/com/coding/platformapi/k8s/K8sClient.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/PipelineRunServiceTest.java`

**Interfaces:**
- Consumes: Task 10 的 `PipelineService.preview`/`WORKSPACE`；Task 11 的 `assertUsableByPipelines`
- Produces: `PipelineRunService.run(...)` → `String runName`；`cancel(...)`；`list(...)`；`get(...)`

- [ ] **Step 1: 写失败的测试**

```java
    @Test
    void theRunNameIsGeneratedAndPersistedBeforeTheClusterCall() {
        // ⭐ spec §4.5①：run_name 由平台先生成 → 幂等键从源头就有，
        //    也不会出现「集群里有对象、库里没记录」的孤儿
        when(k8s.createPipelineRun(any())).thenReturn(new PipelineRunDTO());
        String runName = svc.run(pipeline, "alice", ctx);

        InOrder inOrder = inOrder(runMapper, k8s);
        inOrder.verify(runMapper).insert(any());          // 先落库
        inOrder.verify(k8s).createPipelineRun(any());     // 再创建
        assertThat(runName).isNotBlank();
    }

    @Test
    void aFailedClusterCreateDoesNotLeaveARunningOrphan() {
        // ⭐ ReviewFocus：先落库再创建的代价要在这里堵住
        when(k8s.createPipelineRun(any())).thenThrow(new CloudPlatformException(
                EnumResponseType.K8S_ERROR, "boom"));

        assertThatThrownBy(() -> svc.run(pipeline, "alice", ctx)).isInstanceOf(CloudPlatformException.class);
        // 库里不能留一条 RUNNING 的孤儿
        verify(runMapper).deleteById(any());
    }

    @Test
    void cancelCapturesLogsBeforeTellingTektonToCancel() {
        // ⭐ spec §4.2：这是「取消之后日志仍然看得到」的全部依据
        when(runMapper.selectByRunName("r1")).thenReturn(archivedRun("r1"));
        svc.cancel("r1", ctx);

        InOrder inOrder = inOrder(archive, k8s);
        inOrder.verify(archive).capture("r1", ctx);       // 先抓
        inOrder.verify(k8s).cancelPipelineRun(any());     // 再取消
    }
```

- [ ] **Step 2-4: 跑红 → 实现 → 跑绿**

实现要点（**顺序是这一条链的全部价值，代码里要能一眼看出来**）：

```
run(pipeline, user, ctx):
  1. 渲染画布 → PipelineSpec → JSON
  2. **生成 runName** = pipelineName + "-" + 时间戳 + "-" + 4 位随机
  3. **先落库** RUNNING
  4. try { k8s.createPipelineRun(...) } catch { runMapper.deleteById(...); throw; }
  5. 返回 runName

cancel(runName, ctx):
  1. archive.capture(runName, ctx)      ← **先抓日志**
  2. k8s.cancelPipelineRun(...)         ← 再取消
```

`K8sClient` 加四个方法：`createPipelineRun` / `getPipelineRun` / `listPipelineRuns` / `deletePipelineRun` / `cancelPipelineRun`
（照该文件里既有的资源方法写法 —— 它们都是拼 `dto.getApiPath()` 再打 k8s-server）。

- [ ] **Step 5: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/ platform-data/src/main/java/com/coding/data/ platform-api/src/test/java/com/coding/platformapi/services/PipelineRunServiceTest.java
git commit -m "feat(pipeline): 触发（先生成 run_name 并落库，再创建）与取消（先抓日志，再取消）"
```

---

### Task 13: 内部回调端点（密钥 fail-closed）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/controllers/PipelineInternalController.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/security/ExemptPaths.java`（加本路径）
- Modify: `platform-api/src/main/resources/application.yaml`（加 `platform.pipeline.*` 配置块）
- Test: `platform-api/src/test/java/com/coding/platformapi/controllers/PipelineInternalControllerTest.java`

**Interfaces:**
- Consumes: Task 14 的 `PipelineArchiveService.archive`
- Produces: `POST /internal/pipeline-run/finished`（`X-Internal-Token`）

> ⚠️ 本端点**不进权限表**（它不是用户端点），但必须进 `ExemptPaths`，
> 否则 `PermissionCrossCheckRunner` 会让应用**拒启**。
> ⚠️ 它**自己校验密钥** —— 绕过了用户 JWT 体系，密钥就是唯一防线。

- [ ] **Step 1: 写失败的测试**

```java
class PipelineInternalControllerTest {

    @Test
    void anEmptyConfiguredTokenRefusesEverything() {
        // ⭐ Review Focus #5：没配密钥 → 拒绝一切，不是"没配就放行"
        PipelineInternalController c = new PipelineInternalController(null, "");
        assertThatThrownBy(() -> c.finished("wrong", new FinishedRequest("c1", "ns1", "r1")))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void aWrongTokenIsRefused() {
        PipelineInternalController c = new PipelineInternalController(null, "s3cret");
        assertThatThrownBy(() -> c.finished("nope", new FinishedRequest("c1", "ns1", "r1")))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void aMissingHeaderIsRefusedNotTreatedAsEmptyMatch() {
        PipelineInternalController c = new PipelineInternalController(null, "s3cret");
        assertThatThrownBy(() -> c.finished(null, new FinishedRequest("c1", "ns1", "r1")))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void theRightTokenDelegatesToTheArchiveService() {
        PipelineArchiveService archive = mock(PipelineArchiveService.class);
        PipelineInternalController c = new PipelineInternalController(archive, "s3cret");
        c.finished("s3cret", new FinishedRequest("c1", "ns1", "r1"));
        verify(archive).archive("c1", "ns1", "r1");
    }
}
```

- [ ] **Step 2-4: 跑红 → 实现 → 跑绿**

实现要点：

```java
@RestController
@RequestMapping("/internal")
public class PipelineInternalController {

    private final PipelineArchiveService archive;
    private final String token;   // @Value("${platform.pipeline.archive.internal-token:}")

    @PostMapping("/pipeline-run/finished")
    public ResponseData<Void> finished(@RequestHeader(value = "X-Internal-Token", required = false) String given,
                                       @RequestBody FinishedRequest req) {
        // ⚠️ 密钥留空 → 拒绝一切（fail-closed，与凭据主密钥同一条判断）。
        // 平台是单体，没法只给这一个端点绑 127.0.0.1 —— 密钥是唯一防线（spec §4.5⑤）。
        if (!StringUtils.hasText(token) || !token.equals(given)) {
            throw new CloudPlatformException(EnumResponseType.FORBIDDEN, "内部回调鉴权失败");
        }
        archive.archive(req.clusterId(), req.namespace(), req.runName());
        return new ResponseData<>();
    }

    public record FinishedRequest(String clusterId, String namespace, String runName) { }
}
```

`application.yaml` 加：

```yaml
platform:
  pipeline:
    archive:
      enabled: ${PIPELINE_ARCHIVE_ENABLED:true}
      # ⚠️ k8s-server 与 platform-api 之间的**内部回调密钥**。**必须配**，留空则内部端点拒绝一切请求。
      # 必须是随机长串（32+ 字节）—— 它是这个端点的唯一防线。
      internal-token: ${PIPELINE_INTERNAL_TOKEN:}
    run:
      timeout: ${PIPELINE_RUN_TIMEOUT:30m}
      timeout-check: ${PIPELINE_TIMEOUT_CHECK:30s}
    log:
      max-bytes: ${PIPELINE_LOG_MAX_BYTES:1048576}
      retention-days: ${PIPELINE_LOG_RETENTION_DAYS:90}
    callback:
      url: ${PIPELINE_CALLBACK_URL:http://127.0.0.1:8081}
```

- [ ] **Step 5: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/ platform-api/src/main/resources/application.yaml platform-api/src/test/java/com/coding/platformapi/controllers/PipelineInternalControllerTest.java
git commit -m "feat(pipeline): 内部回调端点（密钥 fail-closed）与配置项"
```

---

### Task 14: 归档（抓快照 → 落库 → 删对象）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/services/PipelineArchiveService.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/PipelineArchiveServiceTest.java`

**Interfaces:**
- Consumes: Task 12 的 `K8sClient` 方法；Task 5 的 `childTaskRunNames`
- Produces: `PipelineArchiveService.archive(clusterId, namespace, runName)`；`capture(runName, ctx)`（取消前抢抓日志用）

- [ ] **Step 1: 写失败的测试**

```java
class PipelineArchiveServiceTest {

    @Test
    void anAlreadyArchivedRunStillTriesTheDelete() {
        // ⭐ Review Focus #2 的正面：幂等分支**不能直接 return** ——
        // 想象「库写好了但删对象失败」，直接 return 会让对象永远留在集群里
        when(runMapper.selectByRunName("r1")).thenReturn(archivedRun("r1"));

        svc.archive("c1", "ns1", "r1");

        verify(runMapper, never()).update(any());          // 不重写库
        verify(k8s).deletePipelineRun(any());              // **但删除照做**
    }

    @Test
    void aBrandNewRunIsCapturedThenPersistedThenDeletedInThatOrder() {
        when(runMapper.selectByRunName("r1")).thenReturn(null);

        svc.archive("c1", "ns1", "r1");

        InOrder o = inOrder(k8s, logMapper, runMapper);
        o.verify(k8s).getPipelineRun(any());               // 抓状态
        o.verify(k8s, atLeastOnce()).listTaskRuns(any());  // 抓 TaskRun
        o.verify(logMapper, atLeastOnce()).insert(any());  // 落日志
        o.verify(runMapper).update(any());                 // 改状态
        o.verify(k8s).deletePipelineRun(any());            // 最后删
    }

    @Test
    void aLogOverTheLimitIsTruncatedAndFlagged() {
        when(runMapper.selectByRunName("r1")).thenReturn(null);
        when(k8s.podLog(any())).thenReturn("x".repeat(2_000_000));

        svc.archive("c1", "ns1", "r1");

        ArgumentCaptor<PlatformPipelineRunLog> cap = ArgumentCaptor.forClass(PlatformPipelineRunLog.class);
        verify(logMapper, atLeastOnce()).insert(cap.capture());
        assertThat(cap.getValue().getTruncated()).isEqualTo(1);
        assertThat(cap.getValue().getContent().length()).isLessThanOrEqualTo(1_048_576);
    }

    @Test
    void aLogThatVanishesMidwayIsRecordedAsPartialNotDropped() {
        // 竞态：抓到一半 Pod 没了 —— 抓到多少存多少，**不静默丢整条**
        when(runMapper.selectByRunName("r1")).thenReturn(null);
        when(k8s.podLog(any())).thenThrow(new CloudPlatformException(EnumResponseType.K8S_ERROR, "gone"));

        svc.archive("c1", "ns1", "r1");

        verify(logMapper, atLeastOnce()).insert(any());
        verify(runMapper).update(any());
    }
}
```

- [ ] **Step 2-4: 跑红 → 实现 → 跑绿**

实现要点（**六步，顺序即价值**，spec §4.5③）：

```
archive(clusterId, namespace, runName):
  1. 查 run_name：已归档 → **跳到第 5 步**（不重写库，但删除照做）
  2. 读 PipelineRun 快照（状态/时间/子 TaskRun）
  3. 对每个 TaskRun 的每个 step 容器读日志（上限 1 MB，超出截断并置 truncated）
     ⚠️ 单步失败 → 记一条**空内容 + truncated** 的行，**不让整条归档失败**
  4. **一个事务里**：写日志行（按 (run_id, node_name) 唯一 → 重投覆盖不追加）+ 更新 run
  5. 删 PipelineRun（级联删 TaskRun 与 Pod）—— **删是幂等的，每次都试**
  6. 返回
```

- [ ] **Step 5: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/PipelineArchiveService.java platform-api/src/test/java/com/coding/platformapi/services/PipelineArchiveServiceTest.java
git commit -m "feat(pipeline): 归档 —— 抓快照、落库（事务）、删对象；幂等分支仍执行删除"
```

---

### Task 15: 保留清理 + capability 门控

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/services/PipelineRetentionService.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/services/PipelineCapabilityService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ClusterCapabilityService.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/PipelineRetentionServiceTest.java`

**Interfaces:**
- Produces: `PipelineRetentionService.purge(Instant now)`；`PipelineCapabilityService.available(clusterId)` → `Capability(String reasonOrNull)`

- [ ] **Step 1: 写失败的测试**

```java
class PipelineRetentionServiceTest {

    @Test
    void purgesRunsOlderThanTheRetentionWindowAndTheirLogs() {
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        when(runMapper.selectOlderThan("2026-07-17T00:00:00Z"))
                .thenReturn(List.of(run("r1"), run("r2")));

        int n = svc.purge(now);

        assertThat(n).isEqualTo(2);
        verify(logMapper).deleteByRunIds(List.of("r1", "r2"));
        verify(runMapper).deleteByIds(List.of("r1", "r2"));
    }

    @Test
    void ninetyDaysAgoIsTheBoundaryNotEightyNine() {
        // 89 天前的必须留着
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        svc.purge(now);
        verify(runMapper).selectOlderThan("2026-07-17T00:00:00Z");
    }
}

class PipelineCapabilityTest {

    @Test
    void withoutTektonCrdsTheFeatureIsUnavailableWithAReason() {
        when(k8s.hasCrd("tekton.dev", "pipelineruns")).thenReturn(false);
        Capability c = svc.available("c1");
        assertThat(c.available()).isFalse();
        assertThat(c.reason()).contains("Tekton");
    }

    @Test
    void withoutADynamicStorageClassItIsUnavailableToo() {
        // workspace 用 volumeClaimTemplate，没有可动态供应的 StorageClass 就跑不起来（spec §3.3）
        when(k8s.hasCrd("tekton.dev", "pipelineruns")).thenReturn(true);
        when(k8s.hasDynamicStorageClass("c1")).thenReturn(false);
        assertThat(svc.available("c1").available()).isFalse();
    }

    @Test
    void withBothItIsAvailable() {
        when(k8s.hasCrd("tekton.dev", "pipelineruns")).thenReturn(true);
        when(k8s.hasDynamicStorageClass("c1")).thenReturn(true);
        assertThat(svc.available("c1").available()).isTrue();
    }
}
```

- [ ] **Step 2-4: 跑红 → 实现 → 跑绿**

实现要点：

- `purge(now)` = `runMapper.selectOlderThan(now - retentionDays)` → 先删日志行（**靠代码不靠外键**）→ 再删 run 行。返回删了多少条。挂一个每日 `@Scheduled`。
- `PipelineCapabilityService` 探两件事：`tekton.dev/v1` 的 CRD 在不在、有没有可动态供应的 StorageClass。
  ⚠️ **缺任一 → CI 整块隐藏**（照 S4.3 VolumeSnapshot 那条「缺一整块隐藏」的先例），不是报错。
  ⚠️ 本批只做一次最小探测；spec 里写明**将来 S1.2 会接手**（台账已记）。

- [ ] **Step 5: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/ platform-api/src/test/java/com/coding/platformapi/services/
git commit -m "feat(pipeline): 保留清理（90 天）与 capability 门控（缺 Tekton/StorageClass 则整块隐藏）"
```

---

## Part D —— 运行日志端点与前端

### Task 16: 运行日志端点（`tenant:pipeline:logs` 的**唯一落点**）

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/PipelineRunService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/controllers/PipelineController.java`（加 `/pipeline/run/logs`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/k8s/K8sClient.java`（Pod 日志转发）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/PipelineRunServiceTest.java`

**Interfaces:**
- Produces: `PipelineRunService.logs(runName, nodeName, ctx)` → `String`

> **⚠️ 这个端点是写计划时才发现必须有的**，理由是 spec §4.4 与 §5.1 撞了：
>
> 日志内容本来「复用现有 Pod 日志通道」（`GET /pods/{name}/logs`）—— 但那个端点归
> **Pod 的权限码**管。于是 `tenant:pipeline:logs` 这个码**没有落点**，
> 「定义 / 触发 / 看日志分开授权」（台账 S3.5.12）就成了空话。
>
> 所以补一个 `/pipeline/run/logs`：**它按 `tenant:pipeline:logs` 鉴权**，
> 由 platform-api 自己解析 `run → TaskRun → Pod → 容器`（前端不用知道 Tekton 的结构），
> 再把 k8s-server 的日志流**转发**回来。
>
> 顺带的好处：**不需要给租户更宽的 Pod 读取权** —— 看流水线日志的人不必同时有看任意 Pod 日志的权。

- [ ] **Step 1: 写失败的测试**

```java
    @Test
    void logsResolveRunToTaskRunToPodWithoutTheFrontendKnowingTekton() {
        when(k8s.listTaskRuns("c1", "ns1", "r1")).thenReturn(List.of(taskRun("tr1", "n1")));
        when(k8s.podForTaskRun("c1", "ns1", "tr1")).thenReturn("tr1-pod");
        when(k8s.podLog("c1", "ns1", "tr1-pod", "step-n1")).thenReturn("hello");

        assertThat(svc.logs("r1", "n1", ctx)).isEqualTo("hello");
    }

    @Test
    void anUnknownNodeNameIsRefusedRatherThanReturningEmpty() {
        // 空字符串会让用户以为"这一步没有输出"，而其实是节点名打错了
        when(k8s.listTaskRuns("c1", "ns1", "r1")).thenReturn(List.of(taskRun("tr1", "n1")));
        assertThatThrownBy(() -> svc.logs("r1", "nope", ctx))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void aRunPodThatIsAlreadyGoneSaysSoRatherThanFailing() {
        // 归档删了 Pod 之后再点日志 —— 要给人话，不是 500
        when(k8s.listTaskRuns("c1", "ns1", "r1")).thenReturn(List.of(taskRun("tr1", "n1")));
        when(k8s.podForTaskRun("c1", "ns1", "tr1")).thenReturn(null);

        assertThat(svc.logs("r1", "n1", ctx)).contains("已归档");
    }
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
mvn -o -pl platform-api -am test -Dtest=PipelineRunServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败。

- [ ] **Step 3: 实现**

`PipelineRunService.logs`：

```java
    /**
     * 取某一节点的日志。
     *
     * <p>⚠️ 它存在的理由是**权限码要有落点**：日志内容本身走现有的 Pod 日志通道，
     * 但那个端点归 Pod 的码管，`tenant:pipeline:logs` 就没地方挂了。
     * 所以由本方法解析 run → TaskRun → Pod → 容器，前端不需要知道 Tekton 的结构。
     */
    public String logs(String runName, String nodeName, Ctx ctx) {
        List<TaskRunRef> refs = k8s.listTaskRuns(ctx.clusterId(), ctx.namespace(), runName);
        TaskRunRef hit = refs.stream()
                .filter(r -> nodeName.equals(r.pipelineTaskName()))
                .findFirst()
                .orElseThrow(() -> new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                        "这次运行里没有名为 " + nodeName + " 的节点"));
        String pod = k8s.podForTaskRun(ctx.clusterId(), ctx.namespace(), hit.taskRunName());
        if (pod == null) {
            // 归档之后 Pod 就没了 —— 说人话，不是 500
            return "（这次运行已归档，Pod 已被清理。历史日志见运行详情。）";
        }
        return k8s.podLog(ctx.clusterId(), ctx.namespace(), pod, "step-" + nodeName);
    }
```

`PipelineController` 加：

```java
    @PostMapping("/run/logs")
    @Operation(summary = "取某节点的运行日志")
    public ResponseData<String> runLogs(@RequestBody RunLogsRequest req) {
        return new ResponseData<>(runService.logs(req.getRunName(), req.getNodeName(), ctxOf(req)));
    }
```

⚠️ **容器名**：Tekton 把 step 跑在名为 `step-<stepName>` 的容器里。本平台的 step 名就是节点 id
（Task 2 的渲染器：`task.setName(n.id())`、`step.setName("run")`）—— 所以容器名是
**`step-<节点id>` 还是 `step-run`** 取决于取哪一个。**实现时以真集群为准**（§11 待实测已经记了
「Tekton 给 Pod 打的标签名」，容器名这条一并记进去）。

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -o -pl platform-api -am test -Dtest=PipelineRunServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/ platform-api/src/test/java/com/coding/platformapi/services/PipelineRunServiceTest.java
git commit -m "feat(pipeline): 运行日志端点 —— tenant:pipeline:logs 的唯一落点（不让日志码悬空）"
```

---

### Task 17: 画布页（引入 Vue Flow）

**Files:**
- Modify: `platform-web/package.json`（加 `@vue-flow/core`）
- Create: `platform-web/src/composables/usePipelineNodeTypes.ts`
- Create: `platform-web/src/components/pipeline/{NodePalette,PipelineNodeVue}.vue`
- Create: `platform-web/src/views/resource/PipelineEditorView.vue`
- Modify: `platform-web/src/types.ts` `platform-web/src/api/index.ts` `platform-web/src/router/index.ts`

**Interfaces:**
- Consumes: Task 10 的 `/pipeline/node-types` 与 `/pipeline/preview`
- Produces: `usePipelineNodeTypes()`；画布页

- [ ] **Step 1: 装依赖**

```bash
cd platform-web && npm install @vue-flow/core
```
⚠️ 内网可能要配 npm 代理 —— 这一步失败就先把 npm 的网络打通（与平台自己的出站代理是两回事）。

- [ ] **Step 2: 节点元数据 composable**

```ts
// platform-web/src/composables/usePipelineNodeTypes.ts
import { ref } from 'vue'
import { pipelineApi } from '@/api'
import type { PipelineNodeType } from '@/types'

/**
 * 内置节点的类型与表单 schema。**属性面板完全由它驱动** ——
 * 后端加一个字段不用改前端，这是「一个节点一个类 + schema」那一半设计的目的。
 *
 * 单例缓存：schema 在一次会话里不会变，而画布页会反复打开。
 */
const cache = ref<PipelineNodeType[] | null>(null)

export function usePipelineNodeTypes() {
  async function load(): Promise<PipelineNodeType[]> {
    if (!cache.value) {
      cache.value = await pipelineApi.nodeTypes()
    }
    return cache.value
  }
  return { load, nodeTypes: cache }
}
```

`types.ts` 加：

```ts
export interface PipelineNodeField {
  key: string
  label: string
  type: 'TEXT' | 'TEXTAREA' | 'SELECT' | 'IMAGE'
  required: boolean
  placeholder?: string | null
  help?: string | null
  defaultValue?: string | null
  options: { value: string; label: string }[]
}

export interface PipelinePreset {
  key: string
  label: string
  image: string
  command: string
}

export interface PipelineNodeType {
  type: string
  fields: PipelineNodeField[]
  presets: PipelinePreset[]
}

export interface PipelineCanvasNode { id: string; type: string; config: Record<string, string> }
export interface PipelineCanvasEdge { from: string; to: string }
export interface PipelineCanvas { nodes: PipelineCanvasNode[]; edges: PipelineCanvasEdge[] }
```

- [ ] **Step 3: 画布页**

要点（**这些是页面的产品语义，不是实现细节**）：

- 左侧 `NodePalette`：从 `/pipeline/node-types` 渲染节点列表，拖入画布
- 中间：`<VueFlow>` + 自定义节点组件 `PipelineNodeVue`（显示节点 id 与类型标签）
- 右侧：`NodePropsPanel`（Task 18）
- **环检测在客户端实时提示**（点连线时跑一次），**但保存与预览都以后端为准** ——
  前端提示是体验，后端拒绝才是保证（Review Focus #1 的两半）
- **无入边的节点标成「起点」**；**两个没连线的节点要显式提示「并行执行」** ——
  这是 Tekton 的默认语义，不提示的话用户会以为"没连上"
- 顶部：保存 / 预览 YAML（复用 `YamlBlock`）

- [ ] **Step 4: 类型检查 + 构建**

```bash
cd platform-web && npm run build
```
Expected: **0 条 TS error**。⚠️ 不要用 `npx vue-tsc --noEmit` 代替 —— 它会零报错放过真错误。

- [ ] **Step 5: 提交**

```bash
git add platform-web/package.json platform-web/package-lock.json platform-web/src/
git commit -m "feat(web): 流水线画布页（Vue Flow）—— 拖入、连线、环提示、并行语义提示"
```

---

### Task 18: 属性面板（**完全由 schema 驱动**）

**Files:**
- Create: `platform-web/src/components/pipeline/NodePropsPanel.vue`
- Test: 无前端测试套件 —— 用 `npm run build` + 浏览器实测（Task 21）

**Interfaces:**
- Consumes: Task 17 的 `usePipelineNodeTypes`

- [ ] **Step 1: 实现面板**

要点：

- 遍历 `nodeType.fields` 渲染控件：`TEXT`→`el-input`、`TEXTAREA`→`el-input type=textarea`、
  `SELECT`→`el-select`、**`IMAGE`→`el-autocomplete` 挂 `useImageSuggest`**（S3.1 那套）
- **`IMAGE` 字段**：placeholder 用 `field.placeholder`，失败静默、选中整段替换 —— 与 S3.1 的两处完全一致
- **选预设只换默认值**：`preset` 字段变化时，把 `image` / `command` 两个框**填上该预设的值**，
  但**不锁定** —— 用户改了就以用户为准（后端 Task 3 的 `ShellNode.render` 同一条判据）
- 字段的 `help` 用现成的 `FieldHelp` 组件显示

- [ ] **Step 2: 构建**

```bash
cd platform-web && npm run build
```
Expected: 0 条 TS error。

- [ ] **Step 3: 提交**

```bash
git add platform-web/src/components/pipeline/NodePropsPanel.vue
git commit -m "feat(web): 节点属性面板 —— 完全由后端 schema 渲染（含镜像字段接 S3.1 补全）"
```

---

### Task 19: 流水线列表页、菜单与权限

**Files:**
- Create: `platform-web/src/views/resource/PipelineListView.vue`
- Modify: `platform-web/src/pageCodes.ts` `apiCodes.ts` `router/index.ts` `layouts/MainLayout.vue`

- [ ] **Step 1: 加常量与路由**

```ts
// pageCodes.ts
  pipeline: 'tenant:page:pipeline',

// apiCodes.ts
  pipelineRead: 'tenant:pipeline:read',
  pipelineManage: 'tenant:pipeline:manage',
  pipelineRun: 'tenant:pipeline:run',
  pipelineLogs: 'tenant:pipeline:logs',
```

路由 `/pipelines` 带 `meta.requiresPerm: [pageCodes.pipeline]`；菜单挂在资源管理组。

⚠️ **路由与菜单都引用常量，不写字面量**（`frontend-permission-conventions.md` §1.2）。

- [ ] **Step 2: 列表页**

列：名称 / 描述 / 最近一次运行状态 / 更新时间 / 操作（打开画布、触发、删除）。
**「打开画布」「删除」按 `apiCodes.pipelineManage` 显隐；「触发」按 `apiCodes.pipelineRun`** ——
member 有 run 没 manage，这一页正是那条区分的落点。

- [ ] **Step 3: 构建**

```bash
cd platform-web && npm run build
```
Expected: 0 条 TS error。

- [ ] **Step 4: 提交**

```bash
git add platform-web/src/
git commit -m "feat(web): 流水线列表页、菜单与权限常量"
```

---

### Task 20: 运行历史与运行详情

**Files:**
- Create: `platform-web/src/views/resource/PipelineRunView.vue`
- Create: `platform-web/src/components/pipeline/RunDag.vue`

- [ ] **Step 1: 实现**

要点：

- **运行历史**：列表（状态 / 触发人 / 耗时 / 起止）。⚠️ 数据源是**平台库**（归档后集群里没有对象了）
- **运行详情**：三个部分
  1. **DAG 着色** —— 复用画布数据 + 每个节点的状态
  2. **日志** —— 点节点 → 调 `/pipeline/run/logs`（Task 16，按 `apiCodes.pipelineLogs` 鉴权）。
     滚动/跟随的交互**复用 `PodLogDialog` 的那套写法**（tailLines 回看 + 增量游标 + 剥时间戳）
  3. **渲染出来的 PipelineRun YAML** —— 复用 `YamlBlock`。排查利器，也几乎零成本
- **取消**按钮按 `apiCodes.pipelineRun` 显隐

- [ ] **Step 2: 构建**

```bash
cd platform-web && npm run build
```
Expected: 0 条 TS error。

- [ ] **Step 3: 提交**

```bash
git add platform-web/src/
git commit -m "feat(web): 运行历史与运行详情（DAG 着色 + 日志 + 渲染出的 YAML）"
```

---

## Part E —— 收口

### Task 21: 全量回归与启动校验

**Files:** 无（只跑）

- [ ] **Step 1: 后端全量**

```bash
mvn -o -q install -Dmaven.test.skip=true && mvn -o test
```
⚠️ **先 install**：`mvn -pl platform-api spring-boot:run` 用的是**已安装的 platform-data jar**。

Expected：**platform-common 19 · k8s-core 218+ · k8s-server 20+ · platform-api 500+**，全绿。
`k8s-core` 的基线依赖工作区里那个**有意删掉的** `WorkloadConverterStaticIpTest.java`。

- [ ] **Step 2: 前端**

```bash
cd platform-web && npm run build
```
Expected: 0 条 TS error。

- [ ] **Step 3: 启动校验（**最容易被漏、也最容易出事的一步**）**

```bash
PIPELINE_INTERNAL_TOKEN=<一段 32+ 字节的随机串> \
  mvn -o -q -pl platform-api spring-boot:run -Dspring-boot.run.arguments=--server.port=18086
```

看四件事：

1. `[RBAC] 交叉校验通过：N 个 endpoint` —— **N 必须比本批开工前多 13**
   （12 个用户端点 + 1 个走 ExemptPaths 的内部端点**不算**在内；内部端点若漏进 ExemptPaths，
   应用会**拒启**，这正是我们要的 fail-closed）。
   ⚠️ 端点数变了就要回头核对：多/少一个都说明有一行权限没对上。
2. 启动没有 `找不到权限行` 之类的拒启信息
3. `platform.pipeline.archive.internal-token` 未配时，应用**照常启动**（不 fail-closed 到启动不了），
   但内部端点拒绝一切 —— 这条在 Task 13 的单测里钉住了
4. 日志里没有 Tekton 相关的 WARN 刷屏

- [ ] **Step 4: 确认工作区只剩用户自己的改动**

```bash
git status --short
```
Expected: 只有这三条（**都不是本批的，不要提交**）：

```
 D k8s-core/src/test/java/com/coding/k8score/converter/impl/workload/WorkloadConverterStaticIpTest.java
 M platform-api/src/main/resources/application.yaml
 M platform-common/src/main/java/com/coding/common/utils/AESUtils.java
```

⚠️ 本批**会**改 `application.yaml`（加 `platform.pipeline.*`）—— 用户那条 aes-key 默认值与它同文件。
提交时**只暂存自己那一段**（用 `git hash-object -w` + `git update-index --cacheinfo` 重建一个
只含本批改动的版本，Docker Hub 那批用过这个手法），不要把他的默认值一起带进仓库。

---

## 自审记录

**Spec 覆盖**：逐节对过 `2026-10-15-ci-tekton-design.md` ——
§2（节点模型）→ T1/T3；§2.3（预设只是默认值）→ T3；§2.4（护栏）→ T3；
§3.1（渲染器 + 环检测 + 并发语义）→ T2；§3.2（内联、不留 Pipeline 对象）→ T5；
§3.3（隐式 PVC workspace）→ T2（声明）+ T5（组装）；
§4.1（按需 watch）→ T7；§4.1.1（只碰平台自己的）→ T5；
§4.1.2（补漏：启动扫描 + 打开页面时对账）→ T8（启动扫描）+ T20（页面）；
§4.2（取消与超时收进平台）→ T12（取消顺序）+ T8（超时判定）；
§4.4（日志落库、1 MB 截断）→ T14；§4.5（调用时序）→ T12/T13/T14；
§5.1（5 个码）→ T9；§5.3（逐条授权）→ T11；§6（前端）→ T17-T20；
§7（数据模型）→ T9；§8（审计）→ T10/T12；§12（部署）→ T21。

**计划外新增的两条**（都写明了理由）：

1. **T16 运行日志端点** —— 写计划时发现 spec §4.4 与 §5.1 撞了：日志走现有 Pod 日志通道，
   而那个端点归 **Pod 的码**管，于是 `tenant:pipeline:logs` 这个码**没有落点**。
   补一个 `/pipeline/run/logs` 才有落点，也才不给租户更宽的 Pod 读取权。
2. **T6 在 `NamespacedOperations` 加 `cancel` 的 default 方法** —— 否则控制器要裸转型。
   与既有的 `listAll` 同一条判断：**「不支持」必须响亮**。

**类型一致性**：`PipelineNode`/`NodeSchema`/`NodeField`（T1）→ T2/T3/T10 使用；
`PipelineRunDTO`（T4）→ T5/T6/T12 使用；`PipelineRunWatchManager.ActiveRun`（T7）→ T8 使用；
`PipelineCanvas` 的 JSON 结构与前端的 `PipelineCanvas` 接口（T17）逐字段对齐。
逐一对过签名，一致。

**仍待实测**（spec §11，计划里没有对应任务 —— 它们只能在真集群上验）：
Buildah per-Pod 的 `securityContext` · Tekton v1 的取消取值 · Tekton 的 `default-timeout`
· Tekton 给 Pod 打的标签名与 step 容器的**确切容器名** · `volumeClaimTemplate` 能否绑定
· `tekton-client:7.9.0` 与 fabric8 7.9.0 共存 · 按需 watch 的 `withField`/`close` 形态。

**执行时的建议**：T17-T20（前端）是天然的中断点 —— 后端 T1-T16 全部有单测可验，
前端只能靠 `npm run build` + 浏览器实测。

