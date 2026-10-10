# listAll 补齐 + K8sServerGateway 流式化 + capability 判据收敛 — 实施计划

> **执行者**：本计划按任务逐个执行；每个 Task 末尾都有独立可验证的产出。步骤用 `- [ ]` 跟踪。
> 建议配合 superpowers:subagent-driven-development（每任务一个新 subagent）或 superpowers:executing-plans（本会话内批量执行）。

- **日期**：2026-10-10
- **来源**：B4 集群概览 Phase 1 之后的架构讨论（适配器定位审计）
- **当前状态**：**三个 Phase 全部完成，已验证，待部署**（2026-10-10 由单独 session 执行，B4 session 复核）。HEAD = `34ad076`（main），未提交。

### 完成后记（2026-10-10，复核结论）

**验证证据**（复核者独立重跑，非执行者自述）：

- `mvn -o -pl platform-common,k8s-core,k8s-server,platform-api -am test -Dtest='!WorkloadConverterStaticIpTest' -Dsurefire.failIfNoSpecifiedTests=false` → 四模块 **BUILD SUCCESS**；`NamespacedListAllTest` **18/18**、`K8sServerGatewayTest` **5/5**、`MeshServiceTest` **5/5**；platform-api 合计 207。
- `npm --prefix platform-web run build` → ✓ built（前端零改动）。
- **零暴露面**：`grep "list-all" platform-data/src/main/resources/db/migration/*.sql` 仍只有 `/configmaps/list-all`、`/pods/list-all` 两条 —— 本批**没有**新增权限行。
- **判据单一出处**：全仓 Java 里剩余的 `gateway.networking.k8s.io` 字面量都是"跟 K8s API 说话用"的 CRD group（converter `GROUP` / `.withGroup(...)` / 工厂的版本分派），派生 flag 的判据只在 `ClusterCapabilityService` 一处。
- 逐类核对 18 个 `listAll`：方法体内 `inNamespace` 出现 **0** 次，client 链与各自资源一致（含 HpaV1→`v1()` / HpaV2→`v2()`、CRD 形态用各自 `CRD`/`crd` 变量）。

**与计划的偏差 / 遗留**：

1. Task 1.4 期望的「Tests run: 17」是计划里的算错（实际 18：17 个单资源 + Workload 三族），不影响验收。
2. **网关缺一条「大 body **有效** JSON 流式解析成功」的用例**（用户 2026-10-10 决定不补）。数据正确性风险低 —— `ResponsePreviewStream.record*` 只写预览副本，喂给 Jackson 的 `buf` 原样透传；缺的是"以后有人改回整包缓冲"的那道保险。
3. `exchange_does_not_log_the_whole_body_on_parse_failure` 已收紧（`isNotEmpty` + 断言含前 8KB 预览 + 断言不含末尾哨兵串）—— 原写法在 `appender.list` 为空时会**空过**。
4. `k8s-server/README.md` 的 MeshController 行顺带同步（计划外，正确）。
5. `docs/requirements-batch-index.md` 未加本计划的行（本批非 B 编号批次，有意）。

**部署顺序**：本批无迁移、无新端点，直接起新构建即可。B4 那批的 `V2026_10_10_1` 仍须先重放。

> ⚠️ **工作区带着 B4 集群概览 Phase 1 的未提交改动**（`docs/superpowers/plans/2026-10-10-b4-cluster-overview.md`，用户在另一个 session 做的）。
> - Phase 1 / Phase 2 与它**完全不重叠**，随便做。
> - **Phase 3 会碰其中两个文件**：`platform-api/.../services/ClusterService.java`（B4 在里面加了 `overview`/`resourceBreakdown`）、`k8s-core/.../factory/KubernetesOperationsFactory.java`（B4 在里面加了 `getClusterAggregationOperation`）。改动区域不同（本计划只动 capability 派生那段），正常读文件后局部编辑即可。
> - **严禁** `git checkout` / `git stash` / `git clean` / `git restore` —— 那会丢掉 B4 Phase 1 的全部工作。也**不要**提交任何东西（用户未授权）。

**本轮用户已拍的决定**：
  1. `Secret` 的 `listAll` **先不补**（跨 ns 读全集群 Secret 是敏感面：现在平台管理员读 Secret 必须带已分配的 (tenantId, ns) 三元组，补了就变成读 kube-system 里的 SA token 那类）。
  2. **istio 族（Gateway + 5 种 Route）也补**（推翻了 `GatewayOperations` 里 2026-10-08 的「有意不覆写」）。
  3. `K8sServerGateway` 选**方案 A**（流式反序列化，不落 String）。
  4. 大规模聚合（如 B4 的 `ClusterAggregationOperations`）**可留在 k8s-server，但必须标注原因** —— 已在代码与 `backend-layering.md` §5.1 标注完毕（属已完成项，不在本计划内）。

**三个 Phase 相互独立，可分别执行与评审。** 建议顺序 **① → ② → ③**：
① 是纯机械、零暴露面；② 是唯一漏斗、先补测试再动；**③ 放最后** —— 它要改的两个文件正带着 B4 Phase 1 的未提交改动，等 B4 那批先落地（提交或合并）再动最省心。

---

## 0. 落地前必读（约定 & 模板）

| 要写什么 | 照抄这个 | 关键差异 |
|---|---|---|
| ops 的 `listAll` 覆写 | `CoreV1PodOperations.listAll()`（[k8s-core/.../operations/core/CoreV1PodOperations.java:56](../../../k8s-core/src/main/java/com/coding/k8score/operations/core/CoreV1PodOperations.java)） | 18 个类**逐份重复**，不抽公共基类/工具（见 §0.1） |
| `list` 的既有形状（`listAll` 只在它上面换一处） | 任意 ops 的 `list(namespace, labelSelector, fieldSelector)` | 唯一的差异：`.inNamespace(ns)` → `.inAnyNamespace()`，并删掉 `String ns = ...` 那行 |
| api→k8s-server 流式先例 | `K8sServerGateway.streamGet()`（同一文件已有，读 InputStream 不落 String） | `exchange` 是**信封**（`ResponseData<T>`），`streamGet` 是裸文本 |
| 越界判据 / 适配器立场 | [`docs/development/backend-layering.md`](../../development/backend-layering.md) §5（含 §5.1 规模例外、§5.2 能力 vs 暴露） | 本批所有代码都必须过这条判据 |
| 权限行写法（本批**不写**，留作参考） | `V2026_10_08_2__cross_namespace_list_permissions.sql` | 新 code 必须**显式绑角色**：`V2026_09_24_2` 的「admin→全部 platform:%」是一次性快照 SELECT，新 code 不会自动进 admin |

