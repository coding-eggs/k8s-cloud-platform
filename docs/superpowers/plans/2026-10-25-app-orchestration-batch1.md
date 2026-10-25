# 应用编排（画布）· 计划①：模型 + 画布 + 计算节点渲染部署

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用户在一张类型化画布上画「服务 + 后台进程」，平台渲染成资源清单、预览、部署，并把每个节点的部署状态回显到画布上。

**Architecture:** `spec JSON` 是真相源（画布只是编辑器）；platform-api 持有台账、做归属校验与编排，所有集群写入仍走 k8s-server 的唯一 Helm/SSA 通道；每个节点类型一份内置 chart 片段负责渲染，合并成单份 manifest 后一次性 SSA apply，清理靠 `platform_application_resource` 台账做差集 prune；部署是平台侧的阶段机（持久化 `current_step`），失败停住等人工重试。

**Tech Stack:** Java 21 / Spring Boot 4.1.2 / fabric8 7.9.0 / MyBatis / MySQL / Helm 4.2.0 / Jackson 3（`tools.jackson`，YAML 用 `YAMLMapper`）/ Vue 3 + Element Plus + `@vue-flow/core` 1.48。

**Spec:** [docs/superpowers/specs/2026-10-25-app-orchestration-design.md](../specs/2026-10-25-app-orchestration-design.md)

> 本计划只覆盖 spec 的 §14「①a」：服务 / 后台进程两类节点 + 渲染 + 部署 + 状态。
> 中间件节点、依赖注入、初始化动作、引用守卫在**计划②（①b）**；暴露 HTTPRoute 待 S4.2 定案。

## Global Constraints

- **不做 CronJob**：节点目录里没有它，遇到 `kind=CronJob` 必须报错而不是忽略。
- **一个应用 = 一个集群 + 一个命名空间**；跨命名空间 / 跨集群直接拒绝。
- **spec JSON 是真相源**：任何集群状态都不能反写 spec；资源页对被管理对象只读。
- **密码/令牌不进入** spec、revision 快照、渲染清单、日志；只允许 `secretKeyRef` 形式出现。
- **唯一写入原语**：集群写入只经 `k8s-server`（`HelmRunner` / fabric8 SSA），platform-api 不直连集群。
- **新端点必须有权限行**，否则 `PermissionCrossCheckRunner` 拒绝启动。
- **本仓没有 Flyway**：`db/migration/*.sql` 一律手工重放；MySQL 注释 `--` 后必须跟空白字符。
- **同类资源不抽公共基类**：渲染器按节点类型各写一份（`ServiceNodeRenderer` / `WorkerNodeRenderer` 合成一个 chart，但服务代码不引入抽象基类）。
- **面向用户的文案一律中文**；代码注释解释「为什么」，不复述「做了什么」。
- 单次应用节点上限 **20**；`platform-api` 假定单副本运行（worker 不做分布式锁，超出一个副本时在文档里标注）。

---

## Review Focus

以下五类输入/失败模式 spec 没有逐条展开，但会真的伤到人。每条在对应任务里都有测试钉住：

1. **图里有环或自指**（A 依赖 B、B 依赖 A）→ 预检必须报错并拒绝部署，而不是渲染到一半死循环（Task 1）。
2. **对象名撞车**（两个节点渲染出同名对象 / 与集群里手工建的对象同名）→ 合并阶段必须报冲突（Task 6 的测试），部署的 CONFLICT 阶段必须拒绝接管不属于本应用的对象（Task 10 的测试），绝不能后写覆盖先写。
3. **敏感值泄漏**（用户把密码填进普通环境变量）→ 渲染清单里只能出现 `secretKeyRef`；测试要断言渲染结果中不含明文（Task 5）。
4. **prune 误删**（升级时把 PVC 或别人的对象删掉）→ 只删本应用上一 revision 且不在本 revision 的对象，PVC 默认跳过（Task 7 / Task 10）。
5. **重启后部署卡死**（platform-api 重启，操作停在 RUNNING 永远不动）→ worker 必须从持久化的 `current_step` 续跑（Task 10）。

---

## File Structure

**platform-common（跨服务 DTO）**
- Create `platform-common/src/main/java/com/coding/common/models/app/AppNodeDTO.java`
- Create `.../app/AppEdgeDTO.java` · `AppSpecDTO.java` · `AppProblemDTO.java`
- Create `.../app/AppRenderRequestDTO.java` · `AppRenderResultDTO.java` · `AppObjectRefDTO.java`
- Create `.../app/AppApplyRequestDTO.java` · `AppPruneRequestDTO.java`
- Create `.../app/AppStatusRequestDTO.java` · `AppStatusResultDTO.java` · `AppObjectStatusDTO.java`

**platform-data**
- Create `platform-data/src/main/resources/db/migration/V2026_10_25_7__application.sql`
- Create `platform-data/src/main/java/com/coding/data/models/system/PlatformApplication.java`（同名 `PlatformApplicationRevision` / `PlatformApplicationOperation` / `PlatformApplicationResource`）
- Create `platform-data/src/main/java/com/coding/data/mapper/system/PlatformApplicationMapper.java`（同名三个）
- Create `platform-data/src/main/resources/mapper/system/PlatformApplicationMapper.xml`（同名三个）

**k8s-server**
- Create `k8s-server/src/main/resources/charts/app/workload/1.0.0/chart/`（`Chart.yaml` / `values.yaml` / `templates/*.yaml` / `README.md`）
- Create `k8s-server/src/main/java/com/coding/k8sserver/app/AppNaming.java`
- Create `.../app/AppWorkloadValues.java`（节点参数 → chart values）
- Create `.../app/AppRenderService.java`
- Create `.../app/AppApplyService.java`
- Create `.../app/AppStatusService.java`
- Create `k8s-server/src/main/java/com/coding/k8sserver/controllers/app/AppController.java`

**platform-api**
- Create `platform-api/src/main/java/com/coding/platformapi/app/AppSpecValidator.java`
- Create `.../app/AppObjectNames.java`
- Create `platform-api/src/main/java/com/coding/platformapi/k8s/K8sAppClient.java`
- Create `platform-api/src/main/java/com/coding/platformapi/services/ApplicationService.java`
- Create `.../services/ApplicationPreviewService.java`
- Create `.../services/ApplicationDeployService.java`
- Create `.../services/ApplicationOperationWorker.java`
- Create `platform-api/src/main/java/com/coding/platformapi/models/ApplicationCreateRequest.java`（同包另有 `ApplicationQueryRequest` / `ApplicationUpdateRequest` / `ApplicationDeployRequest` / `ApplicationDeleteRequest` / `ApplicationPreviewRequest` / `ApplicationView` / `ApplicationPreview` / `ApplicationRevisionView` / `ApplicationOptionsView`）
- Create `platform-api/src/main/java/com/coding/platformapi/controllers/ApplicationController.java`

**platform-web**
- Create `platform-web/src/views/ApplicationListView.vue`
- Create `platform-web/src/views/ApplicationCanvasView.vue`
- Create `platform-web/src/components/app-canvas/AppNodeCard.vue`（画布节点渲染，按 kind 显示图标/状态灯）
- Create `platform-web/src/components/app-canvas/AppNodeInspector.vue`（右侧检查器表单）
- Create `platform-web/src/components/app-canvas/AppDeployDrawer.vue`（阶段时间线 + 状态轮询）
- Modify `platform-web/src/pageCodes.ts` / `router/index.ts` / `api/index.ts` / `types.ts`

**文档**
- Create `docs/development/application-canvas.md`
- Create `k8s-server/src/main/resources/charts/app/workload/README.md`（组件级约定，见 [[component-readme-convention]]）

---

### Task 1: 图的 spec 模型与校验器

**Files:**
- Create: `platform-common/src/main/java/com/coding/common/models/app/AppNodeDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/app/AppEdgeDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/app/AppSpecDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/app/AppProblemDTO.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/app/AppSpecValidator.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/app/AppSpecValidatorTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `AppSpecDTO { String namespace; Map<String,Object> settings; List<AppNodeDTO> nodes; List<AppEdgeDTO> edges; }`
  - `AppNodeDTO { String key; String kind; Map<String,Object> params; }`（`kind` 取值 `SERVICE` / `WORKER`）
  - `AppEdgeDTO { String from; String to; }`
  - `AppProblemDTO { String severity; String nodeKey; String message; }`（`severity` = `ERROR` / `WARN`）
  - `AppSpecValidator.validate(AppSpecDTO spec) → List<AppProblemDTO>`（纯函数，无 Spring 依赖）

- [ ] **Step 1: Write the failing test**

```java
package com.coding.platformapi.app;

