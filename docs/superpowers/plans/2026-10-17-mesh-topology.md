# 服务网格拓扑可视化（S2.4）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在「服务网格」下加一页，把集群里真实发生的调用关系画成有向图（节点=工作负载，边=谁调谁，粗细=QPS，颜色=错误率）。

**Architecture:** 后端在既有的 `metrics` 包上加"三条 PromQL + 一个把结果合并成图的服务方法 + 一个新端点"；前端在既有的 `echarts` 依赖上用 `graph` 力导向画布渲染。

**Tech Stack:** Spring Boot / Java 21 / MyBatis / Vue 3 + Element Plus + TypeScript / ECharts 5 / Thanos(PromQL) / JUnit5 + AssertJ + Mockito

**Spec:** `docs/superpowers/specs/2026-10-17-mesh-topology-design.md`

## Global Constraints

- **本仓没有 Flyway**：`db/migration/*.sql` 一律**手工重放**到库；MySQL 注释 `--` 后面**必须跟空白**。
- **每个端点一行权限**：新端点必须在 `platform_permission` 里有行，`PermissionCrossCheckRunner` 在缺行时 **fail-closed**（启动直接失败）。新权限行**必须显式绑角色**（不按 code 继承）。
- **凭证/敏感值绝不回显**（本批不涉及，但别顺手带出来）。
- 前端 `platform-web/src/api/index.ts` **可能有别的 session 在改** —— 只加自己的段落，不要重排/格式化整个文件。
- 提交只 `git add <具体文件>`，**不要 `git add <目录>`**（本 session 已两次把别的 session 的文件扫进提交）。

## Review Focus

Spec 是愿景文档，下列是它没写、但很容易做坏的地方，每一条都要有测试钉住：

1. **`reporter` 过滤** —— 照抄网上的 `reporter="destination"` 会查出**空图**（本集群没有 sidecar）。
2. **`l7Available` 判据** —— 用"边数为 0"还是"主查询一条序列都没有"？用错会把"网格里没流量"和"没接采集"混成一件事。
3. **`MeshNode.id` 的命名空间** —— 只用 workload 名会把不同命名空间的同名 workload 粘成一个节点。
4. **空态文案** —— 没数据时必须说"没在采集"，不能画空图。
5. **`hasL7=false` 的边** —— 三个数要显示"—"而不是 0（0 会被读成"没有错误"，是假信息）。

---

## File Structure

**后端（platform-api）**
- 改 `metrics/MetricQuery.java` —— 加 3 个 PromQL 常量
- 建 `metrics/labels/MeshEdgeLabels.java`、`MeshErrorLabels.java`、`MeshLatencyLabels.java`
- 建 `metrics/dto/MeshTopologyResponse.java`（含内嵌 `MeshNode` / `MeshEdge`）
- 改 `metrics/MetricsService.java` —— 加 `meshTopology(...)`
- 改 `controllers/MeshController.java` —— 加 `POST /mesh/topology`
- 建 `platform-data/.../db/migration/V2026_10_17_1__mesh_topology_permission.sql`

**前端（platform-web）**
- 改 `src/types.ts`（加类型）、`src/api/index.ts`（加调用）
- 建 `src/views/mesh/MeshTopologyView.vue`
- 改 `src/router/index.ts`、`src/layouts/MainLayout.vue`（路由 + 菜单）

---