### 0.1 现行约定（务必遵守）

- **不抽公共基类、不抽工具方法**：每个 ops 各写一份 `listAll`（用户 2026-10-09 明确否决抽象提案）。18 份重复是正确的形态。
- **能力 ≠ 暴露**（`backend-layering.md` §5.2）：`/xxx/list-all` 端点在基类上对**所有**命名空间级资源都存在；「本资源支不支持」看 ops 有没有覆写；「要不要对外暴露」看 platform-api 权限表有没有配码。**本批只补能力，一条权限行都不配** —— 没有权限行 = 没有可达路径 = 零暴露面变化。
- **写方法必须过 `AccessBoundary`**：`listAll` 走基类的 `resolvePlatformNamespacedAccess`（要求 `PLATFORM_SCOPE`，且**不校验分配表**），不要新增任何绕过它的路径。
- **单位/口径**：本批不涉及。
- **`mvn` 用离线**：本地仓库在 `D:\repository`，加 `-o` 免联网。
- **已知既有失败**：`WorkloadConverterStaticIpTest.revert_ip_addrs_annotation_maps_to_staticIps_and_is_removed_from_annotations` 在 HEAD `34ad076` 就 NPE（`getAnnotations()` 为 null 时 `new LinkedHashMap<>(null)`），**不是本批引入**。验证命令里用 `-Dtest='!WorkloadConverterStaticIpTest'` 排除它；**不要**顺手"修"它（不在本批范围）。

---

## Phase 1 · 补齐 18 个 ops 的 `listAll`

**目标**：所有命名空间级 ops 都支持跨命名空间列举（`Secret` 除外），让「api 侧做跨 ns 聚合」在能力上不再有缺口。

### Task 1.1：覆盖清单与规则（先读，不改代码）

**产物**：无代码；确认下表与 `grep` 结果一致。

每个类的改法**只有一个规则**：复制本类 `list(...)` 方法体 → `.inNamespace(ns)` 换成 `.inAnyNamespace()` → 删掉 `String ns = StringUtils.hasText(namespace) ? namespace : "";` → 方法签名改成 `public List<XxxDTO> listAll(String labelSelector, String fieldSelector)`（去掉 `namespace` 参数）→ 加 `@Override`。

| # | 类（`k8s-core/.../operations/` 下） | client 链（`list` 里那一句） | DTO | 备注 |
|---|---|---|---|---|
| 1 | `workload/WorkloadOperations` | `client.apps().deployments()/statefulSets()/daemonSets()` **三条** | `WorkloadDTO` | **特例**：三族合并成一个 List，见 Task 1.5 |
| 2 | `workload/AppsV1ReplicaSetOperations` | `client.resources(ReplicaSet.class)` | `ReplicaSetDTO` | |
| 3 | `core/CoreV1ServiceOperations` | `client.services()` | `ServiceDTO` | Task 1.3 的首个完整样例 |
| 4 | `core/CoreV1PersistentVolumeClaimOperations` | `client.persistentVolumeClaims()` | `PersistentVolumeClaimDTO` | |
| 5 | `core/CoreV1ResourceQuotaOperations` | `client.resourceQuotas()` | `ResourceQuotaDTO` | |
| 6 | `core/CoreV1LimitRangeOperations` | `client.limitRanges()` | `LimitRangeDTO` | |
| 7 | `core/CoreV1ServiceAccountOperations` | `client.serviceAccounts()` | `ServiceAccountDTO` | |
| 8 | `rbac/RbacV1RoleBindingOperations` | `client.rbac().roleBindings()` | `RoleBindingDTO` | |
| 9 | `monitoring/ServiceMonitorOperations` | `client.genericKubernetesResources(CRD)` | `ServiceMonitorDTO` | CRD 形态，同 `list` |
| 10 | `monitoring/PodMonitorOperations` | `client.genericKubernetesResources(CRD)` | `PodMonitorDTO` | 同上 |
| 11 | `autoscaling/HpaV1Operations` | `client.autoscaling().v1().horizontalPodAutoscalers()` | `HpaDTO` | |
| 12 | `autoscaling/HpaV2Operations` | `client.autoscaling().v2().horizontalPodAutoscalers()` | `HpaDTO` | |
| 13 | `gateway/GatewayOperations` | `client.genericKubernetesResources(CRD)` | `GatewayDTO` | **要同时改注释**，见 Task 1.6 |
| 14 | `gateway/HttpRouteOperations` | 同上 | `HttpRouteDTO` | |
| 15 | `gateway/GrpcRouteOperations` | 同上 | `GrpcRouteDTO` | |
| 16 | `gateway/TcpRouteOperations` | 同上 | `TcpRouteDTO` | |
| 17 | `gateway/TlsRouteOperations` | 同上 | `TlsRouteDTO` | |
| 18 | `gateway/UdpRouteOperations` | 同上 | `UdpRouteDTO` | |

**⛔ 明确不补**：`core/CoreV1SecretOperations`（用户 2026-10-10 决定）。在该类里加一行注释说明原因，见 Task 1.7。

- [x] **Step 1**：跑 `grep -rl "implements NamespacedOperations" k8s-core/src/main/java` 与上表比对；再对每个类确认 `grep -c "listAll" <file>` == 0。数目应为 **19 个缺**（18 补 + Secret 不补），已有 2 个（`CoreV1PodOperations` / `CoreV1ConfigMapOperations`）。

### Task 1.2：测试骨架（先写，后实现）

**Files**
- Create: `k8s-core/src/test/java/com/coding/k8score/operations/NamespacedListAllTest.java`

**Interfaces**
- Consumes: 各 ops 的公开构造器（`(KubernetesClient, CommonConverter<...>)` 或仅 `(KubernetesClient)`）、`NamespacedOperations#listAll(String labelSelector, String fieldSelector)`
- Produces: 无（测试类）

测试要抓的真实 bug 是**复制粘贴错误**：把某个资源接到了别的 client 上（`.inNamespace(ns)` 没换、或 client getter 抄错）。所以每条用例都要：
1. 只 stub **本资源自己的** client 链（抄错 → 未 stub → NPE，响亮失败）；
2. 断言 `inAnyNamespace()` 恰好一次、`inNamespace(anyString())` **从未**；
3. 断言返回的 DTO 名字/命名空间与喂进去的对象一致。