import com.coding.common.models.app.AppEdgeDTO;
import com.coding.common.models.app.AppNodeDTO;
import com.coding.common.models.app.AppProblemDTO;
import com.coding.common.models.app.AppSpecDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppSpecValidatorTest {

    private static AppNodeDTO node(String key, String kind) {
        AppNodeDTO n = new AppNodeDTO();
        n.setKey(key);
        n.setKind(kind);
        n.setParams(Map.of());
        return n;
    }

    private static AppEdgeDTO edge(String from, String to) {
        AppEdgeDTO e = new AppEdgeDTO();
        e.setFrom(from);
        e.setTo(to);
        return e;
    }

    private static AppSpecDTO spec(List<AppNodeDTO> nodes, List<AppEdgeDTO> edges) {
        AppSpecDTO s = new AppSpecDTO();
        s.setNamespace("demo");
        s.setNodes(nodes);
        s.setEdges(edges);
        return s;
    }

    private static List<String> errors(AppSpecDTO s) {
        return AppSpecValidator.validate(s).stream()
                .filter(p -> "ERROR".equals(p.getSeverity()))
                .map(AppProblemDTO::getMessage)
                .toList();
    }

    @Test
    void acceptsSimpleValidGraph() {
        assertThat(errors(spec(List.of(node("web", "SERVICE")), List.of()))).isEmpty();
    }

    @Test
    void rejectsUnknownKindAndCronjob() {
        assertThat(errors(spec(List.of(node("job", "CRONJOB")), List.of())))
                .anySatisfy(m -> assertThat(m).contains("不支持的节点类型"));
        assertThat(errors(spec(List.of(node("db", "DATABASE")), List.of())))
                .anySatisfy(m -> assertThat(m).contains("不支持的节点类型"));
    }

    @Test
    void rejectsDuplicateAndIllegalKey() {
        assertThat(errors(spec(List.of(node("web", "SERVICE"), node("web", "WORKER")), List.of())))
                .anySatisfy(m -> assertThat(m).contains("重复的节点 key"));
        assertThat(errors(spec(List.of(node("Web_1", "SERVICE")), List.of())))
                .anySatisfy(m -> assertThat(m).contains("节点 key 只能用小写字母"));
    }

    @Test
    void rejectsEdgeToMissingNodeAndSelfLoop() {
        assertThat(errors(spec(List.of(node("web", "SERVICE")), List.of(edge("web", "ghost")))))
                .anySatisfy(m -> assertThat(m).contains("连线指向不存在的节点"));
        assertThat(errors(spec(List.of(node("web", "SERVICE")), List.of(edge("web", "web")))))
                .anySatisfy(m -> assertThat(m).contains("不能指向自己"));
    }

    @Test
    void rejectsCycle() {
        List<AppNodeDTO> nodes = List.of(node("a", "SERVICE"), node("b", "SERVICE"), node("c", "SERVICE"));
        List<AppEdgeDTO> edges = List.of(edge("a", "b"), edge("b", "c"), edge("c", "a"));
        assertThat(errors(spec(nodes, edges))).anySatisfy(m -> assertThat(m).contains("循环依赖"));
    }

    @Test
    void rejectsTooManyNodesAndMissingNamespace() {
        List<AppNodeDTO> nodes = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            nodes.add(node("n" + i, "SERVICE"));
        }
        assertThat(errors(spec(nodes, List.of()))).anySatisfy(m -> assertThat(m).contains("最多 20 个节点"));
        AppSpecDTO noNamespace = spec(List.of(node("web", "SERVICE")), List.of());
        noNamespace.setNamespace("");
        assertThat(errors(noNamespace)).anySatisfy(m -> assertThat(m).contains("未选择命名空间"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl platform-api -am test -Dtest=AppSpecValidatorTest`
Expected: 编译失败 —— `AppSpecValidator` / `App*DTO` 不存在。

- [ ] **Step 3: Write minimal implementation**

DTO（`platform-common`，全部 `@Data` + Lombok，与既有 DTO 同风格）：

```java
package com.coding.common.models.app;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class AppSpecDTO {
    private String namespace;
    private Map<String, Object> settings;
    private List<AppNodeDTO> nodes;
    private List<AppEdgeDTO> edges;
}
```

```java
package com.coding.common.models.app;

import lombok.Data;
import java.util.Map;

@Data
public class AppNodeDTO {
    private String key;
    /** SERVICE / WORKER（CronJob 明确不支持） */
    private String kind;
    private Map<String, Object> params;
}
```

```java
package com.coding.common.models.app;

import lombok.Data;

@Data
public class AppEdgeDTO {
    private String from;
    private String to;
}
```

```java
package com.coding.common.models.app;

import lombok.Data;

@Data
public class AppProblemDTO {
    /** ERROR / WARN */
    private String severity;
    private String nodeKey;
    private String message;

    public static AppProblemDTO error(String nodeKey, String message) {
        AppProblemDTO p = new AppProblemDTO();
        p.setSeverity("ERROR");
        p.setNodeKey(nodeKey);
        p.setMessage(message);
        return p;
    }
}
```

```java
package com.coding.platformapi.app;

import com.coding.common.models.app.AppEdgeDTO;
import com.coding.common.models.app.AppNodeDTO;
import com.coding.common.models.app.AppProblemDTO;
import com.coding.common.models.app.AppSpecDTO;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 图的静态校验（纯函数）。
 *
 * <p>为什么在这里就把环判掉：部署是分阶段推进的，环意味着「谁都得等对方先就绪」，
 * 一旦放过会在 COMPONENTS 阶段卡到超时，而错误信息已经离用户的图很远了。
 */
public final class AppSpecValidator {

    public static final int MAX_NODES = 20;
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9-]{0,19}$");
    private static final Set<String> KINDS = Set.of("SERVICE", "WORKER");

    private AppSpecValidator() {
    }

    public static List<AppProblemDTO> validate(AppSpecDTO spec) {
        List<AppProblemDTO> out = new ArrayList<>();
        if (spec == null) {
            out.add(AppProblemDTO.error(null, "应用定义为空"));
            return out;
        }
        if (spec.getNamespace() == null || spec.getNamespace().isBlank()) {
            out.add(AppProblemDTO.error(null, "未选择命名空间"));
        }
        List<AppNodeDTO> nodes = spec.getNodes() == null ? List.of() : spec.getNodes();
        if (nodes.isEmpty()) {
            out.add(AppProblemDTO.error(null, "应用里至少要有一个节点"));
        }
        if (nodes.size() > MAX_NODES) {
            out.add(AppProblemDTO.error(null, "一个应用最多 20 个节点"));
        }

        Map<String, AppNodeDTO> byKey = new HashMap<>();
        for (AppNodeDTO n : nodes) {
            String key = n.getKey() == null ? "" : n.getKey();
            if (!KEY.matcher(key).matches()) {
                out.add(AppProblemDTO.error(key, "节点 key 只能用小写字母、数字和连字符，且以字母开头（≤20 字符）：" + key));
            }
            if (byKey.putIfAbsent(key, n) != null) {
                out.add(AppProblemDTO.error(key, "重复的节点 key：" + key));
            }
            String kind = n.getKind() == null ? "" : n.getKind();
            if (!KINDS.contains(kind)) {
                out.add(AppProblemDTO.error(key, "不支持的节点类型：" + kind + "（平台不支持 CronJob）"));
            }
        }

        List<AppEdgeDTO> edges = spec.getEdges() == null ? List.of() : spec.getEdges();
        Map<String, List<String>> outEdges = new HashMap<>();
        for (AppEdgeDTO e : edges) {
            String from = e.getFrom() == null ? "" : e.getFrom();
            String to = e.getTo() == null ? "" : e.getTo();
            if (from.equals(to)) {
                out.add(AppProblemDTO.error(from, "连线不能指向自己：" + from));
                continue;
            }
            if (!byKey.containsKey(from) || !byKey.containsKey(to)) {
                out.add(AppProblemDTO.error(from, "连线指向不存在的节点：" + from + " → " + to));
                continue;
            }
            outEdges.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        }
        String cycle = firstCycle(byKey.keySet(), outEdges);
        if (cycle != null) {
            out.add(AppProblemDTO.error(null, "检测到循环依赖：" + cycle));
        }
        return out;
    }

    /** DFS 找一条环（返回 a → b → a 形式），没有环返回 null。 */
    static String firstCycle(Set<String> keys, Map<String, List<String>> outEdges) {
        Set<String> done = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();
        for (String k : keys) {
            String hit = dfs(k, outEdges, done, path, new HashSet<>());
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static String dfs(String node, Map<String, List<String>> outEdges, Set<String> done,
                              Deque<String> path, Set<String> onPath) {
        if (onPath.contains(node)) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(node);
            return String.join(" → ", cycle);
        }
        if (!done.add(node)) {
            return null;
        }
        onPath.add(node);
        path.addLast(node);
        for (String next : outEdges.getOrDefault(node, List.of())) {
            String hit = dfs(next, outEdges, done, path, onPath);
            if (hit != null) {
                return hit;
            }
        }
        path.removeLast();
        onPath.remove(node);
        return null;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl platform-api -am test -Dtest=AppSpecValidatorTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add platform-common/src/main/java/com/coding/common/models/app \
        platform-api/src/main/java/com/coding/platformapi/app/AppSpecValidator.java \
        platform-api/src/test/java/com/coding/platformapi/app/AppSpecValidatorTest.java
git commit -m "feat(application): 应用图的 spec 模型与静态校验"
```

---

### Task 2: 命名规则（app slug / 对象名 / 环境变量前缀）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/app/AppObjectNames.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/app/AppObjectNamesTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `AppObjectNames.slug(String appName) → String`（小写、非法字符转 `-`、去首尾 `-`、截断 30）
  - `AppObjectNames.objectName(String slug, String nodeKey) → String`（`<slug>-<nodeKey>`，超 63 截断并保留可读性）
  - `AppObjectNames.envPrefix(String nodeKey) → String`（大写、`-` → `_`）

- [ ] **Step 1: Write the failing test**

```java
package com.coding.platformapi.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppObjectNamesTest {

    @Test
    void slugifiesChineseAndSymbols() {
        assertThat(AppObjectNames.slug("我的 应用_v1")).isEqualTo("v1");
        assertThat(AppObjectNames.slug("Demo App")).isEqualTo("demo-app");
        assertThat(AppObjectNames.slug("--a--b--")).isEqualTo("a--b");
    }

    @Test
    void truncatesLongSlugWithoutTrailingDash() {
        String slug = AppObjectNames.slug("a".repeat(40));
        assertThat(slug).hasSize(30);
        assertThat(slug).doesNotEndWith("-");
    }

    @Test
    void rejectsSlugWithNothingUsable() {
        assertThatThrownBy(() -> AppObjectNames.slug("中间件"))
                .hasMessageContaining("应用名至少要包含一个字母或数字");
    }

    @Test
    void buildsObjectNameAndKeepsItUnder63() {
        assertThat(AppObjectNames.objectName("demo", "web")).isEqualTo("demo-web");
        String name = AppObjectNames.objectName("a".repeat(30), "b".repeat(20));
        assertThat(name.length()).isLessThanOrEqualTo(63);
        assertThat(name).startsWith("a");
        assertThat(name).contains("-");
    }

    @Test
    void uppercasesNodeKeyForEnvPrefix() {
        assertThat(AppObjectNames.envPrefix("mysql-main")).isEqualTo("MYSQL_MAIN");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl platform-api -am test -Dtest=AppObjectNamesTest`
Expected: 编译失败 —— `AppObjectNames` 不存在。

- [ ] **Step 3: Write minimal implementation**

```java
package com.coding.platformapi.app;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 应用相关名字的生成规则。
 *
 * <p>K8s 对象名上限 63（DNS-1123 label），而节点 key 最长 20、应用 slug 上限 30 —— 两者相加会超，
 * 所以超长时截断 slug 而不是截断 key（key 是用户在图里认得出的那个名字）。
 */
public final class AppObjectNames {

    public static final int SLUG_MAX = 30;
    public static final int OBJECT_NAME_MAX = 63;

    private AppObjectNames() {
    }

    public static String slug(String appName) {
        String s = appName == null ? "" : appName.trim().toLowerCase(Locale.ROOT);
        s = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        s = s.replaceAll("[^a-z0-9-]+", "-");
        s = s.replaceAll("^-+", "").replaceAll("-+$", "");
        if (s.isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用名至少要包含一个字母或数字");
        }
        if (s.length() > SLUG_MAX) {
            s = s.substring(0, SLUG_MAX).replaceAll("-+$", "");
        }
        return s;
    }

    public static String objectName(String slug, String nodeKey) {
        String name = slug + "-" + nodeKey;
        if (name.length() <= OBJECT_NAME_MAX) {
            return name;
        }
        int keep = OBJECT_NAME_MAX - nodeKey.length() - 1;
        String head = slug.substring(0, Math.max(1, keep)).replaceAll("-+$", "");
        return head + "-" + nodeKey;
    }

    public static String envPrefix(String nodeKey) {
        return nodeKey.toUpperCase(Locale.ROOT).replace('-', '_');
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl platform-api -am test -Dtest=AppObjectNamesTest`
Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/app/AppObjectNames.java \
        platform-api/src/test/java/com/coding/platformapi/app/AppObjectNamesTest.java
git commit -m "feat(application): 应用命名规则（slug / 对象名 / 环境变量前缀）"
```

---

### Task 3: 迁移、数据模型与 mapper

**Files:**
- Create: `platform-data/src/main/resources/db/migration/V2026_10_25_7__application.sql`
- Create: `platform-data/src/main/java/com/coding/data/models/system/PlatformApplication.java`
- Create: `.../system/PlatformApplicationRevision.java` · `PlatformApplicationOperation.java` · `PlatformApplicationResource.java`
- Create: `platform-data/src/main/java/com/coding/data/mapper/system/PlatformApplicationMapper.java`（同名 `Revision` / `Operation` / `Resource`）
- Create: `platform-data/src/main/resources/mapper/system/PlatformApplicationMapper.xml`（同名三个）
- Test: `platform-api/src/test/java/com/coding/platformapi/app/ApplicationMigrationTest.java`

**Interfaces:**
- Consumes: Task 1 的 DTO 包
- Produces:
  - 表：`platform_application` / `platform_application_revision` / `platform_application_operation` / `platform_application_resource`
  - 权限码：`tenant:application:read` / `tenant:application:write`；页面码 `tenant:page:application`
  - `PlatformApplicationMapper`：`insert` / `updateById` / `selectById` / `selectByTenant` / `softDelete`
  - `PlatformApplicationRevisionMapper`：`insert` / `updateById` / `selectByApp` / `selectByRevision` / `selectMaxRevision`
  - `PlatformApplicationOperationMapper`：`insert` / `updateById` / `selectById` / `selectActiveByApp` / `selectFirstActive`
  - `PlatformApplicationResourceMapper`：`batchInsert` / `selectByRevision` / `selectByApp` / `deleteByAppRevision`

- [ ] **Step 1: Write the failing test**

```java
package com.coding.platformapi.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移文件的内容检查（没有 Flyway，也没法在单测里连库）。
 * 钉住：四张表、页面码、两类权限码、角色绑定都必须在这个文件里。
 */
class ApplicationMigrationTest {

    private static final String SQL = read();

    private static String read() {
        try {
            // 与 ObjectStoragePermissionMigrationTest 同惯例：测试的工作目录是模块目录
            return Files.readString(Path.of(
                    "../platform-data/src/main/resources/db/migration/V2026_10_25_7__application.sql"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void createsFourTables() {
        assertThat(SQL).contains("CREATE TABLE IF NOT EXISTS platform_application ");
        assertThat(SQL).contains("CREATE TABLE IF NOT EXISTS platform_application_revision");
        assertThat(SQL).contains("CREATE TABLE IF NOT EXISTS platform_application_operation");
        assertThat(SQL).contains("CREATE TABLE IF NOT EXISTS platform_application_resource");
    }

    @Test
    void declaresPageCodeAndPermissionCodes() {
        assertThat(SQL).contains("tenant:page:application");
        assertThat(SQL).contains("tenant:application:read");
        assertThat(SQL).contains("tenant:application:write");
    }

    @Test
    void bindsWriteCodeToAdminAndTenantAdminOnly() {
        assertThat(SQL).contains("'builtin_role_admin'");
        assertThat(SQL).contains("'builtin_role_tenant_admin'");
        assertThat(SQL).doesNotContain("'rp_tenant_member_application_write'");
    }

    @Test
    void coversEveryEndpointOfTheNewModule() {
        List<String> endpoints = List.of(
                "/application/list", "/application/get", "/application/create", "/application/update",
                "/application/delete", "/application/preview", "/application/deploy",
                "/application/operation/get", "/application/revision/list", "/application/options");
        assertThat(SQL).contains(endpoints.toArray(String[]::new));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl platform-api -am test -Dtest=ApplicationMigrationTest`
Expected: FAIL —— `V2026_10_25_7__application.sql` 不存在（`NoSuchFileException`）。

- [ ] **Step 3: Write the migration**

```sql
-- ============================================================================================
-- 应用编排（画布）· 计划①：应用台账 / 版本 / 操作 / 资源（2026-10-25）
-- ============================================================================================
-- spec JSON 是真相源；每次部署写一条不可变 revision（spec + 解析结果 + 渲染清单）；
-- 操作是平台侧阶段机，current_step 持久化，重启后从断点续跑；
-- 资源台账用于 prune 与资源页反查。
--
-- 幂等：CREATE TABLE IF NOT EXISTS + 显式 id + ODKU；⚠️ 本仓没有 Flyway，手工重放。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

CREATE TABLE IF NOT EXISTS platform_application (
  id               varchar(64)  NOT NULL,
  tenant_id        varchar(64)  NOT NULL,
  cluster_id       varchar(64)  NOT NULL,
  namespace        varchar(128) NOT NULL,
  name             varchar(128) NOT NULL,
  slug             varchar(64)  NOT NULL COMMENT 'K8s 对象名前缀',
  display_name     varchar(128) NULL,
  description      varchar(512) NULL,
  status           varchar(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / DEPLOYING / READY / FAILED / DELETING',
  spec_json        MEDIUMTEXT   NOT NULL,
  current_revision int          NOT NULL DEFAULT 0,
  created_by       varchar(128) NULL,
  created_at       datetime     NOT NULL,
  updated_at       datetime     NOT NULL,
  deleted_at       datetime     NULL,
  active_key       varchar(160) GENERATED ALWAYS AS
                   (IF(deleted_at IS NULL, CONCAT(cluster_id, '/', namespace, '/', name), NULL)) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_active (active_key),
  KEY idx_app_tenant (tenant_id, cluster_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用（画布）台账';

CREATE TABLE IF NOT EXISTS platform_application_revision (
  id            bigint       NOT NULL AUTO_INCREMENT,
  app_id        varchar(64)  NOT NULL,
  revision      int          NOT NULL,
  spec_json     MEDIUMTEXT   NOT NULL,
  resolved_json MEDIUMTEXT   NULL COMMENT '解析后的注入上下文（不含任何密码明文）',
  manifest      MEDIUMTEXT   NULL COMMENT '渲染后的多文档 YAML',
  status        varchar(16)  NOT NULL DEFAULT 'RENDERED' COMMENT 'RENDERED / DEPLOYED / FAILED',
  message       varchar(1024) NULL,
  created_by    varchar(128) NULL,
  created_at    datetime     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_revision (app_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用的不可变版本快照';

CREATE TABLE IF NOT EXISTS platform_application_operation (
  id               bigint      NOT NULL AUTO_INCREMENT,
  app_id           varchar(64) NOT NULL,
  revision         int         NOT NULL,
  action           varchar(16) NOT NULL COMMENT 'DEPLOY / UPGRADE / DELETE / RETRY',
  status           varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / SUCCESS / FAILED',
  current_step     varchar(32) NOT NULL DEFAULT 'PRECHECK'
                   COMMENT 'PRECHECK / RENDER / APPLY / PRUNE / HEALTH / DONE',
  node_states_json varchar(4096) NULL COMMENT '{节点key: 状态}',
  message          varchar(1024) NULL,
  started_at       datetime    NOT NULL,
  finished_at      datetime    NULL,
  created_by       varchar(128) NULL,
  PRIMARY KEY (id),
  KEY idx_app_op_active (app_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用的部署操作（阶段机）';

CREATE TABLE IF NOT EXISTS platform_application_resource (
  id          bigint       NOT NULL AUTO_INCREMENT,
  app_id      varchar(64)  NOT NULL,
  revision    int          NOT NULL,
  api_version varchar(128) NOT NULL,
  kind        varchar(64)  NOT NULL,
  namespace   varchar(128) NOT NULL,
  name        varchar(253) NOT NULL,
  node_key    varchar(64)  NULL,
  object_uid  varchar(64)  NULL,
  created_at  datetime     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_res (app_id, revision, kind, namespace, name),
  KEY idx_app_res_rev (app_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用创建的资源台账（prune 与反查）';

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_page_application',        'Page','/applications',                 'view','tenant:page:application',  '应用编排页面可见性'),
 ('perm_app_list',                'API', '/application/list',             'POST','tenant:application:read',  '应用列表'),
 ('perm_app_get',                 'API', '/application/get',              'POST','tenant:application:read',  '应用详情'),
 ('perm_app_preview',             'API', '/application/preview',          'POST','tenant:application:read',  '应用渲染预览'),
 ('perm_app_operation_get',       'API', '/application/operation/get',    'POST','tenant:application:read',  '应用操作轮询'),
 ('perm_app_revision_list',       'API', '/application/revision/list',    'POST','tenant:application:read',  '应用版本历史'),
 ('perm_app_options',             'API', '/application/options',          'POST','tenant:application:read',  '应用可选项目录'),
 ('perm_app_create',              'API', '/application/create',           'POST','tenant:application:write', '创建应用'),
 ('perm_app_update',              'API', '/application/update',           'POST','tenant:application:write', '编辑应用'),
 ('perm_app_delete',              'API', '/application/delete',           'POST','tenant:application:write', '删除应用'),
 ('perm_app_deploy',              'API', '/application/deploy',           'POST','tenant:application:write', '部署应用')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_page_application',        'builtin_role_admin',        'perm_page_application'),
 ('rp_tenant_admin_page_application', 'builtin_role_tenant_admin', 'perm_page_application'),
 ('rp_tenant_member_page_application','builtin_role_tenant_member','perm_page_application'),
 ('rp_admin_app_read',                'builtin_role_admin',        'perm_app_list'),
 ('rp_admin_app_get',                 'builtin_role_admin',        'perm_app_get'),
 ('rp_admin_app_preview',             'builtin_role_admin',        'perm_app_preview'),
 ('rp_admin_app_op',                  'builtin_role_admin',        'perm_app_operation_get'),
 ('rp_admin_app_rev',                 'builtin_role_admin',        'perm_app_revision_list'),
 ('rp_admin_app_options',             'builtin_role_admin',        'perm_app_options'),
 ('rp_admin_app_create',              'builtin_role_admin',        'perm_app_create'),
 ('rp_admin_app_update',              'builtin_role_admin',        'perm_app_update'),
 ('rp_admin_app_delete',              'builtin_role_admin',        'perm_app_delete'),
 ('rp_admin_app_deploy',              'builtin_role_admin',        'perm_app_deploy'),
 ('rp_tenant_admin_app_read',         'builtin_role_tenant_admin', 'perm_app_list'),
 ('rp_tenant_admin_app_get',          'builtin_role_tenant_admin', 'perm_app_get'),
 ('rp_tenant_admin_app_preview',      'builtin_role_tenant_admin', 'perm_app_preview'),
 ('rp_tenant_admin_app_op',           'builtin_role_tenant_admin', 'perm_app_operation_get'),
 ('rp_tenant_admin_app_rev',          'builtin_role_tenant_admin', 'perm_app_revision_list'),
 ('rp_tenant_admin_app_options',      'builtin_role_tenant_admin', 'perm_app_options'),
 ('rp_tenant_admin_app_create',       'builtin_role_tenant_admin', 'perm_app_create'),
 ('rp_tenant_admin_app_update',       'builtin_role_tenant_admin', 'perm_app_update'),
 ('rp_tenant_admin_app_delete',       'builtin_role_tenant_admin', 'perm_app_delete'),
 ('rp_tenant_admin_app_deploy',       'builtin_role_tenant_admin', 'perm_app_deploy'),
 ('rp_tenant_member_app_read',        'builtin_role_tenant_member','perm_app_list'),
 ('rp_tenant_member_app_get',         'builtin_role_tenant_member','perm_app_get'),
 ('rp_tenant_member_app_preview',     'builtin_role_tenant_member','perm_app_preview'),
 ('rp_tenant_member_app_op',          'builtin_role_tenant_member','perm_app_operation_get'),
 ('rp_tenant_member_app_rev',         'builtin_role_tenant_member','perm_app_revision_list'),
 ('rp_tenant_member_app_options',     'builtin_role_tenant_member','perm_app_options')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
```

- [ ] **Step 4: Add models and mappers**

`PlatformApplication`（`@Data`，字段与表一一对应：`id/tenantId/clusterId/namespace/name/slug/displayName/description/status/specJson/currentRevision/createdBy/createdAt/updatedAt/deletedAt`；**不要**给生成列 `active_key` 建字段）。其余三个 model 同法：

```java
@Data
public class PlatformApplicationRevision {
    private Long id;
    private String appId;
    private Integer revision;
    private String specJson;
    private String resolvedJson;
    private String manifest;
    private String status;
    private String message;
    private String createdBy;
    private Date createdAt;
}

@Data
public class PlatformApplicationOperation {
    private Long id;
    private String appId;
    private Integer revision;
    private String action;
    private String status;
    private String currentStep;
    private String nodeStatesJson;
    private String message;
    private Date startedAt;
    private Date finishedAt;
    private String createdBy;
}

@Data
public class PlatformApplicationResource {
    private Long id;
    private String appId;
    private Integer revision;
    private String apiVersion;
    private String kind;
    private String namespace;
    private String name;
    private String nodeKey;
    private String objectUid;
    private Date createdAt;
}
```

Mapper 接口（`@Mapper`，与既有 mapper 同风格）+ XML：

```java
@Mapper
public interface PlatformApplicationMapper {
    int insert(PlatformApplication row);
    int updateById(PlatformApplication row);
    PlatformApplication selectById(@Param("id") String id);
    List<PlatformApplication> selectByTenant(@Param("tenantId") String tenantId,
                                             @Param("clusterId") String clusterId);
    int softDelete(@Param("id") String id, @Param("now") Date now);
}
```

```java
@Mapper
public interface PlatformApplicationOperationMapper {
    int insert(PlatformApplicationOperation row);
    int updateById(PlatformApplicationOperation row);
    PlatformApplicationOperation selectById(@Param("id") Long id);
    PlatformApplicationOperation selectActiveByApp(@Param("appId") String appId);
    /** worker 每次取最老的一条活操作（单副本部署，不做分布式锁） */
    PlatformApplicationOperation selectFirstActive();
}
```

XML 片段（`PlatformApplicationMapper.xml`；其余三个同构，一一对应上面的方法名）：

```xml
<insert id="insert" parameterType="com.coding.data.models.system.PlatformApplication">
  INSERT INTO platform_application
    (id, tenant_id, cluster_id, namespace, name, slug, display_name, description,
     status, spec_json, current_revision, created_by, created_at, updated_at)
  VALUES
    (#{id}, #{tenantId}, #{clusterId}, #{namespace}, #{name}, #{slug}, #{displayName}, #{description},
     #{status}, #{specJson}, #{currentRevision}, #{createdBy}, #{createdAt}, #{updatedAt})
</insert>

<update id="softDelete">
  UPDATE platform_application SET deleted_at = #{now}, updated_at = #{now} WHERE id = #{id}
</update>

<select id="selectByTenant" resultType="com.coding.data.models.system.PlatformApplication">
  SELECT * FROM platform_application
  WHERE deleted_at IS NULL AND tenant_id = #{tenantId}
  <if test="clusterId != null and clusterId != ''"> AND cluster_id = #{clusterId} </if>
  ORDER BY created_at DESC
</select>
```

`selectFirstActive`：

```xml
<select id="selectFirstActive" resultType="com.coding.data.models.system.PlatformApplicationOperation">
  SELECT * FROM platform_application_operation
  WHERE status IN ('PENDING','RUNNING')
  ORDER BY id ASC LIMIT 1
</select>
```

- [ ] **Step 5: Run tests and commit**

Run: `mvn -q -pl platform-api -am test -Dtest=ApplicationMigrationTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

```bash
git add platform-data/src/main/resources/db/migration/V2026_10_25_7__application.sql \
        platform-data/src/main/java/com/coding/data/models/system/PlatformApplication*.java \
        platform-data/src/main/java/com/coding/data/mapper/system/PlatformApplication*.java \
        platform-data/src/main/resources/mapper/system/PlatformApplication*.xml \
        platform-api/src/test/java/com/coding/platformapi/app/ApplicationMigrationTest.java
git commit -m "feat(application): 应用台账/版本/操作/资源四张表与权限行"
```

---

### Task 4: 内置 chart 片段 `app/workload`

**Files:**
- Create: `k8s-server/src/main/resources/charts/app/workload/1.0.0/chart/Chart.yaml`
- Create: `.../chart/values.yaml`
- Create: `.../chart/templates/_helpers.tpl`
- Create: `.../chart/templates/deployment.yaml` · `statefulset.yaml` · `service.yaml` · `configmap.yaml` · `secret.yaml` · `hpa.yaml` · `servicemonitor.yaml`
- Create: `k8s-server/src/main/resources/charts/app/workload/README.md`

**Interfaces:**
- Consumes: 无（纯 chart）
- Produces: `ChartBundle.chartDir("app", "workload", "1.0.0")` 可定位的目录；values 结构见下（Task 5 按此组装）

- [ ] **Step 1: Write `Chart.yaml` and `values.yaml`**

```yaml
# Chart.yaml
apiVersion: v2
name: app-workload
description: 平台应用编排的「服务 / 后台进程」节点模板（内部使用，不对外发布）
type: application
version: 1.0.0
appVersion: "1.0.0"
```

```yaml
# values.yaml
app:
  id: ""
  slug: ""
  revision: 0

workload:
  key: ""
  # service = 有端口、可建 Service；worker = 无端口、不建 Service
  mode: service
  image: ""
  imagePullPolicy: IfNotPresent
  port: 8080
  replicas: 1
  command: []
  args: []
  env: []            # [{name, value}]
  secretEnv: []      # [{name, secretName, secretKey}]
  resources:
    requests: { cpu: 100m, memory: 128Mi }
    limits: { cpu: "1", memory: 1Gi }
  persistence:
    enabled: false
    size: 1Gi
    storageClass: ""
    mountPath: /data
  autoscaling:
    enabled: false
    minReplicas: 1
    maxReplicas: 10
    targetCpu: 60
  metrics:
    enabled: false
    path: /metrics
  expose:
    # none（仅集群内 ClusterIP）/ nodeport
    type: none
    port: 8080
```

- [ ] **Step 2: Write templates**

`_helpers.tpl`：

```yaml
{{- define "app.name" -}}
{{ .Values.app.slug }}-{{ .Values.workload.key }}
{{- end -}}

{{- define "app.labels" -}}
app.kubernetes.io/name: {{ .Values.app.slug }}
app.kubernetes.io/managed-by: k8s-cloud-platform
platform.coding/app: {{ .Values.app.id | quote }}
platform.coding/app-node: {{ .Values.workload.key | quote }}
platform.coding/managed: "true"
{{- end -}}

{{- define "app.selector" -}}
platform.coding/app: {{ .Values.app.id | quote }}
platform.coding/app-node: {{ .Values.workload.key | quote }}
{{- end -}}
```

`configmap.yaml`（只有普通变量）：

```yaml
{{- if .Values.workload.env }}
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "app.name" . }}-env
  labels:
    {{- include "app.labels" . | nindent 4 }}
  annotations:
    platform.coding/revision: {{ .Values.app.revision | quote }}
data:
  {{- range .Values.workload.env }}
  {{ .name }}: {{ .value | quote }}
  {{- end }}
{{- end }}
```

`secret.yaml`：**节点自带的敏感变量**才建（清单里只出现 `secretKeyRef` 的引用不建 Secret）：

```yaml
{{- if .Values.workload.secretValues }}
apiVersion: v1
kind: Secret
metadata:
  name: {{ include "app.name" . }}-secret
  labels:
    {{- include "app.labels" . | nindent 4 }}
type: Opaque
stringData:
  {{- range .Values.workload.secretValues }}
  {{ .name }}: {{ .value | quote }}
  {{- end }}
{{- end }}
```

> `secretValues` 只在**创建时**由 platform-api 经 k8s-server 写入，**不进入 revision 的 manifest 快照**（
> Task 5 在渲染后会把这份 Secret 文档从快照里剥掉，只保留「需要哪些键」的说明）。见 Task 5 Step 3。

`deployment.yaml`：

```yaml
{{- if eq .Values.workload.mode "service" }}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "app.name" . }}
  labels:
    {{- include "app.labels" . | nindent 4 }}
  annotations:
    platform.coding/revision: {{ .Values.app.revision | quote }}
spec:
  replicas: {{ if .Values.workload.autoscaling.enabled }}1{{ else }}{{ .Values.workload.replicas }}{{ end }}
  selector:
    matchLabels:
      {{- include "app.selector" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "app.selector" . | nindent 8 }}
      annotations:
        platform.coding/revision: {{ .Values.app.revision | quote }}
    spec:
      containers:
        - name: app
          image: {{ .Values.workload.image | quote }}
          imagePullPolicy: {{ .Values.workload.imagePullPolicy }}
          ports:
            - name: http
              containerPort: {{ .Values.workload.port }}
          {{- with .Values.workload.command }}
          command: {{ toYaml . | nindent 12 }}
          {{- end }}
          {{- with .Values.workload.args }}
          args: {{ toYaml . | nindent 12 }}
          {{- end }}
          {{- if or .Values.workload.env .Values.workload.secretEnv }}
          env:
            {{- range .Values.workload.env }}
            - name: {{ .name }}
              valueFrom:
                configMapKeyRef:
                  name: {{ include "app.name" $ }}-env
                  key: {{ .name }}
            {{- end }}
            {{- range .Values.workload.secretEnv }}
            - name: {{ .name }}
              valueFrom:
                secretKeyRef:
                  name: {{ .secretName }}
                  key: {{ .secretKey }}
            {{- end }}
          {{- end }}
          {{- if .Values.workload.persistence.enabled }}
          volumeMounts:
            - name: data
              mountPath: {{ .Values.workload.persistence.mountPath }}
          {{- end }}
          resources:
            {{- toYaml .Values.workload.resources | nindent 12 }}
          readinessProbe:
            httpGet: { path: /, port: http }
            initialDelaySeconds: 10
            failureThreshold: 3
          livenessProbe:
            httpGet: { path: /, port: http }
            initialDelaySeconds: 30
            failureThreshold: 3
      {{- if .Values.workload.persistence.enabled }}
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: {{ include "app.name" . }}-data
      {{- end }}
{{- end }}
```

`statefulset.yaml`：当 `mode=service` 且 `persistence.enabled=true` 时替代 Deployment（`serviceName: <name>-headless`，`volumeClaimTemplates` 用 `persistence.size/storageClass`）。

```yaml
{{- if and (eq .Values.workload.mode "service") .Values.workload.persistence.enabled }}
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: {{ include "app.name" . }}
  labels:
    {{- include "app.labels" . | nindent 4 }}
spec:
  serviceName: {{ include "app.name" . }}-headless
  replicas: {{ .Values.workload.replicas }}
  selector:
    matchLabels:
      {{- include "app.selector" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "app.selector" . | nindent 8 }}
    spec:
      containers:
        - name: app
          image: {{ .Values.workload.image | quote }}
          ports:
            - name: http
              containerPort: {{ .Values.workload.port }}
          volumeMounts:
            - name: data
              mountPath: {{ .Values.workload.persistence.mountPath }}
          resources:
            {{- toYaml .Values.workload.resources | nindent 12 }}
  volumeClaimTemplates:
    - metadata:
        name: data
      spec:
        accessModes: ["ReadWriteOnce"]
        {{- if .Values.workload.persistence.storageClass }}
        storageClassName: {{ .Values.workload.persistence.storageClass | quote }}
        {{- end }}
        resources:
          requests:
            storage: {{ .Values.workload.persistence.size }}
{{- end }}
```

`service.yaml`：

```yaml
{{- if eq .Values.workload.mode "service" }}
apiVersion: v1
kind: Service
metadata:
  name: {{ include "app.name" . }}
  labels:
    {{- include "app.labels" . | nindent 4 }}
spec:
  type: {{ if eq .Values.workload.expose.type "nodeport" }}NodePort{{ else }}ClusterIP{{ end }}
  selector:
    {{- include "app.selector" . | nindent 4 }}
  ports:
    - name: http
      port: {{ .Values.workload.expose.port }}
      targetPort: http
{{- end }}
```

`service-headless.yaml`（只在持久化时建）：

```yaml
{{- if and (eq .Values.workload.mode "service") .Values.workload.persistence.enabled }}
apiVersion: v1
kind: Service
metadata:
  name: {{ include "app.name" . }}-headless
  labels:
    {{- include "app.labels" . | nindent 4 }}
spec:
  clusterIP: None
  selector:
    {{- include "app.selector" . | nindent 4 }}
  ports:
    - name: http
      port: {{ .Values.workload.port }}
      targetPort: http
{{- end }}
```

`hpa.yaml`：

```yaml
{{- if .Values.workload.autoscaling.enabled }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "app.name" . }}
  labels:
    {{- include "app.labels" . | nindent 4 }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: {{ if .Values.workload.persistence.enabled }}StatefulSet{{ else }}Deployment{{ end }}
    name: {{ include "app.name" . }}
  minReplicas: {{ .Values.workload.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.workload.autoscaling.maxReplicas }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ .Values.workload.autoscaling.targetCpu }}
{{- end }}
```

`servicemonitor.yaml`：

```yaml
{{- if and .Values.workload.metrics.enabled (eq .Values.workload.mode "service") }}
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: {{ include "app.name" . }}
  labels:
    {{- include "app.labels" . | nindent 4 }}
spec:
  selector:
    matchLabels:
      {{- include "app.selector" . | nindent 6 }}
  endpoints:
    - port: http
      path: {{ .Values.workload.metrics.path }}
      interval: 30s
{{- end }}
```

- [ ] **Step 3: Lint the chart and write the component README**

Run:

```bash
helm lint k8s-server/src/main/resources/charts/app/workload/1.0.0/chart
helm template demo k8s-server/src/main/resources/charts/app/workload/1.0.0/chart \
  --set app.id=app_demo --set app.slug=demo --set app.revision=1 \
  --set workload.key=web --set workload.mode=service --set workload.image=nginx:1.27 \
  --namespace demo | grep -E "^kind:|^  name:"
```

Expected: `helm lint` 输出 `1 chart(s) linted, 0 chart(s) failed`；`helm template` 只出现
`Deployment` / `Service` / `ConfigMap`（没有 ServiceMonitor / StatefulSet / HPA —— 开关默认关）。

`README.md` 写清：values 结构、`mode` 两种取值的产物差异、默认资源与探针、标签约定
（`platform.coding/app` / `app-node` / `managed` / `revision`）、以及「新增节点类型 = 新增一个 chart 目录」的约定。

- [ ] **Step 4: Commit**

```bash
git add k8s-server/src/main/resources/charts/app
git commit -m "feat(application): 服务/后台进程节点内置 chart 片段"
```

---

### Task 5: 节点参数 → chart values（含敏感值处理）

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/app/AppWorkloadValues.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/app/AppWorkloadValuesTest.java`

**Interfaces:**
- Consumes: `AppNodeDTO`（Task 1）、`AppObjectNames.slug`（Task 2 的等价物在 k8s-server 侧不需要，slug 由调用方传入）
- Produces: `AppWorkloadValues.build(String appId, String slug, int revision, AppNodeDTO node) → Map<String,Object>`
  以及 `AppWorkloadValues.secretValues(Map<String,Object> params) → Map<String,String>`（节点自带的敏感值，**只用于创建 Secret**）

- [ ] **Step 1: Write the failing test**

```java
package com.coding.k8sserver.app;

import com.coding.common.models.app.AppNodeDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppWorkloadValuesTest {

    private static AppNodeDTO node(Map<String, Object> params) {
        AppNodeDTO n = new AppNodeDTO();
        n.setKey("web");
        n.setKind("SERVICE");
        n.setParams(params);
        return n;
    }

    @Test
    void mapsBasicParamsAndAppliesDefaults() {
        Map<String, Object> v = AppWorkloadValues.build("app_1", "demo", 3,
                node(Map.of("image", "nginx:1.27")));

        assertThat(v).containsEntry("app", Map.of("id", "app_1", "slug", "demo", "revision", 3));
        @SuppressWarnings("unchecked")
        Map<String, Object> w = (Map<String, Object>) v.get("workload");
        assertThat(w).containsEntry("key", "web").containsEntry("mode", "service")
                .containsEntry("image", "nginx:1.27").containsEntry("replicas", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) w.get("resources");
        assertThat(res.get("requests")).isEqualTo(Map.of("cpu", "100m", "memory", "128Mi"));
    }

    @Test
    void splitsPlainAndSecretEnv() {
        AppNodeDTO n = node(Map.of(
                "image", "nginx",
                "env", List.of(
                        Map.of("name", "MODE", "value", "prod"),
                        Map.of("name", "DB_PASSWORD", "value", "p@ss", "secret", true))));
        Map<String, Object> v = AppWorkloadValues.build("app_1", "demo", 1, n);
        @SuppressWarnings("unchecked")
        Map<String, Object> w = (Map<String, Object>) v.get("workload");

        assertThat((List<?>) w.get("env")).containsExactly(Map.of("name", "MODE", "value", "prod"));
        assertThat(AppWorkloadValues.secretValues(n.getParams()))
                .containsExactly(Map.entry("DB_PASSWORD", "p@ss"));
    }

    @Test
    void rejectsMissingImage() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> AppWorkloadValues.build("app_1", "demo", 1, node(Map.of())))
                .hasMessageContaining("请填写镜像");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl k8s-server -am test -Dtest=AppWorkloadValuesTest`
Expected: 编译失败 —— `AppWorkloadValues` 不存在。

- [ ] **Step 3: Write minimal implementation**

```java
package com.coding.k8sserver.app;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.app.AppNodeDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把节点参数翻译成 chart values。
 *
 * <p>三种环境变量在这里分开：普通值 → ConfigMap；节点自带敏感值 → Secret（只在创建时下发，
 * 不进 revision 的 manifest 快照）；引用别的节点的值 → secretEnv（只带 secretName/secretKey）。
 * 「引用」那一路在计划② 才出现，这里先把字段留出来，免得以后改 values 结构。
 */
public final class AppWorkloadValues {

    private AppWorkloadValues() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> build(String appId, String slug, int revision, AppNodeDTO node) {
        Map<String, Object> params = node.getParams() == null ? Map.of() : node.getParams();
        String image = str(params.get("image"));
        if (image.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请填写镜像：" + node.getKey());
        }
        String mode = "WORKER".equalsIgnoreCase(node.getKind()) ? "worker" : "service";

        Map<String, Object> workload = new LinkedHashMap<>();
        workload.put("key", node.getKey());
        workload.put("mode", mode);
        workload.put("image", image);
        workload.put("port", num(params.get("port"), 8080));
        workload.put("replicas", num(params.get("replicas"), 1));
        workload.put("env", plainEnv(params));
        workload.put("secretEnv", secretRefs(params));
        workload.put("resources", resources(params));
        workload.put("persistence", persistence(params));
        workload.put("autoscaling", autoscaling(params));
        workload.put("metrics", Map.of("enabled", bool(params.get("metricsEnabled")),
                "path", str(params.getOrDefault("metricsPath", "/metrics"))));
        workload.put("expose", Map.of(
                "type", "worker".equals(mode) ? "none" : str(params.getOrDefault("expose", "none")),
                "port", num(params.get("port"), 8080)));

        Map<String, Object> app = new LinkedHashMap<>();
        app.put("id", appId);
        app.put("slug", slug);
        app.put("revision", revision);
        return Map.of("app", app, "workload", workload);
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> plainEnv(Map<String, Object> params) {
        return envList(params).stream()
                .filter(e -> !Boolean.TRUE.equals(e.get("secret")))
                .map(e -> Map.<String, Object>of("name", str(e.get("name")), "value", str(e.get("value"))))
                .toList();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, String> secretValues(Map<String, Object> params) {
        Map<String, String> out = new LinkedHashMap<>();
        envList(params).stream()
                .filter(e -> Boolean.TRUE.equals(e.get("secret")))
                .forEach(e -> out.put(str(e.get("name")), str(e.get("value"))));
        return out;
    }

    /** 计划② 使用：引用别的节点的 Secret（只有名字与键，没有值）。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> secretRefs(Map<String, Object> params) {
        Object raw = params.get("secretEnv");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            Map<String, Object> e = (Map<String, Object>) o;
            out.add(Map.of("name", str(e.get("name")),
                    "secretName", str(e.get("secretName")),
                    "secretKey", str(e.get("secretKey"))));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> envList(Map<String, Object> params) {
        Object raw = params.get("env");
        return raw instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static Map<String, Object> resources(Map<String, Object> params) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("requests", Map.of(
                "cpu", str(params.getOrDefault("cpuRequest", "100m")),
                "memory", str(params.getOrDefault("memoryRequest", "128Mi"))));
        r.put("limits", Map.of(
                "cpu", str(params.getOrDefault("cpuLimit", "1")),
                "memory", str(params.getOrDefault("memoryLimit", "1Gi"))));
        return r;
    }

    private static Map<String, Object> persistence(Map<String, Object> params) {
        return Map.of(
                "enabled", bool(params.get("persistenceEnabled")),
                "size", str(params.getOrDefault("storageSize", "1Gi")),
                "storageClass", str(params.getOrDefault("storageClass", "")),
                "mountPath", str(params.getOrDefault("mountPath", "/data")));
    }

    private static Map<String, Object> autoscaling(Map<String, Object> params) {
        return Map.of(
                "enabled", bool(params.get("autoscalingEnabled")),
                "minReplicas", num(params.get("minReplicas"), 1),
                "maxReplicas", num(params.get("maxReplicas"), 10),
                "targetCpu", num(params.get("targetCpu"), 60));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int num(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static boolean bool(Object o) {
        return Boolean.TRUE.equals(o);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl k8s-server -am test -Dtest=AppWorkloadValuesTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add k8s-server/src/main/java/com/coding/k8sserver/app/AppWorkloadValues.java \
        k8s-server/src/test/java/com/coding/k8sserver/app/AppWorkloadValuesTest.java
git commit -m "feat(application): 节点参数到 chart values 的映射（敏感值分流）"
```

---

### Task 6: 渲染与合并（k8s-server `/application/render`）

**Files:**
- Create: `platform-common/src/main/java/com/coding/common/models/app/AppRenderRequestDTO.java` · `AppRenderResultDTO.java` · `AppObjectRefDTO.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/app/AppRenderService.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/controllers/app/AppController.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/app/AppRenderServiceTest.java`

**Interfaces:**
- Consumes: `AppWorkloadValues.build(...)`（Task 5）、`HelmRunner.render(kubeconfig, chartDir, valuesFile, namespace)`、`ManifestInspector.inspect(manifest, ns, strict, scopes)`、`ChartBundle.chartDir("app","workload","1.0.0")`、`KubernetesClientFactory.adminKubeconfig(clusterId)`
- Produces:
  - `AppRenderRequestDTO { String clusterId; String namespace; String appId; String appSlug; Integer revision; AppSpecDTO spec; }`
  - `AppRenderResultDTO { String manifest; List<AppObjectRefDTO> objects; List<AppProblemDTO> problems; }`
  - `AppObjectRefDTO { String apiVersion; String kind; String namespace; String name; }`
  - `AppRenderService.render(AppRenderRequestDTO req) → AppRenderResultDTO`

- [ ] **Step 1: Write the failing test**

```java
package com.coding.k8sserver.app;

import com.coding.common.models.app.AppNodeDTO;
import com.coding.common.models.app.AppRenderRequestDTO;
import com.coding.common.models.app.AppRenderResultDTO;
import com.coding.common.models.app.AppSpecDTO;
import com.coding.k8sserver.helm.ChartBundle;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppRenderServiceTest {

    /** 假 helm：把 values 里的 slug/key 拼成两份文档，足够验证合并/解析/标签。 */
    private static HelmRunner fakeHelm(Path tempDir) {
        return new HelmRunner("true", List.of(), Map.of(), tempDir,
                java.time.Duration.ofSeconds(5)) {
            @Override
            public HelmResult render(String kubeconfig, Path chartDir, Path valuesFile, String namespace) {
                return new HelmResult(true, 0, """
                        apiVersion: apps/v1
                        kind: Deployment
                        metadata:
                          name: demo-web
                          namespace: %s
                        ---
                        apiVersion: v1
                        kind: Service
                        metadata:
                          name: demo-web
                          namespace: %s
                        """.formatted(namespace, namespace));
            }
        };
    }

    private static AppRenderRequestDTO request(String kind, Map<String, Object> params) {
        AppNodeDTO node = new AppNodeDTO();
        node.setKey("web");
        node.setKind(kind);
        node.setParams(params);
        AppSpecDTO spec = new AppSpecDTO();
        spec.setNamespace("demo");
        spec.setNodes(List.of(node));
        AppRenderRequestDTO req = new AppRenderRequestDTO();
        req.setClusterId("c1");
        req.setNamespace("demo");
        req.setAppId("app_1");
        req.setAppSlug("demo");
        req.setRevision(1);
        req.setSpec(spec);
        return req;
    }

    @Test
    void rendersAndListsObjects(@TempDir Path tempDir) throws Exception {
        AppRenderService svc = new AppRenderService(fakeHelm(tempDir),
                new ChartBundle(tempDir.toString()), null, null);

        AppRenderResultDTO r = svc.render(request("SERVICE", Map.of("image", "nginx")));

        assertThat(r.getObjects()).extracting("kind").containsExactly("Deployment", "Service");
        assertThat(r.getObjects()).allSatisfy(o -> assertThat(o.getName()).isEqualTo("demo-web"));
        assertThat(r.getProblems()).isEmpty();
    }

    @Test
    void rejectsUnknownKindAsProblem(@TempDir Path tempDir) throws Exception {
        AppRenderService svc = new AppRenderService(fakeHelm(tempDir),
                new ChartBundle(tempDir.toString()), null, null);

        AppRenderResultDTO r = svc.render(request("CRONJOB", Map.of("image", "nginx")));

        assertThat(r.getObjects()).isEmpty();
        assertThat(r.getProblems()).anySatisfy(p ->
                assertThat(p.getMessage()).contains("不支持的节点类型"));
    }

    @Test
    void reportsObjectNameConflictAcrossNodes(@TempDir Path tempDir) throws Exception {
        AppRenderService svc = new AppRenderService(fakeHelm(tempDir),
                new ChartBundle(tempDir.toString()), null, null);
        AppNodeDTO a = new AppNodeDTO();
        a.setKey("web");
        a.setKind("SERVICE");
        a.setParams(Map.of("image", "nginx"));
        AppNodeDTO b = new AppNodeDTO();
        b.setKey("api");
        b.setKind("SERVICE");
        b.setParams(Map.of("image", "nginx"));
        AppSpecDTO spec = new AppSpecDTO();
        spec.setNamespace("demo");
        spec.setNodes(List.of(a, b));
        AppRenderRequestDTO req = request("SERVICE", Map.of("image", "nginx"));
        req.setSpec(spec);

        AppRenderResultDTO r = svc.render(req);

        assertThat(r.getProblems()).anySatisfy(p -> assertThat(p.getMessage()).contains("对象名冲突"));
        // 冲突的第二份不进入清单（第一份保留）
        assertThat(r.getObjects()).hasSize(2);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl k8s-server -am test -Dtest=AppRenderServiceTest`
Expected: 编译失败 —— `AppRenderService` 不存在。

- [ ] **Step 3: Write DTOs and the service**

```java
// platform-common/.../app/AppRenderRequestDTO.java
@Data
public class AppRenderRequestDTO {
    private String clusterId;
    private String namespace;
    private String appId;
    private String appSlug;
    private Integer revision;
    private AppSpecDTO spec;
}
```

```java
// platform-common/.../app/AppObjectRefDTO.java
@Data
public class AppObjectRefDTO {
    private String apiVersion;
    private String kind;
    private String namespace;
    private String name;
}
```

```java
// platform-common/.../app/AppRenderResultDTO.java
@Data
public class AppRenderResultDTO {
    private String manifest;
    private List<AppObjectRefDTO> objects;
    private List<AppProblemDTO> problems;
}
```

```java
package com.coding.k8sserver.app;

import com.coding.common.models.app.*;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8sserver.chart.ClusterScopeIndex;
import com.coding.k8sserver.chart.ManifestInspector;
import com.coding.k8sserver.chart.Violation;
import com.coding.k8sserver.helm.ChartBundle;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import org.springframework.stereotype.Service;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 逐节点渲染 + 合并 + 静态检查。
 *
 * <p>为什么合并成单份 manifest：apply 走一次 SSA（fieldManager=platform-app），
 * 局部失败时平台的台账和集群状态更容易对齐；也让 prune 的差集计算只有一处。
 */
@Service
public class AppRenderService {

    private static final String WORKLOAD_CHART = "1.0.0";
    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private final HelmRunner helm;
    private final ChartBundle charts;
    private final KubernetesClientFactory clients;
    private final ClusterScopeIndex scopes;

    public AppRenderService(HelmRunner helm, ChartBundle charts,
                            KubernetesClientFactory clients, ClusterScopeIndex scopes) {
        this.helm = helm;
        this.charts = charts;
        this.clients = clients;
        this.scopes = scopes;
    }

    public AppRenderResultDTO render(AppRenderRequestDTO req) {
        List<AppProblemDTO> problems = new ArrayList<>();
        List<String> docs = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        String kubeconfig = clients == null ? "" : clients.adminKubeconfig(req.getClusterId());
        Path values = null;
        try {
            Path chartDir = charts.chartDir("app", "workload", WORKLOAD_CHART);
            for (AppNodeDTO node : req.getSpec().getNodes() == null ? List.<AppNodeDTO>of() : req.getSpec().getNodes()) {
                if (!"SERVICE".equals(node.getKind()) && !"WORKER".equals(node.getKind())) {
                    problems.add(AppProblemDTO.error(node.getKey(), "不支持的节点类型：" + node.getKind()));
                    continue;
                }
                values = writeValues(AppWorkloadValues.build(req.getAppId(), req.getAppSlug(),
                        req.getRevision() == null ? 0 : req.getRevision(), node));
                HelmResult rendered = helm.render(kubeconfig, chartDir, values, req.getNamespace());
                if (!rendered.ok()) {
                    problems.add(AppProblemDTO.error(node.getKey(), "渲染失败：" + rendered.output()));
                    continue;
                }
                for (String doc : split(rendered.output())) {
                    AppObjectRefDTO ref = refOf(doc, req.getNamespace());
                    if (ref == null) {
                        continue;
                    }
                    String id = ref.getKind() + "/" + ref.getName();
                    if (!names.add(id)) {
                        problems.add(AppProblemDTO.error(node.getKey(),
                                "对象名冲突（两个节点生成了同一个对象）：" + id));
                        continue;
                    }
                    docs.add(doc);
                }
            }
        } catch (RuntimeException e) {
            problems.add(AppProblemDTO.error(null, e.getMessage()));
        } finally {
            deleteQuietly(values);
        }

        String manifest = String.join("\n---\n", docs);
        for (Violation v : ManifestInspector.inspect(manifest, req.getNamespace(), true, scopes)) {
            problems.add(problemFrom(v));
        }
        AppRenderResultDTO out = new AppRenderResultDTO();
        out.setManifest(manifest);
        out.setObjects(docs.stream().map(d -> refOf(d, req.getNamespace()))
                .filter(java.util.Objects::nonNull).toList());
        out.setProblems(problems);
        return out;
    }

    /** 把静态检查的 Violation 归一到 AppProblemDTO：BLOCK → ERROR，其余 → WARN。 */
    private static AppProblemDTO problemFrom(Violation v) {
        AppProblemDTO p = new AppProblemDTO();
        p.setSeverity(Violation.BLOCK.equals(v.severity()) ? "ERROR" : "WARN");
        p.setMessage(v.object() + "：" + v.message());
        return p;
    }

    private static List<String> split(String manifest) {
        List<String> out = new ArrayList<>();
        for (String doc : manifest.split("(?m)^---[ \t]*$")) {
            if (!doc.isBlank()) {
                out.add(doc.strip() + "\n");
            }
        }
        return out;
    }

    private static AppObjectRefDTO refOf(String doc, String fallbackNs) {
        JsonNode root = YAML.readTree(doc);
        if (root == null || !root.has("kind") || !root.has("metadata")) {
            return null;
        }
        AppObjectRefDTO ref = new AppObjectRefDTO();
        ref.setApiVersion(root.path("apiVersion").asString(""));
        ref.setKind(root.path("kind").asString(""));
        ref.setName(root.path("metadata").path("name").asString(""));
        ref.setNamespace(root.path("metadata").path("namespace").asString(fallbackNs));
        return ref;
    }

    private static Path writeValues(Map<String, Object> values) {
        try {
            Path f = Files.createTempFile("app-values-", ".yaml");
            Files.writeString(f, YAML.writeValueAsString(values));
            return f;
        } catch (Exception e) {
            throw new IllegalStateException("写临时 values 失败", e);
        }
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (Exception ignored) {
                // 临时文件清不掉不影响渲染结果
            }
        }
    }
}
```

- [ ] **Step 4: Add the controller endpoint and run tests**

```java
@RestController
@RequestMapping("/application")
public class AppController {
    private final AppRenderService render;
    private final AppApplyService apply;

    public AppController(AppRenderService render, AppApplyService apply) {
        this.render = render;
        this.apply = apply;
    }

    @PostMapping("/render")
    public ResponseData<AppRenderResultDTO> render(@RequestBody AppRenderRequestDTO req) {
        return new ResponseData<>(render.render(req));
    }
}
```

Run: `mvn -q -pl k8s-server -am test -Dtest=AppRenderServiceTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add platform-common/src/main/java/com/coding/common/models/app/AppRender*.java \
        platform-common/src/main/java/com/coding/common/models/app/AppObjectRefDTO.java \
        k8s-server/src/main/java/com/coding/k8sserver/app k8s-server/src/main/java/com/coding/k8sserver/controllers/app \
        k8s-server/src/test/java/com/coding/k8sserver/app
git commit -m "feat(application): 逐节点渲染、合并与静态检查（/application/render）"
```

---

### Task 7: apply / prune / status（k8s-server）

**Files:**
- Create: `platform-common/src/main/java/com/coding/common/models/app/AppApplyRequestDTO.java` · `AppPruneRequestDTO.java` · `AppStatusRequestDTO.java` · `AppStatusResultDTO.java` · `AppObjectStatusDTO.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/app/AppApplyService.java` · `AppStatusService.java`
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/controllers/app/AppController.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/app/AppApplyServiceTest.java` · `AppStatusServiceTest.java`

**Interfaces:**
- Consumes: `HelmRunner.applyServerSide(kubeconfig, manifest)`（SSA）、`KubernetesClientFactory`
- Produces:
  - `AppApplyService.apply(AppApplyRequestDTO) → void`（失败抛 `CloudPlatformException`）
  - `AppApplyService.prune(AppPruneRequestDTO) → int`（返回删除数量）
  - `AppStatusService.status(AppStatusRequestDTO) → AppStatusResultDTO`
  - `AppObjectStatusDTO { String kind; String name; String state; String message; }`（`READY` / `PENDING` / `MISSING` / `FAILED`）

- [ ] **Step 1: Write the failing tests**

```java
package com.coding.k8sserver.app;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.app.AppApplyRequestDTO;
import com.coding.common.models.app.AppObjectRefDTO;
import com.coding.common.models.app.AppPruneRequestDTO;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppApplyServiceTest {

    private static HelmRunner helm(boolean ok) {
        return new HelmRunner("true", List.of(), Map.of(), Path.of(System.getProperty("java.io.tmpdir")),
                Duration.ofSeconds(5)) {
            @Override
            public HelmResult applyServerSide(String kubeconfig, String manifest) {
                return new HelmResult(ok, ok ? 0 : 1, ok ? "applied" : "boom");
            }
        };
    }

    @Test
    void applyFailsLoudlyOnHelmError() {
        AppApplyService svc = new AppApplyService(helm(false), null);
        AppApplyRequestDTO req = new AppApplyRequestDTO();
        req.setClusterId("c1");
        req.setManifest("apiVersion: v1\nkind: ConfigMap\n");

        assertThatThrownBy(() -> svc.apply(req)).isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void pruneSkipsPvcUnlessExplicitlyAllowed() {
        AppPruneRequestDTO req = new AppPruneRequestDTO();
        req.setClusterId("c1");
        req.setDeletePvc(false);
        req.setObjects(List.of(ref("PersistentVolumeClaim", "demo-web-data"), ref("Service", "demo-old")));

        // 只应尝试删 Service；PVC 被跳过（返回 1）。删除动作由 fake client 计数。
        RecordingAppApplyService svc = new RecordingAppApplyService();
        int deleted = svc.prune(req);

        assertThat(deleted).isEqualTo(1);
        assertThat(svc.deleted).containsExactly("Service/demo-old");
    }

    private static AppObjectRefDTO ref(String kind, String name) {
        AppObjectRefDTO r = new AppObjectRefDTO();
        r.setApiVersion("v1");
        r.setKind(kind);
        r.setNamespace("demo");
        r.setName(name);
        return r;
    }

    /** 测试用子类：把「真正删对象」换成一个可断言的记录器。 */
    static class RecordingAppApplyService extends AppApplyService {
        final java.util.List<String> deleted = new java.util.ArrayList<>();

        RecordingAppApplyService() {
            super(null, null);
        }

        @Override
        void deleteObject(String clusterId, AppObjectRefDTO ref) {
            deleted.add(ref.getKind() + "/" + ref.getName());
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl k8s-server -am test -Dtest=AppApplyServiceTest`
Expected: 编译失败 —— `AppApplyService` 不存在。

- [ ] **Step 3: Write the services**

```java
package com.coding.k8sserver.app;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.app.AppApplyRequestDTO;
import com.coding.common.models.app.AppObjectRefDTO;
import com.coding.common.models.app.AppPruneRequestDTO;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import org.springframework.stereotype.Service;

/** 只做事实与落盘：apply 一次合并后的 manifest；prune 按平台给的清单删。 */
@Service
public class AppApplyService {

    private final HelmRunner helm;
    private final KubernetesClientFactory clients;

    public AppApplyService(HelmRunner helm, KubernetesClientFactory clients) {
        this.helm = helm;
        this.clients = clients;
    }

    public void apply(AppApplyRequestDTO req) {
        String kubeconfig = clients.adminKubeconfig(req.getClusterId());
        HelmResult r = helm.applyServerSide(kubeconfig, req.getManifest());
        if (!r.ok()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用清单写入失败：" + r.output());
        }
    }

    /**
     * 删除差集对象。PVC 默认跳过：它承载数据，误删不可恢复；只有节点被移除且用户显式勾选时才删。
     */
    public int prune(AppPruneRequestDTO req) {
        int deleted = 0;
        for (AppObjectRefDTO ref : req.getObjects()) {
            if ("PersistentVolumeClaim".equals(ref.getKind()) && !req.isDeletePvc()) {
                continue;
            }
            deleteObject(req.getClusterId(), ref);
            deleted++;
        }
        return deleted;
    }

    /**
     * 用最小 manifest + {@code kubectl delete --ignore-not-found} 删除。
     *
     * <p>为什么不像读操作那样走 generic resource：删除需要 kind → plural 的映射，而平台没有维护
     * 一张全量 kind 表；交给 kubectl 就绕开了这件事。对象已不存在视为成功（与 S3 删除语义一致）。
     */
    void deleteObject(String clusterId, AppObjectRefDTO ref) {
        String manifest = """
                apiVersion: %s
                kind: %s
                metadata:
                  name: %s
                  namespace: %s
                """.formatted(ref.getApiVersion(), ref.getKind(), ref.getName(), ref.getNamespace());
        HelmResult r = helm.deleteManifest(clients.adminKubeconfig(clusterId), manifest);
        if (!r.ok()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "清理对象失败（" + ref.getKind() + "/" + ref.getName() + "）：" + r.output());
        }
    }
}
```

**同一步里给 `HelmRunner` 加一个方法**（与 `applyServerSide` 同形，`kubectl delete -f - --ignore-not-found`）：

```java
    /** 删除 manifest 里列出的对象；不存在的对象不算失败（与 S3 删除语义一致）。 */
    public HelmResult deleteManifest(String kubeconfig, String manifest) {
        Path manifestFile = null;
        Path kubeconfigFile = null;
        try {
            manifestFile = Files.createTempFile(tempDir, "delete-", ".yaml");
            Files.writeString(manifestFile, manifest, StandardCharsets.UTF_8);
            kubeconfigFile = writeTemp(kubeconfig);
            List<String> args = List.of(
                    "delete", "-f", manifestFile.toString(),
                    "--ignore-not-found",
                    "--kubeconfig", kubeconfigFile.toString());
            return run(toolCommand(args), args);
        } catch (Exception e) {
            return new HelmResult(false, -1, "kubectl 执行失败: " + e.getMessage());
        } finally {
            deleteQuietly(manifestFile);
            deleteQuietly(kubeconfigFile);
        }
    }
```

```java
package com.coding.k8sserver.app;

import com.coding.common.models.app.*;
import com.coding.k8score.factory.KubernetesClientFactory;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 现读被跟踪对象的状态；不做缓存，部署期几秒一次足够。 */
@Service
public class AppStatusService {

    private final KubernetesClientFactory clients;

    public AppStatusService(KubernetesClientFactory clients) {
        this.clients = clients;
    }

    public AppStatusResultDTO status(AppStatusRequestDTO req) {
        List<AppObjectStatusDTO> out = new ArrayList<>();
        for (AppObjectRefDTO ref : req.getObjects()) {
            out.add(stateOf(req.getClusterId(), ref));
        }
        AppStatusResultDTO r = new AppStatusResultDTO();
        r.setObjects(out);
        return r;
    }

    private AppObjectStatusDTO stateOf(String clusterId, AppObjectRefDTO ref) {
        AppObjectStatusDTO s = new AppObjectStatusDTO();
        s.setKind(ref.getKind());
        s.setName(ref.getName());
        var client = clients.getAdminClient(clusterId);
        switch (ref.getKind()) {
            case "Deployment" -> {
                Deployment d = client.apps().deployments()
                        .inNamespace(ref.getNamespace()).withName(ref.getName()).get();
                if (d == null) {
                    s.setState("MISSING");
                    return s;
                }
                int want = d.getSpec().getReplicas() == null ? 1 : d.getSpec().getReplicas();
                int have = d.getStatus() == null || d.getStatus().getAvailableReplicas() == null
                        ? 0 : d.getStatus().getAvailableReplicas();
                boolean observed = d.getStatus() != null
                        && d.getStatus().getObservedGeneration() != null
                        && d.getMetadata().getGeneration() != null
                        && d.getStatus().getObservedGeneration() >= d.getMetadata().getGeneration();
                s.setState(observed && have >= want ? "READY" : "PENDING");
                s.setMessage("可用副本 " + have + "/" + want);
            }
            case "StatefulSet" -> {
                StatefulSet sts = client.apps().statefulSets()
                        .inNamespace(ref.getNamespace()).withName(ref.getName()).get();
                if (sts == null) {
                    s.setState("MISSING");
                    return s;
                }
                int want = sts.getSpec().getReplicas() == null ? 1 : sts.getSpec().getReplicas();
                int have = sts.getStatus() == null || sts.getStatus().getReadyReplicas() == null
                        ? 0 : sts.getStatus().getReadyReplicas();
                s.setState(have >= want ? "READY" : "PENDING");
                s.setMessage("就绪副本 " + have + "/" + want);
            }
            default -> {
                // Service / ConfigMap / HPA / ServiceMonitor：存在即视为就绪（它们没有可用副本概念）
                s.setState(exists(client, ref) ? "READY" : "MISSING");
            }
        }
        return s;
    }

    private static boolean exists(io.fabric8.kubernetes.client.KubernetesClient client, AppObjectRefDTO ref) {
        return switch (ref.getKind()) {
            case "Service" -> client.services().inNamespace(ref.getNamespace()).withName(ref.getName()).get() != null;
            case "ConfigMap" -> client.configMaps().inNamespace(ref.getNamespace()).withName(ref.getName()).get() != null;
            case "Secret" -> client.secrets().inNamespace(ref.getNamespace()).withName(ref.getName()).get() != null;
            case "HorizontalPodAutoscaler" -> client.autoscaling().v2().horizontalPodAutoscalers()
                    .inNamespace(ref.getNamespace()).withName(ref.getName()).get() != null;
            // ServiceMonitor 等 CR：按 apiVersion/kind 走 generic 读（实施时用 ResourceDefinitionContext 构造）
            default -> client.genericKubernetesResources(ref.getApiVersion(), ref.getKind())
                    .inNamespace(ref.getNamespace()).withName(ref.getName()).get() != null;
        };
    }
}
```

> `genericKubernetesResources(apiVersion, kind)` 在 fabric8 7.9 上可用（仓库里 Calico 用的是
> `ResourceDefinitionContext` 重载；两者都走同类客户端）。若编译期发现该重载不存在，改为用
> `ResourceDefinitionContext.Builder` 构造一个上下文，`withNamespaced(true)`。

- [ ] **Step 4: Add endpoints and run tests**

```java
@RestController
@RequestMapping("/application")
public class AppController {

    private final AppRenderService render;
    private final AppApplyService apply;
    private final AppStatusService status;

    public AppController(AppRenderService render, AppApplyService apply, AppStatusService status) {
        this.render = render;
        this.apply = apply;
        this.status = status;
    }

    @PostMapping("/render")
    public ResponseData<AppRenderResultDTO> render(@RequestBody AppRenderRequestDTO req) {
        return new ResponseData<>(render.render(req));
    }

    @PostMapping("/apply")
    public ResponseData<Void> apply(@RequestBody AppApplyRequestDTO req) {
        apply.apply(req);
        return new ResponseData<>();
    }

    @PostMapping("/prune")
    public ResponseData<Integer> prune(@RequestBody AppPruneRequestDTO req) {
        return new ResponseData<>(apply.prune(req));
    }

    @PostMapping("/status")
    public ResponseData<AppStatusResultDTO> status(@RequestBody AppStatusRequestDTO req) {
        return new ResponseData<>(status.status(req));
    }
}
```

Run: `mvn -q -pl k8s-server -am test -Dtest='AppApplyServiceTest,AppStatusServiceTest'`
Expected: 全绿。

- [ ] **Step 5: Commit**

```bash
git add platform-common/src/main/java/com/coding/common/models/app \
        k8s-server/src/main/java/com/coding/k8sserver/app \
        k8s-server/src/test/java/com/coding/k8sserver/app
git commit -m "feat(application): apply / prune / status 三个集群侧端点"
```

---

### Task 8: platform-api 客户端与应用的增删改查

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/k8s/K8sAppClient.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/services/ApplicationService.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/ApplicationCreateRequest.java`（同目录另有 `ApplicationQueryRequest` / `ApplicationUpdateRequest` / `ApplicationDeleteRequest` / `ApplicationView` / `ApplicationRevisionView` / `ApplicationOptionsView`）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/ApplicationServiceTest.java`

**Interfaces:**
- Consumes: `PlatformApplicationMapper`（Task 3）、`AppSpecValidator`（Task 1）、`AppObjectNames`（Task 2）、`K8sServerGateway.responseType(Class)`、`AuthContext`
- Produces:
  - `K8sAppClient.render(AppRenderRequestDTO) → AppRenderResultDTO`（Task 9/10 用）
  - `ApplicationService.create(ApplicationCreateRequest, String operator, String tenantId) → ApplicationView`
  - `ApplicationService.update(ApplicationUpdateRequest, String operator) → ApplicationView`
  - `ApplicationService.get(String appId, String tenantId) → ApplicationView`
  - `ApplicationService.list(String clusterId, String tenantId) → List<ApplicationView>`
  - `ApplicationService.delete(ApplicationDeleteRequest, String operator) → void`
  - `ApplicationService.revisions(String appId) → List<ApplicationRevisionView>`
  - `ApplicationService.options(String clusterId, String tenantId) → ApplicationOptionsView`

- [ ] **Step 1: Write the failing test**

```java
package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.app.AppNodeDTO;
import com.coding.common.models.app.AppSpecDTO;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.models.system.PlatformApplication;
import com.coding.platformapi.models.ApplicationCreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationServiceTest {

    private PlatformApplicationMapper apps;
    private ApplicationService service;

    @BeforeEach
    void setUp() {
        apps = mock(PlatformApplicationMapper.class);
        service = new ApplicationService(apps, null, null);
    }

    private static ApplicationCreateRequest req(AppSpecDTO spec) {
        ApplicationCreateRequest r = new ApplicationCreateRequest();
        r.setClusterId("c1");
        r.setNamespace("demo");
        r.setName("Demo App");
        r.setSpec(spec);
        return r;
    }

    private static AppSpecDTO spec() {
        AppNodeDTO n = new AppNodeDTO();
        n.setKey("web");
        n.setKind("SERVICE");
        n.setParams(Map.of("image", "nginx"));
        AppSpecDTO s = new AppSpecDTO();
        s.setNamespace("demo");
        s.setNodes(List.of(n));
        return s;
    }

    @Test
    void createRejectsInvalidGraphBeforeTouchingDb() {
        AppSpecDTO bad = spec();
        bad.getNodes().get(0).setKind("CRONJOB");

        assertThatThrownBy(() -> service.create(req(bad), "admin", "t1"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("不支持的节点类型");
        verify(apps, org.mockito.Mockito.never()).insert(any());
    }

    @Test
    void createPersistsSlugAndSpec() {
        service.create(req(spec()), "admin", "t1");

        ArgumentCaptor<PlatformApplication> cap = ArgumentCaptor.forClass(PlatformApplication.class);
        verify(apps).insert(cap.capture());
        PlatformApplication row = cap.getValue();
        assertThat(row.getSlug()).isEqualTo("demo-app");
        assertThat(row.getStatus()).isEqualTo("DRAFT");
        assertThat(row.getSpecJson()).contains("\"web\"");
        assertThat(row.getTenantId()).isEqualTo("t1");
    }

    @Test
    void namespaceMismatchBetweenSpecAndRequestIsRejected() {
        ApplicationCreateRequest r = req(spec());
        r.setNamespace("other");

        assertThatThrownBy(() -> service.create(r, "admin", "t1"))
                .hasMessageContaining("命名空间不一致");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl platform-api -am test -Dtest=ApplicationServiceTest`
Expected: 编译失败 —— `ApplicationService` 不存在。

- [ ] **Step 3: Write the client, models and service**

```java
package com.coding.platformapi.k8s;

import com.coding.common.models.app.*;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 应用编排的集群侧调用（渲染 / 落盘 / 状态）。 */
@Component
public class K8sAppClient {

    private final K8sServerGateway gateway;

    public K8sAppClient(K8sServerGateway gateway) {
        this.gateway = gateway;
    }

    public AppRenderResultDTO render(AppRenderRequestDTO body) {
        return gateway.exchange(HttpMethod.POST, "/application/render", Map.of(), body,
                gateway.responseType(AppRenderResultDTO.class));
    }

    public void apply(AppApplyRequestDTO body) {
        gateway.exchange(HttpMethod.POST, "/application/apply", Map.of(), body,
                gateway.responseType(Void.class));
    }

    public Integer prune(AppPruneRequestDTO body) {
        return gateway.exchange(HttpMethod.POST, "/application/prune", Map.of(), body,
                gateway.responseType(Integer.class));
    }

    public AppStatusResultDTO status(AppStatusRequestDTO body) {
        return gateway.exchange(HttpMethod.POST, "/application/status", Map.of(), body,
                gateway.responseType(AppStatusResultDTO.class));
    }
}
```

```java
@Data
public class ApplicationCreateRequest {
    private String clusterId;
    private String namespace;
    private String name;
    private String displayName;
    private String description;
    private AppSpecDTO spec;
    private String tenantId;
}
```

```java
@Data
public class ApplicationView {
    private String id;
    private String clusterId;
    private String namespace;
    private String name;
    private String slug;
    private String displayName;
    private String description;
    private String status;
    private Integer currentRevision;
    private AppSpecDTO spec;
    private Date createdAt;
    private Date updatedAt;
}
```

`ApplicationService` 的关键逻辑（完整实现，不留空）：

```java
@Service
public class ApplicationService {

    // Jackson 3（本仓统一用 tools.jackson）：与 k8s-server 的写法一致
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final PlatformApplicationMapper apps;
    private final PlatformApplicationRevisionMapper revisions;
    private final PlatformApplicationOperationMapper operations;

    public ApplicationService(PlatformApplicationMapper apps,
                              PlatformApplicationRevisionMapper revisions,
                              PlatformApplicationOperationMapper operations) {
        this.apps = apps;
        this.revisions = revisions;
        this.operations = operations;
    }

    public ApplicationView create(ApplicationCreateRequest req, String operator, String tenantId) {
        assertValid(req.getSpec(), req.getNamespace());
        PlatformApplication row = new PlatformApplication();
        row.setId("app_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        row.setTenantId(tenantId);
        row.setClusterId(req.getClusterId());
        row.setNamespace(req.getSpec().getNamespace());
        row.setName(req.getName());
        row.setSlug(AppObjectNames.slug(req.getName()));
        row.setDisplayName(isBlank(req.getDisplayName()) ? req.getName() : req.getDisplayName());
        row.setDescription(req.getDescription());
        row.setStatus("DRAFT");
        row.setSpecJson(JSON.writeValueAsString(req.getSpec()));
        row.setCurrentRevision(0);
        row.setCreatedBy(operator);
        row.setCreatedAt(new Date());
        row.setUpdatedAt(new Date());
        apps.insert(row);
        return view(row);
    }

    private void assertValid(AppSpecDTO spec, String requestNamespace) {
        if (spec == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用定义为空");
        }
        if (requestNamespace != null && !requestNamespace.equals(spec.getNamespace())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请求命名空间与应用定义里的命名空间不一致");
        }
        String errors = AppSpecValidator.validate(spec).stream()
                .filter(p -> "ERROR".equals(p.getSeverity()))
                .map(AppProblemDTO::getMessage)
                .collect(java.util.stream.Collectors.joining("；"));
        if (!errors.isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, errors);
        }
    }
}
```

同一个类里其余方法的完整实现：

```java
    public ApplicationView get(String appId, String tenantId) {
        PlatformApplication row = apps.selectById(appId);
        if (row == null || row.getDeletedAt() != null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用不存在或已删除");
        }
        // 租户只能看自己的；tenantId 为 null = 管理员轨道（调用方已按权限码区分）
        if (tenantId != null && !tenantId.equals(row.getTenantId())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用不存在或已删除");
        }
        return view(row);
    }

    public List<ApplicationView> list(String clusterId, String tenantId) {
        return apps.selectByTenant(tenantId, clusterId).stream().map(this::view).toList();
    }

    public ApplicationView update(ApplicationUpdateRequest req, String operator) {
        PlatformApplication row = apps.selectById(req.getAppId());
        if (row == null || row.getDeletedAt() != null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用不存在或已删除");
        }
        if (!"DRAFT".equals(row.getStatus()) && !"READY".equals(row.getStatus())
                && !"FAILED".equals(row.getStatus())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "部署进行中，不能改图");
        }
        if (req.getSpec() != null) {
            assertValid(req.getSpec(), row.getNamespace());
            row.setSpecJson(JSON.writeValueAsString(req.getSpec()));
        }
        if (req.getName() != null && !req.getName().isBlank()) {
            row.setName(req.getName());
            row.setSlug(AppObjectNames.slug(req.getName()));
        }
        if (req.getDisplayName() != null) {
            row.setDisplayName(req.getDisplayName());
        }
        if (req.getDescription() != null) {
            row.setDescription(req.getDescription());
        }
        row.setUpdatedAt(new Date());
        apps.updateById(row);
        return view(row);
    }

    /** 只软删台账；集群对象由部署阶段删除（计划②补 DELETE 流程），v1 要求应用不在部署中。 */
    public void delete(ApplicationDeleteRequest req, String operator) {
        PlatformApplication row = apps.selectById(req.getAppId());
        if (row == null || row.getDeletedAt() != null) {
            return;
        }
        if (operations.selectActiveByApp(req.getAppId()) != null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用正在部署，不能删除");
        }
        apps.softDelete(req.getAppId(), new Date());
    }

    public List<ApplicationRevisionView> revisions(String appId) {
        return revisions.selectByApp(appId).stream().map(r -> {
            ApplicationRevisionView v = new ApplicationRevisionView();
            v.setRevision(r.getRevision());
            v.setStatus(r.getStatus());
            v.setMessage(r.getMessage());
            v.setCreatedAt(r.getCreatedAt());
            return v;
        }).toList();
    }

    /** 画布左栏的可选项：命名空间来自租户分配表，节点类型与默认值写死在代码里。 */
    public ApplicationOptionsView options(String clusterId, String tenantId) {
        ApplicationOptionsView out = new ApplicationOptionsView();
        out.setNodeKinds(List.of(
                Map.of("kind", "SERVICE", "name", "服务", "description", "有端口的无状态/有状态服务"),
                Map.of("kind", "WORKER", "name", "后台进程", "description", "无端口、不建 Service")));
        out.setNamespaces(/* 复用中间件商店的 formOptions 数据源：本租户在该集群分配到的命名空间 */ List.of());
        return out;
    }

    private ApplicationView view(PlatformApplication row) {
        ApplicationView v = new ApplicationView();
        v.setId(row.getId());
        v.setClusterId(row.getClusterId());
        v.setNamespace(row.getNamespace());
        v.setName(row.getName());
        v.setSlug(row.getSlug());
        v.setDisplayName(row.getDisplayName());
        v.setDescription(row.getDescription());
        v.setStatus(row.getStatus());
        v.setCurrentRevision(row.getCurrentRevision());
        v.setSpec(JSON.readValue(row.getSpecJson(), AppSpecDTO.class));
        v.setCreatedAt(row.getCreatedAt());
        v.setUpdatedAt(row.getUpdatedAt());
        return v;
    }
```

> `options` 的命名空间列表直接复用 `MiddlewareService.formOptions(...)` 的取数逻辑（按分配表收窄），
> 不要另写一套查询 —— 两处口径不一致时，用户在画布里能选到中间件商店选不到的命名空间。

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl platform-api -am test -Dtest=ApplicationServiceTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/k8s/K8sAppClient.java \
        platform-api/src/main/java/com/coding/platformapi/services/ApplicationService.java \
        platform-api/src/main/java/com/coding/platformapi/models/Application*.java \
        platform-api/src/test/java/com/coding/platformapi/services/ApplicationServiceTest.java
git commit -m "feat(application): 应用台账的增删改查与集群侧客户端"
```

---

### Task 9: 预览（解析 + 渲染 + 差异）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/services/ApplicationPreviewService.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/ApplicationPreview.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/ApplicationPreviewServiceTest.java`

**Interfaces:**
- Consumes: `ApplicationService.get`、`K8sAppClient.render`、`PlatformApplicationResourceMapper.selectByRevision`
- Produces:
  - `ApplicationPreviewService.preview(ApplicationPreviewRequest req, String tenantId) → ApplicationPreview`（控制器用）
  - `ApplicationPreviewService.diff(List<AppObjectRefDTO> previous, AppRenderResultDTO rendered) → ApplicationPreview`（包内可见，部署阶段复用）
  - `ApplicationPreview { String manifest; List<AppObjectRefDTO> objects; List<AppProblemDTO> problems; List<String> added; List<String> removed; }`

- [ ] **Step 1: Write the failing test**

```java
package com.coding.platformapi.services;

import com.coding.common.models.app.*;
import com.coding.platformapi.k8s.K8sAppClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplicationPreviewServiceTest {

    private static AppObjectRefDTO ref(String kind, String name) {
        AppObjectRefDTO r = new AppObjectRefDTO();
        r.setApiVersion("v1");
        r.setKind(kind);
        r.setNamespace("demo");
        r.setName(name);
        return r;
    }

    @Test
    void reportsAddedAndRemovedObjects() {
        K8sAppClient client = mock(K8sAppClient.class);
        AppRenderResultDTO rendered = new AppRenderResultDTO();
        rendered.setManifest("kind: Deployment\n");
        rendered.setObjects(List.of(ref("Deployment", "demo-web"), ref("Service", "demo-web")));
        rendered.setProblems(List.of());
        when(client.render(any())).thenReturn(rendered);

        ApplicationPreviewService svc = new ApplicationPreviewService(null, null, client);
        ApplicationPreview p = svc.diff(List.of(ref("Deployment", "demo-web"), ref("Service", "demo-old")),
                rendered);

        assertThat(p.getAdded()).containsExactly("Service/demo-web");
        assertThat(p.getRemoved()).containsExactly("Service/demo-old");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl platform-api -am test -Dtest=ApplicationPreviewServiceTest`
Expected: 编译失败。

- [ ] **Step 3: Implement**

```java
@Service
public class ApplicationPreviewService {

    private final ApplicationService apps;
    private final PlatformApplicationResourceMapper resources;
    private final K8sAppClient client;

    public ApplicationPreviewService(ApplicationService apps,
                                     PlatformApplicationResourceMapper resources,
                                     K8sAppClient client) {
        this.apps = apps;
        this.resources = resources;
        this.client = client;
    }

    /**
     * 用「草稿 spec」预览（画布上还没保存的图也能看），previous 取最近一次部署的资源台账。
     */
    public ApplicationPreview preview(ApplicationPreviewRequest req, String tenantId) {
        ApplicationView app = apps.get(req.getAppId(), tenantId);
        AppRenderRequestDTO renderReq = new AppRenderRequestDTO();
        renderReq.setClusterId(app.getClusterId());
        renderReq.setNamespace(app.getNamespace());
        renderReq.setAppId(app.getId());
        renderReq.setAppSlug(app.getSlug());
        renderReq.setRevision(app.getCurrentRevision() == null ? 1 : app.getCurrentRevision() + 1);
        renderReq.setSpec(req.getSpec() != null ? req.getSpec() : app.getSpec());

        List<AppObjectRefDTO> previous = app.getCurrentRevision() == null || app.getCurrentRevision() == 0
                ? List.of()
                : resources.selectByRevision(app.getId(), app.getCurrentRevision()).stream()
                        .map(r -> {
                            AppObjectRefDTO ref = new AppObjectRefDTO();
                            ref.setApiVersion(r.getApiVersion());
                            ref.setKind(r.getKind());
                            ref.setNamespace(r.getNamespace());
                            ref.setName(r.getName());
                            return ref;
                        }).toList();

        AppRenderResultDTO rendered = client.render(renderReq);
        ApplicationPreview out = diff(previous, rendered);
        out.setManifest(rendered.getManifest());
        out.setObjects(rendered.getObjects());
        out.setProblems(rendered.getProblems());
        return out;
    }

    /** 只做对象身份（kind/name）的增删比较；字段级差异由部署阶段的 SSA 自己处理。 */
    ApplicationPreview diff(List<AppObjectRefDTO> previous, AppRenderResultDTO rendered) {
        java.util.Set<String> before = previous.stream().map(ApplicationPreviewService::id)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        java.util.Set<String> after = rendered.getObjects().stream().map(ApplicationPreviewService::id)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        ApplicationPreview p = new ApplicationPreview();
        p.setAdded(after.stream().filter(x -> !before.contains(x)).toList());
        p.setRemoved(before.stream().filter(x -> !after.contains(x)).toList());
        return p;
    }

    private static String id(AppObjectRefDTO r) {
        return r.getKind() + "/" + r.getName();
    }
}
```

- [ ] **Step 4: Run test and commit**

Run: `mvn -q -pl platform-api -am test -Dtest=ApplicationPreviewServiceTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0`

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/ApplicationPreviewService.java \
        platform-api/src/main/java/com/coding/platformapi/models/ApplicationPreview.java \
        platform-api/src/test/java/com/coding/platformapi/services/ApplicationPreviewServiceTest.java
git commit -m "feat(application): 预览与对象差集"
```

---

### Task 10: 部署编排与操作状态机（含重启续跑）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/services/ApplicationDeployService.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/services/ApplicationOperationWorker.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/ApplicationDeployServiceTest.java`

**Interfaces:**
- Consumes: Task 3 的 `PlatformApplication*Mapper`、Task 8 `K8sAppClient`、Task 9 `ApplicationPreviewService`
- Produces:
  - `ApplicationDeployService.start(String appId, Integer revision, String operator) → Long operationId`
  - `ApplicationDeployService.advance(PlatformApplicationOperation op) → void`（幂等，可被反复调用）
  - `ApplicationDeployService.operation(Long operationId) → PlatformApplicationOperation`（轮询用）
  - `ApplicationOperationWorker.@Scheduled(fixedDelayString = "${platform.application.worker-delay-ms:3000}") tick()`

- [ ] **Step 1: Write the failing test**

```java
package com.coding.platformapi.services;

import com.coding.data.mapper.system.*;
import com.coding.data.models.system.*;
import com.coding.platformapi.k8s.K8sAppClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApplicationDeployServiceTest {

    private PlatformApplicationMapper apps;
    private PlatformApplicationRevisionMapper revisions;
    private PlatformApplicationOperationMapper operations;
    private PlatformApplicationResourceMapper resources;
    private K8sAppClient client;
    private ApplicationDeployService service;

    @BeforeEach
    void setUp() {
        apps = mock(PlatformApplicationMapper.class);
        revisions = mock(PlatformApplicationRevisionMapper.class);
        operations = mock(PlatformApplicationOperationMapper.class);
        resources = mock(PlatformApplicationResourceMapper.class);
        client = mock(K8sAppClient.class);
        service = new ApplicationDeployService(apps, revisions, operations, resources, client, null);
    }

    private static PlatformApplication app() {
        PlatformApplication a = new PlatformApplication();
        a.setId("app_1");
        a.setClusterId("c1");
        a.setNamespace("demo");
        a.setSlug("demo");
        a.setStatus("DRAFT");
        a.setCurrentRevision(0);
        a.setSpecJson("{\"namespace\":\"demo\",\"nodes\":[{\"key\":\"web\",\"kind\":\"SERVICE\",\"params\":{\"image\":\"nginx\"}}],\"edges\":[]}");
        return a;
    }

    @Test
    void startCreatesRevisionAndOperationInPendingState() {
        when(apps.selectById("app_1")).thenReturn(app());
        when(revisions.selectMaxRevision("app_1")).thenReturn(0);
        when(operations.selectActiveByApp("app_1")).thenReturn(null);

        Long opId = service.start("app_1", null, "admin");

        ArgumentCaptor<PlatformApplicationOperation> cap =
                ArgumentCaptor.forClass(PlatformApplicationOperation.class);
        verify(operations).insert(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(cap.getValue().getCurrentStep()).isEqualTo("PRECHECK");
        assertThat(cap.getValue().getRevision()).isEqualTo(1);
        assertThat(opId).isNull();   // insert 由 MyBatis 回填 id；测试里 mapper 是 mock
    }

    @Test
    void refusesSecondConcurrentOperation() {
        when(apps.selectById("app_1")).thenReturn(app());
        PlatformApplicationOperation running = new PlatformApplicationOperation();
        running.setStatus("RUNNING");
        when(operations.selectActiveByApp("app_1")).thenReturn(running);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.start("app_1", null, "admin"))
                .hasMessageContaining("已有进行中的部署");
    }

    @Test
    void resumesFromPersistedStepAfterRestart() {
        PlatformApplicationOperation op = new PlatformApplicationOperation();
        op.setId(7L);
        op.setAppId("app_1");
        op.setRevision(1);
        op.setStatus("RUNNING");
        op.setCurrentStep("APPLY");
        when(apps.selectById("app_1")).thenReturn(app());
        when(revisions.selectByRevision("app_1", 1)).thenReturn(revisionWithManifest());

        service.advance(op);

        verify(client).apply(any());                       // 从 APPLY 继续，没有重跑 PRECHECK/RENDER
        verify(operations).updateById(argThat(o -> "PRUNE".equals(o.getCurrentStep())));
    }

    @Test
    void refusesToAdoptExistingForeignObject() {
        PlatformApplicationOperation op = new PlatformApplicationOperation();
        op.setId(8L);
        op.setAppId("app_1");
        op.setRevision(1);
        op.setStatus("RUNNING");
        op.setCurrentStep("CONFLICT");
        when(apps.selectById("app_1")).thenReturn(app());
        when(revisions.selectByRevision("app_1", 1)).thenReturn(revisionWithManifest());

        com.coding.common.models.app.AppObjectStatusDTO s =
                new com.coding.common.models.app.AppObjectStatusDTO();
        s.setKind("ConfigMap");
        s.setName("demo-web");
        s.setState("READY");
        com.coding.common.models.app.AppStatusResultDTO st =
                new com.coding.common.models.app.AppStatusResultDTO();
        st.setObjects(List.of(s));
        when(client.status(any())).thenReturn(st);
        when(resources.selectByApp("app_1")).thenReturn(List.of());

        service.advance(op);

        assertThat(op.getStatus()).isEqualTo("FAILED");
        assertThat(op.getMessage()).contains("不属于本应用");
        verify(client, never()).apply(any());
    }

    private static PlatformApplicationRevision revisionWithManifest() {
        PlatformApplicationRevision r = new PlatformApplicationRevision();
        r.setAppId("app_1");
        r.setRevision(1);
        r.setManifest("apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: demo-web\n");
        r.setStatus("RENDERED");
        return r;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl platform-api -am test -Dtest=ApplicationDeployServiceTest`
Expected: 编译失败 —— `ApplicationDeployService` 不存在。

- [ ] **Step 3: Implement the state machine**

```java
@Service
public class ApplicationDeployService {

    /** 阶段顺序；最后一个之后的 DONE 用 finish 处理。 */
    static final List<String> STEPS =
            List.of("PRECHECK", "RENDER", "CONFLICT", "APPLY", "PRUNE", "HEALTH");

    private static final tools.jackson.databind.ObjectMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();
    private static final tools.jackson.dataformat.yaml.YAMLMapper YAML =
            tools.jackson.dataformat.yaml.YAMLMapper.builder().build();

    /** 整个部署操作的超时（分钟）：超过仍未就绪就判失败，避免 worker 无限重试。 */
    @org.springframework.beans.factory.annotation.Value("${platform.application.timeout-minutes:30}")
    private long timeoutMinutes = 30;

    private final PlatformApplicationMapper apps;
    private final PlatformApplicationRevisionMapper revisions;
    private final PlatformApplicationOperationMapper operations;
    private final PlatformApplicationResourceMapper resources;
    private final K8sAppClient client;
    private final ApplicationPreviewService preview;

    public ApplicationDeployService(PlatformApplicationMapper apps,
                                    PlatformApplicationRevisionMapper revisions,
                                    PlatformApplicationOperationMapper operations,
                                    PlatformApplicationResourceMapper resources,
                                    K8sAppClient client,
                                    ApplicationPreviewService preview) {
        this.apps = apps;
        this.revisions = revisions;
        this.operations = operations;
        this.resources = resources;
        this.client = client;
        this.preview = preview;
    }

    public Long start(String appId, Integer revision, String operator) {
        PlatformApplication app = requireApp(appId);
        if (operations.selectActiveByApp(appId) != null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "该应用已有进行中的部署，请等它结束或先重试");
        }
        int next = revision != null ? revision : revisions.selectMaxRevision(appId) + 1;
        PlatformApplicationRevision rev = revision != null
                ? revisions.selectByRevision(appId, revision)
                : newRevision(app, next, operator);
        if (rev == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "找不到版本：" + revision);
        }
        PlatformApplicationOperation op = new PlatformApplicationOperation();
        op.setAppId(appId);
        op.setRevision(rev.getRevision());
        op.setAction(revision == null ? (app.getCurrentRevision() == 0 ? "DEPLOY" : "UPGRADE") : "RETRY");
        op.setStatus("PENDING");
        op.setCurrentStep("PRECHECK");
        op.setNodeStatesJson("{}");
        op.setStartedAt(new Date());
        op.setCreatedBy(operator);
        operations.insert(op);
        app.setStatus("DEPLOYING");
        app.setUpdatedAt(new Date());
        apps.updateById(app);
        return op.getId();
    }

    /** 幂等推进：每一步成功后写 current_step；异常 → FAILED，人工重试从失败步继续。 */
    public void advance(PlatformApplicationOperation op) {
        try {
            op.setStatus("RUNNING");
            operations.updateById(op);
            String step = op.getCurrentStep();
            while (true) {
                if ("PRECHECK".equals(step)) {
                    step = precheck(op);
                } else if ("RENDER".equals(step)) {
                    step = render(op);
                } else if ("CONFLICT".equals(step)) {
                    step = conflict(op);
                } else if ("APPLY".equals(step)) {
                    step = apply(op);
                } else if ("PRUNE".equals(step)) {
                    step = prune(op);
                } else if ("HEALTH".equals(step)) {
                    step = health(op);
                } else {
                    finish(op, "SUCCESS", null);
                    return;
                }
                op.setCurrentStep(step);
                operations.updateById(op);
            }
        } catch (DeploymentPending pending) {
            // 还在等资源就绪：保持 RUNNING，让 worker 下一拍再进一次 HEALTH；不计失败
        } catch (RuntimeException e) {
            finish(op, "FAILED", e.getMessage());
        }
    }

    private String precheck(PlatformApplicationOperation op) {
        PlatformApplication app = requireApp(op.getAppId());
        // 图在入队前已校验；这里只复核版本仍在、应用未被删
        if (app.getDeletedAt() != null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用已被删除");
        }
        return "RENDER";
    }

    private String render(PlatformApplicationOperation op) {
        PlatformApplicationRevision rev = revisions.selectByRevision(op.getAppId(), op.getRevision());
        if (rev.getManifest() != null && !rev.getManifest().isBlank()) {
            return "CONFLICT";   // 续跑：清单已渲染过，直接进冲突检查
        }
        PlatformApplication app = requireApp(op.getAppId());
        AppRenderRequestDTO req = new AppRenderRequestDTO();
        req.setClusterId(app.getClusterId());
        req.setNamespace(app.getNamespace());
        req.setAppId(app.getId());
        req.setAppSlug(app.getSlug());
        req.setRevision(rev.getRevision());
        req.setSpec(readSpec(rev.getSpecJson()));
        AppRenderResultDTO rendered = client.render(req);
        if (!rendered.getProblems().isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    rendered.getProblems().get(0).getMessage());
        }
        rev.setManifest(rendered.getManifest());
        rev.setStatus("RENDERED");
        revisions.updateById(rev);
        return "CONFLICT";
    }

    /**
     * 冲突检查：要写的对象在集群里已存在，但不在本应用的历史台账里 → 拒绝。
     *
     * <p>为什么必须拦：SSA 会把「别人的手工对象」直接接管（fieldManager 一改就成了我们的），
     * 用户不会收到任何提示，而下次 prune 还可能把它删掉。
     */
    private String conflict(PlatformApplicationOperation op) {
        PlatformApplication app = requireApp(op.getAppId());
        PlatformApplicationRevision rev = revisions.selectByRevision(op.getAppId(), op.getRevision());
        AppStatusRequestDTO req = new AppStatusRequestDTO();
        req.setClusterId(app.getClusterId());
        req.setObjects(objectsOf(rev.getManifest()));
        AppStatusResultDTO st = client.status(req);
        java.util.Set<String> ours = resources.selectByApp(op.getAppId()).stream()
                .map(r -> r.getKind() + "/" + r.getName())
                .collect(java.util.stream.Collectors.toSet());
        for (AppObjectStatusDTO s : st.getObjects()) {
            String id = s.getKind() + "/" + s.getName();
            if (!"MISSING".equals(s.getState()) && !ours.contains(id)) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "对象已存在且不属于本应用：" + id + "（请改名或先手工处理）");
            }
        }
        return "APPLY";
    }

    private String apply(PlatformApplicationOperation op) {
        PlatformApplication app = requireApp(op.getAppId());
        PlatformApplicationRevision rev = revisions.selectByRevision(op.getAppId(), op.getRevision());
        AppApplyRequestDTO req = new AppApplyRequestDTO();
        req.setClusterId(app.getClusterId());
        req.setManifest(rev.getManifest());
        client.apply(req);
        // 落资源台账（先删本 revision 旧记录再写，保证重试幂等）
        resources.deleteByAppRevision(op.getAppId(), op.getRevision());
        resources.batchInsert(objectsOf(rev.getManifest()).stream()
                .map(o -> toResource(op, o)).toList());
        return "PRUNE";
    }

    private String prune(PlatformApplicationOperation op) {
        PlatformApplication app = requireApp(op.getAppId());
        int previous = op.getRevision() - 1;
        if (previous < 1) {
            return "HEALTH";
        }
        java.util.Set<String> current = objectsOf(revisions.selectByRevision(op.getAppId(), op.getRevision())
                        .getManifest()).stream()
                .map(o -> o.getKind() + "/" + o.getName()).collect(java.util.stream.Collectors.toSet());
        List<AppObjectRefDTO> stale = resources.selectByRevision(op.getAppId(), previous).stream()
                .map(ApplicationDeployService::toRef)
                .filter(r -> !current.contains(r.getKind() + "/" + r.getName()))
                .toList();
        if (!stale.isEmpty()) {
            AppPruneRequestDTO req = new AppPruneRequestDTO();
            req.setClusterId(app.getClusterId());
            req.setObjects(stale);
            req.setDeletePvc(false);
            client.prune(req);
        }
        return "HEALTH";
    }

    private String health(PlatformApplicationOperation op) {
        PlatformApplication app = requireApp(op.getAppId());
        List<AppObjectRefDTO> refs = objectsOf(revisions.selectByRevision(op.getAppId(), op.getRevision())
                .getManifest());
        AppStatusRequestDTO req = new AppStatusRequestDTO();
        req.setClusterId(app.getClusterId());
        req.setObjects(refs);
        AppStatusResultDTO status = client.status(req);

        // 对象名 = <slug>-<nodeKey>；StatefulSet 的 headless Service 是 <name>-headless，归到同一节点
        String prefix = app.getSlug() + "-";
        java.util.Map<String, String> nodeStates = new java.util.LinkedHashMap<>();
        boolean allReady = true;
        for (AppObjectStatusDTO s : status.getObjects()) {
            String key = s.getName().startsWith(prefix) ? s.getName().substring(prefix.length()) : s.getName();
            if (key.endsWith("-headless")) {
                key = key.substring(0, key.length() - "-headless".length());
            }
            if (key.endsWith("-env") || key.endsWith("-secret")) {
                key = key.substring(0, key.lastIndexOf('-'));
            }
            boolean ready = "READY".equals(s.getState());
            allReady &= ready;
            nodeStates.merge(key, ready ? "READY" : s.getState(), (a, b) -> "READY".equals(a) && "READY".equals(b)
                    ? "READY" : ("MISSING".equals(b) || "MISSING".equals(a) ? "MISSING" : "PENDING"));
        }
        // 先把节点状态落库：画布在等待期间要能看见每个节点的灯
        op.setNodeStatesJson(writeJson(nodeStates));
        operations.updateById(op);

        if (!allReady) {
            long minutes = java.time.Duration.between(op.getStartedAt().toInstant(),
                    java.time.Instant.now()).toMinutes();
            if (minutes > timeoutMinutes) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "部署超时（" + timeoutMinutes + " 分钟）：仍有节点未就绪");
            }
            // 用内部信号停在 HEALTH（不算失败）：worker 下一拍会再进来
            throw new DeploymentPending();
        }
        return "DONE";
    }

    /** 轮询用：操作详情（含节点状态）。 */
    public PlatformApplicationOperation operation(Long operationId) {
        PlatformApplicationOperation op = operations.selectById(operationId);
        if (op == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "操作记录不存在");
        }
        return op;
    }

    private PlatformApplication requireApp(String appId) {
        PlatformApplication app = apps.selectById(appId);
        if (app == null || app.getDeletedAt() != null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用不存在或已删除");
        }
        return app;
    }

    private PlatformApplicationRevision newRevision(PlatformApplication app, int revision, String operator) {
        PlatformApplicationRevision rev = new PlatformApplicationRevision();
        rev.setAppId(app.getId());
        rev.setRevision(revision);
        rev.setSpecJson(app.getSpecJson());
        rev.setStatus("RENDERED");
        rev.setCreatedBy(operator);
        rev.setCreatedAt(new Date());
        revisions.insert(rev);
        return rev;
    }

    private void finish(PlatformApplicationOperation op, String status, String message) {
        op.setStatus(status);
        op.setMessage(message);
        op.setFinishedAt(new Date());
        operations.updateById(op);
        PlatformApplication app = apps.selectById(op.getAppId());
        if (app != null) {
            app.setStatus("FAILED".equals(status) ? "FAILED" : "READY");
            if ("SUCCESS".equals(status)) {
                app.setCurrentRevision(op.getRevision());
            }
            app.setUpdatedAt(new Date());
            apps.updateById(app);
        }
    }

    private static AppSpecDTO readSpec(String json) {
        return JSON.readValue(json, AppSpecDTO.class);
    }

    private static String writeJson(Object o) {
        return JSON.writeValueAsString(o);
    }

    /** 从 manifest 里取出对象身份（与 k8s-server 的 ref 解析同规则：kind/name/namespace）。 */
    private static List<AppObjectRefDTO> objectsOf(String manifest) {
        List<AppObjectRefDTO> out = new java.util.ArrayList<>();
        if (manifest == null) {
            return out;
        }
        for (String doc : manifest.split("(?m)^---[ \\t]*$")) {
            if (doc.isBlank()) {
                continue;
            }
            tools.jackson.databind.JsonNode root = YAML.readTree(doc);
            if (root == null || !root.has("kind")) {
                continue;
            }
            AppObjectRefDTO ref = new AppObjectRefDTO();
            ref.setApiVersion(root.path("apiVersion").asString(""));
            ref.setKind(root.path("kind").asString(""));
            ref.setName(root.path("metadata").path("name").asString(""));
            ref.setNamespace(root.path("metadata").path("namespace").asString(""));
            out.add(ref);
        }
        return out;
    }

    private PlatformApplicationResource toResource(PlatformApplicationOperation op, AppObjectRefDTO ref) {
        PlatformApplicationResource r = new PlatformApplicationResource();
        r.setAppId(op.getAppId());
        r.setRevision(op.getRevision());
        r.setApiVersion(ref.getApiVersion());
        r.setKind(ref.getKind());
        r.setNamespace(ref.getNamespace());
        r.setName(ref.getName());
        r.setCreatedAt(new Date());
        return r;
    }

    private static AppObjectRefDTO toRef(PlatformApplicationResource r) {
        AppObjectRefDTO ref = new AppObjectRefDTO();
        ref.setApiVersion(r.getApiVersion());
        ref.setKind(r.getKind());
        ref.setNamespace(r.getNamespace());
        ref.setName(r.getName());
        return ref;
    }

    /** 控制流信号：本轮还没就绪，保持 RUNNING。 */
    private static final class DeploymentPending extends RuntimeException {
        DeploymentPending() {
            super(null, null, false, false);   // 不留栈：它是流程信号，不是错误
        }
    }
}
```

> `DeploymentPending` 是内部信号：`advance` 捕获它时**不写 FAILED**，只保留 RUNNING 让 worker 下一拍再来
> （超时由 `started_at` 与 `platform.application.timeout-minutes:30` 判定，超时后写 FAILED）。

`ApplicationOperationWorker`：

```java
@Component
public class ApplicationOperationWorker {

    private final ApplicationDeployService deploy;
    private final PlatformApplicationOperationMapper operations;

    public ApplicationOperationWorker(ApplicationDeployService deploy,
                                      PlatformApplicationOperationMapper operations) {
        this.deploy = deploy;
        this.operations = operations;
    }

    /** 单副本部署：一次只推进一条最早的活动操作（并发推进会在 PRUNE 的差集上打架）。 */
    @Scheduled(fixedDelayString = "${platform.application.worker-delay-ms:3000}")
    public void tick() {
        PlatformApplicationOperation op = operations.selectFirstActive();
        if (op != null) {
            deploy.advance(op);
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn -q -pl platform-api -am test -Dtest=ApplicationDeployServiceTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/ApplicationDeployService.java \
        platform-api/src/main/java/com/coding/platformapi/services/ApplicationOperationWorker.java \
        platform-api/src/test/java/com/coding/platformapi/services/ApplicationDeployServiceTest.java
git commit -m "feat(application): 部署阶段机与重启续跑"
```

---

### Task 11: 控制器、审计与权限行对齐

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/controllers/ApplicationController.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/ApplicationDeployRequest.java`（Task 9 已建 `ApplicationPreviewRequest`，Task 8 已建其余模型）
- Modify: `platform-api/src/main/java/com/coding/platformapi/audit/AuditAction.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/security/ApplicationPermissionMigrationTest.java`

**Interfaces:**
- Consumes: Task 3 的权限行、Task 8/9/10 的服务
- Produces: `/application/**` 十个端点（与迁移里的 resource 完全一致）

- [ ] **Step 1: Write the failing cross-check test**

```java
package com.coding.platformapi.security;

import com.coding.platformapi.controllers.ApplicationController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 控制器端点 ↔ 迁移里的权限行，一一对应（漏一行 platform-api 启动就会 fail-closed）。
 * 与 {@link ObjectStoragePermissionMigrationTest} 同一套路。
 */
class ApplicationPermissionMigrationTest {

    @Test
    void everyEndpointHasPermissionRow() throws Exception {
        Set<String> endpoints = new HashSet<>();
        Class<?> c = ApplicationController.class;
        String base = c.getAnnotation(RequestMapping.class).value()[0];
        for (Method m : c.getDeclaredMethods()) {
            PostMapping pm = m.getAnnotation(PostMapping.class);
            if (pm != null) {
                endpoints.add(base + pm.value()[0]);
            }
        }
        String sql = Files.readString(Path.of(
                "../platform-data/src/main/resources/db/migration/V2026_10_25_7__application.sql"));
        assertThat(endpoints).isNotEmpty();
        for (String e : endpoints) {
            assertThat(sql).as("缺少权限行：" + e).contains("'" + e + "'");
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl platform-api -am test -Dtest=ApplicationPermissionMigrationTest`
Expected: 编译失败 —— `ApplicationController` 不存在。

- [ ] **Step 3: Implement the controller**

```java
@Tag(name = "应用编排", description = "应用图 / 预览 / 部署 / 版本")
@RestController
@RequestMapping("/application")
public class ApplicationController {

    private final ApplicationService service;
    private final ApplicationPreviewService preview;
    private final ApplicationDeployService deploy;

    @Autowired
    private AuthContext auth;

    public ApplicationController(ApplicationService service, ApplicationPreviewService preview,
                                 ApplicationDeployService deploy) {
        this.service = service;
        this.preview = preview;
        this.deploy = deploy;
    }

    @PostMapping("/list")
    public ResponseData<List<ApplicationView>> list(@RequestBody ApplicationQueryRequest req) {
        return new ResponseData<>(service.list(req.getClusterId(),
                auth.effectiveTenantId(req.getTenantId())));
    }

    @PostMapping("/get")
    public ResponseData<ApplicationView> get(@RequestBody ApplicationQueryRequest req) {
        return new ResponseData<>(service.get(req.getAppId(), auth.effectiveTenantId(req.getTenantId())));
    }

    @PostMapping("/create")
    @Auditable(action = AuditAction.APPLICATION_CREATE, target = "#request.name")
    public ResponseData<ApplicationView> create(@RequestBody ApplicationCreateRequest req) {
        return new ResponseData<>(service.create(req, auth.currentUsername(),
                auth.effectiveTenantId(req.getTenantId())));
    }

    @PostMapping("/update")
    @Auditable(action = AuditAction.APPLICATION_UPDATE, target = "#request.appId")
    public ResponseData<ApplicationView> update(@RequestBody ApplicationUpdateRequest req) {
        return new ResponseData<>(service.update(req, auth.currentUsername()));
    }

    @PostMapping("/preview")
    public ResponseData<ApplicationPreview> preview(@RequestBody ApplicationPreviewRequest req) {
        return new ResponseData<>(preview.preview(req, auth.effectiveTenantId(req.getTenantId())));
    }

    @PostMapping("/deploy")
    @Auditable(action = AuditAction.APPLICATION_DEPLOY, target = "#request.appId")
    public ResponseData<Long> deploy(@RequestBody ApplicationDeployRequest req) {
        return new ResponseData<>(deploy.start(req.getAppId(), req.getRevision(), auth.currentUsername()));
    }

    @PostMapping("/operation/get")
    public ResponseData<PlatformApplicationOperation> operation(@RequestBody ApplicationQueryRequest req) {
        return new ResponseData<>(deploy.operation(req.getOperationId()));
    }

    @PostMapping("/revision/list")
    public ResponseData<List<ApplicationRevisionView>> revisions(@RequestBody ApplicationQueryRequest req) {
        return new ResponseData<>(service.revisions(req.getAppId()));
    }

    @PostMapping("/options")
    public ResponseData<ApplicationOptionsView> options(@RequestBody ApplicationQueryRequest req) {
        return new ResponseData<>(service.options(req.getClusterId(),
                auth.effectiveTenantId(req.getTenantId())));
    }

    @PostMapping("/delete")
    @Auditable(action = AuditAction.APPLICATION_DELETE, target = "#request.appId")
    public ResponseData<Void> delete(@RequestBody ApplicationDeleteRequest req) {
        service.delete(req, auth.currentUsername());
        return new ResponseData<>();
    }
}
```

`AuditAction` 追加四个枚举值：`APPLICATION_CREATE` / `APPLICATION_UPDATE` / `APPLICATION_DEPLOY` /
`APPLICATION_DELETE`（与既有枚举同风格，`description` 用中文）。

- [ ] **Step 4: Run tests**

Run: `mvn -q -pl platform-api -am test -Dtest=ApplicationPermissionMigrationTest`
Expected: `Tests run: 1, Failures: 0`

- [ ] **Step 5: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/controllers/ApplicationController.java \
        platform-api/src/main/java/com/coding/platformapi/audit/AuditAction.java \
        platform-api/src/test/java/com/coding/platformapi/security/ApplicationPermissionMigrationTest.java
git commit -m "feat(application): 控制器端点、审计动作与权限行交叉校验"
```

---

### Task 12: 前端接线（页面码 / 路由 / 类型 / API）+ 应用列表页

**Files:**
- Modify: `platform-web/src/pageCodes.ts`、`platform-web/src/router/index.ts`、`platform-web/src/types.ts`、`platform-web/src/api/index.ts`
- Create: `platform-web/src/views/ApplicationListView.vue`

**Interfaces:**
- Consumes: `/application/**` 端点
- Produces:
  - `pageCodes.application = 'tenant:page:application'`
  - `applicationApi`：`list/get/create/update/delete/preview/deploy/operation/revisions/options`
  - 类型：`AppSpec` / `AppNode` / `AppEdge` / `AppProblem` / `ApplicationView` / `ApplicationPreview` / `ApplicationOperation`

- [ ] **Step 1: Add page code, route, types, api**

```ts
// pageCodes.ts（在 app 那一行后面）
/** 应用编排（画布）：与中间件商店（tenant:page:app）是两件事 */
application: 'tenant:page:application',
```

```ts
// router/index.ts（应用交付分组）
{ path: 'applications', name: 'applications', component: () => import('@/views/ApplicationListView.vue'),
  meta: { title: '应用编排', group: '应用交付', requiresPerm: [pageCodes.application] } },
{ path: 'applications/:id', name: 'application-canvas', component: () => import('@/views/ApplicationCanvasView.vue'),
  meta: { title: '应用画布', group: '应用交付', requiresPerm: [pageCodes.application] } },
```

```ts
// types.ts
export interface AppNode { key: string; kind: 'SERVICE' | 'WORKER'; params: Record<string, unknown> }
export interface AppEdge { from: string; to: string }
export interface AppSpec { namespace: string; settings?: Record<string, unknown>; nodes: AppNode[]; edges: AppEdge[] }
export interface AppProblem { severity: 'ERROR' | 'WARN'; nodeKey?: string; message: string }
export interface ApplicationView {
  id: string; clusterId: string; namespace: string; name: string; slug: string
  displayName?: string; description?: string; status: string; currentRevision: number
  spec: AppSpec; createdAt?: string; updatedAt?: string
}
export interface AppObjectRef { apiVersion: string; kind: string; namespace: string; name: string }
export interface ApplicationPreview {
  manifest: string; objects: AppObjectRef[]; problems: AppProblem[]; added: string[]; removed: string[]
}
export interface ApplicationOperation {
  id: number; appId: string; revision: number; action: string
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED'
  currentStep: string; nodeStatesJson?: string; message?: string
}
```

```ts
// api/index.ts
export const applicationApi = {
  list: (body: { clusterId?: string; tenantId?: string }) =>
    http.post<ApplicationView[]>('/application/list', body),
  get: (appId: string) => http.post<ApplicationView>('/application/get', { appId }),
  create: (body: Partial<ApplicationView> & { name: string; clusterId: string; spec: AppSpec }) =>
    http.post<ApplicationView>('/application/create', body),
  update: (body: { appId: string; name?: string; displayName?: string; description?: string; spec?: AppSpec }) =>
    http.post<ApplicationView>('/application/update', body),
  remove: (appId: string) => http.post<void>('/application/delete', { appId }),
  preview: (body: { appId: string; spec?: AppSpec }) =>
    http.post<ApplicationPreview>('/application/preview', body),
  deploy: (body: { appId: string; revision?: number }) =>
    http.post<number>('/application/deploy', body),
  operation: (operationId: number) =>
    http.post<ApplicationOperation>('/application/operation/get', { operationId }),
  revisions: (appId: string) => http.post<unknown[]>('/application/revision/list', { appId }),
  options: (clusterId: string) => http.post<unknown>('/application/options', { clusterId }),
}
```

> `http.post` 的返回形态沿用现有 `api/index.ts` 的封装（`ResponseData.data` 已解包）；照抄相邻模块的写法。

- [ ] **Step 2: Implement the list page**

`ApplicationListView.vue` 结构（与既有列表页同构）：

- 顶栏：集群下拉（复用 `K8sClusterOption` 数据源）+「新建应用」按钮；
- 表格列：名称（可点进画布）、命名空间、状态 tag（DRAFT 灰 / DEPLOYING 蓝 / READY 绿 / FAILED 红）、
  当前版本、更新时间、操作（打开画布 / 删除）；
- 新建弹窗：名称、命名空间（`/middleware/form-options` 的命名空间候选或现有分配表接口）、描述；
  创建后跳 `/applications/:id`。

关键提交代码：

```ts
async function create() {
  if (!form.name.trim()) return ElMessage.warning('请填写应用名')
  if (!form.namespace) return ElMessage.warning('请选择命名空间')
  const created = await applicationApi.create({
    clusterId: clusterId.value, namespace: form.namespace, name: form.name.trim(),
    displayName: form.displayName || undefined, description: form.description || undefined,
    spec: { namespace: form.namespace, nodes: [], edges: [] },
  })
  router.push(`/applications/${created.id}`)
}
```

- [ ] **Step 3: Build**

Run: `cd platform-web && npm run build`
Expected: 构建通过（`vue-tsc` 无类型错误）。

- [ ] **Step 4: Commit**

```bash
git add platform-web/src/pageCodes.ts platform-web/src/router/index.ts \
        platform-web/src/types.ts platform-web/src/api/index.ts \
        platform-web/src/views/ApplicationListView.vue
git commit -m "feat(web): 应用编排页面码、路由、类型与列表页"
```

---

### Task 13: 画布（Vue Flow + 节点面板 + 检查器）

**Files:**
- Create: `platform-web/src/views/ApplicationCanvasView.vue`
- Create: `platform-web/src/components/app-canvas/AppNodeCard.vue`
- Create: `platform-web/src/components/app-canvas/AppNodeInspector.vue`

**Interfaces:**
- Consumes: `applicationApi.get/update`、`AppSpec` 类型
- Produces: 画布编辑并保存 spec（`applicationApi.update`），节点 key 唯一、连线只允许 `from ≠ to`

- [ ] **Step 1: Canvas skeleton**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { VueFlow, useVueFlow, type Connection, type Node, type Edge } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'
import { ElMessage } from 'element-plus'
import { applicationApi } from '@/api'
import AppNodeCard from '@/components/app-canvas/AppNodeCard.vue'
import AppNodeInspector from '@/components/app-canvas/AppNodeInspector.vue'
import type { AppNode, AppSpec, ApplicationView } from '@/types'

const route = useRoute()
const { onConnect, addEdges, removeEdges, getEdges } = useVueFlow()
const app = ref<ApplicationView>()
const nodes = ref<Node[]>([])
const edges = ref<Edge[]>([])
const selectedKey = ref<string | null>(null)

/** K8s 对象名 = <slug>-<key>；key 必须是小写字母开头（与后端 AppSpecValidator 同规则）。 */
function addNode(kind: AppNode['kind']) {
  const used = new Set(nodes.value.map(n => n.id))
  let i = 1
  while (used.has(`${kind.toLowerCase()}-${i}`)) i++
  const key = `${kind.toLowerCase()}-${i}`
  nodes.value.push({
    id: key, type: 'app', position: { x: 80 + nodes.value.length * 40, y: 80 },
    data: { key, kind, params: defaultParams(kind) },
  })
  selectedKey.value = key
}

function defaultParams(kind: AppNode['kind']): Record<string, unknown> {
  return kind === 'SERVICE'
    ? { image: '', port: 8080, replicas: 1, expose: 'none', env: [] }
    : { image: '', replicas: 1, env: [] }
}

onConnect((c: Connection) => {
  if (c.source === c.target) return ElMessage.warning('连线不能指向自己')
  addEdges([{ ...c, id: `${c.source}->${c.target}` } as Edge])
})

function toSpec(): AppSpec {
  return {
    namespace: app.value!.namespace,
    settings: {},
    nodes: nodes.value.map(n => ({
      key: n.id, kind: n.data.kind, params: n.data.params,
    })) as AppNode[],
    edges: getEdges.value.map(e => ({ from: e.source, to: e.target })),
  }
}

async function save() {
  const saved = await applicationApi.update({ appId: app.value!.id, spec: toSpec() })
  app.value = saved
  ElMessage.success('已保存')
}

onMounted(async () => {
  app.value = await applicationApi.get(String(route.params.id))
  nodes.value = app.value.spec.nodes.map((n, i) => ({
    id: n.key, type: 'app', position: { x: 80 + (i % 4) * 220, y: 80 + Math.floor(i / 4) * 160 },
    data: { ...n },
  }))
  edges.value = app.value.spec.edges.map(e => ({ id: `${e.from}->${e.to}`, source: e.from, target: e.to }))
})
</script>
```

模板（左侧节点面板 / 中间画布 / 右侧检查器）用 Element Plus 容器布局；节点用 `AppNodeCard` 作为
`#node-app` 插槽；检查器绑定 `selectedKey` 对应节点的 `params`。**保存前不做后端校验**——校验由
`/application/preview` 返回 `problems` 展示（下一步任务）。

> 画布上的节点位置 v1 不持久化（spec 里没有坐标字段），按顺序自动排布。这样避免「换分辨率后图乱」
> 这类纯视觉问题进入数据模型。

- [ ] **Step 2: `AppNodeCard.vue`**

```vue
<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  data: { key: string; kind: string; params: Record<string, unknown>; state?: string }
  selected?: boolean
}>()

const state = computed(() => props.data.state ?? 'PENDING')
</script>

<template>
  <div class="app-node" :class="{ selected, failed: state === 'FAILED' }">
    <div class="app-node__title">
      <span class="dot" :class="`dot--${state.toLowerCase()}`" />
      {{ data.key }}
    </div>
    <div class="app-node__kind">{{ data.kind === 'SERVICE' ? '服务' : '后台进程' }}</div>
    <div class="app-node__image">{{ data.params.image || '未填写镜像' }}</div>
  </div>
</template>
```

`state` 由画布页在部署期间按 `operation.nodeStatesJson` 传入（默认 `PENDING`），节点状态灯：
`PENDING` 灰 / `RUNNING` 蓝（呼吸）/ `READY` 绿 / `FAILED` 红。

- [ ] **Step 3: `AppNodeInspector.vue`**

按 kind 渲染表单：

- SERVICE：镜像（必填）、容器端口（数字 1–65535）、副本数、暴露方式（不暴露 / NodePort）、
  持久化开关 + 大小（勾了显示）、自动伸缩开关 + 范围、监控开关、环境变量表格（键 / 值 / 是否敏感）；
- WORKER：镜像、副本数、环境变量。

敏感项在表格里用开关标记，提交时 `params.env[i] = { name, value, secret: true }`（后端 Task 5 会分流）。

- [ ] **Step 4: Build and verify in the browser**

Run: `cd platform-web && npm run build`
Expected: 构建通过。

浏览器验证（用 `efficient-browser-verification` 的假 token + XHR stub 思路）：
从左侧面板添加两个「服务」节点 + 一个「后台进程」→ 连一条线 → 保存 → 刷新后图还在；
自连被拒（提示「连线不能指向自己」）。

- [ ] **Step 5: Commit**

```bash
git add platform-web/src/views/ApplicationCanvasView.vue platform-web/src/components/app-canvas
git commit -m "feat(web): 应用画布、节点卡片与检查器"
```

---

### Task 14: 预览 / 部署抽屉 / 状态轮询

**Files:**
- Create: `platform-web/src/components/app-canvas/AppDeployDrawer.vue`
- Modify: `platform-web/src/views/ApplicationCanvasView.vue`

**Interfaces:**
- Consumes: `applicationApi.preview/deploy/operation`
- Produces: 预览弹窗（资源清单 / 新增 / 删除 / 问题）与部署抽屉（阶段时间线 + 轮询）

- [ ] **Step 1: Preview dialog**

「预览」按钮 → `applicationApi.preview({ appId, spec })` →
- 有 `problems` 里 `severity === 'ERROR'`：红色列表，**禁用**「部署」按钮；
- 否则显示：新增对象（绿）/ 删除对象（红）/ 资源清单（`YamlBlock` 组件，已存在，别手写 `<pre>`）。

- [ ] **Step 2: Deploy drawer with polling**

```ts
const op = ref<ApplicationOperation | null>(null)
let timer: number | undefined

async function deploy() {
  const id = await applicationApi.deploy({ appId: app.value!.id })
  await poll(id)
}

async function poll(operationId: number) {
  timer && window.clearInterval(timer)
  timer = window.setInterval(async () => {
    const o = await applicationApi.operation(operationId)
    op.value = o
    applyNodeStates(o)
    if (o.status === 'SUCCESS' || o.status === 'FAILED') {
      window.clearInterval(timer)
      ElMessage[o.status === 'SUCCESS' ? 'success' : 'error'](
        o.status === 'SUCCESS' ? '部署完成' : `部署失败：${o.message ?? ''}`)
      app.value = await applicationApi.get(app.value!.id)
    }
  }, 3000)
}

function applyNodeStates(o: ApplicationOperation) {
  const states = o.nodeStatesJson ? JSON.parse(o.nodeStatesJson) as Record<string, string> : {}
  nodes.value = nodes.value.map(n => ({ ...n, data: { ...n.data, state: states[n.id] ?? 'PENDING' } }))
}

onUnmounted(() => timer && window.clearInterval(timer))
```

抽屉内容：阶段时间线（预检 → 渲染 → 写入 → 清理 → 健康）+ `op.currentStep` 高亮 + 失败时的
「重试」按钮（`applicationApi.deploy({ appId, revision: op.revision })`）。

- [ ] **Step 3: Build and browser verification**

Run: `cd platform-web && npm run build`

验证清单（stub `/application/*`）：
1. 预览里出现资源清单与新增/删除列表；
2. 有 ERROR 时「部署」按钮禁用；
3. 点部署后 3 秒一次轮询，节点灯从 PENDING 变 READY，成功后提示「部署完成」；
4. 失败时抽屉显示错误并出现「重试」。

- [ ] **Step 4: Commit**

```bash
git add platform-web/src/components/app-canvas/AppDeployDrawer.vue \
        platform-web/src/views/ApplicationCanvasView.vue
git commit -m "feat(web): 应用预览、部署抽屉与节点状态轮询"
```

---

### Task 15: 文档、全量验证与收口

**Files:**
- Create: `docs/development/application-canvas.md`
- Modify: `k8s-server/src/main/resources/charts/app/workload/README.md`（若 Task 4 未写全）

- [ ] **Step 1: Write the development doc**

内容必须包含：概念与真相源、节点目录与「用户填 / 平台生成」对照表、部署阶段机与状态值、
prune 规则与 PVC 保护、权限码与页面码、部署步骤（重放 `V2026_10_25_7` → 重建
`platform-common` / `platform-data` / `k8s-server` / `platform-api` / `platform-web`）、
以及**计划②要补的**（中间件节点、注入、初始化、引用守卫）。

- [ ] **Step 2: Run the full build**

```bash
mvn -q test
cd platform-web && npm run build
```

Expected: 后端全绿（在 2026-10-25 基线 1126 + 328 + 6 之上新增用例全过）；前端构建通过。

- [ ] **Step 3: Manual smoke on a real cluster（部署后）**

1. 重放迁移 → 重启 platform-api（确认启动成功 = 权限行齐全）；
2. 新建应用「demo」→ 画 `web`（SERVICE，镜像 nginx:1.27）+ `sidecar`（WORKER）→ 保存 → 预览；
3. 部署 → 画布节点变绿 → `kubectl -n <ns> get deploy,svc,cm` 能看到 `demo-web` / `demo-sidecar`；
4. 改镜像版本 → 预览显示无新增删除 → 部署 → Pod 滚动；
5. 删掉 `sidecar` 节点 → 预览显示「删除 Deployment/demo-sidecar」→ 部署 → 对象消失、
   `web` 的服务不中断；
6. 在「工作负载」页确认 `demo-web` 只读：渲染出的对象带 `platform.coding/managed=true`，
   既有 `ManagedResourceGuard` 会判成 COMPONENT 并拒绝写入（前端置灰）。这一步只验证「确实拒了」，
   不需要新代码；若要显示「由应用 X 管理」的具体名称，留给计划②（guard 目前只区分 COMPONENT / MIDDLEWARE）。

- [ ] **Step 4: Commit**

```bash
git add docs/development/application-canvas.md k8s-server/src/main/resources/charts/app/workload/README.md
git commit -m "docs(application): 应用编排开发文档与 chart 约定"
```

---

## 计划①完成标准

- 能画「服务 + 后台进程」并保存；刷新后图还在；
- 预览能给出资源清单、新增/删除对象、图问题；有 ERROR 时不能部署；
- 部署走完预检 → 渲染 → 写入 → 清理 → 健康，节点状态回显到画布；
- 重启 platform-api 后，进行中的操作能接着跑（不是永远 RUNNING）；
- 升级（改图/改镜像）走新 revision，prune 只删本应用上一版本多出来的对象，PVC 不动；
- 全量测试与前端构建绿。
