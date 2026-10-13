# 租户概览（Tenant Overview）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给租户一个跨其全部集群的概览（配额 / 健康 / 用量），并把"命名空间的行"做成一个作用域对齐、三处共用的数据面。

**Architecture:** 缓存按作用域拆两层 —— `cluster:agg:<clusterId>` 只放集群级事实，`ns:<clusterId>:<ns>` 放该命名空间**一切**（行 + 异常 Pod + 不健康工作负载）。装配只有一处（复用既有 `ClusterAggregationOperations`，改为按命名空间切片）；采样有两条路（集群 9 次 list / 单命名空间 7 次 in-namespace list），喂同一个装配函数。范围、合并、缓存、权限、阈值全在 platform-api。

**Tech Stack:** Java 21 / Spring Boot 4 / fabric8 7.x / MyBatis / Flyway / Redis（`StringRedisTemplate`）/ Vue 3 + Element Plus + ECharts；测试 JUnit 5 + AssertJ + Mockito（前端无测试框架，靠预览实测）。

**Spec:** `docs/superpowers/specs/2026-10-12-tenant-overview-design.md`

## Global Constraints

- **口径唯一**：任何页面的合计由**本次拿到的 ns 切片**合并现算；不另存总计值；同一屏必须满足"合计 = Σ明细"。
- **作用域对齐**：集群键只放集群级事实（节点/存储/命名空间索引/`sampledAt`）；带 `namespace` 字段的一切进 ns 键。
- **一个事实一个键**：同一事实不允许存在两个读路径。
- **缓存失败不致命**：Redis 读失败退回直采、写失败只记日志、只缓存非 null 结果（`RedisJsonCache` 既有语义，不得改成抛错）。
- **k8s-server 不得知道**：租户、分配表（除边界校验这一处既定用途）、缓存、阈值。
- **不许并行扇出跨集群调用**：`K8sServerGateway` 透传 token 靠 `RequestContextHolder`（线程绑定），换线程即 401。顺序调。
- **新增 platform-api 端点必须有权限行**（`PermissionCrossCheckRunner` 启动期 fail-closed），迁移先于新构建重放。
- **时间戳**：`sampledAt` = epoch 毫秒（`long`）。
- **命名**：迁移 `V2026_10_12_2__tenant_overview_permissions.sql`；键 `cluster:agg:` / `ns:`（`RedisJsonCache` 自动加 `platform:cache:` 前缀）。
- **不回退既有行为**：改造期间 `ClusterAggregationOperationsTest`（24 例）、`ClusterServiceTest`（8 例）、`RedisJsonCacheTest`（6 例）必须全程绿。

## 与 spec 的一处偏差（本文件为准）

spec §4 写的是"抽 `NamespaceStatAssembler` 纯函数"。落地改为 **复用既有的 `ClusterAggregationOperations.aggregate(ClusterObjects, now)`（改成 `static`，并按命名空间切片）** —— 它本来就只依赖静态辅助函数、不碰 client，抽新类等于把同一段逻辑抄一遍。不变式（两条采样路径同输入同行）**由构造保证**，单测仍要钉住它。

## 文件结构

| 文件 | 责任 |
|---|---|
| `platform-common/.../dto/NamespaceSnapshotDTO.java`（新） | 一个命名空间的切片：行 + 异常 Pod（计数/分组/名单）+ 不健康工作负载（计数/名单） |
| `k8s-core/.../operations/core/ClusterAggregationOperations.java`（改） | 加 `namespaceSnapshots(...)`（按 ns 切片）；`aggregate(...)` 改 static 并由切片派生全局结果 |
| `k8s-core/.../operations/core/NamespaceStatOperations.java`（新） | 单命名空间采样（7 次 in-namespace list → 同一个装配） |
| `k8s-core/.../factory/KubernetesOperationsFactory.java`（改） | `getNamespaceStatOperation(clusterId, tenantId, adminMode)` |
| `k8s-server/.../controllers/namespace/NamespaceStatController.java`（新） | `GET /namespacestats/{namespace}`，TENANT 边界 |
| `platform-api/.../cache/RedisJsonCache.java`（改） | 加批量读（MGET）与批量写（pipeline） |
| `platform-api/.../k8s/K8sNamespaceStatClient.java`（新） | 一方法一端点 |
| `platform-api/.../services/NamespaceStatService.java`（新） | **ns 键的唯一读写入口**：`row` / `rows`（含页内一致性阈值） |
| `platform-api/.../services/ClusterService.java`（改） | 集群键瘦身 + 切片写 ns 键；`overview`/`resourceBreakdown` 由行现算 |
| `platform-api/.../services/QuotaClassifier.java`（新） | 配额三档判定（纯函数，三处共用） |
| `platform-api/.../services/TenantOverviewService.java`（新） | 范围（分配表）+ 合并 + 降级 |
| `platform-api/.../controllers/TenantOverviewController.java`（新） | 4 个端点（`/tenant/overview*`） |
| `platform-api/.../controllers/NamespaceController.java`（改） | +1 端点 `/namespace/stat/get` |
| `platform-api/.../metrics/MetricsService.java`（改） | +租户合计曲线（按 ns 过滤后求和） |
| `platform-web/src/components/tenant/TenantOverviewPanel.vue`（新） | 租户概览面板 |
| `platform-web/src/components/TenantDetailPanel.vue`（改） | 概览 tab（首个、lazy） |
| `platform-web/src/views/TenantView.vue`（改） | 抽屉 760 → 1040 |
| `platform-web/src/views/NamespaceDetailView.vue`（改） | 概览 tab 改读 `/namespace/stat/get` |
| `platform-web/src/views/ClusterDetailView.vue`（改） | 资源明细 tab 加状态列 |
| `platform-data/.../V2026_10_12_2__tenant_overview_permissions.sql`（新） | 9 行权限（4 端点 × 2 码 + 1）+ 角色绑定 |

---

## Phase 1 — 采样与装配（k8s-core / k8s-server）

### Task 1: 按命名空间切片的装配

**Files:**
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/NamespaceSnapshotDTO.java`
- Modify: `k8s-core/src/main/java/com/coding/k8score/operations/core/ClusterAggregationOperations.java`
- Test: `k8s-core/src/test/java/com/coding/k8score/operations/core/NamespaceSnapshotsTest.java`

**Interfaces:**
- Produces:
  - `NamespaceSnapshotDTO { NamespaceStatDTO stat; int abnormalPodCount; Map<String,Integer> abnormalPodReasonCounts; List<AbnormalPodDTO> abnormalPods; int unhealthyWorkloadCount; List<UnhealthyWorkloadDTO> unhealthyWorkloads; long sampledAt; }`
  - `static Map<String, NamespaceSnapshotDTO> ClusterAggregationOperations.namespaceSnapshots(ClusterObjects objects, long nowEpochSecond)`（`TreeMap`，按 ns 升序）
  - `static ClusterAggregateDTO ClusterAggregationOperations.aggregate(ClusterObjects objects, long nowEpochSecond)`（**签名不变，改为 static**；全局结果由切片派生）

- [ ] **Step 1: 写失败测试**

```java
// NamespaceSnapshotsTest.java
class NamespaceSnapshotsTest {

    private Node node(String name) {
        return new NodeBuilder().withNewMetadata().withName(name).endMetadata().build();
    }

    private Pod crashing(String ns, String name) {
        return new PodBuilder()
                .withNewMetadata().withNamespace(ns).withName(name).endMetadata()
                .withNewStatus().withPhase("Running")
                .addNewContainerStatus().withName("c").withReady(false).withRestartCount(7)
                .withNewState().withNewWaiting().withReason("CrashLoopBackOff").endWaiting().endState()
                .endContainerStatus()
                .endStatus().build();
    }

    private ClusterObjects objects(List<Pod> pods, List<Deployment> deps) {
        return new ClusterObjects(pods, deps, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(node("n1")));
    }

    @Test
    void snapshots_are_partitioned_by_namespace_with_exact_counts() {
        var objects = objects(
                List.of(crashing("team-a", "p1"), crashing("team-a", "p2"), crashing("team-b", "p3")),
                List.of());

        Map<String, NamespaceSnapshotDTO> snaps = ClusterAggregationOperations.namespaceSnapshots(objects, 1_700_000_000L);

        assertThat(snaps.keySet()).containsExactly("team-a", "team-b");
        // 每 ns 的计数是全量、不是封顶后的条数
        assertThat(snaps.get("team-a").getAbnormalPodCount()).isEqualTo(2);
        assertThat(snaps.get("team-a").getAbnormalPodReasonCounts()).containsEntry("CrashLoopBackOff", 2);
        assertThat(snaps.get("team-a").getAbnormalPods()).hasSize(2);
        assertThat(snaps.get("team-b").getAbnormalPodCount()).isEqualTo(1);
        assertThat(snaps.get("team-a").getStat().getPodCount()).isEqualTo(2);
    }

    @Test
    void empty_namespace_produces_no_snapshot() {
        assertThat(ClusterAggregationOperations.namespaceSnapshots(objects(List.of(), List.of()), 1L)).isEmpty();
    }