骨架（`null`/`anyString` 的静态导入按本仓既有测试风格）：

```java
package com.coding.k8score.operations;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AnyNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * listAll 覆盖测试：每个命名空间级资源一条用例。
 * <p>抓的是「复制粘贴错误」——接错 client / 忘了把 inNamespace(ns) 换成 inAnyNamespace()。
 * 故每条用例只 stub 本资源自己的 client 链：抄错 getter 会得到未 stub 的 null → NPE（响亮失败），
 * 而不是安静地返回空列表。
 * <p>Secret 有意没有 listAll（2026-10-10 决定，见 CoreV1SecretOperations 的注释），故此处没有它的用例。
 */
class NamespacedListAllTest {

    /**
     * 挂一条 {@code inAnyNamespace().list()} 链并返回 (mixed, any) 两个 mock 供 verify。
     * <p>不用 RETURNS_DEEP_STUBS：{@code inAnyNamespace()} 的返回类型带泛型变量，deep stubs 解不出来会返回 null
     * （MeshOperationsTest 踩过）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends HasMetadata, L extends KubernetesResourceList<T>> MixedOperation<T, L, ?> stubAny(
            MixedOperation<T, L, ?> op, L list) {
        AnyNamespaceOperation any = mock(AnyNamespaceOperation.class);
        when(((MixedOperation) op).inAnyNamespace()).thenReturn(any);
        when(any.list(any(io.fabric8.kubernetes.api.model.ListOptions.class))).thenReturn(list);
        clearInvocations(op);
        return op;
    }

    /** 每个用例末尾都调它：一次 inAnyNamespace、从不 inNamespace(具体 ns)。 */
    private static void assertCrossNamespace(MixedOperation<?, ?, ?> op) {
        verify(op, times(1)).inAnyNamespace();
        verify(op, never()).inNamespace(anyString());
    }
}
```

> ⚠️ `list(options)` 有重载：本仓 ops 一律传 `ListOptions`（不是无参 `list()`），所以 stub 用 `any(ListOptions.class)`。落地时若某个 ops 调的是无参 `list()`，改用 `when(any.list())`。

- [x] **Step 1**：写上述骨架（此时无 `@Test`，编译通过即可）。
- [x] **Step 2**：跑 `mvn -o -pl platform-common,k8s-core -am test -Dtest=NamespacedListAllTest -Dsurefire.failIfNoSpecifiedTests=false`，期望 **BUILD SUCCESS（0 用例）**。

### Task 1.3：第一个完整实现（CoreV1ServiceOperations）+ 用例

**Files**
- Modify: `k8s-core/src/main/java/com/coding/k8score/operations/core/CoreV1ServiceOperations.java`（加 `listAll`）
- Test: `k8s-core/src/test/java/com/coding/k8score/operations/NamespacedListAllTest.java`（加第一个用例）

- [x] **Step 1：先写失败用例**

```java
    @Test
    void service_listAll_lists_across_namespaces() {
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation<Service, ServiceList, ServiceResource<Service>> services = mock(MixedOperation.class);
        when(client.services()).thenReturn(services);
        ServiceList list = new ServiceListBuilder().withItems(
                new ServiceBuilder().withNewMetadata().withNamespace("ns-a").withName("svc-a").endMetadata().build(),
                new ServiceBuilder().withNewMetadata().withNamespace("kube-system").withName("svc-b").endMetadata().build()
        ).build();
        stubAny(services, list);

        CoreV1ServiceOperations ops = new CoreV1ServiceOperations(client, new CoreV1ServiceConverter());
        List<ServiceDTO> out = ops.listAll(null, null);

        assertThat(out).extracting(ServiceDTO::getName).containsExactly("svc-a", "svc-b");
        assertThat(out).extracting(ServiceDTO::getNamespace).containsExactly("ns-a", "kube-system");
        assertCrossNamespace(services);
    }
```

