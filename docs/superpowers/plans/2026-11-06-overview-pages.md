# 平台总览与各维度概览 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 平台总览改成「资源数量 + 状态」的全平台汇总，集群 / 命名空间 / 节点 / 租户概览页签与租户工作台补上应用、中间件、CI、告警等统计。

**Architecture:** 数据采集缺口补在 k8s-core（Pod 按 phase 分桶、节点 limits 与 Pod 状态）；口径归类与计数在 platform-api（新增计数 SQL + 一个 `ScopeSummaryService` + 平台总览 `PlatformOverviewService`）；前端三个小展示组件（数量卡 / 饼图 / 水位条）复用到各页。

**Tech Stack:** Spring Boot 4 + MyBatis + fabric8（k8s-core）、Vue 3 + Element Plus + echarts（按需引入 `echarts/core`）。

**Spec:** `docs/superpowers/specs/2026-11-06-overview-pages-design.md`

**分册**（每册一组任务，按顺序执行）：
- 本文件：全局约束、Review Focus、Task 1–4（k8s-core 采集 + 计数 SQL + 口径工具）
- [`2026-11-06-overview-pages-part2.md`](2026-11-06-overview-pages-part2.md)：Task 5–9（platform-api 服务、接口、权限迁移）
- [`2026-11-06-overview-pages-part3.md`](2026-11-06-overview-pages-part3.md)：Task 10–14（前端与收口）

## Global Constraints

- k8s-server 零业务逻辑：分桶 / 汇总只做「采集」，归类口径在 platform-api。
- 一页一 controller；同类资源不抽公共基类；不加外键。
- 本仓无 Flyway：新迁移 `V2026_11_06_4__overview_permissions.sql` 手工重放；MySQL 注释 `--` 后必须跟空白。
- 新权限行必须显式绑角色（不按 code 继承）；租户可读能力用 `tenant:` 码并同时绑 admin。
- 空值显示「—」不显示 0；不可用集群不计入合计、不让整页失败。
- 中间件状态必须实时评估（复用 `MiddlewareService` 的 `refreshGroup` 路径），不得只读库。
- 前端收口用 `npm run build`（`vue-tsc --noEmit` 会放过真错误）。
- 提交只提交本任务文件；不碰 `platform-auth/src/main/resources/application.yaml`、`platform-web/src/views/MiddlewareInstanceView.vue`、`docs/superpowers/plans/2026-11-04-platform-gateway.md`。提交信息以 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` 结尾。

## Review Focus

1. **一个集群聚合失败**：平台总览合计只含可用集群，`clusters.unavailable` 列名字；页面不报错 → Task 7 测试。
2. **没有告警 / 流水线读权限的租户成员**：摘要里对应块为 `null`，不是 0 → Task 8 测试。
3. **Pod 各档相加 = 总数**（异常优先、Succeeded 单列）→ Task 1 测试。
4. **命名空间同时在分配表和系统名单里**（kube-system 曾被分给租户 default）：归「平台系统」→ Task 4 测试。
5. **某平台服务未配探测地址**：不计入分母 → Task 6 测试。

---

### Task 1: 聚合里 Pod 按 phase 分桶

**Files:**
- Modify: `platform-common/src/main/java/com/coding/common/models/k8s/dto/NamespaceStatDTO.java`、`ClusterTotalDTO.java`（同目录）
- Modify: `k8s-core/src/main/java/com/coding/k8score/operations/core/ClusterAggregationOperations.java`（`NsAcc`、逐 ns 累加处约 :300、合计约 :720）
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/NamespaceStatMerge.java`（`total`）
- Test: `k8s-core/src/test/java/com/coding/k8score/operations/core/ClusterAggregationOperationsTest.java`、`platform-api/src/test/java/com/coding/platformapi/services/NamespaceStatMergeTest.java`（无则新建）