### Task 1: 三条 PromQL 常量 + label 类

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/metrics/MetricQuery.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/metrics/labels/MeshEdgeLabels.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/metrics/labels/MeshErrorLabels.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/metrics/labels/MeshLatencyLabels.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/metrics/MeshTopologyQueryTest.java`

**Interfaces:**
- Produces: `MetricQuery.MESH_TOPOLOGY_EDGES` / `MESH_TOPOLOGY_ERRORS` / `MESH_TOPOLOGY_LATENCY`（`sql()` 返回含**一个** `%s` 的模板，参数是命名空间过滤片段）；label 类 `MeshEdgeLabels`（sourceWorkload/destinationWorkload/sourceNamespace/destinationNamespace）、`MeshErrorLabels`（+responseCode）、`MeshLatencyLabels`（+le）

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.platformapi.metrics;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 三条 PromQL 的形状 —— 钉住 2026-10-17 实测出来的那几个坑。 */
class MeshTopologyQueryTest {

    @Test
    void theEdgeQueryGroupsByBothEndsOfTheCall() {
        String sql = MetricQuery.MESH_TOPOLOGY_EDGES.sql();
        assertThat(sql).contains("istio_requests_total");
        assertThat(sql).contains("sum by (source_workload, destination_workload)");
        assertThat(sql).contains("rate(");
    }

    @Test
    void noQueryFiltersOnTheDestinationReporter() {
        // ⚠️ 实测：本集群只有 reporter=source(网关) 与 reporter=waypoint，
        // 照抄网上的 reporter="destination" 会查出空图。
        for (MetricQuery q : new MetricQuery[]{
                MetricQuery.MESH_TOPOLOGY_EDGES, MetricQuery.MESH_TOPOLOGY_ERRORS, MetricQuery.MESH_TOPOLOGY_LATENCY}) {
            assertThat(q.sql()).as(q.name()).doesNotContain("reporter=");
        }
    }

    @Test
    void theLatencyQueryUsesTheMillisecondsBucket() {
        // ⚠️ 实测：本集群没有 _seconds 版，也没有不带后缀的裸名。
        String sql = MetricQuery.MESH_TOPOLOGY_LATENCY.sql();
        assertThat(sql).contains("istio_request_duration_milliseconds_bucket");
        assertThat(sql).doesNotContain("duration_seconds");
        assertThat(sql).contains("histogram_quantile(0.95");
    }

    @Test
    void theErrorQueryFiltersOn5xx() {
        assertThat(MetricQuery.MESH_TOPOLOGY_ERRORS.sql()).contains("response_code=~\"5..\"");
    }

    @Test
    void eachTemplateTakesExactlyTwoSlots() {
        // 两个 %s：① 命名空间过滤片段（空串或 ,source_…="ns",destination_…="ns"）② 时间窗（如 300s）
        // 多一个少一个都会让 String.format 在运行期炸，所以钉住
        for (MetricQuery q : MetricQuery.values()) {
            if (!q.name().startsWith("MESH_TOPOLOGY")) continue;
            long slots = q.sql().chars().filter(c -> c == '%').count();
            assertThat(slots).as(q.name()).isEqualTo(2);
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl platform-api -am test -Dtest=MeshTopologyQueryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（`MESH_TOPOLOGY_EDGES` 不存在）

- [ ] **Step 3: 加三个常量**

在 `MetricQuery` 枚举里加（`sql()` 的模板风格与既有常量一致）。**每个模板恰好两个 `%s`**：
① 命名空间过滤片段 ② 时间窗。

```java
    /** 拓扑的边 + QPS：一条序列 = 一条边。 */
    MESH_TOPOLOGY_EDGES("sum by (source_workload, destination_workload, source_workload_namespace, destination_workload_namespace) (rate(istio_requests_total%s[%s]))", MeshEdgeLabels.class),
    /** 同为边，但只看 5xx —— 与上面相除得错误率。 */
    MESH_TOPOLOGY_ERRORS("sum by (source_workload, destination_workload, source_workload_namespace, destination_workload_namespace) (rate(istio_requests_total{response_code=~\"5..\"%s}[%s]))", MeshErrorLabels.class),
    /** 边的 P95 延迟（毫秒）。 */
    MESH_TOPOLOGY_LATENCY("histogram_quantile(0.95, sum by (le, source_workload, destination_workload, source_workload_namespace, destination_workload_namespace) (rate(istio_request_duration_milliseconds_bucket%s[%s])))", MeshLatencyLabels.class),
```

⚠️ 命名空间过滤片段本身**不放进方括号里**，而是拼在花括号**内部**（`istio_requests_total{...%s}`）——
所以它必须以 `,` 开头（有过滤时）或为空串（无过滤时），参数顺序**先过滤片段、后时间窗**。

- [ ] **Step 4: 加三个 label 类**

按 `MetricLabels` 里写明的约定（多词标签用 `@JsonProperty` 显式映射）：

```java
package com.coding.platformapi.metrics.labels;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** 拓扑的边：两端的工作负载 + 命名空间。⚠️ 命名空间必须带 —— 同名 workload 跨命名空间很常见。 */
@Data
public class MeshEdgeLabels extends MetricLabels {
    @JsonProperty("source_workload") private String sourceWorkload;
    @JsonProperty("destination_workload") private String destinationWorkload;
    @JsonProperty("source_workload_namespace") private String sourceNamespace;
    @JsonProperty("destination_workload_namespace") private String destinationNamespace;
}
```

`MeshErrorLabels extends MeshEdgeLabels` 加 `@JsonProperty("response_code") private String responseCode;`；
`MeshLatencyLabels extends MeshEdgeLabels` 加 `@JsonProperty("le") private String le;`。

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -pl platform-api -am test -Dtest=MeshTopologyQueryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/metrics/MetricQuery.java platform-api/src/main/java/com/coding/platformapi/metrics/labels/Mesh*Labels.java platform-api/src/test/java/com/coding/platformapi/metrics/MeshTopologyQueryTest.java
git commit -m "feat(mesh): 拓扑的三条 PromQL + label 类（实测出来的坑钉进测试）"
```