- [x] **Step 2：跑，确认失败**
  Run: `mvn -o -pl platform-common,k8s-core -am test -Dtest=NamespacedListAllTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: 编译失败 / 方法不存在（`listAll` 未定义）。

- [x] **Step 3：实现**

```java
    /**
     * 跨全部命名空间列举（平台侧；本实例必须是 admin client，见 {@link NamespacedOperations#listAll}）。
     * <p>返回的每个 item 自带其所在 namespace —— converter 从 metadata 取，本方法<b>不做</b>统一回填。
     */
    @Override
    public List<ServiceDTO> listAll(String labelSelector, String fieldSelector) {
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<Service> items = client.services().inAnyNamespace().list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }
```

- [x] **Step 4：跑，确认通过**
  Run: 同 Step 2 命令。
  Expected: `Tests run: 1, Failures: 0, Errors: 0`。

### Task 1.4：其余 15 个单资源类（逐个用例 + 实现）

**Files**
- Modify: 上表 #2、#4–#12、#14–#18（共 16 个文件；#3 已完成）
- Test: 同一 `NamespacedListAllTest`，每个资源加一个 `@Test` 方法

**做法**：对每个类，按 Task 1.3 的四步走一遍（先用例 → 红 → 实现 → 绿）。用例之间的差异只有三处：client getter、`CommonConverter` 实现类、DTO 类型。以下是每个类的**精确取值**（照着填，不要猜）：

| 类 | client getter（stub 用） | converter | DTO / List 类型 |
|---|---|---|---|
| `AppsV1ReplicaSetOperations` | `client.resources(ReplicaSet.class)` | `new AppsV1ReplicaSetConverter()` | `ReplicaSetDTO` / `ReplicaSetList` |
| `CoreV1PersistentVolumeClaimOperations` | `client.persistentVolumeClaims()` | `new CoreV1PvcConverter()` | `PersistentVolumeClaimDTO` / `PersistentVolumeClaimList` |
| `CoreV1ResourceQuotaOperations` | `client.resourceQuotas()` | `new CoreV1ResourceQuotaConverter()` | `ResourceQuotaDTO` / `ResourceQuotaList` |
| `CoreV1LimitRangeOperations` | `client.limitRanges()` | `new CoreV1LimitRangeConverter()` | `LimitRangeDTO` / `LimitRangeList` |
| `CoreV1ServiceAccountOperations` | `client.serviceAccounts()` | `new CoreV1ServiceAccountConverter()` | `ServiceAccountDTO` / `ServiceAccountList` |
| `RbacV1RoleBindingOperations` | `client.rbac().roleBindings()` | `new RbacV1RoleBindingConverter()` | `RoleBindingDTO` / `RoleBindingList` |
| `ServiceMonitorOperations` | `client.genericKubernetesResources(<本类 CRD 常量>)` | `new ServiceMonitorConverter()` | `ServiceMonitorDTO` / `GenericKubernetesResourceList` |
| `PodMonitorOperations` | 同上（各自 CRD） | `new PodMonitorConverter()` | `PodMonitorDTO` / 同上 |
| `HpaV1Operations` | `client.autoscaling().v1().horizontalPodAutoscalers()` | `new HpaV1Converter()` | `HpaDTO` / `HorizontalPodAutoscalerList` |
| `HpaV2Operations` | `client.autoscaling().v2().horizontalPodAutoscalers()` | `new HpaV2Converter()` | `HpaDTO` / 同上 |
| `GatewayOperations` | `client.genericKubernetesResources(<CRD>)` | `new GatewayConverter()` | `GatewayDTO` / `GenericKubernetesResourceList` |
| `HttpRouteOperations` | 同上 | `new HttpRouteConverter()` | `HttpRouteDTO` / 同上 |
| `GrpcRouteOperations` | 同上 | `new GrpcRouteConverter()` | `GrpcRouteDTO` / 同上 |
| `TcpRouteOperations` | 同上 | `new TcpRouteConverter(...)` | `TcpRouteDTO` / 同上 |
| `TlsRouteOperations` | 同上 | `new TlsRouteConverter(...)` | `TlsRouteDTO` / 同上 |
| `UdpRouteOperations` | 同上 | `new UdpRouteConverter(...)` | `UdpRouteDTO` / 同上 |

⚠️ `Tcp/Tls/UdpRouteOperations` 的 converter 构造器**带版本参数**（`resolveL4Version(clusterId)` 那条链，见 `KubernetesOperationsFactory`）—— 抄本类现有的构造器调用方式，别自己编。

⚠️ CRD 形态的类（SM/PM/Gateway/Route）：`client.genericKubernetesResources(CRD)` 返回 `MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>>`。**第二个完整样例（CRD 形态，照着抄）** —— `ServiceMonitorOperations`：

```java
    @Test
    void serviceMonitor_listAll_lists_across_namespaces() {
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList,
                Resource<GenericKubernetesResource>> sms = mock(MixedOperation.class);
        when(client.genericKubernetesResources(any(CustomResourceDefinitionContext.class))).thenReturn(sms);
        GenericKubernetesResource a = generic("ns-a", "sm-a");
        GenericKubernetesResource b = generic("kube-system", "sm-b");
        stubAny(sms, new GenericKubernetesResourceListBuilder().withItems(a, b).build());

        ServiceMonitorOperations ops = new ServiceMonitorOperations(client, new ServiceMonitorConverter());
        List<ServiceMonitorDTO> out = ops.listAll(null, null);

        assertThat(out).extracting(ServiceMonitorDTO::getName).containsExactly("sm-a", "sm-b");
        assertThat(out).extracting(ServiceMonitorDTO::getNamespace).containsExactly("ns-a", "kube-system");
        assertCrossNamespace(sms);
    }

    /** CRD 形态的对象（converter 从 metadata 取 namespace/name） */
    private static GenericKubernetesResource generic(String ns, String name) {
        GenericKubernetesResource r = new GenericKubernetesResource();
        r.setMetadata(new ObjectMetaBuilder().withNamespace(ns).withName(name).build());
        return r;
    }
```

（若 `client.genericKubernetesResources(...)` 的 stub 因 CRD 常量不可见而写不出来，就在 `when(...)` 里放宽匹配器，但**必须**保留 `verify(op, never()).inNamespace(anyString())` 这条断言 —— 它才是抓复制粘贴错误的那条。）

- [x] **Step 1**：16 个类按上表逐个（用例→红→实现→绿）。
- [x] **Step 2**：跑整类
  Run: `mvn -o -pl platform-common,k8s-core -am test -Dtest=NamespacedListAllTest`
  Expected: `Tests run: 17`（1 个 service + 16 个）、全绿。

### Task 1.5：WorkloadOperations 特例（三族合并）

**Files**
- Modify: `k8s-core/src/main/java/com/coding/k8score/operations/workload/WorkloadOperations.java`
- Test: `NamespacedListAllTest` 加一个用例

`WorkloadOperations.list` 是**三条** `inNamespace(ns).list()` 后合并成一个 `List<WorkloadDTO>`；`listAll` 就是这三条各换成 `inAnyNamespace()`。注意三族用的是**各自**的 fabric8 类型（`Deployment` / `StatefulSet` / `DaemonSet`），converter 是同一个 `WorkloadConverter`。

- [x] **Step 1：用例**（stub 三条链，喂 1 个 Deployment + 1 个 StatefulSet + 1 个 DaemonSet，断言返回 3 个 DTO 且 kind 正确）
- [x] **Step 2：跑红**
- [x] **Step 3：实现**

```java
    /**
     * 跨全部命名空间列举（平台侧；本实例必须是 admin client，见 {@link NamespacedOperations#listAll}）。
     * <p>三族（Deployment / StatefulSet / DaemonSet）一次列全，合并成一个 List —— 与 {@link #list} 同形，
     * 只是把 inNamespace(ns) 换成 inAnyNamespace()。
     */
    @Override
    public List<WorkloadDTO> listAll(String labelSelector, String fieldSelector) {
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<WorkloadDTO> result = new ArrayList<>();
        client.apps().deployments().inAnyNamespace().list(options).getItems()
                .forEach(d -> result.add(converter.revert(d)));
        client.apps().statefulSets().inAnyNamespace().list(options).getItems()
                .forEach(s -> result.add(converter.revert(s)));
        client.apps().daemonSets().inAnyNamespace().list(options).getItems()
                .forEach(ds -> result.add(converter.revert(ds)));
        return result;
    }
```

- [x] **Step 4：跑绿**

### Task 1.6：Gateway 族的注释同步（推翻旧决策）

**Files**
- Modify: `k8s-core/src/main/java/com/coding/k8score/operations/gateway/GatewayOperations.java`（删掉"有意不覆写"那段）

`GatewayOperations` 现在写着「**有意不覆写** `NamespacedOperations#listAll`：Gateway 归租户域（2026-10-08 决策）」。本批推翻了它 —— **代码与注释必须一致**，否则下一个人会以为这是遗漏。

- [x] **Step 1**：把那段注释改成（放在类 javadoc 或 `listAll` 上）：