**Interfaces — Produces:** 两个 DTO 新增 `int podRunning; int podPending; int podSucceeded;`（只数**非异常** Pod；异常沿用 `abnormalReason(pod) != null`）。不变量：`podRunning + podPending + podSucceeded + 异常数 == podCount`。

- [ ] Step 1: 测试 `podPhaseBucketsSumToPodCount`：构造 4 个 Pod —— Running 正常、Pending 正常、Succeeded、Running 但 CrashLoopBackOff；断言 ns 行 `podRunning=1, podPending=1, podSucceeded=1`，`abnormalPodCount=1`，合计 total 同值。
- [ ] Step 2: `mvn -q -pl k8s-core -am test -Dtest=ClusterAggregationOperationsTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL（字段不存在）。
- [ ] Step 3: 实现：`NsAcc` 加三计数，逐 Pod 先判 `abnormalReason`，非异常再按 `status.phase` 分桶；`toDTO` 与合计带上。
- [ ] Step 4: `NamespaceStatMergeTest.totalSumsPodBuckets`：两个切片各 `podRunning=2`，`total().getPodRunning()==4`；实现 `total` 求和。
- [ ] Step 5: `mvn -q -pl k8s-core,platform-api -am test -Dtest='ClusterAggregationOperationsTest,NamespaceStatMergeTest' -Dsurefire.failIfNoSpecifiedTests=false` → PASS；提交。

### Task 2: 单节点 Pod 统计（limits + 状态）

**Files:**
- Modify: `platform-common/.../dto/NodePodStatDTO.java`
- Modify: `k8s-core/.../operations/core/CoreV1NodeOperations.java`（`aggregate` 约 :231，新增 `podStat`）
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/controllers/cluster/NodeController.java`、`platform-api/src/main/java/com/coding/platformapi/controllers/NodeController.java`（及其调用 k8s-server 的 client）
- Test: `k8s-core/src/test/java/com/coding/k8score/operations/core/CoreV1NodeOperationsTest.java`

**Interfaces — Produces:**
- `NodePodStatDTO` 新增 `long cpuLimitMillicores; long memLimitBytes; long podRunning; long podPending; long podSucceeded; long podAbnormal;`（分桶口径同 Task 1，用同包的 `ClusterAggregationOperations.abnormalReason`）。
- `public NodePodStatDTO podStat(String nodeName)`：`client.pods().inAnyNamespace().withField("spec.nodeName", nodeName)` 后复用 `aggregate`；无 Pod 返回全 0 的 DTO（nodeName 已设）。
- k8s-server `GET /nodes/{name}/podstat?clusterId=`；platform-api 同路径透传。前端在 Task 13 用 `nodeApi.podStat(name, clusterId)`。

- [ ] Step 1: 测试 `aggregateSumsLimitsAndBuckets`：一个节点上 2 个 Pod（limits 500m/1Gi 与 1/512Mi，一个 Running、一个 ImagePullBackOff）；断言 `cpuLimitMillicores=1500`、`memLimitBytes=1610612736`、`podRunning=1`、`podAbnormal=1`。
- [ ] Step 2: 跑 `-Dtest=CoreV1NodeOperationsTest` → FAIL。
- [ ] Step 3: 实现 `aggregate` 扩展（limits 取 `resources.limits`，累加数组扩到 8 位）+ `podStat` + 两层端点。
- [ ] Step 4: 测试 PASS；提交（权限行在 Task 9 统一写：`'/nodes/{name}/podstat','GET','platform:cluster:manage'`）。

### Task 3: 计数 SQL

**Files:**
- Create: `platform-data/src/main/java/com/coding/data/models/system/StatusCount.java`（`@Data`：`String status; long count;`）
- Modify（接口 + XML）：`PlatformApplicationMapper`、`PlatformPipelineMapper`、`PlatformAlertEventMapper`（`mapper/k8s`）、`PlatformUserTenantMapper`（`mapper/auth`）