---

### Task 2: 把三条查询合并成图

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/metrics/dto/MeshTopologyResponse.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/metrics/MetricsService.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/MeshTopologyServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的三个常量与 label 类；既有 `ThanosQueryClient.query(sql, time, labelClass)`
- Produces:
  ```java
  record MeshTopologyResponse(List<MeshNode> nodes, List<MeshEdge> edges, int windowSeconds, boolean l7Available)
  record MeshNode(String id, String namespace, String workload, boolean gateway)
  record MeshEdge(String source, String target, double rps, double errorRate, Double p95Ms, boolean hasL7)
  // MetricsService:
  public MeshTopologyResponse meshTopology(String clusterId, String namespace, int windowSeconds)
  ```

- [ ] **Step 1: 写失败的测试**（**纯合并逻辑**，Thanos 客户端 mock 掉）

测试要覆盖的（每一条对应 Review Focus 里的一条）：

```java
    @Test void edgesBecomeNodesAndEdgesWithQps()
    @Test void theErrorRateIsErrorsOverTotal()
    @Test void aMissingLatencySeriesMeansNullNotZero()      // p95Ms == null，不是 0
    @Test void aMissingErrorSeriesMeansZeroErrorsNotNoEdge() // 有边、无 5xx → errorRate 0、hasL7 true
    @Test void noSeriesAtAllMeansL7Unavailable()             // l7Available == false
    @Test void theSameWorkloadNameInTwoNamespacesStaysTwoNodes()   // id = ns + "/" + workload
    @Test void theGatewayIsFlaggedAsGateway()
    @Test void theNamespaceFilterIsPassedThrough()           // 断言发出去的 sql 带上了过滤片段
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl platform-api -am test -Dtest=MeshTopologyServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（`meshTopology` 不存在）

- [ ] **Step 3: 实现**

要点（写代码时按这个来）：
1. 命名空间过滤片段：空 → `""`；有值 → `,source_workload_namespace="<ns>",destination_workload_namespace="<ns>"`（**两端都滤**：只滤一端会漏掉"从别处进来"的边）。
2. 三条查询**并发**发出（`CompletableFuture`）；延迟那条**失败/为空不算错**，只让 `p95Ms = null`。
3. `id = namespace + "/" + workload`。
4. `gateway = "istio-system".equals(namespace)`（外部网关就在那儿）。
5. `errorRate = errors / (rps + errors)`？ ❌ **不对** —— 错误查询是**总量里筛 5xx 的子集**，所以分母是 `rps`（总量），`errorRate = errors / rps`，且要防除零。
6. `l7Available = !edges.isEmpty()`（**主查询**为空才是"没接采集"）。
7. 节点集合由边两端推出来（不额外查）。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl platform-api -am test -Dtest=MeshTopologyServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 5: 变异验证**（本 session 的规矩：新测试要证明会咬）

逐条改坏再改回来，确认对应测试变红：`errorRate` 的分母换成 `rps+errors`、`id` 去掉命名空间、`l7Available` 改成 `edges.size() > 1`。

- [ ] **Step 6: 提交**

---

### Task 3: 端点 + 权限行 + 重放迁移

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/controllers/MeshController.java`
- Create: `platform-data/src/main/resources/db/migration/V2026_10_17_1__mesh_topology_permission.sql`

- [ ] **Step 1: 写迁移**（含端点行 + **显式绑角色**）

```sql
-- 网格拓扑（只读）：复用 tenant:gateway:list，与 metrics 端点复用 :get 同一套路
INSERT INTO platform_permission (id, domain, resource, method, code, description) VALUES
 ('perm_mesh_topology','API','/mesh/topology','POST','tenant:gateway:list','服务网格拓扑（只读，共享网关 list 动词）');
-- ⚠️ 新权限行必须显式绑角色，不按 code 继承
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_mesh_topology_admin','builtin_role_admin','perm_mesh_topology'),
 ('rp_mesh_topology_tenant_admin','builtin_role_tenant_admin','perm_mesh_topology');
```