    @Test
    void aggregate_matches_the_sum_of_snapshots() {
        var objects = objects(List.of(crashing("team-a", "p1"), crashing("team-b", "p2")), List.of());

        ClusterAggregateDTO agg = ClusterAggregationOperations.aggregate(objects, 1_700_000_000L);

        assertThat(agg.getAbnormalPodTotal()).isEqualTo(2);
        assertThat(agg.getAbnormalPodReasonCounts()).containsEntry("CrashLoopBackOff", 2);
        assertThat(agg.getNamespaces()).extracting(NamespaceStatDTO::getNamespace)
                .containsExactly("team-a", "team-b");
        assertThat(agg.getTotal().getNamespaceCount()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl k8s-core -am test -Dtest=NamespaceSnapshotsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— `NamespaceSnapshotDTO` 与 `namespaceSnapshots` 不存在

- [ ] **Step 3: 建 DTO**

```java
// NamespaceSnapshotDTO.java
/**
 * 一个命名空间的**全部事实**（租户概览/集群明细/命名空间页共用的一行 + 它的异常与不健康清单）。
 * <p>作用域判据：凡带 namespace 的东西都归这里（异常 Pod、不健康工作负载都带），集群键里不放。
 */
@Data
public class NamespaceSnapshotDTO {
    private NamespaceStatDTO stat;
    /** 该 ns 异常 Pod 总数（**全量**计数，不是名单条数） */
    private int abnormalPodCount;
    /** 该 ns 的 reason 分组计数（同样全量） */
    private Map<String, Integer> abnormalPodReasonCounts;
    /** 名单（沿用既有的 severity 排序 + 每 ns 封顶 ABNORMAL_POD_CAP） */
    private List<AbnormalPodDTO> abnormalPods;
    private int unhealthyWorkloadCount;
    private List<UnhealthyWorkloadDTO> unhealthyWorkloads;
    /** 采样时刻：epoch 毫秒（缓存的陈旧上界与页内一致性阈值都基于它） */
    private long sampledAt;
}
```

- [ ] **Step 4: 在 `ClusterAggregationOperations` 里加切片 + 让 `aggregate` 由切片派生**

把现有 `aggregate(ClusterObjects, long)` 的 per-ns 累加改造为：**先按 ns 分桶，再逐 ns 装配**。要点（照此改，别重写整类）：

```java
    /** 按命名空间切片：一个 ns 一份，含它自己的行/异常/不健康（TreeMap = 按 ns 升序）。 */
    public static Map<String, NamespaceSnapshotDTO> namespaceSnapshots(ClusterObjects objects, long nowEpochSecond) {
        Map<String, List<Pod>> podsByNs = new TreeMap<>();
        for (Pod pod : orEmpty(objects.pods())) {
            String ns = namespaceOf(pod);
            if (ns != null) {
                podsByNs.computeIfAbsent(ns, k -> new ArrayList<>()).add(pod);
            }
        }
        Map<String, List<Deployment>> depByNs = groupByNamespace(objects.deployments());
        Map<String, List<StatefulSet>> stsByNs = groupByNamespace(objects.statefulSets());
        Map<String, List<DaemonSet>> dsByNs = groupByNamespace(objects.daemonSets());
        Map<String, List<Service>> svcByNs = groupByNamespace(objects.services());
        Map<String, List<ResourceQuota>> quotaByNs = groupByNamespace(objects.quotas());
        Map<String, List<PersistentVolumeClaim>> pvcByNs = groupByNamespace(objects.pvcs());

        Map<String, List<UnhealthyWorkloadDTO>> unhealthyByNs = new TreeMap<>();
        collectUnhealthy(depByNs, KIND_DEPLOYMENT, unhealthyByNs);
        collectUnhealthy(stsByNs, KIND_STATEFULSET, unhealthyByNs);

        Map<String, NamespaceSnapshotDTO> out = new TreeMap<>();
        for (String ns : unionKeys(podsByNs, depByNs, stsByNs, dsByNs, svcByNs, quotaByNs, pvcByNs)) {
            NamespaceSnapshotDTO s = new NamespaceSnapshotDTO();
            s.setStat(rowOf(ns, podsByNs, depByNs, stsByNs, dsByNs, svcByNs, quotaByNs, pvcByNs, nowEpochSecond));
            s.setAbnormalPods(abnormalOf(podsByNs.get(ns), nowEpochSecond));   // 复用既有排序 + 封顶
            s.setAbnormalPodCount(orEmpty(podsByNs.get(ns)).size() == 0 ? 0 : countAbnormal(podsByNs.get(ns)));
            s.setAbnormalPodReasonCounts(reasonCounts(s.getAbnormalPods()));
            s.setUnhealthyWorkloads(sortAndCapWorkloads(unhealthyByNs.getOrDefault(ns, List.of())));
            s.setUnhealthyWorkloadCount(unhealthyByNs.getOrDefault(ns, List.of()).size());
            s.setSampledAt(nowEpochSecond * 1000L);
            out.put(ns, s);
        }
        return out;
    }

    static ClusterAggregateDTO aggregate(ClusterObjects objects, long nowEpochSecond) {
        Map<String, NamespaceSnapshotDTO> byNs = namespaceSnapshots(objects, nowEpochSecond);
        // 全局的异常/不健康 = 各切片合并后再沿用既有排序+封顶；计数与 reason 分组是切片求和
        List<AbnormalPodDTO> abnormal = byNs.values().stream().flatMap(s -> s.getAbnormalPods().stream()).toList();
        ... // 其余（nodes / PV / totalOf / pvc 状态计数）保持原样，只是 per-ns 数值从 byNs 取
    }
```

辅助方法（`groupByNamespace` / `collectUnhealthy` / `unionKeys` / `rowOf` / `countAbnormal`）都写成 `private static`，放在同一类里；`rowOf` 就是把现在散在 `aggregate` 里的 `NsAcc` 累加逻辑收进"单个 ns 的输入"这一个函数。

- [ ] **Step 5: 跑新测试 + 既有回归**

Run: `mvn -o -pl k8s-core test -Dtest='NamespaceSnapshotsTest+ClusterAggregationOperationsTest' -Dsurefire.failIfNoSpecifiedTests=false`
（拿不到 `+` 语法就分两次跑）
Expected: 全绿。**既有 24 例是这次重构的回归底线** —— 有任何一例红就说明全局口径被改动了，改回实现而不是改测试。

- [ ] **Step 6: 提交**

```bash
git add platform-common/src/main/java/com/coding/common/models/k8s/dto/NamespaceSnapshotDTO.java \
        k8s-core/src/main/java/com/coding/k8score/operations/core/ClusterAggregationOperations.java \
        k8s-core/src/test/java/com/coding/k8score/operations/core/NamespaceSnapshotsTest.java
git commit -m "feat(tenant-overview): 集群聚合按命名空间切片（NamespaceSnapshotDTO）"
```

### Task 2: 单命名空间采样

**Files:**
- Create: `k8s-core/src/main/java/com/coding/k8score/operations/core/NamespaceStatOperations.java`
- Modify: `k8s-core/src/main/java/com/coding/k8score/factory/KubernetesOperationsFactory.java`
- Test: `k8s-core/src/test/java/com/coding/k8score/operations/core/NamespaceStatOperationsTest.java`

**Interfaces:**
- Consumes: `ClusterAggregationOperations.namespaceSnapshots(ClusterObjects, long)`、`ClusterObjects`
- Produces:
  - `NamespaceSnapshotDTO NamespaceStatOperations.row(String namespace)`
  - `NamespaceStatOperations KubernetesOperationsFactory.getNamespaceStatOperation(String clusterId, String tenantId, boolean adminMode)`

- [ ] **Step 1: 写失败测试**

```java
class NamespaceStatOperationsTest {

    /** 不触网：只钉"喂进去同一批对象，单 ns 路径与集群路径产出的切片逐字段相同" */
    @Test
    void single_namespace_row_equals_the_slice_of_the_cluster_path() {
        Pod p = new PodBuilder().withNewMetadata().withNamespace("team-a").withName("p1").endMetadata()
                .withNewStatus().withPhase("Pending").endStatus().build();
        Deployment d = new DeploymentBuilder().withNewMetadata().withNamespace("team-a").withName("web").endMetadata()
                .withNewSpec().withReplicas(2).endSpec()
                .withNewStatus().withReplicas(2).withReadyReplicas(1).endStatus().build();

        long now = 1_700_000_000L;
        ClusterObjects clusterWide = new ClusterObjects(List.of(p), List.of(d), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
        NamespaceSnapshotDTO fromCluster = ClusterAggregationOperations.namespaceSnapshots(clusterWide, now).get("team-a");

        NamespaceSnapshotDTO fromSingle = ClusterAggregationOperations
                .namespaceSnapshots(new ClusterObjects(List.of(p), List.of(d), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of()), now).get("team-a");

        assertThat(fromSingle).usingRecursiveComparison().isEqualTo(fromCluster);
    }

    @Test
    void empty_namespace_yields_a_zero_row_not_null() {
        // 该 ns 一个对象都没有：仍要回一行（全零、quota 为 null），否则前端拿不到"这个 ns 存在"
        NamespaceSnapshotDTO snap = NamespaceStatOperations.emptySnapshot("empty-ns", 1_700_000_000_000L);

        assertThat(snap.getStat().getNamespace()).isEqualTo("empty-ns");
        assertThat(snap.getStat().getPodCount()).isZero();
        assertThat(snap.getStat().getQuotaCpuHard()).isNull();
        assertThat(snap.getAbnormalPodCount()).isZero();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl k8s-core test -Dtest=NamespaceStatOperationsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现**

```java
/**
 * 单个命名空间的行采样：该 ns 内 7 次 list（pods / 3 种 workload / services / resourcequotas / PVC），
 * 装配复用 {@link ClusterAggregationOperations#namespaceSnapshots} —— 与集群概览**同一个函数**，
 * 这是"两条采样路径不会漂移"的机制保证（不是靠人工对齐两段代码）。
 * <p>不采 PV / Node：它们是集群级事实，不属于任何命名空间。
 */
@RequiredArgsConstructor
public class NamespaceStatOperations {

    /** 与集群聚合同一族：pods + 3 workload + services + resourcequotas + pvc（PV/Node 属集群级，不取） */
    private final KubernetesClient client;

    public NamespaceSnapshotDTO row(String namespace) {
        if (!StringUtils.hasText(namespace)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        ClusterObjects objects = new ClusterObjects(
                client.pods().inNamespace(namespace).list().getItems(),
                client.apps().deployments().inNamespace(namespace).list().getItems(),
                client.apps().statefulSets().inNamespace(namespace).list().getItems(),
                client.apps().daemonSets().inNamespace(namespace).list().getItems(),
                client.services().inNamespace(namespace).list().getItems(),
                client.resourceQuotas().inNamespace(namespace).list().getItems(),
                client.persistentVolumeClaims().inNamespace(namespace).list().getItems(),
                List.of(), List.of());
        return ClusterAggregationOperations.namespaceSnapshots(objects, Instant.now().getEpochSecond())
                .getOrDefault(namespace, emptySnapshot(namespace, System.currentTimeMillis()));
    }

    /** 空命名空间也要有一行（全零、配额 null）—— "这个 ns 存在但什么都没跑" 是合法状态，不是缺数 */
    public static NamespaceSnapshotDTO emptySnapshot(String namespace, long sampledAtMillis) {
        NamespaceSnapshotDTO s = new NamespaceSnapshotDTO();
        NamespaceStatDTO stat = new NamespaceStatDTO();
        stat.setNamespace(namespace);
        s.setStat(stat);
        s.setAbnormalPodReasonCounts(new LinkedHashMap<>());
        s.setAbnormalPods(List.of());
        s.setUnhealthyWorkloads(List.of());
        s.setSampledAt(sampledAtMillis);
        return s;
    }
}
```

工厂（放在 `getNodeOperation` 附近，注释同款）：

```java
    /** 单命名空间采样（租户可达）：client 按访问模式选，同 getWorkloadOperation。 */
    public NamespaceStatOperations getNamespaceStatOperation(String clusterId, String tenantId, boolean adminMode) {
        KubernetesClient client = adminMode
                ? clientFactory.getAdminClient(clusterId)
                : clientFactory.getTenantClient(clusterId, tenantId);
        return new NamespaceStatOperations(client);
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -pl k8s-core test -Dtest=NamespaceStatOperationsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（3 例）

- [ ] **Step 5: 提交**

```bash
git add k8s-core/src/main/java/com/coding/k8score/operations/core/NamespaceStatOperations.java \
        k8s-core/src/main/java/com/coding/k8score/factory/KubernetesOperationsFactory.java \
        k8s-core/src/test/java/com/coding/k8score/operations/core/NamespaceStatOperationsTest.java
git commit -m "feat(tenant-overview): 单命名空间采样（复用集群装配函数）"
```

### Task 3: k8s-server 端点（TENANT 边界）

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/controllers/namespace/NamespaceStatController.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/controllers/namespace/NamespaceStatControllerTest.java`

**Interfaces:**
- Consumes: `ResourceAccessResolver.resolveNamespacedAccess(explicitTenantId, clusterId, namespace)` → `AccessContext(tenantId, adminMode)`；`KubernetesOperationsFactory.getNamespaceStatOperation(...)`
- Produces: `GET /namespacestats/{namespace}?clusterId[&tenantId]` → `ResponseData<NamespaceSnapshotDTO>`

- [ ] **Step 1: 写失败测试**

```java
/** 边界与身份解析是本端点的全部内容：租户 token 用 token 租户、admin 代操作要显式 tenantId —— 都交给 resolveNamespacedAccess。 */
class NamespaceStatControllerTest {

    private final KubernetesOperationsFactory factory = mock(KubernetesOperationsFactory.class);
    private final ResourceAccessResolver resolver = mock(ResourceAccessResolver.class);
    private final NamespaceStatOperations ops = mock(NamespaceStatOperations.class);

    @Test
    void resolves_namespaced_access_then_samples_that_namespace() {
        when(resolver.resolveNamespacedAccess(null, "c1", "team-a")).thenReturn(new AccessContext("t1", false));
        when(factory.getNamespaceStatOperation("c1", "t1", false)).thenReturn(ops);
        when(ops.row("team-a")).thenReturn(NamespaceStatOperations.emptySnapshot("team-a", 1L));

        var controller = new NamespaceStatController(factory, resolver);
        var resp = controller.stat("team-a", null, "c1");

        assertThat(resp.getData().getStat().getNamespace()).isEqualTo("team-a");
        verify(resolver).resolveNamespacedAccess(null, "c1", "team-a");
        verify(factory).getNamespaceStatOperation("c1", "t1", false);
    }

    @Test
    void admin_delegation_uses_explicit_tenant_and_admin_client() {
        when(resolver.resolveNamespacedAccess("t9", "c1", "team-a")).thenReturn(new AccessContext("t9", true));
        when(factory.getNamespaceStatOperation("c1", "t9", true)).thenReturn(ops);
        when(ops.row("team-a")).thenReturn(NamespaceStatOperations.emptySnapshot("team-a", 1L));

        new NamespaceStatController(factory, resolver).stat("team-a", "t9", "c1");

        verify(factory).getNamespaceStatOperation("c1", "t9", true);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl k8s-server -am test -Dtest=NamespaceStatControllerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（controller 不存在）

- [ ] **Step 3: 实现（参照 `NodeAddressController` 的独立 controller 写法，但边界是 TENANT）**

```java
/**
 * 命名空间域 - 单个命名空间的行 + 该 ns 的异常/不健康清单（`GET /namespacestats/{namespace}`）。
 * <p>边界 {@link AccessBoundary#TENANT}：身份解析走 {@code resolveNamespacedAccess} ——
 * 租户 token 以 claim 为唯一身份源、admin 代操作须显式 tenantId 且必须是平台侧身份，
 * 两者都要过 (tenantId, clusterId, namespace) 分配表三元组。故本端点**一次只回答一个命名空间**，
 * 范围由上游 platform-api 决定（不提供批量入参：批量要么逐项再校验一遍、要么留越权洞）。
 */
@Tag(name = "资源管理-Namespace", description = "单命名空间快照（租户可达，边界=分配表）")
@RestController
@RequestMapping("/namespacestats")
public class NamespaceStatController implements AccessBoundaryAware {

    private final KubernetesOperationsFactory operationsFactory;
    private final ResourceAccessResolver accessResolver;

    public NamespaceStatController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        this.operationsFactory = operationsFactory;
        this.accessResolver = accessResolver;
    }

    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.TENANT;
    }

    @GetMapping("/{namespace}")
    @Operation(summary = "单命名空间快照（行 + 异常 Pod + 不健康工作负载）")
    public ResponseData<NamespaceSnapshotDTO> stat(@PathVariable String namespace,
                                                  @RequestParam(required = false) String tenantId,
                                                  @RequestParam String clusterId) {
        AccessContext ctx = accessResolver.resolveNamespacedAccess(tenantId, clusterId, namespace);
        return new ResponseData<>(operationsFactory
                .getNamespaceStatOperation(clusterId, ctx.tenantId(), ctx.adminMode())
                .row(namespace));
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -pl k8s-server test -Dtest=NamespaceStatControllerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（2 例）

- [ ] **Step 5: 提交**

```bash
git add k8s-server/src/main/java/com/coding/k8sserver/controllers/namespace/NamespaceStatController.java \
        k8s-server/src/test/java/com/coding/k8sserver/controllers/namespace/NamespaceStatControllerTest.java
git commit -m "feat(tenant-overview): k8s-server 单命名空间快照端点（TENANT 边界）"
```

---

## Phase 2 — 缓存层改造（platform-api）

### Task 4: `RedisJsonCache` 批量读/写

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/cache/RedisJsonCache.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/cache/RedisJsonCacheTest.java`（追加用例）

**Interfaces:**
- Produces:
  - `Map<String, T> getMany(Collection<String> keys, TypeReference<T> type)` —— 只回命中项（MGET，一次往返）
  - `void setMany(Map<String, T> values, Duration ttl)` —— pipeline 一次写入（每键同 TTL）

- [ ] **Step 1: 写失败测试（追加到既有测试类，mock 手法照抄它）**

```java
    @Test
    void getMany_returns_only_hits_in_one_round_trip() {
        when(valueOps.multiGet(List.of("platform:cache:a", "platform:cache:b")))
                .thenReturn(Arrays.asList("[\"x\"]", null));

        Map<String, List<String>> got = cache.getMany(List.of("a", "b"), STRING_LIST);

        assertThat(got).containsOnlyKeys("a");
        assertThat(got.get("a")).containsExactly("x");
    }

    @Test
    void getMany_degrades_on_redis_failure() {
        when(valueOps.multiGet(any())).thenThrow(new RuntimeException("redis down"));

        assertThat(cache.getMany(List.of("a"), STRING_LIST)).isEmpty();   // 不抛：调用方当作全未命中
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl platform-api test -Dtest=RedisJsonCacheTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（`getMany` 不存在）

- [ ] **Step 3: 实现（与既有 `get` 同一套降级/前缀语义）**

```java
    /** 批量读（MGET，一次往返）。**只回命中项**：未命中由调用方决定怎么补（本仓的补法是走采样器）。 */
    public <T> Map<String, T> getMany(Collection<String> keys, TypeReference<T> type) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        List<String> fullKeys = keys.stream().map(k -> PREFIX + k).toList();
        List<String> raw;
        try {
            raw = redis.opsForValue().multiGet(fullKeys);
        } catch (Exception e) {
            log.warn("Redis 批量读失败，按全未命中处理（{} 个键）：{}", fullKeys.size(), e.getMessage());
            return Map.of();
        }
        if (raw == null) {
            return Map.of();
        }
        Map<String, T> out = new LinkedHashMap<>();
        Iterator<String> ks = keys.iterator();
        for (String value : raw) {
            String key = ks.next();
            if (value == null) {
                continue;
            }
            try {
                out.put(key, jsonMapper.readValue(value, type));
            } catch (Exception e) {
                log.warn("Redis 缓存反序列化失败，按未命中处理: {} ({})", PREFIX + key, e.getMessage());
            }
        }
        return out;
    }

    /** 批量写（pipeline，每键同 TTL）。写失败只记日志 —— 与 get 的降级立场一致。 */
    public <T> void setMany(Map<String, T> values, Duration ttl) {
        if (values == null || values.isEmpty()) {
            return;
        }
        try {
            Map<String, String> payload = new LinkedHashMap<>();
            values.forEach((k, v) -> payload.put(PREFIX + k, jsonMapper.writeValueAsString(v)));
            redis.executePipelined((RedisCallback<Object>) connection -> {
                RedisStringCommands commands = connection.stringCommands();
                byte[] ttlBytes = String.valueOf(ttl.toMillis()).getBytes(StandardCharsets.UTF_8);
                payload.forEach((k, v) -> commands.set(k.getBytes(StandardCharsets.UTF_8),
                        v.getBytes(StandardCharsets.UTF_8),
                        Expiration.milliseconds(ttl.toMillis()), RedisStringCommands.SetOption.UPSERT));
                return null;
            });
        } catch (Exception e) {
            log.warn("Redis 批量写失败（不影响本次结果，{} 个键）：{}", values.size(), e.getMessage());
        }
    }
```
（`ttlBytes` 若实现里用不到就删掉；`Expiration.milliseconds` 用 `org.springframework.data.redis.core.types.Expiration`。）

- [ ] **Step 4: 跑测试确认通过 + 既有 6 例不回归**

Run: `mvn -o -pl platform-api test -Dtest=RedisJsonCacheTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（8 例）

- [ ] **Step 5: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/cache/RedisJsonCache.java \
        platform-api/src/test/java/com/coding/platformapi/cache/RedisJsonCacheTest.java
git commit -m "feat(tenant-overview): RedisJsonCache 批量读（MGET）与批量写（pipeline）"
```

### Task 5: `NamespaceStatService` —— ns 键的唯一入口

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/k8s/K8sNamespaceStatClient.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/services/NamespaceStatService.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/NamespaceStatServiceTest.java`

**Interfaces:**
- Consumes: `RedisJsonCache.getMany/setMany/get`、`K8sNamespaceStatClient.row(...)`
- Produces:
  - `NamespaceSnapshotDTO row(String tenantId, String clusterId, String namespace)` —— 缓存优先；未命中采样并写回
  - `List<NamespaceSnapshotDTO> rows(String tenantId, String clusterId, Collection<String> namespaces)` —— 含**页内一致性阈值**（`SKEW_TOLERANCE_MILLIS = 15_000`）：本批内 `sampledAt` 相差超阈值时，把**过旧的那些**在本次请求内重采

- [ ] **Step 1: 写失败测试**

```java
class NamespaceStatServiceTest {

    private static final TypeReference<NamespaceSnapshotDTO> TYPE = new TypeReference<>() {};

    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    K8sNamespaceStatClient client = mock(K8sNamespaceStatClient.class);
    RedisJsonCache cache;   // 真实实例，配 mock 的 template（与 RedisJsonCacheTest 同法）
    NamespaceStatService svc;

    NamespaceStatServiceTest() {
        when(redis.opsForValue()).thenReturn(valueOps);
        cache = new RedisJsonCache(redis, JsonMapper.builder().build());
        svc = new NamespaceStatService(cache, client);
    }

    /** platform-api **不依赖 k8s-core**，夹具就地构造（不要引 k8s-core 的类） */
    private NamespaceSnapshotDTO snap(String ns, long sampledAt) {
        NamespaceSnapshotDTO s = new NamespaceSnapshotDTO();
        NamespaceStatDTO stat = new NamespaceStatDTO();
        stat.setNamespace(ns);
        s.setStat(stat);
        s.setAbnormalPodReasonCounts(new LinkedHashMap<>());
        s.setAbnormalPods(List.of());
        s.setUnhealthyWorkloads(List.of());
        s.setSampledAt(sampledAt);
        return s;
    }

    @Test
    void row_fills_from_sampler_on_miss_and_writes_back() {
        when(valueOps.get("platform:cache:ns:c1:team-a")).thenReturn(null);
        when(client.row(null, "c1", "team-a")).thenReturn(snap("team-a", 1_000L));

        assertThat(svc.row(null, "c1", "team-a").getStat().getNamespace()).isEqualTo("team-a");
        verify(valueOps).set(eq("platform:cache:ns:c1:team-a"), anyString(), eq(Duration.ofSeconds(45)));
    }

    @Test
    void rows_reuse_fresh_cached_rows_and_resample_only_stale_ones() {
        long now = System.currentTimeMillis();
        when(valueOps.multiGet(any())).thenReturn(List.of(
                json(snap("a", now)),          // 新鲜
                json(snap("b", now - 60_000))));  // 超过 15s 阈值 → 必须重采
        when(client.row(null, "c1", "b")).thenReturn(snap("b", now));

        List<NamespaceSnapshotDTO> out = svc.rows(null, "c1", List.of("a", "b"));

        assertThat(out).extracting(s -> s.getStat().getNamespace()).containsExactly("a", "b");
        verify(client, never()).row(any(), anyString(), eq("a"));
        verify(client).row(null, "c1", "b");
    }

    @Test
    void rows_with_no_skew_do_not_resample() {
        long now = System.currentTimeMillis();
        when(valueOps.multiGet(any())).thenReturn(List.of(json(snap("a", now)), json(snap("b", now - 5_000))));

        svc.rows(null, "c1", List.of("a", "b"));

        verify(client, never()).row(any(), anyString(), anyString());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl platform-api test -Dtest=NamespaceStatServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败

- [ ] **Step 3: 实现**

```java
/**
 * 命名空间切片的**唯一读写入口**：缓存优先、未命中采样、必要时补采。
 * <p>三个消费者共用它（租户概览 / 集群概览明细 / 命名空间详情页），所以口径与新鲜度规则只需要在这里成立一次。
 * <p><b>页内一致性阈值</b>：调用方要一次拿到多行时（"多行拼一个数"），若各行 sampledAt 相差超过
 * {@link #SKEW_TOLERANCE_MILLIS}，把**过旧的那些**在本次请求内重采 —— 否则页面上的"合计"会是
 * 几个不同时刻的行硬加出来的数（那个数在任何瞬间都不曾存在）。补完即自愈：后续 15s 内的读取不再触发。
 * 单行消费者（命名空间页）不走这条：它本来就只一行，没有"页内"可言。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NamespaceStatService {

    static final Duration TTL = Duration.ofSeconds(45);
    static final long SKEW_TOLERANCE_MILLIS = 15_000L;
    private static final TypeReference<NamespaceSnapshotDTO> TYPE = new TypeReference<>() {};

    private final RedisJsonCache cache;
    private final K8sNamespaceStatClient client;

    private static String key(String clusterId, String namespace) {
        return "ns:" + clusterId + ":" + namespace;
    }

    public NamespaceSnapshotDTO row(String tenantId, String clusterId, String namespace) {
        return cache.get(key(clusterId, namespace), TTL, TYPE, () -> client.row(tenantId, clusterId, namespace));
    }

    public List<NamespaceSnapshotDTO> rows(String tenantId, String clusterId, Collection<String> namespaces) {
        List<String> wanted = namespaces == null ? List.of() : namespaces.stream().sorted().toList();
        if (wanted.isEmpty()) {
            return List.of();
        }
        Map<String, NamespaceSnapshotDTO> hits = cache.getMany(
                wanted.stream().map(ns -> key(clusterId, ns)).toList(), TYPE);
        Map<String, NamespaceSnapshotDTO> byNs = new LinkedHashMap<>();
        for (String ns : wanted) {
            NamespaceSnapshotDTO cached = hits.get(key(clusterId, ns));
            if (cached != null) {
                byNs.put(ns, cached);
            }
        }
        long newest = byNs.values().stream().mapToLong(NamespaceSnapshotDTO::getSampledAt).max().orElse(0L);
        Map<String, NamespaceSnapshotDTO> toWrite = new LinkedHashMap<>();
        List<NamespaceSnapshotDTO> out = new ArrayList<>();
        for (String ns : wanted) {
            NamespaceSnapshotDTO s = byNs.get(ns);
            boolean stale = s != null && newest - s.getSampledAt() > SKEW_TOLERANCE_MILLIS;
            if (s == null || stale) {
                s = client.row(tenantId, clusterId, ns);   // 未命中 或 过旧 → 本次请求内补齐
                toWrite.put(key(clusterId, ns), s);
            }
            out.add(s);
        }
        cache.setMany(toWrite, TTL);
        return out;
    }
}
```

`K8sNamespaceStatClient`（一方法一端点，仿 `K8sNodeClient`）：

```java
    /** 单命名空间快照：GET /namespacestats/{namespace}?clusterId[&tenantId] */
    public NamespaceSnapshotDTO row(String tenantId, String clusterId, String namespace) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("clusterId", clusterId);
        if (tenantId != null) {
            params.put("tenantId", tenantId);
        }
        return gateway.exchange(HttpMethod.GET, "/namespacestats/" + namespace, params, null,
                gateway.responseType(NamespaceSnapshotDTO.class));
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -pl platform-api test -Dtest=NamespaceStatServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（4 例）

- [ ] **Step 5: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/k8s/K8sNamespaceStatClient.java \
        platform-api/src/main/java/com/coding/platformapi/services/NamespaceStatService.java \
        platform-api/src/test/java/com/coding/platformapi/services/NamespaceStatServiceTest.java
git commit -m "feat(tenant-overview): NamespaceStatService（ns 键唯一入口 + 页内一致性阈值）"
```

### Task 6: `ClusterService` 按作用域拆键

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ClusterService.java`（`overview` / `resourceBreakdown` / `aggregateCached` 一段）
- Modify: `platform-common/.../dto/ClusterSnapshotDTO.java`（新：集群键的值）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/ClusterServiceTest.java`（追加用例）

**Interfaces:**
- Consumes: `NamespaceStatService.rows(...)`、`K8sLifecycleClient.resourceAggregate(clusterId)`
- Produces:
  - `ClusterSnapshotDTO { List<NodeHealthDTO> nodeHealth; ResourceCapacityDTO nodeCapacity; StorageStatDTO storage; List<String> namespaceNames; long sampledAt; }`（**不含任何 per-ns 数值**）
  - `ClusterSnapshotDTO ClusterService.snapshot(String clusterId)`（public；缓存优先，未命中聚合 + 切片写 ns 键）
  - `ClusterOverviewDTO overview(String clusterId)`（签名与语义不变）
  - `List<NamespaceStatEntry> resourceBreakdown(String clusterId)` —— **由 `List<NamespaceStatDTO>` 改为 `List<NamespaceStatEntry>`**：回带 `quotaState`，前端不再自己按前缀判"有没有配额"（判定只允许在后端一处）

- [ ] **Step 1: 写失败测试**

```java
    @Test
    void resource_breakdown_comes_from_namespace_slices() {
        when(cache.get(eq("cluster:agg:c1"), any(), any(), any()))
                .thenReturn(snapshot("c1", List.of("team-a"), 1_000L));
        when(statService.rows(null, "c1", List.of("team-a"))).thenReturn(List.of(snap("team-a")));

        assertThat(clusterService.resourceBreakdown("c1"))
                .extracting(e -> e.getSnapshot().getStat().getNamespace())
                .containsExactly("team-a");
        assertThat(clusterService.resourceBreakdown("c1")).extracting(NamespaceStatEntry::getQuotaState)
                .containsExactly(QuotaClassifier.QuotaState.MISSING);
    }

    @Test
    void breakdown_is_null_when_cluster_snapshot_unavailable() {
        when(cache.get(anyString(), any(), any(), any())).thenReturn(null);
        assertThat(clusterService.resourceBreakdown("c1")).isNull();   // 前端「—」的既有降级
    }
```
（`snapshot()`/`snap()` 是测试内的夹具构造器；`statService` 注入 mock。现有测试的构造方式照抄 `ClusterServiceTest`。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl platform-api test -Dtest=ClusterServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（`snapshot` / `statService` 不存在）

- [ ] **Step 3: 实现**

要点：
1. `ClusterService` 注入 `NamespaceStatService`（新增构造参数 → 同步改既有 `ClusterServiceTest` 的构造）。
2. `aggregateCached` 拆成两步：取聚合 → 写 `cluster:agg:<id>`（`ClusterSnapshotDTO`：从聚合里取节点/存储 + `namespaceNames` = 各行 ns 名，`sampledAt` = 现在）+ `statService` 侧写 N 个 ns 键。**ns 键的写入走 `NamespaceStatService` 的新方法**（避免两处拼键）：

```java
    /** 一次采样填 N 个键（同一个 sampledAt）—— 由集群那次聚合的切片来，窄读永远不必为此再扫全集群。 */
    public void putClusterSlices(String clusterId, List<NamespaceSnapshotDTO> slices) {
        Map<String, NamespaceSnapshotDTO> values = new LinkedHashMap<>();
        slices.forEach(s -> values.put(key(clusterId, s.getStat().getNamespace()), s));
        cache.setMany(values, TTL);
    }
```
3. `overview` / `resourceBreakdown` 的 per-ns 与合计（`ClusterTotalDTO`、异常/不健康名单与计数、reason 分组）改为：`statService.rows(null, clusterId, snapshot.getNamespaceNames())` → 合并（合计 = Σ行；名单 = 合并各切片后沿用 `sortAndCap`；`abnormalPodTotal` = Σ `abnormalPodCount`）。
   合并逻辑抽成 `ClusterService` 内的 private 方法，**与租户概览共用**：把它放到 `NamespaceStatService` 旁边的新类 `NamespaceStatMerge`（静态纯函数），P3 的 `TenantOverviewService` 直接调它：

```java
/** 由 ns 切片合并出"合计 + 两个名单"（纯函数，集群概览与租户概览共用同一份口径）。 */
public final class NamespaceStatMerge {
    public static ClusterTotalDTO total(List<NamespaceSnapshotDTO> slices) { ... }
    public static Map<String, Integer> abnormalReasonCounts(List<NamespaceSnapshotDTO> slices) { ... }  // 各切片求和
    public static int abnormalTotal(List<NamespaceSnapshotDTO> slices) { ... }
    public static List<AbnormalPodDTO> abnormalPods(List<NamespaceSnapshotDTO> slices, int cap) { ... } // 合并→按 severity 排序→封顶
    public static List<UnhealthyWorkloadDTO> unhealthyWorkloads(List<NamespaceSnapshotDTO> slices, int cap) { ... }
}
```
排序/封顶的 severity 规则从 `ClusterAggregationOperations` 暴露为包内可见或直接搬进 `NamespaceStatMerge`（搬进后者更合适：它是"合并"的一部分）。

- [ ] **Step 4: 跑回归**

Run: `mvn -o -pl platform-api test -Dtest=ClusterServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 既有 8 例（按新构造调整后）+ 新 2 例全绿；**特别确认 `overview` 的既有断言没变**（它是"改造前后数字一致"的回归基线）

- [ ] **Step 5: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/ClusterService.java \
        platform-api/src/main/java/com/coding/platformapi/services/NamespaceStatMerge.java \
        platform-api/src/main/java/com/coding/platformapi/services/NamespaceStatService.java \
        platform-common/src/main/java/com/coding/common/models/k8s/dto/ClusterSnapshotDTO.java \
        platform-api/src/test/java/com/coding/platformapi/services/ClusterServiceTest.java
git commit -m "refactor(tenant-overview): 集群键只放集群级事实，明细/合计由 ns 切片现算"
```

---

## Phase 3 — 租户概览后端

### Task 7: 配额三档判定 + DTO

**Files:**
- Create: `platform-api/.../services/QuotaClassifier.java`
- Create: `platform-common/.../dto/TenantOverviewDTO.java`、`NamespaceStatEntry.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/QuotaClassifierTest.java`

**Interfaces:**
- Produces:
  - `enum QuotaState { OK, MISSING, PARTIAL }`
  - `static QuotaState QuotaClassifier.state(NamespaceStatDTO stat)`
  - `NamespaceStatEntry { String clusterId; NamespaceSnapshotDTO snapshot; QuotaState quotaState; }`
  - `TenantOverviewDTO { String tenantId; long sampledAt; ClusterTotalDTO total; BigDecimal quotaCpuHard; BigDecimal quotaMemHard; BigDecimal quotaCpuUsed; BigDecimal quotaMemUsed; List<String> namespacesWithoutQuota; List<String> namespacesPartialQuota; int abnormalPodTotal; Map<String,Integer> abnormalPodReasonCounts; List<AbnormalPodDTO> abnormalPods; int unhealthyWorkloadTotal; List<UnhealthyWorkloadDTO> unhealthyWorkloads; List<TenantClusterSummaryDTO> clusters; List<String> unavailableClusters; }`
  - `TenantClusterSummaryDTO { String clusterId; String clusterName; int namespaceCount; boolean available; ClusterTotalDTO total; }`

- [ ] **Step 1: 写失败测试**

```java
    @Test
    void missing_when_no_quota_at_all() {
        NamespaceStatDTO s = new NamespaceStatDTO();
        s.setNamespace("team-a");
        assertThat(QuotaClassifier.state(s)).isEqualTo(QuotaState.MISSING);
    }

    @Test
    void partial_when_quota_lacks_requests_keys() {
        NamespaceStatDTO s = new NamespaceStatDTO();
        s.setQuotaCpuHard(new BigDecimal("4"));      // 只有 cpu
        assertThat(QuotaClassifier.state(s)).isEqualTo(QuotaState.PARTIAL);
    }

    @Test
    void ok_when_both_requests_keys_present() {
        NamespaceStatDTO s = new NamespaceStatDTO();
        s.setQuotaCpuHard(new BigDecimal("4"));
        s.setQuotaMemHard(new BigDecimal("8589934592"));
        assertThat(QuotaClassifier.state(s)).isEqualTo(QuotaState.OK);
    }
```

- [ ] **Step 2: 跑测试确认失败** → `mvn -o -pl platform-api test -Dtest=QuotaClassifierTest -Dsurefire.failIfNoSpecifiedTests=false`（编译失败）

- [ ] **Step 3: 实现**

```java
/**
 * 配额三档判定（产品口径「每个命名空间必须有配额」的落点）。
 * <p>判据只看两个 key：`requests.cpu` / `requests.memory`（与平台配额编辑器同一对 key）。
 * 没有 quota → MISSING；有 quota 但缺任一 key → PARTIAL；两个都有 → OK。
 * <p>**放一处**：租户概览、集群概览明细、命名空间详情页三处都调它，不许各自判。
 */
public final class QuotaClassifier {
    public enum QuotaState { OK, MISSING, PARTIAL }

    public static QuotaState state(NamespaceStatDTO stat) {
        if (stat == null || (stat.getQuotaCpuHard() == null && stat.getQuotaMemHard() == null)) {
            return QuotaState.MISSING;
        }
        return stat.getQuotaCpuHard() != null && stat.getQuotaMemHard() != null
                ? QuotaState.OK : QuotaState.PARTIAL;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**（3 例）
- [ ] **Step 5: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/QuotaClassifier.java \
        platform-common/src/main/java/com/coding/common/models/k8s/dto/TenantOverviewDTO.java \
        platform-common/src/main/java/com/coding/common/models/k8s/dto/TenantClusterSummaryDTO.java \
        platform-common/src/main/java/com/coding/common/models/k8s/dto/NamespaceStatEntry.java \
        platform-api/src/test/java/com/coding/platformapi/services/QuotaClassifierTest.java
git commit -m "feat(tenant-overview): 配额三档判定 + 概览 DTO"
```

### Task 8: `TenantOverviewService`

**Files:**
- Create: `platform-api/.../services/TenantOverviewService.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/TenantOverviewServiceTest.java`

**Interfaces:**
- Consumes: `PlatformTenantNamespaceMapper.listByTenant(tenantId)`（范围）、`K8sClusterMapper`（只要集群名）、`NamespaceStatService.rows`、`NamespaceStatMerge`、`QuotaClassifier`（**不经 `ClusterService`**：租户的命名空间集合来自分配表，不是来自集群快照）
- Produces（签名固定，P3/P4/P5 都依赖）：
  - `TenantOverviewDTO overview(String tenantId)`
  - `List<NamespaceStatEntry> breakdown(String tenantId)`
  - `MetricSeriesResponse cpu(String tenantId, long start, long end)` / `memory(...)`

- [ ] **Step 1: 写失败测试（只统计本租户 / 跨集群求和 / 降级 / 分档）**

```java
    @Test
    void only_the_tenants_namespaces_are_counted() {
        when(allocationMapper.listByTenant("t1")).thenReturn(List.of(alloc("c1", "team-a"), alloc("c1", "team-b")));
        when(statService.rows("t1", "c1", List.of("team-a", "team-b")))
                .thenReturn(List.of(snap("team-a", 1, "2", "4Gi"), snap("team-b", 3, "1", "1Gi")));

        TenantOverviewDTO out = svc.overview("t1");

        assertThat(out.getTotal().getPodCount()).isEqualTo(4);
        assertThat(out.getTotal().getCpuRequest()).isEqualByComparingTo("3");
        assertThat(out.getClusters()).singleElement()
                .satisfies(c -> assertThat(c.getNamespaceCount()).isEqualTo(2));
    }

    @Test
    void one_cluster_being_down_marks_it_unavailable_without_failing_the_page() {
        when(allocationMapper.listByTenant("t1")).thenReturn(List.of(alloc("c1", "team-a"), alloc("c2", "team-c")));
        when(statService.rows("t1", "c1", List.of("team-a"))).thenReturn(List.of(snap("team-a", 1, "1", "1Gi")));
        when(statService.rows("t1", "c2", List.of("team-c"))).thenThrow(new RuntimeException("cluster down"));

        TenantOverviewDTO out = svc.overview("t1");

        assertThat(out.getUnavailableClusters()).containsExactly("c2");
        assertThat(out.getTotal().getPodCount()).isEqualTo(1);          // 其余集群照常出数
    }

    @Test
    void namespaces_without_quota_are_reported() {
        when(allocationMapper.listByTenant("t1")).thenReturn(List.of(alloc("c1", "team-a"), alloc("c1", "team-b")));
        when(statService.rows("t1", "c1", List.of("team-a", "team-b")))
                .thenReturn(List.of(snapWithQuota("team-a", "2", "4Gi"), snapNoQuota("team-b")));

        TenantOverviewDTO out = svc.overview("t1");

        assertThat(out.getNamespacesWithoutQuota()).containsExactly("team-b");
        assertThat(out.getQuotaCpuHard()).isEqualByComparingTo("2");    // 只累加有配额的行
    }

    @Test
    void sampled_at_is_the_oldest_row_on_the_page() {
        when(statService.rows("t1", "c1", List.of("team-a", "team-b")))
                .thenReturn(List.of(snapAt("team-a", 5_000L), snapAt("team-b", 1_000L)));
        assertThat(svc.overview("t1").getSampledAt()).isEqualTo(1_000L);
    }
```

- [ ] **Step 2: 跑测试确认失败** → 编译失败

- [ ] **Step 3: 实现（顺序跨集群、逐个捕获异常）**

```java
/**
 * 租户概览：范围取自分配表（平台侧独有），事实取自 ns 切片（缓存优先），合并只在这里做一次。
 * <p><b>顺序、逐集群捕获</b>：① 跨线程扇出会让 K8sServerGateway 拿不到调用方的 token（RequestContextHolder 是
 * 线程绑定的）；② 一个集群不可用不能拖垮整页（B4 在 Promise.all 一锅端上踩过），故逐集群 try/catch，
 * 失败的集群进 unavailableClusters。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantOverviewService {

    private final PlatformTenantNamespaceMapper allocationMapper;
    private final K8sClusterMapper clusterMapper;
    private final NamespaceStatService statService;

    public TenantOverviewDTO overview(String tenantId) {
        List<PlatformTenantNamespace> allocations = allocationMapper.listByTenant(tenantId);
        Map<String, List<String>> nsByCluster = allocations.stream()
                .collect(Collectors.groupingBy(PlatformTenantNamespace::getClusterId, TreeMap::new,
                        Collectors.mapping(PlatformTenantNamespace::getNamespace, Collectors.toList())));

        List<NamespaceSnapshotDTO> all = new ArrayList<>();
        List<TenantClusterSummaryDTO> clusters = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (var e : nsByCluster.entrySet()) {
            TenantClusterSummaryDTO c = new TenantClusterSummaryDTO();
            c.setClusterId(e.getKey());
            c.setClusterName(clusterNameOf(e.getKey()));
            c.setNamespaceCount(e.getValue().size());
            try {
                List<NamespaceSnapshotDTO> slices = statService.rows(tenantId, e.getKey(), e.getValue());
                all.addAll(slices);
                c.setTotal(NamespaceStatMerge.total(slices));
                c.setAvailable(true);
            } catch (Exception ex) {
                log.warn("租户 {} 的集群 {} 概览数据不可用：{}", tenantId, e.getKey(), ex.getMessage());
                c.setAvailable(false);
                unavailable.add(e.getKey());
            }
            clusters.add(c);
        }
        return assemble(tenantId, all, clusters, unavailable);   // 合计/名单/分档全部由 all 现算
    }
```

- [ ] **Step 4: 跑测试确认通过**（≥6 例）
- [ ] **Step 5: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/TenantOverviewService.java \
        platform-api/src/test/java/com/coding/platformapi/services/TenantOverviewServiceTest.java
git commit -m "feat(tenant-overview): 租户概览服务（范围=分配表，逐集群降级）"
```

### Task 9: 控制器 + 曲线 + 迁移

**Files:**
- Create: `platform-api/.../controllers/TenantOverviewController.java`
- Create: `platform-api/.../models/TenantOverviewRequest.java`
- Modify: `platform-api/.../controllers/NamespaceController.java`（+`/namespace/stat/get`）、`platform-api/.../services/NamespaceService.java`
- Modify: `platform-api/.../metrics/MetricsService.java`（+租户合计曲线）、`platform-api/.../metrics/MetricQuery.java`（若无现成 by-ns 语句则复用 `CLUSTER_CPU_USED_BY_NS`）
- Create: `platform-data/src/main/resources/db/migration/V2026_10_12_2__tenant_overview_permissions.sql`

**Interfaces:**
- Consumes: `TenantContextResolver.requireContext(tokenTenantId, requestTenantId)`、`TenantOverviewService`、`NamespaceStatService`
- Produces:
  - `POST /tenant/overview`、`/tenant/overview/breakdown`、`/tenant/overview/metrics/cpu`、`/tenant/overview/metrics/memory`
  - `POST /namespace/stat/get` → `NamespaceStatEntry`
  - `MetricSeriesResponse MetricsService.tenantCpu(String tenantId, List<String> clusterIds, List<String> namespaces, long start, long end)`（内存里按 `legend` 过滤 + 逐时间点求和成一条"租户合计"）

- [ ] **Step 1: 写失败测试**

```java
    @Test
    void admin_passing_another_tenant_is_rejected_for_self_track() {
        // 自管轨：token 租户在，传别人的 tenantId → TENANT_MISMATCH（既有 resolver 行为，这里钉住新端点用对了它）
        assertThatThrownBy(() -> controller.overview("t-other", mockBody()))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void tenant_metrics_sum_only_the_tenants_namespaces() {
        // 两条序列 a=1,2 / b=10,20；只属于租户的 a → 合计 1,2（不是 11,22）
        MetricSeriesResponse resp = metricsService.tenantCpu("t1", List.of("c1"), List.of("a"), 0, 60);

        assertThat(resp.getSeries()).singleElement()
                .satisfies(s -> assertThat(s.getPoints()).extracting(MetricPoint::getValue)
                        .containsExactly(1.0, 2.0));
    }
```

- [ ] **Step 2: 跑测试确认失败** → 编译失败

- [ ] **Step 3: 实现要点**

- `TenantOverviewController`：`@RequestMapping("/tenant/overview")`；每个方法首行 `String tid = ctx.requireContext(tokenTenantId(), req.getTenantId());`（`tokenTenantId()` 抄 `TenantController` 的私有辅助）；返回 `ResponseData<>`。
- `NamespaceController` + `POST /namespace/stat/get`：`platform:allocation:list`；调 `byteService` 侧 `statService.row(null, clusterId, namespace)` 包成 `NamespaceStatEntry`（带 `QuotaState`）。
- 曲线：按集群取 `CLUSTER_*_USED_BY_NS` 的原始序列（`seriesByNamespace` 的产物），用租户的 ns 名集合过滤 `legend`，逐时间点求和 → 一条 `legend = "租户合计"`。跨集群再把同名 ns 的值相加（不同集群可能有同名 ns）。
- 迁移（**9 行** = 4 个租户端点的两码各一行 + `/namespace/stat/get` 一行；零新码）：

```sql
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_tov_overview','API','/tenant/overview','POST','tenant:overview:view','租户概览快照（自管轨）'),
 ('perm_tov_overview_p','API','/tenant/overview','POST','platform:allocation:list','租户概览快照（代管轨）'),
 ('perm_tov_breakdown','API','/tenant/overview/breakdown','POST','tenant:overview:view','租户概览资源明细（自管轨）'),
 ('perm_tov_breakdown_p','API','/tenant/overview/breakdown','POST','platform:allocation:list','租户概览资源明细（代管轨）'),
 ('perm_tov_metrics_cpu','API','/tenant/overview/metrics/cpu','POST','tenant:overview:view','租户 CPU 用量曲线（自管轨）'),
 ('perm_tov_metrics_cpu_p','API','/tenant/overview/metrics/cpu','POST','platform:allocation:list','租户 CPU 用量曲线（代管轨）'),
 ('perm_tov_metrics_mem','API','/tenant/overview/metrics/memory','POST','tenant:overview:view','租户内存用量曲线（自管轨）'),
 ('perm_tov_metrics_mem_p','API','/tenant/overview/metrics/memory','POST','platform:allocation:list','租户内存用量曲线（代管轨）'),
 ('perm_ns_stat_get','API','/namespace/stat/get','POST','platform:allocation:list','命名空间快照（含配额三档状态）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- 角色绑定：admin 全 9 行；tenant-admin / tenant-member 只绑 tenant:overview:view 那 4 行
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_admin_tov_', p.id), 'builtin_role_admin', p.id FROM platform_permission p
WHERE p.id LIKE 'perm_tov_%' OR p.id = 'perm_ns_stat_get'
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_ttov_', r.role_id, '_', p.id), r.role_id, p.id
FROM platform_permission p
CROSS JOIN (SELECT 'builtin_role_tenant_admin' AS role_id UNION ALL SELECT 'builtin_role_tenant_member') r
WHERE p.id IN ('perm_tov_overview','perm_tov_breakdown','perm_tov_metrics_cpu','perm_tov_metrics_mem')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
```
（注：同一 `(method, path)` 两行 = ANY-of，正是既有 `/tenant/namespace/list` 的范式。）

- [ ] **Step 4: 跑测试 + 起一次 platform-api 确认交叉校验不 brick**

Run: `mvn -o -pl platform-api test -Dsurefire.failIfNoSpecifiedTests=false`（全绿）
Run（迁移后）: `mvn -o -pl platform-api spring-boot:run` 或本地起一次 → 日志出现 `[RBAC] 交叉校验通过：N 个 endpoint…` 即无缺口

- [ ] **Step 5: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/controllers/TenantOverviewController.java \
        platform-api/src/main/java/com/coding/platformapi/models/TenantOverviewRequest.java \
        platform-api/src/main/java/com/coding/platformapi/controllers/NamespaceController.java \
        platform-api/src/main/java/com/coding/platformapi/metrics/MetricsService.java \
        platform-data/src/main/resources/db/migration/V2026_10_12_2__tenant_overview_permissions.sql
git commit -m "feat(tenant-overview): 4 个租户概览端点 + 命名空间快照端点 + 权限迁移"
```

---

## Phase 4 — 前端

### Task 10: api 与类型

**Files:**
- Modify: `platform-web/src/types.ts`（`K8sNamespaceStat` 系列）、`platform-web/src/api/index.ts`（`tenantApi.overview*`、`namespaceApi.statGet`）

- [ ] **Step 1: 加类型**（字段与 `TenantOverviewDTO` 逐字一致：`tenantId/sampledAt/total/quotaTotals/clusters/unavailableClusters/abnormalPodTotal/abnormalPodReasonCounts/abnormalPods/unhealthyWorkloadTotal/unhealthyWorkloads/namespacesWithoutQuota/namespacesPartialQuota`）
- [ ] **Step 2: 加 api**（`overview`/`breakdown`/`metricsCpu`/`metricsMemory` 四个，POST 带 `{tenantId: 可选}`；`namespaceApi.statGet(clusterId, namespace)`）
- [ ] **Step 3: 验证**：`npm run build`（type-check + vite）通过
- [ ] **Step 4: 提交**

```bash
git add platform-web/src/types.ts platform-web/src/api/index.ts
git commit -m "feat(tenant-overview): 前端 api 与类型"
```

### Task 11: `TenantOverviewPanel` + tab + 抽屉加宽

**Files:**
- Create: `platform-web/src/components/tenant/TenantOverviewPanel.vue`
- Modify: `platform-web/src/components/TenantDetailPanel.vue`（首个 tab「概览」+ `lazy`）、`platform-web/src/views/TenantView.vue`（抽屉 size）

- [ ] **Step 1: 面板骨架**：
  - KPI 区：requests/limits 合计 + 配额 hard 合计 + 占用条；**右上角"数据截至 {fmtDate(sampledAt)}"**（spec §6.3）
  - "需要关注"区：未配置配额 / 部分配额（列出命名空间名，可点）、异常 Pod 按 reason 分组计数（可展开名单）、不健康工作负载
  - 明细表：命名空间（可点进命名空间页）/ 集群 / requests / limits / 配额 hard / 占用条 / **状态（直接读后端 `quotaState`，前端不自行判定）**
  - 两张 `MetricChart`（CPU/内存；`percentable` + `limit` = 各自口径的 hard 合计；**请求失败只在图表区显示失败态，不影响上方快照**）
  - 空态：该租户没有任何命名空间 → "请联系平台管理员分配命名空间"
- [ ] **Step 2: 挂 tab**（`<el-tab-pane label="概览" name="overview" lazy>`）；抽屉 `size="760px"` → `"1040px"`
- [ ] **Step 3: 浏览器实测**（假 token + XHR stub，照 `docs/development` 与本仓记忆的既有手法）——**四项验收**：
  1. 首屏四块全出；合计与明细逐行相加一致
  2. 无配额的 ns 计入"需要关注"且明细标黄
  3. stub 某集群 500 → 首屏标"集群 X 不可用"，其余数字照常
  4. 同一 ns 在租户概览与命名空间页读到同一 `sampledAt`（P5 完成后复验）
- [ ] **Step 4: `npm run build` 通过 + 提交**

```bash
git add platform-web/src/components/tenant/TenantOverviewPanel.vue \
        platform-web/src/components/TenantDetailPanel.vue platform-web/src/views/TenantView.vue
git commit -m "feat(tenant-overview): 租户概览面板（首个 tab）"
```

---

## Phase 5 — 三处口径统一

### Task 12: 集群概览「资源明细」tab 加状态列

**Files:** Modify `platform-web/src/views/ClusterDetailView.vue`（明细表加"配额状态"列，`ok/missing/partial` 三态标签）

- [ ] **Step 1: 加列**：读 `/cluster/resource-breakdown` 每行的 `quotaState`（T6 已改为回带 `NamespaceStatEntry`），映射成三态标签（`ok` 不显示 / `missing` ⚠无配额 / `partial` ⚠配额未覆盖 requests）。**前端不做判定**。
- [ ] **Step 2: 浏览器实测**：同一 ns 在集群概览与租户概览的状态一致
- [ ] **Step 3: 提交** `git commit -m "feat(tenant-overview): 集群明细 tab 展示配额三档状态"`

### Task 13: 命名空间详情页「概览」tab 改读快照

**Files:** Modify `platform-web/src/views/NamespaceDetailView.vue`

- [ ] **Step 1: 改数据源**：概览 tab 的用量摘要改调 `namespaceApi.statGet(clusterId, ns)`，显示 `used/hard` 与三档状态；`配额` 编辑 tab 保持 `quotaGet` 不动（它要完整对象），故 `tab === 'overview'` 时不再强制 `loadQuota()`
- [ ] **Step 2: 浏览器实测**：概览 tab 的 used/hard 与租户概览、集群概览三处一致；配额编辑 tab 行为不变
- [ ] **Step 3: `npm run build` + 提交** `git commit -m "feat(tenant-overview): 命名空间概览 tab 统一到 ns 切片口径"`

---

## 收尾清单

- [ ] 四模块全量测试：`mvn -o test`（**已知既有失败** `WorkloadConverterStaticIpTest` @HEAD 就 NPE，与本批无关）
- [ ] 前端 `npm run build`
- [ ] 迁移重放后起一次 platform-api，确认 `[RBAC] 交叉校验通过` 无缺口
- [ ] 在 `docs/superpowers/plans/` 补一条落地记录（含偏差：本计划 §"与 spec 的一处偏差"）
- [ ] 部署顺序：迁移 → 新构建（k8s-server + platform-api 同批）→ 无需模板重下发、无新页面码


---

# 落地记录（2026-10-13）

**状态**：13 个任务 + 1 个 fix wave 全部完成；逐任务复审 + 最终整支复审（opus）+ 恰好一次复验通过。全部在 `main` 上（用户明确同意），17 个 commit：`e563e8d..70bf04f`。

## 实施摘要（含对计划文本的偏差）

- **P1 采样与装配**：`namespaceSnapshots` 按 ns 切片（`aggregate` 改 static 并由切片派生）；`NamespaceStatOperations.row` 复用同一函数；k8s-server 新端点。偏差：**不抽 `NamespaceStatAssembler` 新类**，改为复用既有聚合函数（计划头已注明）。
- **P2 缓存层**：`RedisJsonCache.getMany/setMany`；`NamespaceStatService`（ns 键唯一入口 + 15s 页内阈值）；`ClusterService` 拆键（`ClusterSnapshotDTO` 只放集群级事实 + `namespaceNames` 索引）。偏差（R6）：`ClusterAggregateDTO` 增加 `namespaceSnapshots` 作 per-ns 真值（原"按封顶名单分桶"会少报计数）；`namespaces` 保留作有意的 wire 冗余。
- **P3 后端服务**：`TenantOverviewService`（范围=分配表、逐集群 try/catch、`nonNull` 过滤）+ 4 个端点 + `/namespace/stat/get` + 9 行迁移。偏差：T7 只剩两个 DTO → 并进 T8（R9）；曲线落 `MetricsService`（R10）；曲线作用域改成对 `Map<clusterId, ns>`（R12）。
- **P4 前端**：`TenantOverviewPanel`（首个 tab、lazy）+ 抽屉 1040px + api/类型。偏差：两轨都传 `{tenantId}`（R15，我派发说明写错，实现者纠正）。
- **P5 三处统一**：集群明细状态列 + 命名空间详情页概览 tab 改读切片。偏差：概览 tab **保留** `loadQuota()`（R16，否则丢 pods 配额行）；「未设置配额」由空态改内联提示（R17）。

## fix wave（最终复审后）

最终整支复审判 **Needs fixes**：**C1**（`/namespace/stat/get` 对唯一受众不可用 —— TENANT 边界 + `tenantId=null` ⇒ 平台管理员被拒）、**I1**（`ClusterService` 同根因且无 try/catch ⇒ 集群概览 500、破坏 B4 降级；`resourceBreakdown` 对 null 元素 NPE）、**I2**（误删既有测试）。
修复（`da904e5`/`576cedb`/`70bf04f`）：新增 `POST /namespacestats/platform/get`（独立类、PLATFORM 边界、admin client）；`NamespaceStatService` 按 `tenantId == null` 选平台模式（同一 `ns:` 键）；`ClusterService` 补 try/catch + null 过滤走既有降级；恢复测试；`setMany` 过滤 null + 单测；`backend-layering.md` 三处纠错。复验：全部 addressed、无新破坏。

## 验证证据

- 后端：k8s-core 全量（唯一失败是 HEAD 就存在的 `WorkloadConverterStaticIpTest` NPE）、k8s-server 20 绿、platform-api 255 绿。
- 前端：`npm run build` 通过。
- **浏览器实测**（假 token + XHR stub）：租户概览整页 —— KPI 合计 = Σ明细、配额三档、不可用集群提示、两条曲线、四个新端点被调用且未走集群聚合；`sampledAt=0` → 「数据截至 —」；R14 链接门控生效。
- **未做**：T12/T13 的浏览器实测（需另一套平台侧桩）；集群概览"改造前后数字一致"由既有 24+10 例断言 + 逐字段比对背书，未做真机对比。

## 部署顺序

1. **先重放迁移** `V2026_10_12_2__tenant_overview_permissions.sql`（9 行；无行的新端点会被启动期交叉校验拒启）。
2. k8s-server 与 platform-api **同批**起新构建（新 k8s-server 端点在被 api 调用前不影响既有路径）。
3. 无模板重下发、无新页面码、无 Redis/Thanos 特殊要求。
4. 回滚面：旧键 `cluster:aggregate:` 无其它读写方，自然过期（45s）；回滚到旧构建只会产生 ghost-row WARN。

## 留待后续（最终复审的 triage 结论）

- `quotaBadge` 三态文案三份 → 抽 `@/utils/quota.ts`。
- `ClusterAggregateDTO` 的 `total`/两个名单/计数已成无消费方的 wire 冗余（可瘦身）。
- severity 表与两个 cap 常量在 k8s-core / platform-api 各一份（靠注释同步）。
- `namespacesWithoutQuota/PartialQuota` 只存裸名字（跨集群同名不可区分）；KPI tile 与空态判据口径不一致。
- 提交署名混杂三种模型名（R8，未重写历史）。
