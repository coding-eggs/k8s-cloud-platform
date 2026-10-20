# 流水线节点重试与节点屏蔽 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让流水线支持「触发时屏蔽部分节点」与「运行结束后从断点继续重跑」，并把共享工作区改造成平台可持有、可 TTL 回收的 PVC。

**Architecture:** 屏蔽与重试都在 platform-api 把画布**裁剪成子画布**后交给 k8s-server 渲染（`k8s-core` 渲染器零改动）。重试范围由服务端按「断点」规则计算，客户端不提交集合。共享工作区从 Tekton 的 `volumeClaimTemplate` 改为平台自建 PVC：成功即删、失败/超时/取消保留 24h，并由 k8s-server 的回收器按 TTL 清理。

**Tech Stack:** Java 21 / Spring Boot / fabric8 7.9.0 + tekton-client / Jackson 3（`tools.jackson`）/ MyBatis（XML mapper）/ Vue 3 + Vue Flow + Element Plus。

**Spec:** `docs/superpowers/specs/2026-10-20-pipeline-retry-and-mask-design.md`

## Global Constraints

- **本仓没有 Flyway**：`platform-data/src/main/resources/db/migration/*.sql` 一律**手工重放**，新增迁移文件不代表它会自动执行。
- **不加外键**：级联删除靠代码（删运行行时先删日志行）。
- **platform-api 不依赖 k8s-core / fabric8**：画布 → PipelineSpec 的渲染只在 k8s-server。
- **Jackson 3 命名空间是 `tools.jackson.*`**：客户端/服务端口径一致，不要引入 Jackson 2 的 `com.fasterxml.jackson.databind`。
- **每条新权限行必须显式绑角色**（`platform_role_permission` 按 permission_id 绑，不按 code 继承）。
- **前端验收只用 `npm run build`**；`npx vue-tsc --noEmit` 会零报错放过真错误。
- 单元测试命令统一带 `-Dsurefire.failIfNoSpecifiedTests=false`（模块里没有指定测试类时不失败）。

## Review Focus

1. **`A→B→C` 里 `B` 失败时，`C` 不能作为重试起点** —— 服务端必须拒绝并说出「请从 B 开始」，前端按钮置灰。测试落在 Task 4 与 Task 10。
2. **两条分支时，一个分支失败会掀翻另一条** —— 重试必须把另一条断点的子树一起补上，否则新运行「成功」而另一条永远没跑过。测试落在 Task 4 与 Task 10。
3. **只有 `SUCCEEDED` 的运行才删工作区**；失败/超时/取消必须保留并刷新 TTL。删早了 = 断点再也补不了；删晚了 = 占配额。测试落在 Task 7。
4. **删工作区 PVC 前必须确认它是平台的卷**（标签 `platform.pipeline/role=workspace`）—— 绝不能按名字删掉别人的 PVC。测试落在 Task 6/7。
5. **四个新端点各有一条权限行且逐条绑了三个内置角色** —— 少一条启动期 `PermissionCrossCheckRunner` 直接拒启。落在 Task 1。

---

## Task 1: 迁移 SQL（运行行六列 + 四条权限行 + 角色关联）

**Files:**
- Create: `platform-data/src/main/resources/db/migration/V2026_10_20_1__pipeline_retry_and_mask.sql`

**Interfaces:**
- Produces: 表 `platform_pipeline_run` 的六列（`parent_run_id` / `attempt_no` / `retry_from_node` / `masked_nodes_json` / `reused_nodes_json` / `workspace_pvc`）；四条权限行与其角色关联。

- [ ] **Step 1: 写迁移文件**

```sql
-- 流水线节点重试 + 节点屏蔽（设计见 docs/superpowers/specs/2026-10-20-pipeline-retry-and-mask-design.md）
-- ⚠️ 本仓没有 Flyway：这个文件必须手工重放，否则新代码启动即报 Unknown column。

-- ===== 1) 运行行的六个新列 =====
-- 用 information_schema 判存在，重放不会因为「列已存在」整条失败
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_pipeline_run'
             AND COLUMN_NAME = 'parent_run_id');
SET @ddl := IF(@c = 0,
  'ALTER TABLE platform_pipeline_run ADD COLUMN parent_run_id varchar(32) NULL COMMENT ''重试来源运行 id；NULL = 首跑''',
  'SET @pipeline_retry_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_pipeline_run'
             AND COLUMN_NAME = 'attempt_no');
SET @ddl := IF(@c = 0,
  'ALTER TABLE platform_pipeline_run ADD COLUMN attempt_no int NOT NULL DEFAULT 1 COMMENT ''同一条重试链的第几次尝试''',
  'SET @pipeline_retry_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_pipeline_run'
             AND COLUMN_NAME = 'retry_from_node');
SET @ddl := IF(@c = 0,
  'ALTER TABLE platform_pipeline_run ADD COLUMN retry_from_node varchar(64) NULL COMMENT ''用户点的那个断点（画布节点 id）''',
  'SET @pipeline_retry_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_pipeline_run'
             AND COLUMN_NAME = 'masked_nodes_json');
SET @ddl := IF(@c = 0,
  'ALTER TABLE platform_pipeline_run ADD COLUMN masked_nodes_json mediumtext NULL COMMENT ''本次运行屏蔽的节点（JSON 数组，闭包展开后）''',
  'SET @pipeline_retry_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_pipeline_run'
             AND COLUMN_NAME = 'reused_nodes_json');
SET @ddl := IF(@c = 0,
  'ALTER TABLE platform_pipeline_run ADD COLUMN reused_nodes_json mediumtext NULL COMMENT ''重试时沿用的节点（JSON 数组，父运行里已成功）''',
  'SET @pipeline_retry_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_pipeline_run'
             AND COLUMN_NAME = 'workspace_pvc');
SET @ddl := IF(@c = 0,
  'ALTER TABLE platform_pipeline_run ADD COLUMN workspace_pvc varchar(128) NULL COMMENT ''本次运行（链）使用的共享工作区 PVC 名''',
  'SET @pipeline_retry_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

-- ===== 2) 四条端点权限行（不新增 code，全部复用既有 5 个码）=====
-- ⚠️ 授权按「端点行」匹配：没有权限行的新端点会被 PermissionCrossCheckRunner 判成裸端点并拒启。
INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_pipeline_runretry',      'API','/pipeline/run/retry',          'POST','tenant:pipeline:run',  '从断点继续重跑'),
 ('perm_pipeline_runretryplan',  'API','/pipeline/run/retry/plan',     'POST','tenant:pipeline:read', '重试计划（哪些节点可点、会跑哪些）'),
 ('perm_pipeline_retryws',       'API','/pipeline/retry-workspaces',   'POST','tenant:pipeline:read', '待重试工作区列表'),
 ('perm_pipeline_retrywsclear',  'API','/pipeline/retry-workspaces/clear','POST','tenant:pipeline:run', '清理待重试工作区')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

-- ===== 3) 角色关联：4 行 × 3 角色 = 12 行 =====
-- ⚠️ 按 permission_id 绑，不按 code 继承 —— 漏绑 = 上线可见但点了 403
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_tadmin_pl_retry',      'builtin_role_tenant_admin','perm_pipeline_runretry'),
 ('rp_tadmin_pl_retryplan',  'builtin_role_tenant_admin','perm_pipeline_runretryplan'),
 ('rp_tadmin_pl_retryws',    'builtin_role_tenant_admin','perm_pipeline_retryws'),
 ('rp_tadmin_pl_retrywsc',   'builtin_role_tenant_admin','perm_pipeline_retrywsclear'),
 ('rp_tmember_pl_retry',     'builtin_role_tenant_member','perm_pipeline_runretry'),
 ('rp_tmember_pl_retryplan', 'builtin_role_tenant_member','perm_pipeline_runretryplan'),
 ('rp_tmember_pl_retryws',   'builtin_role_tenant_member','perm_pipeline_retryws'),
 ('rp_tmember_pl_retrywsc',  'builtin_role_tenant_member','perm_pipeline_retrywsclear'),
 ('rp_admin_pl_retry',       'builtin_role_admin','perm_pipeline_runretry'),
 ('rp_admin_pl_retryplan',   'builtin_role_admin','perm_pipeline_runretryplan'),
 ('rp_admin_pl_retryws',     'builtin_role_admin','perm_pipeline_retryws'),
 ('rp_admin_pl_retrywsc',    'builtin_role_admin','perm_pipeline_retrywsclear')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
```

- [ ] **Step 2: 自检四条行与 12 条关联都在**

Run: `grep -c "perm_pipeline_runretry\|perm_pipeline_retryws" platform-data/src/main/resources/db/migration/V2026_10_20_1__pipeline_retry_and_mask.sql`
Expected: 至少 8（4 行插入 + 引用的重复出现；关键是肉眼确认 4 条 INSERT 与 12 条 rp_ 行）

- [ ] **Step 3: 提交**

```bash
git add platform-data/src/main/resources/db/migration/V2026_10_20_1__pipeline_retry_and_mask.sql
git commit -m "feat(pipeline): 重试与屏蔽的迁移（运行行六列 + 四条权限行 + 角色关联）"
```

---

## Task 2: 运行行实体与 mapper XML 六列

**Files:**
- Modify: `platform-data/src/main/java/com/coding/data/models/system/PlatformPipelineRun.java`
- Modify: `platform-data/src/main/resources/mapper/system/PlatformPipelineRunMapper.xml`
- Modify: `platform-data/pom.xml`（加 `spring-boot-starter-test` test 依赖）
- Test: `platform-data/src/test/java/com/coding/data/mapper/system/PlatformPipelineRunMapperXmlTest.java`

**Interfaces:**
- Produces: `PlatformPipelineRun` 的 `getParentRunId/setParentRunId`、`getAttemptNo/setAttemptNo`、`getRetryFromNode/setRetryFromNode`、`getMaskedNodesJson/setMaskedNodesJson`、`getReusedNodesJson/setReusedNodesJson`、`getWorkspacePvc/setWorkspacePvc`（后续任务全部用这六个读写方法）。

- [ ] **Step 1: 写失败测试（XML 必须把六列接上）**

```java
package com.coding.data.mapper.system;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapper XML 是**手写的**，加列最容易漏在这里：实体加了字段而 XML 没接，
 * 表现是「字段永远是 null」而不是报错。所以用一份资源级护栏钉住。
 */
class PlatformPipelineRunMapperXmlTest {

    private static final List<String> NEW_COLUMNS = List.of(
            "parent_run_id", "attempt_no", "retry_from_node",
            "masked_nodes_json", "reused_nodes_json", "workspace_pvc");

    private static String xml() throws Exception {
        try (var in = PlatformPipelineRunMapperXmlTest.class
                .getResourceAsStream("/mapper/system/PlatformPipelineRunMapper.xml")) {
            assertThat(in).as("mapper XML 必须在 classpath 上").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyNewColumnIsMappedInTheResultMap() throws Exception {
        String xml = xml();
        for (String col : NEW_COLUMNS) {
            assertThat(xml).as("resultMap 缺列 " + col).contains("column=\"" + col + "\"");
        }
    }

    @Test
    void everyNewColumnIsInserted() throws Exception {
        String insert = between(xml(), "<insert id=\"insert\"", "</insert>");
        for (String col : NEW_COLUMNS) {
            assertThat(insert).as("insert 缺列 " + col).contains(col);
        }
    }

    @Test
    void theListQueryCarriesTheAttemptColumnsForTheBadge() throws Exception {
        String list = between(xml(), "<sql id=\"List_Column_List\">", "</sql>");
        assertThat(list).contains("parent_run_id").contains("attempt_no");
        // 两个 JSON 大字段**不进列表**（几十条记录不该每条都背着）
        assertThat(list).doesNotContain("masked_nodes_json").doesNotContain("reused_nodes_json");
    }

    private static String between(String s, String from, String to) {
        int a = s.indexOf(from);
        int b = s.indexOf(to, a);
        assertThat(a).as("找不到 " + from).isGreaterThanOrEqualTo(0);
        assertThat(b).as("找不到 " + to).isGreaterThan(a);
        return s.substring(a, b);
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl platform-data -am -Dtest=PlatformPipelineRunMapperXmlTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL（`resultMap 缺列 parent_run_id`）；若报没有测试依赖，先做 Step 3 的 pom 改动再跑

- [ ] **Step 3: 加测试依赖**

`platform-data/pom.xml` 的 `<dependencies>` 末尾加：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 4: 实体加六个字段**

`PlatformPipelineRun` 在 `runYaml` 之后、`createdAt` 之前加：

```java
    /** 重试来源运行 id；NULL = 首跑。 */
    private String parentRunId;

    /** 同一条重试链的第几次尝试；首跑 = 1。 */
    private Integer attemptNo;

    /** 用户点的那个断点（画布节点 id）。范围不存这里 —— 由 steps_json 与画布共同还原。 */
    private String retryFromNode;

    /** 本次运行**实际不跑**的节点集合（JSON 数组，闭包展开后）。首跑与重试各一份。 */
    private String maskedNodesJson;

    /** 重试时沿用的节点（JSON 数组，父运行里已成功）。首跑为 NULL。 */
    private String reusedNodesJson;

    /** 本次运行（链）使用的共享工作区 PVC 名。成功即删；失败保留到 TTL。 */
    private String workspacePvc;
```

- [ ] **Step 5: 改 XML 四处**

`platform-data/src/main/resources/mapper/system/PlatformPipelineRunMapper.xml`：

resultMap（`run_yaml` 那行后）加：

```xml
    <result column="parent_run_id"      jdbcType="VARCHAR"     property="parentRunId" />
    <result column="attempt_no"         jdbcType="INTEGER"     property="attemptNo" />
    <result column="retry_from_node"    jdbcType="VARCHAR"     property="retryFromNode" />
    <result column="masked_nodes_json"  jdbcType="LONGVARCHAR" property="maskedNodesJson" />
    <result column="reused_nodes_json"  jdbcType="LONGVARCHAR" property="reusedNodesJson" />
    <result column="workspace_pvc"      jdbcType="VARCHAR"     property="workspacePvc" />
```

`Base_Column_List` 改为：

```xml
    id, pipeline_id, cluster_id, namespace, canvas_json, run_name, status, reason, triggered_by,
    start_time, completion_time, steps_json, run_yaml, created_at,
    parent_run_id, attempt_no, retry_from_node, masked_nodes_json, reused_nodes_json, workspace_pvc
```

`List_Column_List` 改为：

```xml
    id, pipeline_id, cluster_id, namespace, run_name, status, reason, triggered_by,
    start_time, completion_time, steps_json, created_at, parent_run_id, attempt_no