- [ ] **Step 2: 重放到库**（本仓没有 Flyway）

用 jshell + JDBC 跑（见 `s01` 记忆里的配方），或任何能连 `192.168.31.80:1234/k8s_cloud_platform` 的客户端。
⚠️ MySQL 注释 `--` 后面**必须跟空白**。

- [ ] **Step 3: 加端点**

```java
    @PostMapping("/topology")
    @Operation(summary = "服务网格拓扑", description = "返回节点与边（QPS/错误率/P95）。窗口内一条序列都没有时 l7Available=false")
    public ResponseData<MeshTopologyResponse> topology(@RequestBody MeshTopologyRequest request) {
        return new ResponseData<>(metricsService.meshTopology(
                request.clusterId(), request.namespace(), request.windowSeconds() == null ? 300 : request.windowSeconds()));
    }
```

- [ ] **Step 4: 起 platform-api 验证 `PermissionCrossCheckRunner` 不报错**

Run: `mvn -pl platform-api -am test` 然后本地起一次（端点↔权限行机械对一遍，缺行会 fail-closed）
Expected: 启动通过

- [ ] **Step 5: 手工打一次端点**，确认返回那 5 条边
- [ ] **Step 6: 提交**

---

### Task 4: 前端类型 + api + 路由菜单

**Files:** `src/types.ts`、`src/api/index.ts`、`src/router/index.ts`、`src/layouts/MainLayout.vue`

⚠️ 本仓有**两套码**，别写混：
- `pageCodes.*`（如 `pageCodes.mesh.gateway.list` = `tenant:page:gateway.list`）—— **页面可见性**，路由的 `requiresPerm` 与菜单的 `v-if` 用它
- `apiCodes.*`（如 `apiCodes.gatewayList` = `tenant:gateway:list`）—— **接口权限**，决定"发不发这个请求"

**本页复用既有的码，不新造页面码**（同 S3.5 那条先例：流水线四个路由共用一个页面码）——
能看 Gateway 的人就能看拓扑，合理。

- [ ] **Step 1: 类型**（`src/types.ts`，与后端 record 一一对应）

```ts
/** 拓扑里的一个节点。⚠️ id = `命名空间/工作负载` —— 同名 workload 跨命名空间很常见，只存 workload 名会把两条边粘成一个节点。 */
export interface MeshTopologyNode { id: string; namespace: string; workload: string; gateway: boolean }
/** 拓扑里的一条边。⚠️ hasL7=false 时 rps/errorRate 无意义，UI 要显示「—」而不是 0（0 会被读成"没有错误"）。 */
export interface MeshTopologyEdge {
  source: string; target: string; rps: number; errorRate: number; p95Ms?: number | null; hasL7: boolean
}
export interface MeshTopology { nodes: MeshTopologyNode[]; edges: MeshTopologyEdge[]; windowSeconds: number; l7Available: boolean }
```

- [ ] **Step 2: api**（`src/api/index.ts`，只加自己这一段，别重排文件）

```ts
  /** 服务网格拓扑。⚠️ l7Available=false 表示这个集群没在采集网格指标，不是"没有流量"。 */
  meshTopology: (clusterId: string, namespace?: string, windowSeconds?: number) =>
    http.post<never, MeshTopology>('/mesh/topology', { clusterId, namespace, windowSeconds }),
```

- [ ] **Step 3: 路由**（`src/router/index.ts`，插在 mesh 那几条旁边）

```ts
        { path: 'mesh/topology', name: 'mesh-topology', component: () => import('@/views/mesh/MeshTopologyView.vue'),
          meta: { title: '网格拓扑', group: '服务网格', context: 'full', requiresPerm: [pageCodes.mesh.gateway.list] } },
```

- [ ] **Step 4: 菜单**（`src/layouts/MainLayout.vue`，加在「服务网格」组内）

```html
        <el-menu-item v-if="can(pageCodes.mesh.gateway.list)" index="/mesh/topology">
          <el-icon><Share /></el-icon><template #title>网格拓扑</template>
        </el-menu-item>
```

（顺带把组头那个 `v-if` 加上新入口用的同一个码 —— 它本来就已经包含 `pageCodes.mesh.gateway.list`，所以不用改。）

- [ ] **Step 5: 跑 `npm run type-check` 确认过**
- [ ] **Step 6: 提交**（只 `git add` 这四个文件）