```java
    /**
     * 跨全部命名空间列举（平台侧）。
     * <p><b>2026-10-08 曾"有意不覆写"</b>（理由：Gateway 属租户域，租户要"我全部命名空间的 Gateway"
     * 应由 platform-api 扇出）。<b>2026-10-10 推翻</b>：能力与暴露是两个闸门（backend-layering.md §5.2），
     * 覆写只是补能力 —— 没有权限行就没有可达路径，租户侧的扇出语义不受影响；而平台侧确有"全集群网关视图"
     * 的需求（B6 的平台页）。故与其它命名空间级资源保持一致。
     */
```

- [x] **Step 2**：确认 `backend-layering.md` §5.2 的表下也补一句同样的记录（该表把「租户边界 × 跨命名空间」标为 ❌，本批没有改变那一格 —— 变的是**平台边界**这一列多了一个可用能力）。

### Task 1.7：Secret 的"有意不覆写"落成注释

**Files**
- Modify: `k8s-core/src/main/java/com/coding/k8score/operations/core/CoreV1SecretOperations.java`

- [x] **Step 1**：在类 javadoc 末尾加：

```java
 * <p><b>有意不覆写 {@link NamespacedOperations#listAll}（2026-10-10 决定）</b>：其它命名空间级资源本批都补齐了
 * 跨命名空间列举，Secret 单独保留 —— 现在平台管理员读 Secret 必须显式带<b>已分配的</b> (tenantId, ns) 三元组；
 * 一旦支持 list-all，就变成"读全集群每个命名空间的 Secret"，含 kube-system 里 SA token 那类。那不是能力补齐，
 * 是敏感面扩张，须单独评估后再放开（届时同时要配 `platform:secret:list-all` 权限行的显式角色绑定）。
```

### Task 1.8：Phase 1 验证

- [x] **Step 1**
```bash
mvn -o -pl platform-common,k8s-core,k8s-server,platform-api -am test -Dtest='!WorkloadConverterStaticIpTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 四模块 `BUILD SUCCESS`；`NamespacedListAllTest` 18 个用例全绿。

- [x] **Step 2**：确认**没有**新增权限行、没有新增端点、没有新增 api 侧方法（`grep -rn "list-all" platform-data/src/main/resources/db/migration/` 仍只有 configmaps/pods 两行）。这一步是本 Phase「零暴露面」承诺的证据。

---

## Phase 2 · `K8sServerGateway` 方案 A（流式反序列化）

**目标**：api→k8s-server 的响应不再整包缓冲成 String；解析失败时**不再把整个 body 写进 ERROR 日志**（现状 `log.error("解析 k8s-server {} 响应失败：{}", path, sb.body(), e)`）。

**背景数据**（动手前读一遍，改完要能解释）：
- 现状峰值 ≈ `byte[] + String(≈2×) + 对象图`；改后 ≈ Jackson 增量缓冲（KB 级）+ 对象图。
- `K8sServerGateway` 是**所有** api→k8s-server 调用的唯一漏斗（`K8sClient` / Calico / Node / Pod / Lifecycle 各 client 全走它）。目前**没有它的单测** —— 所以顺序是：**先补基线测试，再改**。
- Jackson 3（`tools.jackson`）的 `JacksonException extends RuntimeException`（已核实），所以可以在 `RestClient.exchange` 的 lambda 里直接抛；`JsonMapper.readValue(InputStream, JavaType)` 存在（已核实）。
- `SimpleClientHttpRequestFactory` = `HttpURLConnection`：**不会**自动加 `Accept-Encoding`、也不自动解压 —— 所以方案 B（gzip）留给下一轮，本 Phase 不做。

### Task 2.1：基线测试（stub HTTP server，先全绿）

**Files**
- Create: `platform-api/src/test/java/com/coding/platformapi/k8s/K8sServerGatewayTest.java`

**依赖**：JDK 自带的 `com.sun.net.httpserver.HttpServer`（classpath 下可直接用，**不引新依赖**）；`spring-boot-starter-test` 已在 `platform-api/pom.xml`。

```java
package com.coding.platformapi.k8s;

import com.coding.common.exception.CloudPlatformException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 网关的**行为**测试（起一个真 HTTP stub，不打真 k8s-server）：
 * 成功信封 / 空 body / 坏 JSON / 业务 code≠200 四条路径。
 * <p>为什么用 JDK HttpServer 而不是 mock 掉 RestClient：本类要保的正是"真实 HTTP 下的流式行为"，
 * 把 HTTP 层 mock 掉等于把被测对象抽掉。也顺带避免为测试改生产代码的构造器。
 */
class K8sServerGatewayTest {