```

`insert` 改为：

```xml
  <insert id="insert" parameterType="com.coding.data.models.system.PlatformPipelineRun">
    insert into platform_pipeline_run
      (id, pipeline_id, cluster_id, namespace, canvas_json, run_name, status, reason, triggered_by,
       parent_run_id, attempt_no, retry_from_node, masked_nodes_json, reused_nodes_json, workspace_pvc)
    values (#{id,jdbcType=VARCHAR}, #{pipelineId,jdbcType=VARCHAR}, #{clusterId,jdbcType=VARCHAR},
      #{namespace,jdbcType=VARCHAR}, #{canvasJson,jdbcType=LONGVARCHAR}, #{runName,jdbcType=VARCHAR},
      #{status,jdbcType=VARCHAR}, #{reason,jdbcType=VARCHAR}, #{triggeredBy,jdbcType=VARCHAR},
      #{parentRunId,jdbcType=VARCHAR}, #{attemptNo,jdbcType=INTEGER},
      #{retryFromNode,jdbcType=VARCHAR}, #{maskedNodesJson,jdbcType=LONGVARCHAR},
      #{reusedNodesJson,jdbcType=LONGVARCHAR}, #{workspacePvc,jdbcType=VARCHAR})
  </insert>
```

> `update`（归档回写）**不加**这六列：它们是触发时的身份与历史，归档只写结果列。

- [ ] **Step 6: 运行测试，确认通过**

Run: `mvn -q -pl platform-data -am -Dtest=PlatformPipelineRunMapperXmlTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS（3 个测试）

- [ ] **Step 7: 提交**

```bash
git add platform-data/pom.xml platform-data/src/main/java/com/coding/data/models/system/PlatformPipelineRun.java \
        platform-data/src/main/resources/mapper/system/PlatformPipelineRunMapper.xml \
        platform-data/src/test/java/com/coding/data/mapper/system/PlatformPipelineRunMapperXmlTest.java
git commit -m "feat(pipeline): 运行行接入重试/屏蔽六列（实体 + mapper + 护栏测试）"
```

---

## Task 3: 画布裁剪纯函数 —— 屏蔽闭包与子画布

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/services/PipelineCanvasMask.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/PipelineCanvasMaskTest.java`

**Interfaces:**
- Consumes: 画布 JSON（`{nodes:[{id,type,name,config,position,onError,finallyTask,finallyWhen}],edges:[{from,to}]}`）
- Produces（后续任务直接用）:
  - `static Set<String> maskedClosure(String canvasJson, List<String> selectedIds)`
  - `static String trim(String canvasJson, Set<String> keepIds, String retryTargetId)`
  - `static boolean isFinally(String canvasJson, String nodeId)`

- [ ] **Step 1: 写失败测试**

```java
package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineCanvasMaskTest {

    /** a → b → c，外加一个收尾节点 fin（不参与连线）。 */
    private static final String CHAIN = """
            {"nodes":[
              {"id":"a","type":"shell","name":"克隆","config":{"command":"echo a"},"position":{"x":1,"y":2}},
              {"id":"b","type":"shell","name":"构建","config":{"command":"echo b"},"onError":"continue"},
              {"id":"c","type":"shell","config":{"command":"echo c"}},
              {"id":"fin","type":"shell","config":{"command":"echo done"},"finallyTask":true,"finallyWhen":"failed"}
            ],"edges":[{"from":"a","to":"b"},{"from":"b","to":"c"}]}""";

    @Test
    void maskingALeafOnlyMasksThatLeaf() {
        assertThat(PipelineCanvasMask.maskedClosure(CHAIN, List.of("c")))
                .containsExactly("c");
    }

    @Test
    void maskingAMiddleNodeTakesItsWholeSubtree() {
        assertThat(PipelineCanvasMask.maskedClosure(CHAIN, List.of("b")))
                .containsExactly("b", "c");
    }

    @Test
    void anUnknownNodeIdIsRejectedNotIgnored() {
        assertThatThrownBy(() -> PipelineCanvasMask.maskedClosure(CHAIN, List.of("nope")))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void trimKeepsTheRequestedNodesAndTheirAttributesVerbatim() {
        String out = PipelineCanvasMask.trim(CHAIN, Set.of("a", "b"), null);
        assertThat(out).contains("\"id\":\"a\"").contains("\"name\":\"克隆\"")
                .contains("\"position\":{\"x\":1,\"y\":2}").contains("\"onError\":\"continue\"")
                .doesNotContain("\"id\":\"c\"").doesNotContain("\"id\":\"fin\"");
    }

    @Test
    void trimDropsEdgesWhoseEitherEndIsGone() {
        String out = PipelineCanvasMask.trim(CHAIN, Set.of("a", "b"), null);
        assertThat(out).contains("\"from\":\"a\",\"to\":\"b\"");
        assertThat(out).doesNotContain("\"from\":\"b\",\"to\":\"c\"");
    }

    @Test
    void retryingAFinallyNodeClearsItsFinallyFlags() {
        // 收尾节点单独重跑时必须当普通任务：否则「只有 finally、没有 tasks」的 spec 不成立
        String out = PipelineCanvasMask.trim(CHAIN, Set.of("fin"), "fin");
        assertThat(out).doesNotContain("finallyTask").doesNotContain("finallyWhen");
    }

    @Test
    void trimmingEverythingRunnableIsRejected() {
        // 只留下收尾节点、且它不是重试目标 → 没有任何普通节点可跑
        assertThatThrownBy(() -> PipelineCanvasMask.trim(CHAIN, Set.of("fin"), null))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("没有可运行");
    }

    @Test
    void isFinallyReadsTheCanvasFlag() {
        assertThat(PipelineCanvasMask.isFinally(CHAIN, "fin")).isTrue();
        assertThat(PipelineCanvasMask.isFinally(CHAIN, "a")).isFalse();
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl platform-api -am -Dtest=PipelineCanvasMaskTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL（`PipelineCanvasMask` 不存在）

- [ ] **Step 3: 实现**

```java
package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 画布的**结构规则**：屏蔽闭包、断点、子画布裁剪。
 *
 * <p>为什么在 platform-api：本模块已经在读画布 JSON（{@link PipelineCanvasRefs}），而
 * `k8s-core` 的 `PipelineCanvas` / 渲染器依赖不到这里。把「哪些节点不跑」在发出去之前算好，
 * k8s-server 的渲染器就**一行都不用改** —— 它照旧把「节点 + 边」翻成 task / runAfter。
 *
 * <p>⚠️ 只做筛选与一处字段改写（收尾节点作为重试目标时清掉 finallyTask/finallyWhen），
 * 不解析 config、不碰连接。
 */
final class PipelineCanvasMask {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PipelineCanvasMask() {
    }

    /** 画布上的一个节点（只取规则需要的两个字段）。 */
    record Node(String id, boolean finallyTask) {
    }

    /** 一条连线。 */
    record Edge(String from, String to) {
    }

    record Canvas(List<Node> nodes, List<Edge> edges) {
    }

    /**
     * 屏蔽闭包 = 勾选的节点 ∪ 它们的全部传递下游。
     *
     * <p>下游必须一起屏蔽：下游的输入往往就是被屏蔽节点刚写进共享工作区的产物。
     */
    static Set<String> maskedClosure(String canvasJson, List<String> selectedIds) {
        if (selectedIds == null || selectedIds.isEmpty()) {
            return Set.of();
        }
        Canvas c = parse(canvasJson);
        Set<String> all = idsOf(c);
        for (String id : selectedIds) {
            if (!all.contains(id)) {
                throw invalid("画布上没有这个节点：" + id);
            }
        }
        Set<String> out = new LinkedHashSet<>(selectedIds);
        out.addAll(descendants(out, c.edges(), all));
        return out;
    }

    /**
     * 只保留 {@code keepIds} 里的节点，以及两端都还在的边。
     *
     * @param retryTargetId 收尾节点作为重试目标时传它 —— 清掉 `finallyTask` / `finallyWhen`，
     *                      否则「只有 finally、没有 tasks」的 PipelineSpec 不成立
     */
    static String trim(String canvasJson, Set<String> keepIds, String retryTargetId) {
        JsonNode root = root(canvasJson);
        if (keepIds == null || keepIds.isEmpty()) {
            throw invalid("裁剪后没有任何节点");
        }
        ArrayNode nodes = JSON.createArrayNode();
        int runnable = 0;
        for (JsonNode n : root.path("nodes")) {
            String id = text(n, "id");
            if (!keepIds.contains(id)) {
                continue;
            }
            JsonNode copy = n.deepCopy();
            if (id.equals(retryTargetId) && copy instanceof ObjectNode obj) {
                obj.remove("finallyTask");
                obj.remove("finallyWhen");
            }
            if (!copy.path("finallyTask").asBoolean(false)) {
                runnable++;
            }
            nodes.add(copy);
        }
        ArrayNode edges = JSON.createArrayNode();
        for (JsonNode e : root.path("edges")) {
            String from = text(e, "from");
            String to = text(e, "to");
            if (keepIds.contains(from) && keepIds.contains(to)) {
                edges.add(e.deepCopy());
            }
        }
        if (runnable == 0) {
            throw invalid("屏蔽后没有可运行的节点了 —— 至少留一个不在收尾里跑的节点");
        }
        ObjectNode out = (ObjectNode) root;
        out.set("nodes", nodes);
        out.set("edges", edges);
        return JSON.writeValueAsString(out);
    }

    /** 这个节点在画布上是不是收尾节点。 */
    static boolean isFinally(String canvasJson, String nodeId) {
        for (Node n : parse(canvasJson).nodes()) {
            if (n.id().equals(nodeId)) {
                return n.finallyTask();
            }
        }
        return false;
    }

    // ---- 解析与图遍历（后续任务的断点计算也复用它们）----

    static Canvas parse(String canvasJson) {
        JsonNode root = root(canvasJson);
        List<Node> nodes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode n : root.path("nodes")) {
            String id = text(n, "id");
            if (id.isBlank()) {
                throw invalid("画布里有节点没有 id");
            }
            if (!seen.add(id)) {
                throw invalid("节点 id 重复：" + id);
            }
            nodes.add(new Node(id, n.path("finallyTask").asBoolean(false)));
        }
        List<Edge> edges = new ArrayList<>();
        for (JsonNode e : root.path("edges")) {
            edges.add(new Edge(text(e, "from"), text(e, "to")));
        }
        return new Canvas(List.copyOf(nodes), List.copyOf(edges));
    }

    static Set<String> idsOf(Canvas c) {
        Set<String> out = new LinkedHashSet<>();
        for (Node n : c.nodes()) {
            out.add(n.id());
        }
        return out;
    }

    /** 从 {@code roots} 出发、沿边正向可达的全部节点（不含 roots 自身，除非成环 —— 画布已保证无环）。 */
    static Set<String> descendants(Collection<String> roots, List<Edge> edges, Set<String> allIds) {
        Map<String, List<String>> next = new LinkedHashMap<>();
        for (Edge e : edges) {
            next.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
        }
        Set<String> out = new LinkedHashSet<>();
        List<String> stack = new ArrayList<>(roots);
        while (!stack.isEmpty()) {
            String cur = stack.remove(stack.size() - 1);
            for (String n : next.getOrDefault(cur, List.of())) {
                if (out.add(n)) {
                    stack.add(n);
                }
            }
        }
        out.retainAll(allIds);
        return out;
    }

    static JsonNode root(String canvasJson) {
        if (canvasJson == null || canvasJson.isBlank()) {
            throw invalid("画布为空");
        }
        try {
            return JSON.readTree(canvasJson);
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_CANVAS_INVALID, "画布内容无法解析");
        }
    }

    static String text(JsonNode n, String field) {
        String v = n.path(field).asString("");
        return v == null ? "" : v;
    }

    static CloudPlatformException invalid(String message) {
        return new CloudPlatformException(EnumResponseType.PIPELINE_INVALID, message);
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -pl platform-api -am -Dtest=PipelineCanvasMaskTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS（8 个测试）

- [ ] **Step 5: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/PipelineCanvasMask.java \
        platform-api/src/test/java/com/coding/platformapi/services/PipelineCanvasMaskTest.java
git commit -m "feat(pipeline): 画布屏蔽闭包与子画布裁剪（纯函数 + 测试）"
```

---

## Task 4: 断点、重试范围与不可重试原因

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/PipelineCanvasMask.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/PipelineCanvasMaskRetryTest.java`

**Interfaces:**
- Produces:
  - `record Plan(Set<String> retryable, Set<String> runNodeIds, Map<String,String> blocked)`
  - `static Plan plan(String canvasJson, Map<String,String> effectiveStatus, Set<String> masked)`

- [ ] **Step 1: 写失败测试**

```java
package com.coding.platformapi.services;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 断点规则是**重试的全部安全边界**：C 在 B 失败时不能当起点；并行的断点必须一起补。
 */
class PipelineCanvasMaskRetryTest {

    private static final String CHAIN = """
            {"nodes":[
              {"id":"a","type":"shell","config":{}},
              {"id":"b","type":"shell","config":{}},
              {"id":"c","type":"shell","config":{}}
            ],"edges":[{"from":"a","to":"b"},{"from":"b","to":"c"}]}""";

    /** 两分支：git → a1 → a2 → a3；git → b1 → b2。 */
    private static final String FORK = """
            {"nodes":[
              {"id":"git","type":"git","config":{}},
              {"id":"a1","type":"shell","config":{}},
              {"id":"a2","type":"shell","config":{}},
              {"id":"a3","type":"shell","config":{}},
              {"id":"b1","type":"shell","config":{}},
              {"id":"b2","type":"shell","config":{}},
              {"id":"fin","type":"shell","config":{},"finallyTask":true}
            ],"edges":[{"from":"git","to":"a1"},{"from":"a1","to":"a2"},{"from":"a2","to":"a3"},
                       {"from":"git","to":"b1"},{"from":"b1","to":"b2"}]}""";

    @Test
    void onlyTheFailedNodeIsTheBreakpointWhenItsUpstreamSucceeded() {
        // a→b→c，b 失败：c 的上游没成功 → c **不是**断点
        var plan = PipelineCanvasMask.plan(CHAIN,
                Map.of("a", "SUCCEEDED", "b", "FAILED"), Set.of());

        assertThat(plan.retryable()).containsExactly("b");
        assertThat(plan.runNodeIds()).containsExactly("b", "c");
        assertThat(plan.blocked()).containsEntry("c", "上游 b 没有成功，请从它开始重试")
                .containsEntry("a", "这个节点已经成功，不需要重试");
    }

    @Test
    void bothBranchesBreakpointsAreRetriedTogether() {
        // A2 失败把正在跑的 B1 取消：B1 也是断点，必须一起补 —— 否则 B 永远补不了
        var plan = PipelineCanvasMask.plan(FORK,
                Map.of("git", "SUCCEEDED", "a1", "SUCCEEDED", "a2", "FAILED", "b1", "CANCELLED"),
                Set.of());

        assertThat(plan.retryable()).containsExactlyInAnyOrder("a2", "b1");
        assertThat(plan.runNodeIds()).containsExactlyInAnyOrder("a2", "a3", "b1", "b2");
    }

    @Test
    void aNodeWithoutAnyStatusCountsAsNotSucceeded() {
        // 没轮到它 → 没有状态 → 如果上游成功，它就是断点
        var plan = PipelineCanvasMask.plan(CHAIN, Map.of("a", "SUCCEEDED"), Set.of());
        assertThat(plan.retryable()).containsExactly("b");
        assertThat(plan.runNodeIds()).containsExactly("b", "c");
    }

    @Test
    void maskedNodesAreNeverRetryableAndStayOutOfTheScope() {
        var plan = PipelineCanvasMask.plan(FORK,
                Map.of("git", "SUCCEEDED", "a1", "SUCCEEDED", "a2", "FAILED", "b1", "CANCELLED"),
                Set.of("b1", "b2"));

        assertThat(plan.retryable()).containsExactly("a2");
        assertThat(plan.runNodeIds()).containsExactly("a2", "a3");
        assertThat(plan.blocked()).containsEntry("b1", "本次运行屏蔽了它（连同下游）");
    }

    @Test
    void anUnfinishedFinallyNodeIsRetryableOnItsOwnButNotInTheScope() {
        var plan = PipelineCanvasMask.plan(FORK,
                Map.of("git", "SUCCEEDED", "a1", "SUCCEEDED", "a2", "FAILED", "b1", "CANCELLED"),
                Set.of());

        assertThat(plan.retryable()).contains("fin");
        assertThat(plan.runNodeIds()).doesNotContain("fin");
    }

    @Test
    void aSucceededFinallyNodeIsNotRetryable() {
        var plan = PipelineCanvasMask.plan(FORK,
                Map.of("git", "SUCCEEDED", "a1", "SUCCEEDED", "a2", "FAILED", "b1", "CANCELLED",
                        "fin", "SUCCEEDED"),
                Set.of());
        assertThat(plan.retryable()).doesNotContain("fin");
        assertThat(plan.blocked()).containsEntry("fin", "这个节点已经成功，不需要重试");
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl platform-api -am -Dtest=PipelineCanvasMaskRetryTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL（`plan` 不存在）

- [ ] **Step 3: 在 `PipelineCanvasMask` 里实现**

```java
    /** 重试计划：可点集合、会执行的节点集合、以及每个不可点节点的原因。 */
    record Plan(Set<String> retryable, Set<String> runNodeIds, Map<String, String> blocked) {
    }

    /**
     * 断点 = 自己没成功、且全部传递上游都已经成功。
     *
     * <p>范围 = 所有断点的子树之并。并行断点必须一起补：Tekton 的停止是整条运行级的，
     * 只补一条会产出「成功但流水线没跑完」的记录，而成功即删工作区 —— 另一条就永远补不了了。
     *
     * @param effectiveStatus 节点 id → 状态；**没有键 = 没轮到它 = 没成功**
     */
    static Plan plan(String canvasJson, Map<String, String> effectiveStatus, Set<String> masked) {
        Canvas c = parse(canvasJson);
        Set<String> all = idsOf(c);
        Map<String, Set<String>> parents = parentsOf(c, all);

        Set<String> frontier = new LinkedHashSet<>();
        for (Node n : c.nodes()) {
            if (n.finallyTask() || masked.contains(n.id()) || succeeded(effectiveStatus, n.id())) {
                continue;
            }
            if (allSucceeded(ancestorsOf(n.id(), parents), effectiveStatus)) {
                frontier.add(n.id());
            }
        }
        Set<String> scope = new LinkedHashSet<>(frontier);
        scope.addAll(descendants(frontier, c.edges(), all));
        scope.removeAll(masked);

        Set<String> retryable = new LinkedHashSet<>(frontier);
        Map<String, String> blocked = new LinkedHashMap<>();
        for (Node n : c.nodes()) {
            if (masked.contains(n.id())) {
                blocked.put(n.id(), "本次运行屏蔽了它（连同下游）");
            } else if (succeeded(effectiveStatus, n.id())) {
                blocked.put(n.id(), "这个节点已经成功，不需要重试");
            } else if (n.finallyTask()) {
                // 收尾不参与连线，上游概念不成立 —— 没跑成就可单独重跑它自己
                retryable.add(n.id());
            } else if (!frontier.contains(n.id())) {
                String up = firstFrontierAncestor(n.id(), parents, frontier);
                blocked.put(n.id(), up == null
                        ? "它的上游还没有全部成功，请从上游开始重试"
                        : "上游 " + up + " 没有成功，请从它开始重试");
            }
        }
        return new Plan(Set.copyOf(retryable), Set.copyOf(scope), Map.copyOf(blocked));
    }

    private static Map<String, Set<String>> parentsOf(Canvas c, Set<String> all) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String id : all) {
            out.put(id, new LinkedHashSet<>());
        }
        for (Edge e : c.edges()) {
            out.get(e.to()).add(e.from());
        }
        return out;
    }

    private static Set<String> ancestorsOf(String id, Map<String, Set<String>> parents) {
        Set<String> out = new LinkedHashSet<>();
        List<String> stack = new ArrayList<>(parents.getOrDefault(id, Set.of()));
        while (!stack.isEmpty()) {
            String cur = stack.remove(stack.size() - 1);
            if (out.add(cur)) {
                stack.addAll(parents.getOrDefault(cur, Set.of()));
            }
        }
        return out;
    }

    private static boolean succeeded(Map<String, String> status, String id) {
        return "SUCCEEDED".equals(status.get(id));
    }

    private static boolean allSucceeded(Set<String> ids, Map<String, String> status) {
        for (String id : ids) {
            if (!succeeded(status, id)) {
                return false;
            }
        }
        return true;
    }

    private static String firstFrontierAncestor(String id, Map<String, Set<String>> parents,
                                                Set<String> frontier) {
        for (String a : ancestorsOf(id, parents)) {
            if (frontier.contains(a)) {
                return a;
            }
        }
        return null;
    }
```

- [ ] **Step 4: 运行两个测试类，确认全绿**

Run: `mvn -q -pl platform-api -am -Dtest='PipelineCanvasMaskTest,PipelineCanvasMaskRetryTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS（14 个测试）

- [ ] **Step 5: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/PipelineCanvasMask.java \
        platform-api/src/test/java/com/coding/platformapi/services/PipelineCanvasMaskRetryTest.java
git commit -m "feat(pipeline): 断点与重试范围（含并行断点合并、不可点原因）"
```

---

## Task 5: 契约 DTO（工作区名、重试计划、工作区列表）

**Files:**
- Modify: `platform-common/src/main/java/com/coding/common/models/k8s/dto/PipelineRunDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/pipeline/PipelineRetryPlanDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/pipeline/PipelineRetryWorkspaceDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/pipeline/PipelineRetryWorkspacesDTO.java`
- Test: `platform-common/src/test/java/com/coding/common/models/pipeline/PipelineRetryDtosTest.java`

**Interfaces:**
- Produces:
  - `PipelineRunDTO.getWorkspacePvc()/setWorkspacePvc(String)`
  - `record PipelineRetryPlanDTO(boolean retryable, String reason, List<String> retryableNodes, List<String> runNodeIds, List<String> reusedNodeIds, Map<String,String> blocked)`
  - `record PipelineRetryWorkspaceDTO(String name, String runName, String createdAt, String retainUntil, long sizeBytes)`
  - `record PipelineRetryWorkspacesDTO(int count, long totalBytes, List<PipelineRetryWorkspaceDTO> items)`

- [ ] **Step 1: 写失败测试（字段名就是前端契约）**

```java
package com.coding.common.models.pipeline;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 这些字段名会**原样进前端**（records 由 Jackson 序列化）。
 * 改名等于改契约，所以用测试把名字钉住。
 */
class PipelineRetryDtosTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void thePlanSerializesWithTheNamesTheFrontendReads() {
        var plan = new PipelineRetryPlanDTO(true, null,
                List.of("b"), List.of("b", "c"), List.of("a"), Map.of("c", "上游 b 没有成功"));

        String json = JSON.writeValueAsString(plan);

        assertThat(json).contains("\"retryable\":true")
                .contains("\"retryableNodes\":[\"b\"]")
                .contains("\"runNodeIds\":[\"b\",\"c\"]")
                .contains("\"reusedNodeIds\":[\"a\"]")
                .contains("\"blocked\":{\"c\":\"上游 b 没有成功\"}");
    }

    @Test
    void theWorkspaceListSerializesCountAndBytes() {
        var out = new PipelineRetryWorkspacesDTO(1, 1073741824L,
                List.of(new PipelineRetryWorkspaceDTO("ws-1", "run-1",
                        "2026-10-20T00:00:00Z", "2026-10-21T00:00:00Z", 1073741824L)));

        String json = JSON.writeValueAsString(out);

        assertThat(json).contains("\"count\":1").contains("\"totalBytes\":1073741824")
                .contains("\"name\":\"ws-1\"").contains("\"retainUntil\":\"2026-10-21T00:00:00Z\"");
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl platform-common -Dtest=PipelineRetryDtosTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL（类不存在，编译失败）

- [ ] **Step 3: 新增三个 record**

`PipelineRetryPlanDTO.java`：

```java
package com.coding.common.models.pipeline;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * 一次运行的重试计划 —— **前端据此决定哪些节点有「重试」按钮、对话框里列什么**。
 *
 * <p>⚠️ 断点规则只在服务端实现（{@code PipelineCanvasMask.plan}）；前端不要自己重算。
 *
 * @param retryable      这条运行整体能不能重试（RUNNING / SUCCEEDED / 没有工作区时为 false）
 * @param reason         整体不能重试的原因（可重试时为 null）
 * @param retryableNodes 可以作为重试起点的节点（普通断点 + 没跑成的收尾节点）
 * @param runNodeIds     点普通断点时**会执行**的节点集合（所有断点的子树之并）
 * @param reusedNodeIds  沿用（上次成功、本次不跑）的节点
 * @param blocked        节点 id → 不能点的原因
 */
@Schema(description = "一次运行的重试计划")
public record PipelineRetryPlanDTO(
        boolean retryable,
        String reason,
        List<String> retryableNodes,
        List<String> runNodeIds,
        List<String> reusedNodeIds,
        Map<String, String> blocked) {
}
```

`PipelineRetryWorkspaceDTO.java`：

```java
package com.coding.common.models.pipeline;

/**
 * 一块「待重试工作区」（命名空间里保留中的平台工作区 PVC）。
 *
 * @param sizeBytes 请求容量（基础单位：字节）
 */
public record PipelineRetryWorkspaceDTO(String name, String runName, String createdAt,
                                        String retainUntil, long sizeBytes) {
}
```

`PipelineRetryWorkspacesDTO.java`：

```java
package com.coding.common.models.pipeline;

import java.util.List;

public record PipelineRetryWorkspacesDTO(int count, long totalBytes,
                                         List<PipelineRetryWorkspaceDTO> items) {
}
```

- [ ] **Step 4: `PipelineRunDTO` 加 `workspacePvc`**

在 `connections` 字段之后加：

```java
    /**
     * 本次运行绑定的工作区 PVC 名。**创建时必填**：
     * 首跑由 platform-api 生成 `platform-pipeline-ws-<runName>`；重试沿用父运行那一块。
     *
     * <p>⚠️ 名字由 platform-api 定，k8s-server 只负责「没有就建、有就刷新 TTL」——
     * 名字只有一个落点，两个模块不需要各自拼前缀。
     */
    @Schema(description = "工作区 PVC 名。create 时必填")
    private String workspacePvc;

    /**
     * 这块工作区是**沿用**的（重试）还是本次新建的（首跑）。
     *
     * <p>⚠️ 必须区分：沿用而卷不存在时**不能重建一块空的** —— 那会让重试在一个空工作区上跑，
     * 看起来成功、产物却全缺。此时要如实报「工作区已过期」。
     */
    @Schema(description = "true = 沿用具名工作区（重试）；false = 本次新建")
    private boolean reuseWorkspace;
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -pl platform-common -Dtest=PipelineRetryDtosTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS（2 个测试）

- [ ] **Step 6: 提交**

```bash
git add platform-common/src/main/java/com/coding/common/models/k8s/dto/PipelineRunDTO.java \
        platform-common/src/main/java/com/coding/common/models/pipeline/PipelineRetryPlanDTO.java \
        platform-common/src/main/java/com/coding/common/models/pipeline/PipelineRetryWorkspaceDTO.java \
        platform-common/src/main/java/com/coding/common/models/pipeline/PipelineRetryWorkspacesDTO.java \
        platform-common/src/test/java/com/coding/common/models/pipeline/PipelineRetryDtosTest.java
git commit -m "feat(pipeline): 重试/工作区的契约 DTO（字段名即前端契约）"
```

---

## Task 6: k8s-core —— 工作区 PVC 的创建、绑定与删除

**Files:**
- Modify: `k8s-core/src/main/java/com/coding/k8score/operations/pipelinerun/PipelineRunOperations.java`
- Modify: `k8s-core/src/main/java/com/coding/k8score/factory/KubernetesOperationsFactory.java`
- Test: `k8s-core/src/test/java/com/coding/k8score/operations/pipelinerun/PipelineRunOperationsTest.java`

**Interfaces:**
- Consumes: `PipelineRunDTO.getWorkspacePvc()` / `isReuseWorkspace()`
- Produces:
  - `PipelineRunOperations(String, PipelineRunConverter, PipelineCanvasOperations, Duration workspaceRetention)`（工厂注入）
  - `public static final String WORKSPACE_ROLE_LABEL = "platform.pipeline/role"`、`WORKSPACE_ROLE_VALUE = "workspace"`、`WORKSPACE_RUN_LABEL = "platform.pipeline/run"`、`RETAIN_UNTIL_ANNOTATION = "platform.pipeline/retain-until"`
  - `public static String workspaceClaimNameOf(PipelineRun pr)`
  - `public static boolean isWorkspacePvc(PersistentVolumeClaim pvc)`
  - `static void assertWorkspaceUsable(boolean exists, boolean reuse, String claimName)`
  - `public String workspaceClaimName(String namespace, String runName)`
  - `public void deleteWorkspacePvc(String namespace, String claimName)`
  - `public void refreshWorkspaceRetention(String namespace, String claimName)`

- [ ] **Step 1: 改写 `buildRun` 的既有绑定测试并补新测试**

把 `PipelineRunOperationsTest` 里 `everyRunBindsTheSharedWorkspaceWithAPvcTemplate` 整个替换为：

```java
    @Test
    void everyRunBindsThePlatformOwnedWorkspacePvc() {
        // ⭐ 不绑就等于没跑：Tekton 对非 optional 的 workspace 要求 PipelineRun 给出绑定，
        //    缺了创建会成功、reconciler 立刻判 Failed，Pod 都不起（连日志都没有）。
        //    现在绑的是**平台自建的具名 PVC**（不再是 volumeClaimTemplate）—— 这样运行删了卷还在，
        //    失败后才有「从断点继续」的余地。
        String canvas = """
                {"nodes":[{"id":"clone","type":"git","config":{"repo":"https://g/r.git"}}],"edges":[]}""";

        PipelineRun pr = opsForBuild().buildRun(runOf(canvas));

        assertThat(pr.getSpec().getPipelineSpec().getWorkspaces()).hasSize(1);
        WorkspaceBinding binding = pr.getSpec().getWorkspaces().get(0);
        assertThat(binding.getName()).isEqualTo("ws");
        assertThat(binding.getPersistentVolumeClaim()).isNotNull();
        assertThat(binding.getPersistentVolumeClaim().getClaimName())
                .isEqualTo("platform-pipeline-ws-my-app-20261015-abcd");
        assertThat(binding.getVolumeClaimTemplate()).as("不再用模板").isNull();
    }

    @Test
    void aRunWithoutAWorkspaceClaimIsRefusedBeforeAnythingIsBuilt() {
        String canvas = """
                {"nodes":[{"id":"clone","type":"git","config":{"repo":"https://g/r.git"}}],"edges":[]}""";
        PipelineRunDTO dto = runOf(canvas);
        dto.setWorkspacePvc(null);

        assertThatThrownBy(() -> opsForBuild().buildRun(dto))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("工作区");
    }

    @Test
    void theWorkspaceClaimNameIsReadFromTheWsBinding() {
        String canvas = """
                {"nodes":[{"id":"clone","type":"git","config":{"repo":"https://g/r.git"}}],"edges":[]}""";
        PipelineRun pr = opsForBuild().buildRun(runOf(canvas));

        assertThat(PipelineRunOperations.workspaceClaimNameOf(pr))
                .isEqualTo("platform-pipeline-ws-my-app-20261015-abcd");
        assertThat(PipelineRunOperations.workspaceClaimNameOf(new PipelineRun())).isNull();
    }

    @Test
    void onlyPvcsCarryingTheWorkspaceRoleAreOursToDelete() {
        PersistentVolumeClaim ours = new PersistentVolumeClaim();
        ours.setMetadata(new ObjectMeta());
        ours.getMetadata().setLabels(Map.of(PipelineRunOperations.WORKSPACE_ROLE_LABEL,
                PipelineRunOperations.WORKSPACE_ROLE_VALUE));
        assertThat(PipelineRunOperations.isWorkspacePvc(ours)).isTrue();

        PersistentVolumeClaim foreign = new PersistentVolumeClaim();
        foreign.setMetadata(new ObjectMeta());
        foreign.getMetadata().setLabels(Map.of("app", "someone-else"));
        assertThat(PipelineRunOperations.isWorkspacePvc(foreign)).isFalse();

        PersistentVolumeClaim noLabels = new PersistentVolumeClaim();
        noLabels.setMetadata(new ObjectMeta());
        assertThat(PipelineRunOperations.isWorkspacePvc(noLabels)).isFalse();
        assertThat(PipelineRunOperations.isWorkspacePvc(null)).isFalse();
    }

    @Test
    void reusingAMissingWorkspaceIsRefusedInsteadOfSowingAnEmptyOne() {
        // ⭐ 沿用而卷没了 = 工作区过期。重建一块空的会让重试「成功」却产物全缺 ——
        //    这是本批最隐蔽的一种假成功。
        assertThatThrownBy(() -> PipelineRunOperations.assertWorkspaceUsable(false, true, "ws-x"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("工作区")
                .hasMessageContaining("过期");

        // 首跑缺卷是正常的（本次要新建）；沿用且卷在：放行
        PipelineRunOperations.assertWorkspaceUsable(false, false, "ws-x");
        PipelineRunOperations.assertWorkspaceUsable(true, true, "ws-x");
    }
```

`runOf` 加点东西（让它带上工作区名）：

```java
    private static PipelineRunDTO runOf(String canvasJson) {
        PipelineRunDTO dto = new PipelineRunDTO();
        dto.setClusterId("c1");
        dto.setNamespace("ns1");
        dto.setName("my-app-20261015-abcd");
        dto.setPipelineCanvasJson(canvasJson);
        dto.setWorkspacePvc("platform-pipeline-ws-" + dto.getName());
        return dto;
    }
```

`opsForBuild` 不变（`new PipelineRunOperations(null, converter, canvasOps)` 由一个 3 参便捷构造器兜住，见下面实现）。

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl k8s-core -Dtest=PipelineRunOperationsTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL（绑定仍是 volumeClaimTemplate / 方法不存在）

- [ ] **Step 3: 实现（k8s-core）**

`PipelineRunOperations`：

1. 类字段与常量：

```java
    /** 工作区 PVC 的角色标签 —— 回收器与删除前的守卫都认它。 */
    public static final String WORKSPACE_ROLE_LABEL = "platform.pipeline/role";
    public static final String WORKSPACE_ROLE_VALUE = "workspace";
    /** 这块卷属于哪条运行（链上的第一次运行名）。 */
    public static final String WORKSPACE_RUN_LABEL = "platform.pipeline/run";
    /** 保留到什么时候（RFC3339 UTC）。回收器只解析这个注解。 */
    public static final String RETAIN_UNTIL_ANNOTATION = "platform.pipeline/retain-until";

    private final Duration workspaceRetention;
```

2. 构造器（替换 Lombok 生成的）：

```java
    public PipelineRunOperations(KubernetesClient client, PipelineRunConverter converter,
                                 PipelineCanvasOperations canvasOps, Duration workspaceRetention) {
        this.client = client;
        this.converter = converter;
        this.canvasOps = canvasOps;
        this.workspaceRetention = workspaceRetention == null ? Duration.ofHours(24) : workspaceRetention;
    }

    /** 测试与老调用点的便捷构造器：默认 24 小时。 */
    public PipelineRunOperations(KubernetesClient client, PipelineRunConverter converter,
                                 PipelineCanvasOperations canvasOps) {
        this(client, converter, canvasOps, Duration.ofHours(24));
    }
```

> 保留 `@RequiredArgsConstructor` 的同时手写构造器会冲突 —— 删掉类上的 `@RequiredArgsConstructor`，改为手写上面两个。

3. `create(...)`：在 creds secret 之后建卷（或校验沿用的卷），`buildRun`/create 抛异常时**只有本次新建的**才删：

```java
        boolean workspaceCreated;
        try {
            workspaceCreated = ensureWorkspacePvc(dto.getWorkspacePvc(), dto.getNamespace(),
                    dto.getName(), dto.isReuseWorkspace());
        } catch (KubernetesClientException e) {
            throw new CloudPlatformException(EnumResponseType.K8S_OPERATION_REJECTED,
                    "准备工作区 PVC 失败：" + apiMessage(e));
        }
        PipelineRun created;
        try {
            PipelineRun pr = buildRun(dto, null);
            if (declaresCache(pr)) {
                pr = buildRun(dto, dto.isCacheWorkspaceAllowed() ? ensureCachePvc(dto.getNamespace()) : null);
            }
            created = client.resources(PipelineRun.class).inNamespace(dto.getNamespace())
                    .resource(pr).create();
        } catch (KubernetesClientException e) {
            if (workspaceCreated) {
                deleteWorkspacePvcQuietly(dto.getNamespace(), dto.getWorkspacePvc());
            }
            throw new CloudPlatformException(EnumResponseType.K8S_OPERATION_REJECTED,
                    "创建 PipelineRun 失败：" + apiMessage(e));
        } catch (RuntimeException e) {
            if (workspaceCreated) {
                deleteWorkspacePvcQuietly(dto.getNamespace(), dto.getWorkspacePvc());
            }
            throw e;
        }
        return converter.toDto(created);
```

（原有的 `KubernetesClientException` 包装保留在 create 那一段。）

4. `bindWorkspaces`：`ws` 分支改为具名 PVC：

```java
            if (PipelineCanvasOperations.WORKSPACE.equals(decl.getName())) {
                PersistentVolumeClaimVolumeSource src = new PersistentVolumeClaimVolumeSource();
                src.setClaimName(workspacePvc);
                b.setPersistentVolumeClaim(src);
                out.add(b);
                continue;
            }
```

方法签名加参数 `String workspacePvc`；`buildRun` 里传 `dto.getWorkspacePvc()`；`buildRun` 开头校验：

```java
        if (!StringUtils.hasText(dto.getWorkspacePvc())) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                    "创建运行必须带工作区 PVC 名（platform-pipeline-ws-<运行名>）");
        }
```

并删掉原来那段 `VolumeResourceRequirements` / `volumeClaimTemplate` 的构造代码（连同不再使用的 import）。

5. 新增工作区方法：

```java
    /**
     * 确保工作区 PVC 存在。返回 true = 本次新建（创建 PipelineRun 失败时要回收它）；
     * false = 已存在（重试路径），只刷新保留时间。
     *
     * <p>⚠️ {@code reuse=true} 而卷不存在 → **拒绝**，不新建空卷：那会让重试在一个空工作区上跑。
     */
    private boolean ensureWorkspacePvc(String claimName, String namespace, String runName, boolean reuse) {
        if (!StringUtils.hasText(claimName)) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                    "创建运行必须带工作区 PVC 名（platform-pipeline-ws-<运行名>）");
        }
        PersistentVolumeClaim existing = client.persistentVolumeClaims().inNamespace(namespace)
                .withName(claimName).get();
        assertWorkspaceUsable(existing != null, reuse, claimName);
        if (existing != null) {
            refreshWorkspaceRetention(namespace, claimName);
            return false;
        }
        PersistentVolumeClaim pvc = new PersistentVolumeClaim();
        pvc.setMetadata(new ObjectMeta());
        pvc.getMetadata().setName(claimName);
        pvc.getMetadata().setNamespace(namespace);
        pvc.getMetadata().setLabels(Map.of(
                MANAGED_LABEL, MANAGED_VALUE,
                WORKSPACE_ROLE_LABEL, WORKSPACE_ROLE_VALUE,
                WORKSPACE_RUN_LABEL, runName));
        pvc.getMetadata().setAnnotations(Map.of(
                RETAIN_UNTIL_ANNOTATION, Instant.now().plus(workspaceRetention).toString()));
        VolumeResourceRequirements res = new VolumeResourceRequirements();
        res.setRequests(Map.of("storage", new Quantity(WORKSPACE_STORAGE)));
        PersistentVolumeClaimSpec spec = new PersistentVolumeClaimSpec();
        spec.setAccessModes(List.of("ReadWriteOnce"));
        spec.setResources(res);
        pvc.setSpec(spec);
        client.persistentVolumeClaims().inNamespace(namespace).resource(pvc).create();
        return true;
    }

    /**
     * 沿用而卷不在 = 工作区已过期（保留窗口过了或被人清了）。**不能在这里补一块空的**：
     * 重试会「成功」但产物全缺 —— 这是最隐蔽的一种假成功。
     */
    static void assertWorkspaceUsable(boolean exists, boolean reuse, String claimName) {
        if (reuse && !exists) {
            throw new CloudPlatformException(EnumResponseType.K8S_OPERATION_REJECTED,
                    "工作区已过期或已被清理（" + claimName + "），这次运行不能从断点继续，请整条重跑");
        }
    }

    /** 把保留时间推到「现在 + retention」—— 重试与失败归档都会调它。 */
    public void refreshWorkspaceRetention(String namespace, String claimName) {
        PersistentVolumeClaim pvc = client.persistentVolumeClaims().inNamespace(namespace)
                .withName(claimName).get();
        if (pvc == null || !isWorkspacePvc(pvc)) {
            return;
        }
        client.persistentVolumeClaims().inNamespace(namespace).withName(claimName)
                .edit(p -> {
                    Map<String, String> ann = p.getMetadata().getAnnotations() == null
                            ? new HashMap<>() : new HashMap<>(p.getMetadata().getAnnotations());
                    ann.put(RETAIN_UNTIL_ANNOTATION, Instant.now().plus(workspaceRetention).toString());
                    p.getMetadata().setAnnotations(ann);
                    return p;
                });
    }

    /** 删工作区 PVC。**先校验标签**：名字撞上别人的卷时，宁可不删也不能误删。 */
    public void deleteWorkspacePvc(String namespace, String claimName) {
        PersistentVolumeClaim pvc = client.persistentVolumeClaims().inNamespace(namespace)
                .withName(claimName).get();
        if (pvc == null) {
            return;
        }
        if (!isWorkspacePvc(pvc)) {
            throw new CloudPlatformException(EnumResponseType.K8S_OPERATION_REJECTED,
                    "拒绝删除非平台工作区 PVC：" + claimName);
        }
        client.persistentVolumeClaims().inNamespace(namespace).withName(claimName).delete();
    }

    private void deleteWorkspacePvcQuietly(String namespace, String claimName) {
        try {
            deleteWorkspacePvc(namespace, claimName);
        } catch (Exception e) {
            log.warn("回收刚创建的工作区 PVC 失败（交给 TTL）：{}/{}", namespace, claimName, e);
        }
    }

    /** 从运行对象的 ws 绑定里读出工作区 PVC 名（归档时删卷要用）。 */
    public String workspaceClaimName(String namespace, String runName) {
        PipelineRun pr = fetch(namespace, runName);
        return pr == null ? null : workspaceClaimNameOf(pr);
    }

    static String workspaceClaimNameOf(PipelineRun pr) {
        PipelineRunSpec spec = pr == null ? null : pr.getSpec();
        if (spec == null || spec.getWorkspaces() == null) {
            return null;
        }
        for (WorkspaceBinding b : spec.getWorkspaces()) {
            if (PipelineCanvasOperations.WORKSPACE.equals(b.getName())
                    && b.getPersistentVolumeClaim() != null) {
                return b.getPersistentVolumeClaim().getClaimName();
            }
        }
        return null;
    }

    static boolean isWorkspacePvc(PersistentVolumeClaim pvc) {
        return pvc != null && pvc.getMetadata() != null && pvc.getMetadata().getLabels() != null
                && WORKSPACE_ROLE_VALUE.equals(
                        pvc.getMetadata().getLabels().get(WORKSPACE_ROLE_LABEL));
    }
```

6. 工厂：`KubernetesOperationsFactory` 构造器加参数并在 `build` 里传下去：

```java
    private final Duration pipelineWorkspaceRetention;

    public KubernetesOperationsFactory(KubernetesClientFactory clientFactory,
                                       K8sClusterMapper k8sClusterMapper,
                                       PipelineCanvasOperations canvasOps,
                                       @Value("${platform.pipeline.workspace.retention:24h}")
                                       Duration pipelineWorkspaceRetention) {
        this.clientFactory = clientFactory;
        this.k8sClusterMapper = k8sClusterMapper;
        this.canvasOps = canvasOps;
        this.pipelineWorkspaceRetention = pipelineWorkspaceRetention;
    }
```

```java
            case PIPELINE_RUN -> new PipelineRunOperations(client, new PipelineRunConverter(),
                    canvasOps, pipelineWorkspaceRetention);
```

并 import `org.springframework.beans.factory.annotation.Value`、`java.time.Duration`。

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -pl k8s-core -Dtest=PipelineRunOperationsTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS（含既有 20+ 个测试）

- [ ] **Step 5: 提交**

```bash
git add k8s-core/src/main/java/com/coding/k8score/operations/pipelinerun/PipelineRunOperations.java \
        k8s-core/src/main/java/com/coding/k8score/factory/KubernetesOperationsFactory.java \
        k8s-core/src/test/java/com/coding/k8score/operations/pipelinerun/PipelineRunOperationsTest.java
git commit -m "feat(pipeline): 工作区改为平台自建 PVC（创建/绑定/刷新/带守卫的删除）"
```

---

## Task 7: k8s-server —— 归档时的分叉（成功删卷、失败刷 TTL）

**Files:**
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/pipeline/PipelineRunFinisher.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/pipeline/PipelineRunFinisherTest.java`

**Interfaces:**
- Consumes: `PipelineRunOperations.workspaceClaimName(namespace, runName)`、`deleteWorkspacePvc(...)`、`refreshWorkspaceRetention(...)`

- [ ] **Step 1: 写失败测试**

在 `PipelineRunFinisherTest` 末尾加：

```java
    @Test
    void aSucceededRunReleasesItsWorkspaceSoNothingIsLeftBehind() {
        opsAre(ops);
        when(ops.snapshot("c1", "ns1", "r1", 1024)).thenReturn(snapshot("SUCCEEDED"));
        when(ops.workspaceClaimName("ns1", "r1")).thenReturn("platform-pipeline-ws-r1");

        assertThat(finisher.finish("c1", "ns1", "r1", false)).isTrue();

        verify(ops).deleteWorkspacePvc("ns1", "platform-pipeline-ws-r1");
        verify(ops, never()).refreshWorkspaceRetention(anyString(), anyString());
    }

    @Test
    void aFailedRunKeepsItsWorkspaceAndPushesTheTtlOut() {
        // ⭐ 删早了 = 断点再也补不了；所以失败/超时/取消只刷新保留时间
        opsAre(ops);
        when(ops.snapshot("c1", "ns1", "r1", 1024)).thenReturn(snapshot("FAILED"));
        when(ops.workspaceClaimName("ns1", "r1")).thenReturn("platform-pipeline-ws-r1");

        assertThat(finisher.finish("c1", "ns1", "r1", false)).isTrue();

        verify(ops).refreshWorkspaceRetention("ns1", "platform-pipeline-ws-r1");
        verify(ops, never()).deleteWorkspacePvc(anyString(), anyString());
    }

    @Test
    void aTimedOutRunKeepsItsWorkspaceToo() {
        opsAre(ops);
        when(ops.snapshot("c1", "ns1", "r1", 1024)).thenReturn(snapshot("RUNNING"));
        when(ops.workspaceClaimName("ns1", "r1")).thenReturn("platform-pipeline-ws-r1");

        assertThat(finisher.finish("c1", "ns1", "r1", true)).isTrue();

        verify(ops).refreshWorkspaceRetention("ns1", "platform-pipeline-ws-r1");
        verify(ops, never()).deleteWorkspacePvc(anyString(), anyString());
    }

    @Test
    void failingToDeleteTheWorkspaceDoesNotFailTheArchive() {
        // 对象与库都已经落定；删卷失败只是多占一会儿，不能因此把归档重投成死循环
        opsAre(ops);
        when(ops.snapshot("c1", "ns1", "r1", 1024)).thenReturn(snapshot("SUCCEEDED"));
        when(ops.workspaceClaimName("ns1", "r1")).thenReturn("platform-pipeline-ws-r1");
        doThrow(new RuntimeException("boom")).when(ops).deleteWorkspacePvc(anyString(), anyString());

        assertThat(finisher.finish("c1", "ns1", "r1", false)).isTrue();
    }
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl k8s-server -Dtest=PipelineRunFinisherTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL（`verify(...)` 没发生 / 方法不存在）

- [ ] **Step 3: 实现**

`PipelineRunFinisher.finish(...)` 在 `deleteObject` / `deleteCredsSecret` 之后追加：

```java
        releaseWorkspace(clusterId, namespace, runName, snapshot != null && "SUCCEEDED".equals(snapshot.status()));
        return true;
```

新增方法：

```java
    /**
     * 工作区的去处由**这次运行的结果**决定：
     * 成功 → 删（没人需要续跑了）；失败/超时/取消 → 保留并把 TTL 推到「现在 + retention」。
     *
     * <p>⚠️ 任何失败都只记 warn、不改归档结论 —— 对象已经删了、库已经写了，重投也修不了；
     * 代价只是多占一会儿配额，TTL 回收器会兜住。
     */
    private void releaseWorkspace(String clusterId, String namespace, String runName, boolean succeeded) {
        String claim;
        try {
            claim = runOps(clusterId).workspaceClaimName(namespace, runName);
        } catch (Exception e) {
            log.warn("读运行的工作区绑定失败（不影响归档）{}/{}", namespace, runName, e);
            return;
        }
        if (claim == null) {
            return;
        }
        try {
            if (succeeded) {
                runOps(clusterId).deleteWorkspacePvc(namespace, claim);
            } else {
                runOps(clusterId).refreshWorkspaceRetention(namespace, claim);
            }
        } catch (Exception e) {
            log.warn("处理工作区 PVC 失败（交给 TTL）{}/{}", namespace, claim, e);
        }
    }
```

⚠️ 调用顺序：`workspaceClaimName` 必须在 **`deleteObject` 之前**读（对象删了就读不到绑定）。所以把 `releaseWorkspace` 拆成「先读、后处理」：

```java
        String claim = claimOf(clusterId, namespace, runName);   // ← deleteObject 之前
        deleteObject(clusterId, namespace, runName);
        deleteCredsSecret(clusterId, namespace, runName);
        releaseWorkspace(clusterId, namespace, claim, snapshot != null && "SUCCEEDED".equals(snapshot.status()));
```

对应实现：

```java
    private String claimOf(String clusterId, String namespace, String runName) {
        try {
            return runOps(clusterId).workspaceClaimName(namespace, runName);
        } catch (Exception e) {
            log.warn("读运行的工作区绑定失败（不影响归档）{}/{}", namespace, runName, e);
            return null;
        }
    }

    private void releaseWorkspace(String clusterId, String namespace, String claim, boolean succeeded) {
        if (claim == null) {
            return;
        }
        try {
            if (succeeded) {
                runOps(clusterId).deleteWorkspacePvc(namespace, claim);
            } else {
                runOps(clusterId).refreshWorkspaceRetention(namespace, claim);
            }
        } catch (Exception e) {
            log.warn("处理工作区 PVC 失败（交给 TTL）{}/{}", namespace, claim, e);
        }
    }
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -pl k8s-server -Dtest=PipelineRunFinisherTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS（既有 + 新增 4 个）

- [ ] **Step 5: 提交**

```bash
git add k8s-server/src/main/java/com/coding/k8sserver/pipeline/PipelineRunFinisher.java \
        k8s-server/src/test/java/com/coding/k8sserver/pipeline/PipelineRunFinisherTest.java
git commit -m "feat(pipeline): 归档按结果处置工作区（成功删、失败刷 TTL）"
```

---

## Task 8: k8s-server —— 工作区 TTL 回收器

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/pipeline/PipelineWorkspaceReaper.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/pipeline/PipelineWorkspaceReaperTest.java`

**Interfaces:**
- Consumes: `KubernetesOperationsFactory.getAdminNamespacedOperation(ResourceType.PERSISTENT_VOLUME_CLAIM, clusterId)`；`PersistentVolumeClaimDTO` 的 `getAnnotations()/getCreationTime()/getStorage()`
- Produces: `static List<PersistentVolumeClaimDTO> expired(Instant now, List<PersistentVolumeClaimDTO> pvcs)`（纯函数，可单测）

- [ ] **Step 1: 写失败测试**

```java
package com.coding.k8sserver.pipeline;

import com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineWorkspaceReaperTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private static PersistentVolumeClaimDTO pvc(String name, String retainUntil) {
        PersistentVolumeClaimDTO dto = new PersistentVolumeClaimDTO();
        dto.setName(name);
        dto.setNamespace("ns1");
        dto.setAnnotations(retainUntil == null ? Map.of()
                : Map.of(PipelineWorkspaceReaper.RETAIN_UNTIL, retainUntil));
        return dto;
    }

    @Test
    void anExpiredWorkspaceIsReapable() {
        assertThat(PipelineWorkspaceReaper.expired(NOW, List.of(
                pvc("old", "2026-10-20T11:59:59Z"),
                pvc("fresh", "2026-10-20T12:00:01Z"))))
                .extracting(PersistentVolumeClaimDTO::getName)
                .containsExactly("old");
    }

    @Test
    void aWorkspaceWithoutAParsableDeadlineIsLeftAlone() {
        // 看不懂就不删 —— 猜错一次就是删掉一块别人正在等着的卷
        assertThat(PipelineWorkspaceReaper.expired(NOW, List.of(
                pvc("no-annotation", null),
                pvc("garbage", "tomorrow-ish"))))
                .isEmpty();
    }

    @Test
    void anEmptyListIsHarmless() {
        assertThat(PipelineWorkspaceReaper.expired(NOW, List.of())).isEmpty();
        assertThat(PipelineWorkspaceReaper.expired(NOW, null)).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl k8s-server -Dtest=PipelineWorkspaceReaperTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现**

```java
package com.coding.k8sserver.pipeline;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 保留中的工作区 PVC 的 TTL 回收。
 *
 * <p>只认**注解**、不查库：`retain-until` 在每次运行创建（含重试）时都会刷新，
 * 而平台侧的超时上限是 30 分钟、远小于 24 小时 —— 一块已过期的卷不可能还挂着活跃运行。
 * 这条推理要留着：以后会有人想在这里加一次 DB 查询。
 *
 * <p>⚠️ 只碰带 {@code platform.pipeline/role=workspace} 标签的卷（选择器 + 纯函数双重过滤）。
 */
@Slf4j
@Component
public class PipelineWorkspaceReaper {

    static final String RETAIN_UNTIL = "platform.pipeline/retain-until";
    private static final String ROLE_SELECTOR = "platform.pipeline/role=workspace";

    private final KubernetesOperationsFactory factory;
    private final K8sClusterMapper clusterMapper;

    public PipelineWorkspaceReaper(KubernetesOperationsFactory factory, K8sClusterMapper clusterMapper) {
        this.factory = factory;
        this.clusterMapper = clusterMapper;
    }

    @Scheduled(fixedDelayString = "${platform.pipeline.workspace.reap-interval:10m}")
    public void scheduledSweep() {
        sweep(Instant.now());
    }

    void sweep(Instant now) {
        List<K8sCluster> clusters;
        try {
            clusters = clusterMapper.listAll();
        } catch (Exception e) {
            log.warn("工作区回收：取集群清单失败（跳过这一拍）", e);
            return;
        }
        for (K8sCluster cluster : clusters) {
            // 一个集群连不上不能把其余集群带走（与启动扫描同形）
            try {
                reapOne(cluster.getClusterId(), now);
            } catch (Exception e) {
                log.warn("工作区回收失败：cluster={}", cluster.getClusterId(), e);
            }
        }
    }

    private void reapOne(String clusterId, Instant now) {
        NamespacedOperations<PersistentVolumeClaimDTO> ops =
                factory.getAdminNamespacedOperation(ResourceType.PERSISTENT_VOLUME_CLAIM, clusterId);
        List<PersistentVolumeClaimDTO> pvcs = ops.listAll(ROLE_SELECTOR, null);
        for (PersistentVolumeClaimDTO pvc : expired(now, pvcs)) {
            try {
                ops.delete(pvc.getNamespace(), pvc.getName());
                log.info("工作区已过期，删除：{}/{}", pvc.getNamespace(), pvc.getName());
            } catch (Exception e) {
                log.warn("删过期工作区失败（下一拍再试）：{}/{}", pvc.getNamespace(), pvc.getName(), e);
            }
        }
    }

    /** 纯函数：哪些卷已经过了保留期。看不懂的（缺注解/解析失败）一律留着。 */
    static List<PersistentVolumeClaimDTO> expired(Instant now, List<PersistentVolumeClaimDTO> pvcs) {
        if (pvcs == null || pvcs.isEmpty()) {
            return List.of();
        }
        List<PersistentVolumeClaimDTO> out = new ArrayList<>();
        for (PersistentVolumeClaimDTO pvc : pvcs) {
            Object raw = pvc.getAnnotations() == null ? null : pvc.getAnnotations().get(RETAIN_UNTIL);
            if (raw == null) {
                continue;
            }
            try {
                if (Instant.parse(String.valueOf(raw)).isBefore(now)) {
                    out.add(pvc);
                }
            } catch (Exception ignore) {
                // 解析不了的时间不是「过期」，是「不知道」——不删
            }
        }
        return out;
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -pl k8s-server -Dtest=PipelineWorkspaceReaperTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS（3 个测试）

- [ ] **Step 5: 提交**

```bash
git add k8s-server/src/main/java/com/coding/k8sserver/pipeline/PipelineWorkspaceReaper.java \
        k8s-server/src/test/java/com/coding/k8sserver/pipeline/PipelineWorkspaceReaperTest.java
git commit -m "feat(pipeline): 工作区 PVC 的 TTL 回收器"
```

---

## Task 9: platform-api —— 触发时支持屏蔽

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/PipelineRunService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/k8s/K8sPipelineClient.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/controllers/PipelineController.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/PipelineRunServiceTest.java`

**Interfaces:**
- Consumes: `PipelineCanvasMask.maskedClosure/trim`、`PipelineRunDTO.setWorkspacePvc`
- Produces:
  - `public String run(PipelineDTO pipeline, String user, PipelineRunContext ctx, List<String> maskedNodeIds)`
  - `public static String workspacePvcName(String runName)`（`platform-pipeline-ws-` + runName）
  - `K8sPipelineClient.createRun(clusterId, tenantId, namespace, runName, canvasJson, connections, workspacePvc, reuseWorkspace)`

- [ ] **Step 1: 改既有测试的桩签名，并加屏蔽相关测试**

`PipelineRunServiceTest` 里所有 `k8s.createRun(...)` 的 `when/verify` 改成 8 参（多一个 `anyString()` 与 `anyBoolean()`；import `org.mockito.ArgumentMatchers.anyBoolean`），并在末尾加：

```java
    @Test
    void theMaskedClosureIsPersistedAndTheTrimmedCanvasIsSent() {
        when(k8s.createRun(anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(new PipelineRunDTO());
        String canvas = """
                {"nodes":[
                  {"id":"a","type":"shell","config":{}},
                  {"id":"b","type":"shell","config":{}},
                  {"id":"c","type":"shell","config":{}}
                ],"edges":[{"from":"a","to":"b"},{"from":"b","to":"c"}]}""";

        // 只屏蔽 c（叶子）
        svc.run(pipeline(canvas), "alice", ctx, List.of("c"));

        ArgumentCaptor<PlatformPipelineRun> row = ArgumentCaptor.forClass(PlatformPipelineRun.class);
        verify(runMapper).insert(row.capture());
        assertThat(row.getValue().getMaskedNodesJson()).isEqualTo("[\"c\"]");
        assertThat(row.getValue().getCanvasJson()).as("库里存完整画布").isEqualTo(canvas);
        assertThat(row.getValue().getWorkspacePvc()).startsWith("platform-pipeline-ws-");

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(k8s).createRun(anyString(), any(), anyString(), anyString(), sent.capture(), any(), anyString(), anyBoolean());
        assertThat(sent.getValue()).doesNotContain("\"id\":\"c\"").contains("\"id\":\"a\"");
    }

    @Test
    void maskingAMiddleNodeDropsItsWholeSubtreeFromTheRun() {
        when(k8s.createRun(anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(new PipelineRunDTO());
        String canvas = """
                {"nodes":[{"id":"a","type":"shell","config":{}},
                          {"id":"b","type":"shell","config":{}},
                          {"id":"c","type":"shell","config":{}}],
                 "edges":[{"from":"a","to":"b"},{"from":"b","to":"c"}]}""";

        svc.run(pipeline(canvas), "alice", ctx, List.of("b"));

        ArgumentCaptor<PlatformPipelineRun> row = ArgumentCaptor.forClass(PlatformPipelineRun.class);
        verify(runMapper).insert(row.capture());
        assertThat(row.getValue().getMaskedNodesJson()).isEqualTo("[\"b\",\"c\"]");
    }

    @Test
    void maskingEveryRunnableNodeIsRefused() {
        String canvas = """
                {"nodes":[{"id":"a","type":"shell","config":{}}],"edges":[]}""";
        assertThatThrownBy(() -> svc.run(pipeline(canvas), "alice", ctx, List.of("a")))
                .isInstanceOf(CloudPlatformException.class);
        verify(runMapper, never()).insert(any());
    }

    @Test
    void connectionsAreResolvedFromTheTrimmedCanvasOnly() {
        // 被屏蔽节点引用的连接不该因为「这次不跑」还要求授权
        when(k8s.createRun(anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(new PipelineRunDTO());
        String canvas = """
                {"nodes":[
                  {"id":"a","type":"git","config":{"connectionId":"conn-1"}},
                  {"id":"b","type":"git","config":{"connectionId":"conn-2"}}
                ],"edges":[]}""";

        svc.run(pipeline(canvas), "alice", ctx, List.of("b"));

        verify(credentials).resolveForPipeline("conn-1", "GIT");
        verify(credentials, never()).resolveForPipeline("conn-2", "GIT");
    }

    @Test
    void noMaskMeansNoMaskedColumnAndTheFullCanvasGoesOut() {
        when(k8s.createRun(anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(new PipelineRunDTO());
        String canvas = """
                {"nodes":[{"id":"a","type":"shell","config":{}}],"edges":[]}""";

        svc.run(pipeline(canvas), "alice", ctx, List.of());

        ArgumentCaptor<PlatformPipelineRun> row = ArgumentCaptor.forClass(PlatformPipelineRun.class);
        verify(runMapper).insert(row.capture());
        assertThat(row.getValue().getMaskedNodesJson()).isNull();
    }
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl platform-api -am -Dtest=PipelineRunServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL（`run` 没有 4 参重载）

- [ ] **Step 3: 实现服务**

`PipelineRunService.run` 改为：

```java
    /** 兼容旧调用点：不屏蔽任何节点。 */
    public String run(PipelineDTO pipeline, String user, PipelineRunContext ctx) {
        return run(pipeline, user, ctx, List.of());
    }

    public String run(PipelineDTO pipeline, String user, PipelineRunContext ctx,
                      List<String> maskedNodeIds) {
        String canvas = pipeline.getCanvasJson();
        Set<String> masked = PipelineCanvasMask.maskedClosure(canvas, maskedNodeIds);
        // ⚠️ 落库的是**完整画布**（详情页要画全图）；发给 k8s-server 的是**裁剪后的子画布**。
        //    这两者从本批起有意不同，别以为写错了。
        String toRun = PipelineCanvasMask.trim(canvas, allBut(canvas, masked), null);
        List<com.coding.common.models.pipeline.PipelineConnectionRef> connections = resolveConnections(toRun);

        String runName = generateRunName(pipeline.getName());
        String workspacePvc = workspacePvcName(runName);

        PlatformPipelineRun row = new PlatformPipelineRun();
        row.setId(ULIDGenerator.generateULID());
        row.setPipelineId(pipeline.getId());
        row.setClusterId(ctx.clusterId());
        row.setNamespace(ctx.namespace());
        row.setCanvasJson(canvas);
        row.setRunName(runName);
        row.setWorkspacePvc(workspacePvc);
        row.setMaskedNodesJson(masked.isEmpty() ? null : jsonArray(masked));
        row.setAttemptNo(1);
        row.setStatus("RUNNING");
        row.setTriggeredBy(user);
        runMapper.insert(row);

        try {
            k8s.createRun(ctx.clusterId(), ctx.tenantId(), ctx.namespace(),
                    runName, toRun, connections, workspacePvc, false);
        } catch (RuntimeException e) {
            log.warn("创建 PipelineRun 失败，回滚刚落的库：{}", runName, e);
            runMapper.deleteById(row.getId());
            throw e;
        }
        return runName;
    }

    /** 平台给这条运行（链）定的工作区 PVC 名。 */
    public static String workspacePvcName(String runName) {
        return "platform-pipeline-ws-" + runName;
    }

    private static Set<String> allBut(String canvasJson, Set<String> masked) {
        Set<String> keep = new LinkedHashSet<>(PipelineCanvasMask.idsOf(PipelineCanvasMask.parse(canvasJson)));
        keep.removeAll(masked);
        return keep;
    }

    static String jsonArray(Collection<String> ids) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String id : ids) {
            if (!first) {
                sb.append(',');
            }
            sb.append('"').append(id).append('"');
            first = false;
        }
        return sb.append(']').toString();
    }
```

需要的 import：`Set`、`LinkedHashSet`、`Collection`（其余已有）。

- [ ] **Step 4: 改 client 签名**

`K8sPipelineClient.createRun` 加最后一个参数并塞进 DTO：

```java
    public PipelineRunDTO createRun(String clusterId, String tenantId, String namespace,
                                   String runName, String canvasJson,
                                   java.util.List<com.coding.common.models.pipeline.PipelineConnectionRef> connections,
                                   String workspacePvc, boolean reuseWorkspace) {
        PipelineRunDTO dto = new PipelineRunDTO();
        dto.setClusterId(clusterId);
        dto.setTenantId(tenantId);
        dto.setNamespace(namespace);
        dto.setName(runName);
        dto.setPipelineCanvasJson(canvasJson);
        dto.setConnections(connections);
        dto.setWorkspacePvc(workspacePvc);
        dto.setReuseWorkspace(reuseWorkspace);
        return k8s.create(dto);
    }
```

- [ ] **Step 5: 改 controller**

`/pipeline/run` 的入参换成新 record（字段名保持 `id`，审计 target 不变）：

```java
    /** 触发一次的入参：`id` 保持原名（审计与前端都在用）。 */
    public record RunRequest(String id, String tenantId, List<String> maskedNodeIds) {
    }

    @PostMapping("/run")
    @Operation(summary = "触发一次运行",
            description = "先生成 run 名并落库、再创建集群对象（幂等键从源头就有）。"
                    + "`maskedNodeIds` 里的节点**连同其全部下游**本次不执行；"
                    + "画布引用的每条连接都必须已授权，否则当场拒（**不静默降级成匿名**）")
    @Auditable(action = AuditAction.PIPELINE_RUN, target = "#request.id")
    public ResponseData<String> run(@RequestBody RunRequest request) {
        PipelineDTO pipeline = pipelineService.get(request.id());
        return new ResponseData<>(runService.run(pipeline, currentUser(),
                new PipelineRunContext(pipeline.getClusterId(), pipeline.getNamespace(),
                        auth.effectiveTenantId(request.tenantId())),
                request.maskedNodeIds() == null ? List.of() : request.maskedNodeIds()));
    }
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `mvn -q -pl platform-api -am -Dtest=PipelineRunServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS（既有 + 新增 5 个）

- [ ] **Step 7: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/PipelineRunService.java \
        platform-api/src/main/java/com/coding/platformapi/k8s/K8sPipelineClient.java \
        platform-api/src/main/java/com/coding/platformapi/controllers/PipelineController.java \
        platform-api/src/test/java/com/coding/platformapi/services/PipelineRunServiceTest.java
git commit -m "feat(pipeline): 触发运行支持按闭包屏蔽节点（落库存完整画布、发送子画布）"
```

---

## Task 10: platform-api —— 重试计划与从断点重跑

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/PipelineRunService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/controllers/PipelineController.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/PipelineRunServiceRetryTest.java`

**Interfaces:**
- Produces:
  - `public PipelineRetryPlanDTO retryPlan(String runName)`
  - `public String retry(String runName, String nodeId, String user, PipelineRunContext ctx)`
  - `static Map<String,String> effectiveStatus(PlatformPipelineRun row)`
  - `static String baseNameOfRun(String runName)`

- [ ] **Step 1: 写失败测试**

```java
package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.common.models.pipeline.PipelineRetryPlanDTO;
import com.coding.data.mapper.system.PlatformPipelineRunLogMapper;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.data.models.system.PlatformPipelineRun;
import com.coding.platformapi.k8s.K8sPipelineClient;
import com.coding.platformapi.models.PipelineRunContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 从断点继续：范围由**图**决定，不能由客户端挑集合。
 */
class PipelineRunServiceRetryTest {

    private final PlatformPipelineRunMapper runMapper = mock(PlatformPipelineRunMapper.class);
    private final PlatformPipelineRunLogMapper logMapper = mock(PlatformPipelineRunLogMapper.class);
    private final K8sPipelineClient k8s = mock(K8sPipelineClient.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final NamespaceAccessGuard access = mock(NamespaceAccessGuard.class);
    private final PipelineRunService svc =
            new PipelineRunService(runMapper, logMapper, k8s, credentials, access);

    private final PipelineRunContext ctx = new PipelineRunContext("c1", "ns1", "t1");

    /** git → a → b → c，b 失败、c 没轮到；a 成功。 */
    private static final String CHAIN = """
            {"nodes":[{"id":"git","type":"shell","config":{}},
                      {"id":"a","type":"shell","config":{}},
                      {"id":"b","type":"shell","config":{}},
                      {"id":"c","type":"shell","config":{}}],
             "edges":[{"from":"git","to":"a"},{"from":"a","to":"b"},{"from":"b","to":"c"}]}""";

    private PlatformPipelineRun parent(String status, String steps) {
        PlatformPipelineRun row = new PlatformPipelineRun();
        row.setId("run-parent");
        row.setPipelineId("p1");
        row.setClusterId("c1");
        row.setNamespace("ns1");
        row.setRunName("my-app-20261020-aaaa");
        row.setCanvasJson(CHAIN);
        row.setStatus(status);
        row.setStepsJson(steps);
        row.setWorkspacePvc("platform-pipeline-ws-my-app-20261020-aaaa");
        row.setAttemptNo(1);
        return row;
    }

    private void parentIs(PlatformPipelineRun row) {
        when(runMapper.selectByRunName(row.getRunName())).thenReturn(row);
    }

    @Test
    void thePlanOnlyLetsYouStartAtBreakpoints() {
        parentIs(parent("FAILED", "git=SUCCEEDED,a=SUCCEEDED,b=FAILED"));

        PipelineRetryPlanDTO plan = svc.retryPlan("my-app-20261020-aaaa");

        assertThat(plan.retryable()).isTrue();
        assertThat(plan.retryableNodes()).containsExactly("b");
        assertThat(plan.runNodeIds()).containsExactly("b", "c");
        assertThat(plan.blocked()).containsEntry("c", "上游 b 没有成功，请从它开始重试");
    }

    @Test
    void youCannotStartAtADescendantOfAFailedNode() {
        parentIs(parent("FAILED", "git=SUCCEEDED,a=SUCCEEDED,b=FAILED"));

        assertThatThrownBy(() -> svc.retry("my-app-20261020-aaaa", "c", "alice", ctx))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("上游 b 没有成功");
        verify(runMapper, never()).insert(any());
    }

    @Test
    void retryingABreakpointRunsItsWholeSubtreeAndReusesTheRest() {
        parentIs(parent("FAILED", "git=SUCCEEDED,a=SUCCEEDED,b=FAILED"));
        when(k8s.createRun(anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(new PipelineRunDTO());

        String runName = svc.retry("my-app-20261020-aaaa", "b", "alice", ctx);

        ArgumentCaptor<PlatformPipelineRun> row = ArgumentCaptor.forClass(PlatformPipelineRun.class);
        verify(runMapper).insert(row.capture());
        PlatformPipelineRun child = row.getValue();
        assertThat(child.getParentRunId()).isEqualTo("run-parent");
        assertThat(child.getAttemptNo()).isEqualTo(2);
        assertThat(child.getRetryFromNode()).isEqualTo("b");
        assertThat(child.getWorkspacePvc()).isEqualTo("platform-pipeline-ws-my-app-20261020-aaaa");
        assertThat(child.getReusedNodesJson()).isEqualTo("[\"git\",\"a\"]");
        assertThat(child.getCanvasJson()).as("库里仍存完整画布").isEqualTo(CHAIN);
        assertThat(runName).startsWith("my-app-").isNotEqualTo("my-app-20261020-aaaa");

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(k8s).createRun(anyString(), any(), anyString(), anyString(), sent.capture(), any(), anyString(), anyBoolean());
        assertThat(sent.getValue()).doesNotContain("\"id\":\"a\"")
                .contains("\"id\":\"b\"").contains("\"id\":\"c\"");
    }

    @Test
    void aSucceededRunCannotBeRetried() {
        parentIs(parent("SUCCEEDED", "git=SUCCEEDED,a=SUCCEEDED,b=SUCCEEDED,c=SUCCEEDED"));

        PipelineRetryPlanDTO plan = svc.retryPlan("my-app-20261020-aaaa");

        assertThat(plan.retryable()).isFalse();
        assertThat(plan.reason()).contains("工作区");
    }

    @Test
    void aRunFromBeforeThisFeatureCannotBeRetried() {
        PlatformPipelineRun row = parent("FAILED", "git=SUCCEEDED,a=SUCCEEDED,b=FAILED");
        row.setWorkspacePvc(null);
        parentIs(row);

        assertThat(svc.retryPlan("my-app-20261020-aaaa").retryable()).isFalse();
    }

    @Test
    void theEffectiveStatusIncludesReusedNodes() {
        PlatformPipelineRun row = parent("FAILED", "b=FAILED");
        row.setReusedNodesJson("[\"git\",\"a\"]");

        assertThat(PipelineRunService.effectiveStatus(row))
                .containsEntry("git", "SUCCEEDED").containsEntry("a", "SUCCEEDED")
                .containsEntry("b", "FAILED");
    }

    @Test
    void legacyStepKeysWithTheRunNamePrefixAreNormalized() {
        PlatformPipelineRun row = parent("FAILED",
                "my-app-20261020-aaaa-git=SUCCEEDED,my-app-20261020-aaaa-b=FAILED");

        assertThat(PipelineRunService.effectiveStatus(row))
                .containsEntry("git", "SUCCEEDED").containsEntry("b", "FAILED");
    }

    @Test
    void anUnfinishedFinallyNodeIsRetriedAloneAndAsANormalTask() {
        String canvas = """
                {"nodes":[{"id":"a","type":"shell","config":{}},
                          {"id":"fin","type":"shell","config":{},"finallyTask":true,"finallyWhen":"failed"}],
                 "edges":[]}""";
        PlatformPipelineRun row = parent("FAILED", "a=SUCCEEDED,fin=SKIPPED");
        row.setCanvasJson(canvas);
        parentIs(row);
        when(k8s.createRun(anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(new PipelineRunDTO());

        svc.retry("my-app-20261020-aaaa", "fin", "alice", ctx);

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(k8s).createRun(anyString(), any(), anyString(), anyString(), sent.capture(), any(), anyString(), anyBoolean());
        assertThat(sent.getValue()).doesNotContain("finallyTask").doesNotContain("finallyWhen");
    }

    @Test
    void aFailedCreateRollsBackTheNewRow() {
        parentIs(parent("FAILED", "git=SUCCEEDED,a=SUCCEEDED,b=FAILED"));
        when(k8s.createRun(anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyBoolean()))
                .thenThrow(new CloudPlatformException(com.coding.common.exception.EnumResponseType.ERROR, "boom"));

        assertThatThrownBy(() -> svc.retry("my-app-20261020-aaaa", "b", "alice", ctx));
        verify(runMapper).deleteById(any());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl platform-api -am -Dtest=PipelineRunServiceRetryTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL（方法不存在）

- [ ] **Step 3: 实现服务**

在 `PipelineRunService` 里加：

```java
    /**
     * 重试计划（只读）。**前端据此决定按钮与对话框内容** —— 断点规则不在这里重算。
     */
    public PipelineRetryPlanDTO retryPlan(String runName) {
        PlatformPipelineRun row = requireRun(runName);
        String blockedReason = retryBlockedReason(row);
        if (blockedReason != null) {
            return new PipelineRetryPlanDTO(false, blockedReason, List.of(), List.of(), List.of(), Map.of());
        }
        Map<String, String> status = effectiveStatus(row);
        Set<String> masked = parseIds(row.getMaskedNodesJson());
        PipelineCanvasMask.Plan plan = PipelineCanvasMask.plan(row.getCanvasJson(), status, masked);

        Set<String> reused = new LinkedHashSet<>();
        status.forEach((id, st) -> {
            if ("SUCCEEDED".equals(st) && !plan.runNodeIds().contains(id) && !masked.contains(id)) {
                reused.add(id);
            }
        });
        return new PipelineRetryPlanDTO(true, null,
                List.copyOf(plan.retryable()), List.copyOf(plan.runNodeIds()),
                List.copyOf(reused), plan.blocked());
    }

    /**
     * 从断点继续：新建一条只跑「所有断点子树」的运行，沿用父运行的工作区。
     *
     * <p>⚠️ 范围**由服务端算**；客户端只给「从哪个断点开始」，而且它必须在可点集合里。
     */
    public String retry(String runName, String nodeId, String user, PipelineRunContext ctx) {
        PlatformPipelineRun parent = requireRun(runName);
        String blockedReason = retryBlockedReason(parent);
        if (blockedReason != null) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID, blockedReason);
        }
        Map<String, String> status = effectiveStatus(parent);
        Set<String> masked = parseIds(parent.getMaskedNodesJson());
        PipelineCanvasMask.Plan plan = PipelineCanvasMask.plan(parent.getCanvasJson(), status, masked);
        if (!plan.retryable().contains(nodeId)) {
            String why = plan.blocked().get(nodeId);
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                    why == null ? "这个节点不能作为重试起点：" + nodeId : why);
        }

        boolean finallyTarget = PipelineCanvasMask.isFinally(parent.getCanvasJson(), nodeId);
        Set<String> toRun = finallyTarget ? Set.of(nodeId) : plan.runNodeIds();
        String trimmed = PipelineCanvasMask.trim(parent.getCanvasJson(), toRun,
                finallyTarget ? nodeId : null);
        List<com.coding.common.models.pipeline.PipelineConnectionRef> connections = resolveConnections(trimmed);

        Set<String> reused = new LinkedHashSet<>();
        status.forEach((id, st) -> {
            if ("SUCCEEDED".equals(st) && !toRun.contains(id) && !masked.contains(id)) {
                reused.add(id);
            }
        });

        String newRunName = generateRunName(baseNameOfRun(parent.getRunName()));
        PlatformPipelineRun row = new PlatformPipelineRun();
        row.setId(ULIDGenerator.generateULID());
        row.setPipelineId(parent.getPipelineId());
        row.setClusterId(parent.getClusterId());
        row.setNamespace(parent.getNamespace());
        row.setCanvasJson(parent.getCanvasJson());
        row.setRunName(newRunName);
        row.setParentRunId(parent.getId());
        row.setAttemptNo((parent.getAttemptNo() == null ? 1 : parent.getAttemptNo()) + 1);
        row.setRetryFromNode(nodeId);
        row.setMaskedNodesJson(masked.isEmpty() ? null : jsonArray(masked));
        row.setReusedNodesJson(reused.isEmpty() ? null : jsonArray(reused));
        row.setWorkspacePvc(parent.getWorkspacePvc());
        row.setStatus("RUNNING");
        row.setTriggeredBy(user);
        runMapper.insert(row);

        try {
            k8s.createRun(ctx.clusterId(), ctx.tenantId(), ctx.namespace(),
                    newRunName, trimmed, connections, parent.getWorkspacePvc(), true);
        } catch (RuntimeException e) {
            log.warn("创建重试运行失败，回滚刚落的库：{}", newRunName, e);
            runMapper.deleteById(row.getId());
            throw e;
        }
        return newRunName;
    }

    /** 整条运行能不能重试；能 → null，不能 → 原因。 */
    private static String retryBlockedReason(PlatformPipelineRun row) {
        if ("RUNNING".equals(row.getStatus())) {
            return "这条运行还在跑，等它结束之后再重试";
        }
        if ("SUCCEEDED".equals(row.getStatus())) {
            return "这次运行成功了 —— 工作区已释放，不能再从中间重试；要重跑就整条重来";
        }
        if (row.getWorkspacePvc() == null || row.getWorkspacePvc().isBlank()) {
            return "这条运行产生于工作区保留功能上线之前，没有可续跑的工作区；要重跑就整条重来";
        }
        return null;
    }

    /** 有效状态 = 本运行自己的结果 ∪ 沿用集合（沿用的一定是 SUCCEEDED）。 */
    static Map<String, String> effectiveStatus(PlatformPipelineRun row) {
        Map<String, String> out = new LinkedHashMap<>();
        String steps = row.getStepsJson();
        if (steps != null && !steps.isBlank()) {
            for (String part : steps.split(",")) {
                int at = part.indexOf('=');
                if (at <= 0) {
                    continue;
                }
                String id = nodeIdOf(part.substring(0, at).trim(), row.getRunName());
                String st = part.substring(at + 1).trim();
                if (id != null && !id.isBlank() && !st.isBlank()) {
                    out.put(id, st);
                }
            }
        }
        for (String id : parseIds(row.getReusedNodesJson())) {
            out.put(id, "SUCCEEDED");
        }
        return out;
    }

    /** 运行名的「流水线名部分」：`<name>-yyyyMMddHHmmss-xxxx` → `<name>`。 */
    static String baseNameOfRun(String runName) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.*)-\\d{14}-[a-z0-9]{4}$").matcher(runName == null ? "" : runName);
        return m.matches() ? m.group(1) : "pipeline";
    }

    /** `["a","b"]` → {"a","b"}；空/坏内容 → 空集合（库里的旧行可能没有这一列）。 */
    static Set<String> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            Set<String> out = new LinkedHashSet<>();
            for (JsonNode n : JSON.readTree(json)) {
                String id = n.asString("");
                if (id != null && !id.isBlank()) {
                    out.add(id);
                }
            }
            return out;
        } catch (Exception e) {
            return Set.of();
        }
    }
```

`JSON` 已存在于类里（`JsonMapper`）。import 加 `JsonNode`（已 import）、`Set`、`LinkedHashSet`（已有 `HashSet`/`Set`）。

- [ ] **Step 4: 加 controller 端点**

```java
    /** 从断点继续的入参。 */
    public record RetryRequest(String runName, String nodeId, String tenantId) {
    }

    /** 重试计划的入参（只读）。 */
    public record RetryPlanRequest(String runName, String tenantId) {
    }

    @PostMapping("/run/retry")
    @Operation(summary = "从断点继续重跑",
            description = "只能从**断点**开始（自己没成功、且全部上游都成功）；范围 = 所有断点的子树之并，"
                    + "由服务端按连线计算。沿用父运行的工作区，不重新克隆/构建已成功的部分")
    @Auditable(action = AuditAction.PIPELINE_RUN, target = "#request.runName")
    public ResponseData<String> retryRun(@RequestBody RetryRequest request) {
        PlatformPipelineRun run = runService.get(request.runName());
        return new ResponseData<>(runService.retry(request.runName(), request.nodeId(), currentUser(),
                contextOf(run, request.tenantId())));
    }

    @PostMapping("/run/retry/plan")
    @Operation(summary = "一次运行的重试计划",
            description = "哪些节点能点、点了会跑哪些、每个不可点节点的原因。前端据此决定按钮与确认框")
    public ResponseData<PipelineRetryPlanDTO> retryPlan(@RequestBody RetryPlanRequest request) {
        return new ResponseData<>(runService.retryPlan(request.runName()));
    }
```

import `PipelineRetryPlanDTO`。

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -pl platform-api -am -Dtest=PipelineRunServiceRetryTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS（9 个测试）

- [ ] **Step 6: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/PipelineRunService.java \
        platform-api/src/main/java/com/coding/platformapi/controllers/PipelineController.java \
        platform-api/src/test/java/com/coding/platformapi/services/PipelineRunServiceRetryTest.java
git commit -m "feat(pipeline): 从断点继续（计划端点 + 范围由服务端计算 + 血缘与沿用）"
```

---

## Task 11: platform-api —— 待重试工作区的查看与清理

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/audit/AuditAction.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/k8s/K8sPipelineClient.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/PipelineRunService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/controllers/PipelineController.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/PipelineRetryWorkspaceTest.java`

**Interfaces:**
- Consumes: `K8sClient.list/delete`（`PersistentVolumeClaimDTO`）
- Produces:
  - `PipelineRetryWorkspacesDTO retryWorkspaces(clusterId, namespace, tenantId)`
  - `void clearRetryWorkspaces(clusterId, namespace, tenantId)`
  - `AuditAction.PIPELINE_RETRY_WORKSPACE_CLEAR = "pipeline:retry-workspace-clear"`

- [ ] **Step 1: 写失败测试**

```java
package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.pipeline.PipelineRetryWorkspaceDTO;
import com.coding.data.mapper.system.PlatformPipelineRunLogMapper;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.platformapi.k8s.K8sPipelineClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineRetryWorkspaceTest {

    private final PlatformPipelineRunMapper runMapper = mock(PlatformPipelineRunMapper.class);
    private final PlatformPipelineRunLogMapper logMapper = mock(PlatformPipelineRunLogMapper.class);
    private final K8sPipelineClient k8s = mock(K8sPipelineClient.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final NamespaceAccessGuard access = mock(NamespaceAccessGuard.class);
    private final PipelineRunService svc =
            new PipelineRunService(runMapper, logMapper, k8s, credentials, access);

    @Test
    void theListCarriesCountAndTotalBytes() {
        when(k8s.listRetryWorkspaces("c1", "t1", "ns1")).thenReturn(List.of(
                new PipelineRetryWorkspaceDTO("ws-1", "r1", "2026-10-20T00:00:00Z",
                        "2026-10-21T00:00:00Z", 1024L),
                new PipelineRetryWorkspaceDTO("ws-2", "r2", "2026-10-20T01:00:00Z",
                        "2026-10-21T01:00:00Z", 2048L)));

        var out = svc.retryWorkspaces("c1", "ns1", "t1");

        assertThat(out.count()).isEqualTo(2);
        assertThat(out.totalBytes()).isEqualTo(3072L);
        assertThat(out.items()).hasSize(2);
    }

    @Test
    void clearingDeletesEveryListedWorkspace() {
        when(k8s.listRetryWorkspaces("c1", "t1", "ns1")).thenReturn(List.of(
                new PipelineRetryWorkspaceDTO("ws-1", "r1", null, null, 1024L)));

        svc.clearRetryWorkspaces("c1", "ns1", "t1");

        verify(k8s).deleteRetryWorkspace("c1", "t1", "ns1", "ws-1");
    }

    @Test
    void theNamespaceBoundaryIsCheckedFirst() {
        org.mockito.Mockito.doThrow(new CloudPlatformException(
                        com.coding.common.exception.EnumResponseType.NAMESPACE_NOT_ACCESSIBLE, "no"))
                .when(access).assertAccessible("c1", "ns1");

        assertThatThrownBy(() -> svc.retryWorkspaces("c1", "ns1", "t1"))
                .isInstanceOf(CloudPlatformException.class);
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl platform-api -am -Dtest=PipelineRetryWorkspaceTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL（方法不存在）

- [ ] **Step 3: 实现 client 两条方法**

`K8sPipelineClient`：

```java
    /** 待重试工作区：本命名空间里带平台工作区标签的 PVC（窄投影）。 */
    public List<PipelineRetryWorkspaceDTO> listRetryWorkspaces(String clusterId, String tenantId,
                                                               String namespace) {
        com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO query =
                new com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO();
        query.setClusterId(clusterId);
        query.setTenantId(tenantId);
        query.setNamespace(namespace);
        query.setLabelSelector("platform.pipeline/role=workspace");
        return k8s.list(query).stream().map(p -> new PipelineRetryWorkspaceDTO(
                p.getName(),
                p.getLabels() == null ? null : p.getLabels().get("platform.pipeline/run"),
                p.getCreationTime(),
                p.getAnnotations() == null ? null
                        : String.valueOf(p.getAnnotations().get("platform.pipeline/retain-until")),
                p.getStorage() == null ? 0L : p.getStorage().longValue())).toList();
    }

    /** 删一块待重试工作区（按 PVC 名）。 */
    public void deleteRetryWorkspace(String clusterId, String tenantId, String namespace, String name) {
        com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO dto =
                new com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO();
        dto.setClusterId(clusterId);
        dto.setTenantId(tenantId);
        dto.setNamespace(namespace);
        dto.setName(name);
        k8s.delete(dto);
    }
```

import `PipelineRetryWorkspaceDTO`。

- [ ] **Step 4: 服务两个方法**

```java
    /** 本命名空间里保留中的工作区（给列表页那一行）。 */
    public PipelineRetryWorkspacesDTO retryWorkspaces(String clusterId, String namespace, String tenantId) {
        access.assertAccessible(clusterId, namespace);
        List<PipelineRetryWorkspaceDTO> items = k8s.listRetryWorkspaces(clusterId, tenantId, namespace);
        long total = items.stream().mapToLong(PipelineRetryWorkspaceDTO::sizeBytes).sum();
        return new PipelineRetryWorkspacesDTO(items.size(), total, items);
    }

    /** 清掉全部待重试工作区。⚠️ 清掉之后那些运行就**不能**再从断点继续了。 */
    public void clearRetryWorkspaces(String clusterId, String namespace, String tenantId) {
        access.assertAccessible(clusterId, namespace);
        for (PipelineRetryWorkspaceDTO item : k8s.listRetryWorkspaces(clusterId, tenantId, namespace)) {
            k8s.deleteRetryWorkspace(clusterId, tenantId, namespace, item.name());
        }
    }
```

- [ ] **Step 5: 审计常量**

`AuditAction` 在 `PIPELINE_RUN_DELETE` 之后加：

```java
    /**
     * 清掉待重试工作区。
     *
     * <p>它值得进审计：清掉之后**那些运行就再也不能从断点继续了**，而用户看到的现象是
     * 「重试按钮突然报工作区已过期」—— 不记下来就只能猜是谁清的。
     */
    public static final String PIPELINE_RETRY_WORKSPACE_CLEAR = "pipeline:retry-workspace-clear";
```

- [ ] **Step 6: controller 两个端点**

```java
    /** 工作区端点的入参（与缓存清理同形）。 */
    public record RetryWorkspacesRequest(String clusterId, String namespace, String tenantId) {
    }

    @PostMapping("/retry-workspaces")
    @Operation(summary = "待重试工作区",
            description = "本命名空间里保留中的平台工作区 PVC（失败运行留下来的）；给列表页显示数量与占用")
    public ResponseData<PipelineRetryWorkspacesDTO> retryWorkspaces(
            @RequestBody RetryWorkspacesRequest request) {
        return new ResponseData<>(runService.retryWorkspaces(request.clusterId(), request.namespace(),
                auth.effectiveTenantId(request.tenantId())));
    }

    @PostMapping("/retry-workspaces/clear")
    @Operation(summary = "清理待重试工作区",
            description = "**不可恢复地**删掉这些卷：之后对应运行不能再从断点继续，只能整条重跑")
    @Auditable(action = AuditAction.PIPELINE_RETRY_WORKSPACE_CLEAR, target = "#request.namespace")
    public ResponseData<Void> clearRetryWorkspaces(@RequestBody RetryWorkspacesRequest request) {
        runService.clearRetryWorkspaces(request.clusterId(), request.namespace(),
                auth.effectiveTenantId(request.tenantId()));
        return new ResponseData<>();
    }
```

- [ ] **Step 7: 运行测试，确认通过**

Run: `mvn -q -pl platform-api -am -Dtest=PipelineRetryWorkspaceTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS（3 个测试）

- [ ] **Step 8: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/audit/AuditAction.java \
        platform-api/src/main/java/com/coding/platformapi/k8s/K8sPipelineClient.java \
        platform-api/src/main/java/com/coding/platformapi/services/PipelineRunService.java \
        platform-api/src/main/java/com/coding/platformapi/controllers/PipelineController.java \
        platform-api/src/test/java/com/coding/platformapi/services/PipelineRetryWorkspaceTest.java
git commit -m "feat(pipeline): 待重试工作区的查看与清理（含审计）"
```

---

## Task 12: 前端 —— types、api 与触发对话框

**Files:**
- Modify: `platform-web/src/types.ts`
- Modify: `platform-web/src/api/index.ts`
- Create: `platform-web/src/components/pipeline/PipelineRunDialog.vue`
- Modify: `platform-web/src/views/PipelineListView.vue`
- Modify: `platform-web/src/views/PipelineEditorView.vue`
- Modify: `platform-web/src/views/PipelineRunView.vue`

**Interfaces:**
- Produces:
  - `pipelineApi.run(id, tenantId?, maskedNodeIds?)`
  - `pipelineApi.retryPlan(runName, tenantId?)` → `PipelineRetryPlan`
  - `pipelineApi.retryRun(runName, nodeId, tenantId?)` → `string`
  - `pipelineApi.retryWorkspaces(clusterId, namespace, tenantId?)` / `clearRetryWorkspaces(...)`
  - `PipelineRun` 新增 `parentRunId / attemptNo / retryFromNode / maskedNodesJson / reusedNodesJson / workspacePvc`

- [ ] **Step 1: types**

`types.ts` 的 `PipelineRun` 末尾（`createdAt` 之前）加：

```ts
  /** 重试来源运行 id；null = 首跑 */
  parentRunId?: string | null
  /** 同一条重试链的第几次尝试；首跑 = 1 */
  attemptNo?: number | null
  /** 用户点的那个断点（画布节点 id） */
  retryFromNode?: string | null
  /** 本次运行屏蔽的节点（JSON 数组字符串，闭包展开后） */
  maskedNodesJson?: string | null
  /** 重试时沿用的节点（JSON 数组字符串） */
  reusedNodesJson?: string | null
  /** 本次运行（链）使用的共享工作区 PVC 名 */
  workspacePvc?: string | null
```

文件末尾（`PipelineStepRef` 之后）加：

```ts
/** 一次运行的重试计划 —— **按钮能不能点、点了会跑哪些，全看它**（后端算，前端不重算断点） */
export interface PipelineRetryPlan {
  retryable: boolean
  reason?: string | null
  /** 可以作为重试起点的节点（普通断点 + 没跑成的收尾节点） */
  retryableNodes: string[]
  /** 点普通断点时**会执行**的节点（所有断点的子树之并） */
  runNodeIds: string[]
  /** 沿用（上次成功、本次不跑）的节点 */
  reusedNodeIds: string[]
  /** 节点 id → 不能点的原因 */
  blocked: Record<string, string>
}

/** 一块「待重试工作区」（失败运行留下的平台工作区 PVC） */
export interface PipelineRetryWorkspace {
  name: string
  runName?: string | null
  createdAt?: string | null
  retainUntil?: string | null
  sizeBytes: number
}

export interface PipelineRetryWorkspaces {
  count: number
  totalBytes: number
  items: PipelineRetryWorkspace[]
}
```

- [ ] **Step 2: api**

`api/index.ts` 的 pipeline 块里替换 `run` 并追加：

```ts
  run: (pipelineId: string, tenantId?: string, maskedNodeIds?: string[]) =>
    http.post<never, string>('/pipeline/run', { id: pipelineId, tenantId, maskedNodeIds }),
  /** 重试计划（只读）：前端据此决定哪些节点有「重试」按钮 */
  retryPlan: (runName: string, tenantId?: string) =>
    http.post<never, PipelineRetryPlan>('/pipeline/run/retry/plan', { runName, tenantId }),
  /** 从断点继续；nodeId 必须是计划里的可重试节点 */
  retryRun: (runName: string, nodeId: string, tenantId?: string) =>
    http.post<never, string>('/pipeline/run/retry', { runName, nodeId, tenantId }),
  /** 待重试工作区（失败运行留下的卷） */
  retryWorkspaces: (clusterId: string, namespace: string, tenantId?: string) =>
    http.post<never, PipelineRetryWorkspaces>('/pipeline/retry-workspaces',
      { clusterId, namespace, tenantId }),
  /** 清理待重试工作区 —— **不可恢复**：之后那些运行不能再从断点继续 */
  clearRetryWorkspaces: (clusterId: string, namespace: string, tenantId?: string) =>
    http.post<never, void>('/pipeline/retry-workspaces/clear',
      { clusterId, namespace, tenantId }),
```

import 里加 `PipelineRetryPlan, PipelineRetryWorkspaces`。

- [ ] **Step 3: 触发对话框**

`components/pipeline/PipelineRunDialog.vue`：

```vue
<script setup lang="ts">
/**
 * 触发运行前的节点勾选：**取消勾选 = 本次屏蔽它（连同全部下游）**。
 *
 * ⚠️ 级联规则与后端 `PipelineCanvasMask.maskedClosure` 同一条：
 * 屏蔽必须连同下游。这里做它只是交互便利 —— 服务端会自己再展开一次，不信任提交的集合。
 */
import { computed, ref, watch } from 'vue'
import type { PipelineCanvasEdge, PipelineCanvasNode } from '@/types'

const props = defineProps<{
  modelValue: boolean
  nodes: PipelineCanvasNode[]
  edges: PipelineCanvasEdge[]
  title?: string
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'confirm', maskedNodeIds: string[]): void
}>()

const selected = ref<Set<string>>(new Set())

const parentsOf = computed(() => {
  const m = new Map<string, string[]>()
  for (const e of props.edges ?? []) {
    const list = m.get(e.to)
    if (list) list.push(e.from)
    else m.set(e.to, [e.from])
  }
  return m
})
const childrenOf = computed(() => {
  const m = new Map<string, string[]>()
  for (const e of props.edges ?? []) {
    const list = m.get(e.from)
    if (list) list.push(e.to)
    else m.set(e.from, [e.to])
  }
  return m
})

/** 拓扑序（Kahn）；收尾节点不参与连线，统一排在最后 */
const ordered = computed<PipelineCanvasNode[]>(() => {
  const nodes = props.nodes ?? []
  const byId = new Map(nodes.map((n) => [n.id, n]))
  const indeg = new Map<string, number>()
  for (const n of nodes) indeg.set(n.id, (parentsOf.value.get(n.id) ?? []).length)
  const queue = nodes.filter((n) => (indeg.get(n.id) ?? 0) === 0).map((n) => n.id)
  const out: string[] = []
  while (queue.length) {
    const id = queue.shift()!
    out.push(id)
    for (const next of childrenOf.value.get(id) ?? []) {
      const d = (indeg.get(next) ?? 0) - 1
      indeg.set(next, d)
      if (d === 0) queue.push(next)
    }
  }
  for (const n of nodes) if (!out.includes(n.id)) out.push(n.id)
  const normal = out.filter((id) => byId.get(id)?.finallyTask !== true)
  const fin = out.filter((id) => byId.get(id)?.finallyTask === true)
  return [...normal, ...fin].map((id) => byId.get(id)!)
})

watch(
  () => props.modelValue,
  (open) => {
    if (open) selected.value = new Set((props.nodes ?? []).map((n) => n.id))
  },
)

/** 上游被屏蔽了 → 自己也必须被屏蔽（勾选置灰且不可单独打开） */
function blockedByUpstream(id: string): boolean {
  return (parentsOf.value.get(id) ?? []).some((p) => !selected.value.has(p))
}

function uncheckWithDescendants(id: string) {
  const next = new Set(selected.value)
  const stack = [id]
  while (stack.length) {
    const cur = stack.pop()!
    next.delete(cur)
    stack.push(...(childrenOf.value.get(cur) ?? []))
  }
  selected.value = next
}

function toggle(node: PipelineCanvasNode, on: boolean) {
  if (on) {
    const next = new Set(selected.value)
    // 重新勾上时必须把上游也勾上（否则依赖不成立）
    const stack = [node.id]
    while (stack.length) {
      const cur = stack.pop()!
      next.add(cur)
      stack.push(...(parentsOf.value.get(cur) ?? []))
    }
    selected.value = next
  } else {
    uncheckWithDescendants(node.id)
  }
}

const runnableCount = computed(
  () => (props.nodes ?? []).filter((n) => selected.value.has(n.id) && n.finallyTask !== true).length,
)
const masked = computed(() => (props.nodes ?? []).filter((n) => !selected.value.has(n.id)))
const labelOf = (n: PipelineCanvasNode) => (n.name ?? '').trim() || n.id

function confirm() {
  emit('confirm', masked.value.map((n) => n.id))
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title ?? '触发运行'"
    width="560px"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <div class="hint">
      取消勾选 = <b>本次屏蔽它及它的全部下游</b>（定义不变）。默认全部运行。
    </div>
    <div class="list">
      <label v-for="n in ordered" :key="n.id" class="row" :class="{ blocked: blockedByUpstream(n.id) }">
        <el-checkbox
          :model-value="selected.has(n.id)"
          :disabled="blockedByUpstream(n.id)"
          @update:model-value="(v: boolean | string | number) => toggle(n, v === true)"
        />
        <span class="name">{{ labelOf(n) }}</span>
        <span v-if="n.finallyTask" class="tag">收尾</span>
        <span v-if="!n.finallyTask && blockedByUpstream(n.id)" class="why">上游已屏蔽</span>
      </label>
    </div>
    <div class="summary">
      本次运行 <b>{{ runnableCount }}</b> 个节点，屏蔽 <b>{{ masked.length }}</b> 个
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="runnableCount === 0" @click="confirm">触发</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  font-size: 12px;
  color: var(--text-3);
  margin-bottom: 10px;
}
.list {
  max-height: 46vh;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 6px 10px;
}
.row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 13px;
  color: var(--text-1);
}
.row.blocked .name {
  color: var(--text-3);
}
.name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tag {
  font-size: 10px;
  color: var(--warning);
  border: 1px solid var(--warning);
  border-radius: 999px;
  padding: 0 6px;
}
.why {
  font-size: 11px;
  color: var(--text-3);
}
.summary {
  margin-top: 10px;
  font-size: 12px;
  color: var(--text-2);
}
</style>
```

- [ ] **Step 4: 三个触发点接上对话框**

`PipelineListView.vue`：加状态与组件；行操作下拉里的 `run` 改成打开对话框：

```ts
import PipelineRunDialog from '@/components/pipeline/PipelineRunDialog.vue'
import type { PipelineCanvas } from '@/types'

const runDialogOpen = ref(false)
const runTarget = ref<Pipeline | null>(null)
const runCanvas = computed<PipelineCanvas>(() => {
  try {
    return JSON.parse(runTarget.value?.canvasJson ?? '{"nodes":[],"edges":[]}')
  } catch {
    return { nodes: [], edges: [] }
  }
})

function openRun(row: Pipeline) {
  runTarget.value = row
  runDialogOpen.value = true
}

async function confirmRun(maskedNodeIds: string[]) {
  const row = runTarget.value
  if (!row?.id) return
  try {
    const runName = await pipelineApi.run(row.id, state.tenantId ?? undefined, maskedNodeIds)
    ElMessage.success(`已触发：${runName}`)
    runDialogOpen.value = false
    toRuns(row.id)
  } catch {
    /* 拦截器已提示 */
  }
}
```

`onCommand` 里那一行改为：

```ts
    case 'run': openRun(row); break
```

（原来的 `async function run(row)` 删掉，避免两份触发路径。）

模板：在页面根节点内加：

```vue
    <PipelineRunDialog
      v-model="runDialogOpen"
      :nodes="runCanvas.nodes"
      :edges="runCanvas.edges"
      :title="`触发运行 · ${runTarget?.name ?? ''}`"
      @confirm="confirmRun"
    />
```

（原来的 `async function run(row)` 删掉，避免两份触发路径。）

`PipelineEditorView.vue`：`run()` 先保存再开对话框：

```ts
import PipelineRunDialog from '@/components/pipeline/PipelineRunDialog.vue'

const runDialogOpen = ref(false)
const savedId = ref('')

async function run() {
  const id = await save()
  if (!id) return
  savedId.value = id
  runDialogOpen.value = true
}

async function confirmRun(maskedNodeIds: string[]) {
  try {
    const runName = await pipelineApi.run(savedId.value, effectiveTenantId.value, maskedNodeIds)
    ElMessage.success(`已触发：${runName}`)
    runDialogOpen.value = false
    await router.push({ path: '/pipelines/runs', query: { id: savedId.value } })
  } catch {
    /* 拦截器已提示 */
  }
}
```

模板根节点内加（编辑器已有 `toCanvasNodes()` 与 `edges`，直接复用，**不要**另造一份图）：

```ts
const dialogNodes = computed(() => toCanvasNodes())
const dialogEdges = computed(() => edges.value.map((e) => ({ from: e.source, to: e.target })))
```

```vue
    <PipelineRunDialog
      v-model="runDialogOpen"
      :nodes="dialogNodes"
      :edges="dialogEdges"
      title="触发运行"
      @confirm="confirmRun"
    />
```

`PipelineRunView.vue`：同样接对话框（它已加载了 `pipeline.value.canvasJson`）：

```ts
import PipelineRunDialog from '@/components/pipeline/PipelineRunDialog.vue'

const runDialogOpen = ref(false)
const runCanvas = computed(() => {
  try {
    return JSON.parse(pipeline.value?.canvasJson ?? '{"nodes":[],"edges":[]}')
  } catch {
    return { nodes: [], edges: [] }
  }
})
function openRunDialog() {
  runDialogOpen.value = true
}
async function confirmRun(maskedNodeIds: string[]) {
  try {
    const runName = await pipelineApi.run(pipelineId.value, effectiveTenantId.value, maskedNodeIds)
    ElMessage.success(`已触发：${runName}`)
    runDialogOpen.value = false
    await load()
  } catch {
    /* 拦截器已提示 */
  }
}
```

模板里顶部的「运行」按钮改调 `openRunDialog`，并在页面内挂 `PipelineRunDialog`。

- [ ] **Step 5: 构建**

Run: `cd platform-web && npm run build`
Expected: 成功（无 TS 报错）

- [ ] **Step 6: 提交**

```bash
git add platform-web/src/types.ts platform-web/src/api/index.ts \
        platform-web/src/components/pipeline/PipelineRunDialog.vue \
        platform-web/src/views/PipelineListView.vue platform-web/src/views/PipelineEditorView.vue \
        platform-web/src/views/PipelineRunView.vue
git commit -m "feat(pipeline): 触发前勾选屏蔽节点（级联 + 三处入口共用）"
```

---

## Task 13: 前端 —— 运行详情的三种节点态、重试按钮与血缘

**Files:**
- Modify: `platform-web/src/components/pipeline/PipelineNodeVue.vue`
- Modify: `platform-web/src/views/PipelineRunDetailView.vue`

**Interfaces:**
- Consumes: `pipelineApi.retryPlan / retryRun`、`PipelineRetryPlan`

- [ ] **Step 1: 节点卡片加状态与重试按钮**

`PipelineNodeVue.vue` 的 `data` 增加：

```ts
    /** 本次重试时是否可点「重试」（来自 plan.retryableNodes） */
    retryable?: boolean
    /** 点重试的回调（放在 data 里是为了不把事件管道绕一圈） */
    onRetry?: (id: string) => void
```

状态表追加：

```ts
  // 三种「没跑」：屏蔽 / 沿用 / 本次未重试 —— 与运行详情那三行文案成对
  MASKED: 'skipped',
  REUSED: 'reused',
  NOT_RETRIED: 'skipped',
```

```ts
  MASKED: '已屏蔽',
  REUSED: '已沿用',
  NOT_RETRIED: '未重试',
```

模板里在 `pnode-status` 之后加：

```vue
    <button
      v-if="data.retryable"
      class="pnode-retry nodrag nopan"
      title="从断点继续：会跑这个节点及全部下游"
      @click.stop="data.onRetry?.(id)"
    >重试</button>
```

样式：

```css
.pnode-retry {
  position: absolute;
  right: 8px;
  bottom: -10px;
  padding: 1px 8px;
  font-size: 10px;
  border-radius: 999px;
  border: 1px solid var(--info);
  color: var(--info);
  background: var(--panel);
  cursor: pointer;
}
.pnode-retry:hover {
  background: color-mix(in srgb, var(--info) 12%, transparent);
}
/* 沿用：虚线 + 信息色 —— 与「失败/跳过」区分开 */
.pnode.reused {
  border-style: dashed;
  border-color: var(--info);
}
.pnode.reused .pnode-status {
  color: var(--info);
  border-color: var(--info);
  border-style: dashed;
}
```

- [ ] **Step 2: 运行详情接计划**

在 `PipelineRunDetailView.vue` 的 script 里加：

```ts
import type { PipelineRetryPlan } from '@/types'

const plan = ref<PipelineRetryPlan | null>(null)

/** 重试计划：按钮能不能点、点了会跑哪些，全听服务端的 */
async function loadPlan() {
  const r = run.value
  if (!r || !canRun.value || r.status === 'RUNNING' || r.status === 'SUCCEEDED') {
    plan.value = null
    return
  }
  try {
    plan.value = await pipelineApi.retryPlan(r.runName, effectiveTenantId.value)
  } catch {
    plan.value = null
  }
}

const maskedSet = computed(() => new Set<string>(parseIds(run.value?.maskedNodesJson)))
const reusedSet = computed(() => new Set<string>(parseIds(run.value?.reusedNodesJson)))
function parseIds(json: string | null | undefined): string[] {
  if (!json) return []
  try {
    const v = JSON.parse(json)
    return Array.isArray(v) ? v.filter((x) => typeof x === 'string') : []
  } catch {
    return []
  }
}
```

在加载运行的地方（`load()` 成功后）调用 `void loadPlan()`；运行结束后（轮询到终态时）再调一次。

- [ ] **Step 3: 节点状态合成三种新态**

把构建节点数据的 `status` 字段改为：

```ts
        status: maskedSet.value.has(n.id)
          ? 'MASKED'
          : st[n.id] ?? (reusedSet.value.has(n.id)
              ? 'REUSED'
              : (run.value?.parentRunId ? 'NOT_RETRIED' : undefined)),
        retryable: plan.value?.retryable === true
          ? plan.value.retryableNodes.includes(n.id)
          : false,
        onRetry: retryNode,
```

- [ ] **Step 4: 重试动作与确认框**

```ts
async function retryNode(nodeId: string) {
  const r = run.value
  const p = plan.value
  if (!r || !p) return
  const canvasNodes = canvas.value?.nodes ?? []
  const byId = new Map(canvasNodes.map((n) => [n.id, (n.name ?? '').trim() || n.id]))
  const target = canvasNodes.find((n) => n.id === nodeId)
  // 收尾节点只跑它自己；普通断点跑「所有断点的子树」（服务端算好的那一段）
  const willRun = target?.finallyTask ? [nodeId] : p.runNodeIds
  const runText = willRun.map((id) => byId.get(id) ?? id).join('、')
  const reuseText = p.reusedNodeIds.map((id) => byId.get(id) ?? id).join('、') || '无'

  await ElMessageBox.confirm(
    `将执行：${runText}\n沿用：${reuseText}\n\n`
      + '⚠️ 连线只表示执行顺序，不表示数据依赖；沿用的是上一次运行结束时的工作区状态。',
    '从断点继续',
    { type: 'warning', confirmButtonText: '继续', cancelButtonText: '算了' },
  )
  try {
    const newRunName = await pipelineApi.retryRun(r.runName, nodeId, effectiveTenantId.value)
    ElMessage.success(`已触发重试：${newRunName}`)
    await router.push({
      path: '/pipelines/run',
      query: { name: newRunName, id: r.pipelineId },
    })
  } catch {
    /* 拦截器已提示 */
  }
}
```

- [ ] **Step 5: 顶部血缘**

在运行详情标题区（运行名附近）加：

```vue
    <span v-if="(run?.attemptNo ?? 1) > 1" class="attempt">
      第 {{ run?.attemptNo }} 次尝试
      <a
        v-if="run?.parentRunId"
        href="javascript:void(0)"
        @click="void router.push({ path: '/pipelines/run', query: { name: parentRunName, id: run?.pipelineId } })"
      >· 源自上一次运行</a>
    </span>
```

其中：

```ts
/** 父运行的**名字**：链接要它（run_get 只给了 parentRunId） */
const parentRunName = ref('')
```

`load()` 里若 `run.value?.parentRunId` 存在，则用 `pipelineApi.runs(run.pipelineId)` 找到 `id === parentRunId` 的那一行取 `runName`（列表已经带了 id 与 runName）。

- [ ] **Step 6: 构建**

Run: `cd platform-web && npm run build`
Expected: 成功

- [ ] **Step 7: 提交**

```bash
git add platform-web/src/components/pipeline/PipelineNodeVue.vue \
        platform-web/src/views/PipelineRunDetailView.vue
git commit -m "feat(pipeline): 运行详情支持从断点重试（计划驱动 + 三种未跑态 + 血缘）"
```

---

## Task 14: 前端 —— 列表页的待重试工作区与运行历史徽标

**Files:**
- Modify: `platform-web/src/views/PipelineListView.vue`
- Modify: `platform-web/src/views/PipelineRunView.vue`

**Interfaces:**
- Consumes: `pipelineApi.retryWorkspaces / clearRetryWorkspaces`

- [ ] **Step 1: 列表页加工作区那一行**

`PipelineListView.vue` 在已有的「构建缓存」那一行下面加：

```ts
import type { PipelineRetryWorkspaces } from '@/types'

const retryWs = ref<PipelineRetryWorkspaces | null>(null)

async function loadRetryWorkspaces() {
  if (!clusterId.value || !namespace.value) {
    retryWs.value = null
    return
  }
  try {
    retryWs.value = await pipelineApi.retryWorkspaces(
      clusterId.value, namespace.value, state.tenantId ?? undefined)
  } catch {
    retryWs.value = null
  }
}

function fmtBytes(bytes: number): string {
  if (!bytes) return '0'
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
  let v = bytes
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(i === 0 ? 0 : 1)} ${units[i]}`
}

async function doClearRetryWorkspaces() {
  await ElMessageBox.confirm(
    `清理「${namespace.value}」里全部待重试工作区？\n\n`
      + '清掉之后，那些运行**不能再从断点继续**，只能整条重跑。',
    '确认清理',
    { type: 'warning', confirmButtonText: '清理', cancelButtonText: '算了' },
  )
  try {
    await pipelineApi.clearRetryWorkspaces(clusterId.value, namespace.value, state.tenantId ?? undefined)
    ElMessage.success('已清理')
    await loadRetryWorkspaces()
  } catch {
    /* 拦截器已提示 */
  }
}
```

`onMounted(load)` 之后加 `void loadRetryWorkspaces()`，`watch([clusterId, namespace], ...)` 里也调用。

模板（构建缓存那一行旁边）：

```vue
      <span v-if="retryWs && retryWs.count > 0" class="retry-ws">
        待重试工作区：{{ retryWs.count }} 块 / {{ fmtBytes(retryWs.totalBytes) }}
        <el-button link type="warning" size="small" @click="doClearRetryWorkspaces">全部清理</el-button>
      </span>
      <span v-else class="retry-ws">待重试工作区：无</span>
```

- [ ] **Step 2: 运行历史列表加「第 N 次」**

`PipelineRunView.vue` 的运行名单元格后加：

```vue
        <span v-if="(r.attemptNo ?? 1) > 1" class="attempt-tag">第 {{ r.attemptNo }} 次</span>
```

```css
.attempt-tag {
  margin-left: 6px;
  font-size: 10px;
  color: var(--info);
  border: 1px solid var(--info);
  border-radius: 999px;
  padding: 0 6px;
}
```

- [ ] **Step 3: 构建**

Run: `cd platform-web && npm run build`
Expected: 成功

- [ ] **Step 4: 提交**

```bash
git add platform-web/src/views/PipelineListView.vue platform-web/src/views/PipelineRunView.vue
git commit -m "feat(pipeline): 待重试工作区的展示与清理 + 运行历史尝试徽标"
```

---

## Task 15: 收口 —— 全量测试、构建与文档核对

**Files:**
- Modify: `docs/superpowers/specs/2026-10-20-pipeline-retry-and-mask-design.md`（只在与实现不符时改）

- [ ] **Step 1: 后端全量测试**

Run: `mvn -q test`
Expected: BUILD SUCCESS，所有模块测试全绿（重点看 `PipelineRunOperationsTest` / `PipelineRunFinisherTest` / `PipelineRunServiceTest` / 两个 `PipelineCanvasMask*Test`）

- [ ] **Step 2: 前端构建**

Run: `cd platform-web && npm run build`
Expected: 成功

- [ ] **Step 3: 对照 spec 的 §10 测试表逐条核对**

逐条确认：
- 画布裁剪：`PipelineCanvasMaskTest` + `PipelineCanvasMaskRetryTest`
- plan 与 retry 一致性：`PipelineRunServiceRetryTest.retryingABreakpointRunsItsWholeSubtreeAndReusesTheRest`
  （断言发送的子画布就是 `runNodeIds`）
- 重试服务：`PipelineRunServiceRetryTest` 九条
- steps_json 老键：`legacyStepKeysWithTheRunNamePrefixAreNormalized`
- k8s-core：`PipelineRunOperationsTest`
- Finisher：`PipelineRunFinisherTest`
- Reaper：`PipelineWorkspaceReaperTest`
- 迁移：手写 SQL，部署时重放（见 §13）
- 回归：本步与 Step 1

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "chore(pipeline): 节点重试与屏蔽收口（全量测试 + 前端构建）"
```

> ⚠️ 真集群验收（spec §13.1）不在本计划的可执行范围内：需要重放迁移、重建三个镜像、准备一条故意失败的流水线。部署时按 spec 的清单逐条勾。