---

### Task 5: 拓扑页（ECharts graph）

**Files:** Create `src/views/mesh/MeshTopologyView.vue`

- [ ] **Step 1: 页面骨架**：顶部（命名空间 / 时间窗 / 刷新）+ 图容器 + 右侧抽屉
- [ ] **Step 2: ECharts option**

```ts
/** 边宽 ∝ log10(rps)。**必须取对数**：实测有一条 26 万次的边，
 *  线性的结果是它一条撑满、其余全被压成一根线。 */
function edgeWidth(rps: number): number {
  return 1 + 5 * Math.min(1, Math.log10(Math.max(rps, 0) + 1) / 6)
}

/** 错误率分档：0 绿 / >0 黄 / >5% 红。 */
function edgeColor(errorRate: number): string {
  if (errorRate > 0.05) return 'var(--danger, #f56c6c)'
  if (errorRate > 0) return 'var(--warning, #e6a23c)'
  return 'var(--success, #67c23a)'
}

/** ⚠️ hasL7=false 的边要显示「—」而不是 0 —— 0 会被读成"没有错误"，那是假信息。 */
function num(v: number | null | undefined, digits = 2): string {
  return v == null ? '—' : v.toFixed(digits)
}

const option = computed<EChartsOption>(() => ({
  tooltip: {
    formatter: (p: any) => {
      const d = p.data
      if (d.source !== undefined) {
        return `${d.sourceName} → ${d.targetName}<br/>QPS ${num(d.rps)}<br/>错误率 ${d.hasL7 ? (d.errorRate * 100).toFixed(1) + '%' : '—'}<br/>P95 ${num(d.p95Ms, 0)} ms`
      }
      return `${d.workload}<br/>命名空间 ${d.namespace}`
    },
  },
  series: [{
    type: 'graph',
    layout: 'force',
    roam: true,                    // 可缩放拖拽（查看态，不需要"摆位"，所以不用 VueFlow）
    draggable: true,
    force: { repulsion: 300, edgeLength: [90, 220], gravity: 0.08 },
    label: { show: true, position: 'right', formatter: (p: any) => p.data.workload },
    edgeSymbol: ['none', 'arrow'],
    edgeSymbolSize: 7,
    lineStyle: { curveness: 0.12 }, // 双向调用时两条线不会叠在一起
    data: nodes.value,              // { id, workload, namespace, symbol:'diamond'|'circle', symbolSize, itemStyle }
    links: links.value,             // { source, target, rps, errorRate, p95Ms, hasL7, lineStyle:{width,color} }
  }],
}))
```

节点侧：`symbolSize = 18 + min(度数 * 2, 18)`；`gateway: true` 的用 `symbol: 'diamond'` 配 `--accent` 色
（它是「外部入口」，不是业务节点，视觉上要能一眼分出来）。
- [ ] **Step 3: 空态** —— `l7Available=false` 时**不画图**，显示：
  「这个集群没在采集网格指标 —— 怎么接见集群前提 §1.7」+ 一个「仍要试试」的按钮
- [ ] **Step 4: 覆盖范围说明**（页面上常驻一段小字，内容照 spec §4 那段抄）
- [ ] **Step 5: 30 秒自动刷新**（`onUnmounted` 清掉定时器；页面不可见时不刷）
- [ ] **Step 6: 提交**

---

### Task 6: 浏览器冒烟 + 真集群核对

**Files:** 临时 `src/../smoke-mesh-topology.ts` + `.html`（**用完删掉，不进仓库** —— 本仓不保存烟台）

- [ ] **Step 1: 假数据冒烟**：断言渲染出 N 条边、空态文案、`hasL7=false` 显示"—"、错误率颜色分档
- [ ] **Step 2: 变异**：把空态判断改坏 → 对应断言应变红
- [ ] **Step 3: 真集群核对**（spec §5 四条验收）：
  1. 打开应看到那 5 条边
  2. 摘掉 `movie-service` 的 `use-waypoint` → 那条边变 `hasL7=false`
  3. 没接采集的集群 → 明确提示
  4. `movie-web → movie-service` 的错误率显示 **100%**
- [ ] **Step 4: 把结果写进提交信息，删掉烟台文件**

---

## 收尾

- 台账 S2.4 那一行改「✅ 已实施待部署」，记下要重建哪几个服务。
- 更新 `docs/development/cluster-prerequisites.md` 里指向 S2.4 的那句（如果有）。