    private HttpServer server;
    private K8sServerGateway gateway;
    /** 每个用例设成本次响应体（UTF-8） */
    private final AtomicReference<String> body = new AtomicReference<>("");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] out = body.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status.get(), out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
        gateway = new K8sServerGateway("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** 成功信封：{"code":200,"data":{...}} → 返回 data */
    @Test
    void exchange_returns_data_on_success_envelope() {
        body.set("{\"code\":200,\"msg\":\"ok\",\"data\":{\"version\":\"v1.30.0\"}}");

        AdminProbeResult r = gateway.exchange(HttpMethod.POST, "/x", null, null,
                gateway.responseType(AdminProbeResult.class));

        assertThat(r.getVersion()).isEqualTo("v1.30.0");
    }

    /** 空 body（请求没走到业务层，如安全链 401 entry point）→ CloudPlatformException */
    @Test
    void exchange_throws_on_empty_body() {
        body.set("");
        assertThatThrownBy(() -> gateway.exchange(HttpMethod.POST, "/x", null, null,
                gateway.responseType(AdminProbeResult.class)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("空响应");
    }

    /** 坏 JSON → CloudPlatformException（**且日志里不含全量 body**，见 Task 2.2） */
    @Test
    void exchange_throws_on_malformed_json() {
        body.set("not json at all");
        assertThatThrownBy(() -> gateway.exchange(HttpMethod.POST, "/x", null, null,
                gateway.responseType(AdminProbeResult.class)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("解析失败");
    }

    /** 信封里 code≠200 → 原样转业务异常（保 msg） */
    @Test
    void exchange_throws_business_error_with_msg() {
        body.set("{\"code\":500,\"msg\":\"集群不可达\"}");
        assertThatThrownBy(() -> gateway.exchange(HttpMethod.POST, "/x", null, null,
                gateway.responseType(AdminProbeResult.class)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("集群不可达");
    }
}
```

- [x] **Step 1**：写下这四个用例。
- [x] **Step 2**：
  Run: `mvn -o -pl platform-api -am test -Dtest=K8sServerGatewayTest`
  Expected: **全绿**（这是基线：现状行为正确，只是实现方式差）。若某条红，先搞清楚是不是现状真的不满足，再继续。

### Task 2.2：加"坏 JSON 不得整包进日志"的测试（红）

**Files**
- Test: `K8sServerGatewayTest`（加一个用例 + logback `ListAppender`）

```java
    /**
     * 5MB 的坏 body：日志里**不得**出现全量内容（现状实现把整个 body 当参数传给 log.error）。
     * 用 body 末尾的哨兵串判定 —— 若它出现在任何一条日志里，就说明整包被打了。
     */
    @Test
    void exchange_does_not_log_the_whole_body_on_parse_failure() {
        String sentinel = "SENTINEL-END-OF-BODY";
        body.set("x".repeat(5 * 1024 * 1024) + sentinel);

        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(K8sServerGateway.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> gateway.exchange(HttpMethod.POST, "/x", null, null,
                    gateway.responseType(AdminProbeResult.class)))
                    .isInstanceOf(CloudPlatformException.class);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .allSatisfy(e -> assertThat(e.getFormattedMessage().length())
                        .as("日志只能带前 8KB 预览，不能带全量 body")
                        .isLessThan(16 * 1024));
    }
```

（`ch.qos.logback.classic.Logger` / `ch.qos.logback.classic.spi.ILoggingEvent` / `ch.qos.logback.core.read.ListAppender` —— logback 经 `spring-boot-starter-webmvc` 已在 classpath。两个测试片段里省略的 import：`com.coding.common.models.k8s.dto.admin.AdminProbeResult`、`org.springframework.http.HttpMethod`、`java.util.List`、以及上面三个 logback 类。）

> **退路（若 `com.sun.net.httpserver` 不可用）**：`jdk.httpserver` 模块在 classpath 模式下默认是解析的，正常可用。万一你的 JVM/surefire 配置下不可见，退路是给 `K8sServerGateway` 加一个**包内可见**的构造器重载 `K8sServerGateway(RestClient.Builder)`（生产构造器改为委托它），测试里用 `spring-test` 的 `MockRestServiceServer.bindTo(builder)` —— 代价是生产代码多一个构造器，且不再走真 HTTP。

- [x] **Step 1**：写下它。
- [x] **Step 2**：
  Run: `mvn -o -pl platform-api -am test -Dtest=K8sServerGatewayTest#exchange_does_not_log_the_whole_body_on_parse_failure`
  Expected: **FAIL**（现状日志带全量 5MB）。

### Task 2.3：实现 `ResponsePreviewStream`（记录前 8KB + 计数）

**Files**
- Modify: `platform-api/src/main/java/com/coding/platformapi/k8s/K8sServerGateway.java`（加一个 `private static final class`）

```java
    /**
     * 记录「前 {@value #PREVIEW_BYTES} 字节 + 累计字节数」的包装流。
     * <p>存在的唯一理由：流式解析后 body 已被消费、拿不回来，而报错时**需要**一点上下文 ——
     * 旧实现是 "整包读成 String"，于是解析失败时能把整个 body 写进日志（大响应 = 日志盘 + 堆双爆）。
     * 这里把上下文限制在前 8KB，既够定位（k8s-server 的错误信封很短），又不会把大 body 拖进日志。
     * <p>本仓没引 commons-io，故手写（等价于 TeeInputStream + CountingInputStream 的组合）。
     */
    private static final class ResponsePreviewStream extends FilterInputStream {

        private static final int PREVIEW_BYTES = 8 * 1024;

        private final ByteArrayOutputStream preview = new ByteArrayOutputStream();
        private long total;

        private ResponsePreviewStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                recordByte(b);
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) {
                recordBytes(buf, off, n);
            }
            return n;
        }

        private void recordByte(int b) {
            total++;
            if (preview.size() < PREVIEW_BYTES) {
                preview.write(b);
            }
        }

        private void recordBytes(byte[] buf, int off, int len) {
            total += len;
            int room = PREVIEW_BYTES - preview.size();
            if (room > 0) {
                preview.write(buf, off, Math.min(room, len));
            }
        }

        /** 已读总字节数（== 0 即"空响应"）。 */
        private long totalBytes() {
            return total;
        }

        /** 前 8KB 的文本（仅用于日志，可能截断在字符中间 —— 无所谓，它是给人看的）。 */
        private String previewText() {
            return preview.toString(StandardCharsets.UTF_8);
        }
    }
```

需要的 import：`java.io.ByteArrayOutputStream`、`java.io.FilterInputStream`、`java.io.IOException`、`java.io.InputStream`、`java.nio.charset.StandardCharsets`。

- [x] **Step 1**：加这个内部类（此时还不被使用，先编译过）。
- [x] **Step 2**：`mvn -o -pl platform-api -am compile`，Expected: SUCCESS。

### Task 2.4：`exchange` 改流式

**Files**
- Modify: `platform-api/src/main/java/com/coding/platformapi/k8s/K8sServerGateway.java`（`exchange` 方法体）

**改后全文**（替换现有 `exchange`；`streamGet` / `buildUri` / `passThroughAuthorization` 不动；`StatusBody` record 删除，`ErrorInfo` 保留给 `streamGet`）：

```java
    /**
     * 调 k8s-server（任意动词）：透传 Authorization，code≠200 原样转 CloudPlatformException。
     * respType 为 ResponseData&lt;T&gt; 完整类型；返回 data。
     *
     * <p><b>流式（2026-10-10 方案 A）</b>：响应体从 {@code InputStream} 直接反序列化，<b>不再</b>先读成 String
     * —— 旧实现的峰值是「byte[] + String(≈2×) + 对象图」，大响应（跨命名空间列举）上就是几百 MB。
     * 报错上下文由 {@link ResponsePreviewStream} 限在前 8KB，避免"解析失败时把整包 body 写进日志"。
     */
    public <T> T exchange(HttpMethod method, String path, Map<String, String> params, Object body, JavaType respType) {
        String uri = buildUri(path, params);
        try {
            var spec = restClient.method(method)
                    .uri(uri)
                    .headers(this::passThroughAuthorization);
            if (body != null) {
                spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            //不用 retrieve()：401/403 也带 ResponseData JSON，统一按 body.code 判定
            return spec.exchange((request, response) -> {
                int status = response.getStatusCode().value();
                ResponsePreviewStream in = new ResponsePreviewStream(response.getBody());
                ResponseData<T> resp;
                try {
                    resp = jsonMapper.readValue(in, respType);
                } catch (Exception e) {
                    if (in.totalBytes() == 0) {
                        log.error("k8s-server {} 返回空响应，HTTP {}", path, status);
                        throw new CloudPlatformException(EnumResponseType.ERROR,
                                "k8s-server 返回空响应 (HTTP " + status + ")");
                    }
                    log.error("解析 k8s-server {} 响应失败（HTTP {}，共 {} 字节，前 8KB：{}）",
                            path, status, in.totalBytes(), in.previewText(), e);
                    throw new CloudPlatformException(EnumResponseType.ERROR, "k8s-server 响应解析失败");
                }
                if (resp == null || resp.getCode() == null || !resp.getCode().equals(EnumResponseType.SUCCESS.getCode())) {
                    Integer code = resp != null && resp.getCode() != null ? resp.getCode() : EnumResponseType.ERROR.getCode();
                    String msg = resp != null && resp.getMsg() != null ? resp.getMsg() : "k8s-server 调用失败";
                    throw new CloudPlatformException(code, msg);
                }
                return resp.getData();
            });
        } catch (CloudPlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 k8s-server {} {} 失败", method, path, e);
            throw new CloudPlatformException(EnumResponseType.ERROR, "调用 k8s-server 失败: " + e.getMessage());
        }
    }
```

**落地时必须确认的两点**
1. `jsonMapper.readValue` 对**空输入**的行为：Jackson 3 抛 `MismatchedInputException`（Jackson 2 返回 null）。两种情况都被上面的 `catch` 覆盖 —— 空流走 `totalBytes() == 0` 分支，语义与旧实现一致（"空响应"）。
2. 解析失败时**不能**再读 body（流已被 Jackson 消费）—— 这正是 `previewText()` 存在的理由。不要试图 `response.bodyTo(String.class)` 补救。
3. 删掉不再使用的 `StatusBody` record（保留 `ErrorInfo`，`streamGet` 还在用）。

- [x] **Step 1**：按上面替换 `exchange`，删 `StatusBody`。
- [x] **Step 2**：
  Run: `mvn -o -pl platform-api -am test -Dtest=K8sServerGatewayTest`
  Expected: **5 个用例全绿**（含 Task 2.2 那条原来红的）。

### Task 2.5：Phase 2 验证

- [x] **Step 1**
```bash
mvn -o -pl platform-common,k8s-core,k8s-server,platform-api -am test -Dtest='!WorkloadConverterStaticIpTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 四模块全绿（这次是全量回归 —— 网关是唯一漏斗，任何 client 的行为变化都会在这里暴露）。

- [x] **Step 2**：跑一次前端 build 确认无连带影响
```bash
npm --prefix platform-web run build
```

---

## Phase 3 · capability 判据收敛（适配器不再派生业务 flag）

**目标**：`hasGatewayApi` / `hasIstio` / `gatewayApiVersions` 这类**从 DB 的 `k8s_cluster.capability` 列派生**的判据，从 k8s-core 上移到 platform-api —— 适配器不读 DB 列做业务判断；同时消掉「同一判据两份实现」（`MeshOperations` 与我为 B4 写的 `ClusterService.capabilitySummary`）。

**副产物**：`KubernetesOperationsFactory.getMeshOperation` 不再读 DB（`readCapability` 只剩 HPA / L4 版本分派在用，那是 K8s 语义，正当）。

### Task 3.1：新建 `ClusterCapabilityService`（判据唯一出处）

**Files**
- Create: `platform-api/src/main/java/com/coding/platformapi/services/ClusterCapabilityService.java`

**Interfaces（后续 Task 依赖这些签名）**
- `Map<String, List<String>> groups(String clusterId)`
- `CapabilitySummaryDTO summary(String clusterId)`
- `List<String> versionsOf(String clusterId, String group)`
- `boolean hasIstio(String clusterId)`

把 `ClusterService` 里的 7 个 group 常量 + `parseCapability` + `capabilitySummary` **整体搬进来**（含 javadoc 里「判据与 MeshOperations 同源，改动须同步」那句 —— 搬完这句话就不需要了，因为只剩一处）。

- [x] **Step 1**：建类并搬迁代码；`ClusterService.capabilitySummary(cluster)` 改为 `capabilityService.summary(clusterId)`。
- [x] **Step 2**：
  Run: `mvn -o -pl platform-api -am test -Dtest=ClusterServiceTest`
  Expected: 全绿（`ClusterServiceTest` 里既有 8 个用例覆盖了 flag 派生与降级，等于给搬迁上了回归网）。

### Task 3.2：`MeshOperations` 只留 ambient 活探测

**Files**
- Modify: `k8s-core/src/main/java/com/coding/k8score/operations/gateway/MeshOperations.java`
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/controllers/gateway/MeshController.java`
- Modify: `k8s-core/src/main/java/com/coding/k8score/factory/KubernetesOperationsFactory.java`（`getMeshOperation` 不再传 capability）
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/admin/AdminMeshProbeResult.java`
- Test: `k8s-core/src/test/java/com/coding/k8score/operations/gateway/MeshOperationsTest.java`（**删掉 4 个 capability 派生用例**，保留/改写 ambient 的 5 个）

新 DTO（放 `.dto.admin`，与 `AdminProbeResult` 同族）：

```java
package com.coding.common.models.k8s.dto.admin;

import lombok.Data;

/** 网格**活探测**结果（k8s-server → platform-api）：只含"资源 probe"那一半。
 *  <p>discovery 那一半（hasGatewayApi / hasIstio / gatewayApiVersions）是 {@code k8s_cluster.capability}
 *  列的派生，属 platform-api（2026-10-10 起）。 */
@Data
public class AdminMeshProbeResult {
    /** Istio 处于 ambient 模式（探测到 ztunnel DaemonSet） */
    private boolean istioAmbient;
}
```

`MeshOperations.status()` → `ambient()`：只调 `probeAmbient()` 并返回 `AdminMeshProbeResult`；构造器去掉 `capability` 参数；类 javadoc 保留「capability 那半已上移 api」的记录。

- [x] **Step 1**：改 k8s-core（`MeshOperations` + 工厂），跑 `mvn -o -pl platform-common,k8s-core -am test -Dtest=MeshOperationsTest -Dsurefire.failIfNoSpecifiedTests=false` → 期望全绿（改完的 ambient 用例）。
- [x] **Step 2**：改 k8s-server `MeshController`（`/mesh/status` 返回 `AdminMeshProbeResult`），跑 `mvn -o -pl k8s-server -am test`。

### Task 3.3：api 侧组装 `MeshStatusDTO`

**Files**
- Modify: `platform-api/src/main/java/com/coding/platformapi/k8s/K8sMeshClient.java`（`meshStatus` → `meshAmbient`，返回 `boolean`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/MeshService.java`（注入 `ClusterCapabilityService`，组装 `MeshStatusDTO`）
- Create: `platform-api/src/test/java/com/coding/platformapi/services/MeshServiceTest.java`

组装口径（写进方法 javadoc）：
- `hasGatewayApi` = capability 含 `gateway.networking.k8s.io`
- `gatewayApiVersions` = 该 group 的 versions
- `hasIstio` = capability 含 `istio.io` **或** `networking.istio.io`
- `istioAmbient` = k8s-server 活探测（失败按 false，**不**上抛 —— 与现状一致：探测失败只是信息位变 false）
- ⚠️ 现状 `MeshStatusDTO` 是 k8s-core 造的；改后由 api 造，**对外 shape 不变** → 前端 `useMeshStatus` / `MeshStatusBanner` 不用改。

- [x] **Step 1**：写 `MeshServiceTest`（喂 capability JSON + mock ambient，断言四个字段；再加一条 ambient 探测抛错 → `istioAmbient=false` 且不抛）。
- [x] **Step 2**：跑红 → 实现 → 跑绿。

### Task 3.4：Phase 3 验证

- [x] **Step 1**
```bash
mvn -o -pl platform-common,k8s-core,k8s-server,platform-api -am test -Dtest='!WorkloadConverterStaticIpTest' -Dsurefire.failIfNoSpecifiedTests=false
npm --prefix platform-web run build
```
- [x] **Step 2**：确认 `backend-layering.md` §5.2 那张表的脚注补上本次变化（平台边界列多了 `listAll` 能力；`hasGatewayApi/hasIstio` 归 api）。

---

## 横切 caveat（实现期处理）

1. **本批不配任何权限行**：能力补齐 ≠ 暴露。`/xxx/list-all` 端点本来就都在（基类给的），没配码就不可达。日后要暴露（例如 B4 聚合搬回 api），需按 `V2026_10_08_2` 的写法配行 + **显式绑角色**。
2. **前端还有第三份 capability 判据**：`platform-web/src/composables/useMeshStatus.ts` 自己从 `/cluster/capability/get` 推 `gatewayApiVersions`。Phase 3 不动它（对外 shape 没变），但要知道它在 —— 将来若要收敛，方向是前端改读 `mesh-status`。
3. **`clusterId` 参数**：`listAll` 的 `clusterId` 在 body（`BaseResources.clusterId`），namespace 必须为空 —— 基类不校验 body 里的 namespace，传了也不影响（`listAll` 实现里根本不读它）。
4. **不要顺手修 `WorkloadConverterStaticIpTest`**（HEAD 就红，与本批无关）。
5. **Phase 2 是唯一漏斗**：改完必须跑全量四模块回归 + 前端 build，不能只看 `K8sServerGatewayTest`。

## 验证命令（每 Phase 末）

```bash
mvn -o -pl platform-common,k8s-core,k8s-server,platform-api -am test -Dtest='!WorkloadConverterStaticIpTest' -Dsurefire.failIfNoSpecifiedTests=false
npm --prefix platform-web run build
```
（`-o` = 离线，本地仓库 `D:\repository`；去掉 `-Dtest` 过滤会因那条既有失败而红。）

## 验收

1. **Phase 1**：19 个缺 `listAll` 的 ops 里，18 个补齐（`Secret` 有意保留并写明原因）；`NamespacedListAllTest` 18 个用例全绿；**权限行、端点、api 侧 client 方法零新增**；`GatewayOperations` 的旧注释已与代码一致。
2. **Phase 2**：`K8sServerGateway.exchange` 从 `InputStream` 直接反序列化；5 个网关用例全绿，其中"5MB 坏 body 不得整包进日志"是可复现的证据；四模块回归 + 前端 build 全绿。
3. **Phase 3**：`k8s_cluster.capability` → flag 的派生**只在 `ClusterCapabilityService` 一处**；`MeshOperations` 只剩 ambient 活探测且不再读 DB；`MeshStatusDTO` 对外 shape 不变（前端零改动）。

## 本轮不做（明确留给下一轮，别顺手做）

按 `backend-layering.md` §5 的越界判据（是否引用租户/分配表/模板/产品命名/产品阈值），下列仍在适配器侧，属**更大的一轮**：

1. **`K8sProvisioningService`（k8s-server，最大一处）**：`provisionCluster` 遍历启用租户 × 全部模板、`ensureTenantSa`/`syncTemplateClusterRole` 的命名约定、`cleanupTenant` 的删除范围、`parseRules` —— 都是平台开通策略 + 读两张业务表。目标形态：api 侧编排，k8s-server 只留 `ensureSa(name)` / `ensureClusterRole(name, rules)` 这类原子幂等动作。反方：会变成 N 次内部调用，多对象一致性要 api 自己兜。
2. **`KubernetesClientFactory.createTenantClient`（k8s-core）**：读路径上的写副作用（SA 自建 + RoleBinding 自愈）+ 读 tenant/allocation/template 三张业务表。
3. **`CoreV1NamespaceConverter` 的 `managed-by` provenance**（"平台创建的 ns 编辑后保留标签；非平台 ns 不补盖"）—— 产品所有权语义，至少要在代码里标成策略而非 K8s 映射。
4. **`CalicoIpamOperations.nextFreeBlocks` 的分页粒度/排序** —— Calico CRD 语义属适配，分页是产品行为（灰）。
5. **`K8sServerGateway` 方案 B/C/E**：gzip（k8s-server 开压缩 + api 侧 `Accept-Encoding` + 手动解压，与 A 同一管道）、K8s 原生 `limit`+`continue` 分页、换 HTTP client（JDK HttpClient / HC5）。
6. **B4 后续**：Phase 2（ClusterView 行操作收敛 + `ClusterDetailView` 4 tab）/ Phase 3（降级/性能/门禁打磨）；以及「B4 聚合是否搬回 api」的决策（前置：本计划 Phase 1 + Phase 2）。