**Interfaces — Produces**（`namespaces` 为 null = 不限命名空间，空列表 = 无结果，返回空列表；`clusterId` 为 null = 全部集群）：
- `List<StatusCount> countByStatus(@Param("clusterId") String clusterId, @Param("namespaces") Collection<String> namespaces)` —— 应用，`deleted_at IS NULL`，按 `status` 分组。
- `List<StatusCount> countLatestRunStatus(clusterId, namespaces)` —— 流水线 LEFT JOIN 每条的最近一次 run（`created_at` 最大，同刻取 id 最大）；从没跑过的 `status` 为 null。
- `List<StatusCount> countFiringBySeverity(clusterId, namespaces)` —— `status='firing' AND alertname <> 'Watchdog'`，按 `severity` 分组（null 归一行）。
- `long countDistinctUsers(@Param("tenantId") String tenantId)` —— tenantId 为 null 数全平台去重用户。

- [ ] Step 1: 写四个方法与 XML（`namespaces` 用 `<if test="namespaces != null">` + `<choose>` 处理空列表为 `AND 1=0`）。
- [ ] Step 2: `mvn -q -pl platform-data -am compile` → 成功。
- [ ] Step 3: 用 jshell + JDBC 在开发库上**只读**执行四条展开后的 SQL 各一次（库坐标见 memory「连真集群/平台库排障的配方」），确认无语法错误、结果合理。
- [ ] Step 4: 提交。

### Task 4: 口径工具（纯函数）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/models/overview/CountBreakdown.java`（record：`long total, Map<String, Long> byStatus`；`byStatus` 用 `LinkedHashMap` 保持下列顺序，值为 0 的键也保留）
- Create: `platform-api/src/main/java/com/coding/platformapi/services/overview/OverviewBuckets.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/services/overview/NamespaceCategory.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/overview/OverviewBucketsTest.java`、`NamespaceCategoryTest.java`

**Interfaces — Produces:**
- `static CountBreakdown applications(List<StatusCount>)` 键序 `RUNNING, DEPLOYING, UNRELEASED, FAILED, OFFLINE`；映射 READY→RUNNING、DEPLOYING→DEPLOYING、DRAFT→UNRELEASED、FAILED、OFFLINE；DELETING 与未知不计入 total。
- `static CountBreakdown pipelines(List<StatusCount>)` 键序 `SUCCEEDED, FAILED, RUNNING, CANCELLED, NEVER`；TIMEOUT→FAILED、null→NEVER。
- `static CountBreakdown alerts(List<StatusCount>)` 键序 `CRITICAL, WARNING, OTHER`（大小写不敏感）。
- `static CountBreakdown middleware(List<String> statuses)` 键序 `NORMAL, ABNORMAL, PROCESSING`；READY→NORMAL、DEGRADED/FAILED→ABNORMAL、CREATING/DELETING→PROCESSING。
- `enum NamespaceCategory { SYSTEM, ALLOCATED, UNALLOCATED; static NamespaceCategory of(String ns, Set<String> allocated) }`：SYSTEM = `ProbeNamespaces.DEFAULT` ∪ `platform-system` ∪ `kube-` 前缀，**优先于** ALLOCATED。

- [ ] Step 1: 测试：`applicationsMapsDraftToUnreleasedAndSkipsDeleting`（READY 3、DRAFT 1、DELETING 2 → total 4，RUNNING 3，UNRELEASED 1）；`pipelinesFoldTimeoutIntoFailedAndNullIntoNever`；`alertsBucketUnknownSeverityAsOther`；`middlewareDegradedIsAbnormal`；`systemWinsOverAllocated`（`of("kube-system", Set.of("kube-system")) == SYSTEM`）；`platformSystemIsSystem`；`unknownIsUnallocated`。
- [ ] Step 2: 跑 → FAIL；Step 3: 实现；Step 4: PASS；提交。
