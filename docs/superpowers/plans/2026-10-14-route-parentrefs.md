# Route 父引用跨 kind 支持 · 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Route 的 `spec.parentRefs[]` 能挂到 Service（不只是 Gateway），并修掉「Service 父引用必需的 `group: ""` 被写盘层抹掉 → 编辑一次存量路由即静默脱挂」的 bug。

**Architecture:** 后端只有一处改动：`GatewaySpecUtil` 里父引用的 group 写盘规则（`null` + `kind=Service` → 显式 `""`），对 5 类 Route 的 create/update 同时生效且幂等。前端把 5 个路由编辑器里「父引用」块的四个字段做成随 kind 联动的选择器，候选加载收口到一个按 `(kind, 命名空间)` 缓存的 composable；列表/详情页的父引用展示去掉 Gateway 预设。

**Tech Stack:** Java 21 / Maven 多模块（k8s-core、platform-common）/ fabric8 GenericKubernetesResource；Vue 3 + TypeScript + Element Plus + Vite。

**Spec:** `docs/superpowers/specs/2026-10-14-route-parentrefs-design.md`（实施前先读；本计划的每个任务都对应它的某一节）

## Global Constraints

- **无网络**：后端一律 `mvn -o`；前端一律 `npm --prefix platform-web run build`（= vue-tsc + vite build）。不要尝试联网拉依赖。
- **中文**：代码注释、FieldHelp、校验文案、commit message 全部中文（技术名词保留英文）。
- **k8s-server 零业务逻辑**：本批不新增端点、不新增权限行、不新增迁移；后端改动只落在 k8s-core 的转换器工具类与 platform-common 的 DTO 注释。
- **同类资源不抽公共基类**：5 个路由编辑器的模板各自改一份（只把**候选加载**收口进 composable），不要顺手抽公共组件或基类。
- **候选项一律** `filterable` + `allow-create` + `default-first-option`；候选拉取失败静默降级为手输，**不得阻断保存**。
- **空值选项 ↔ placeholder 同文案**（EP 对值为 `''` 的选中项会退化成 placeholder，本仓既有约定）。
- **禁止把"清空旧值"写成 `watch`**：一律挂 `el-select` 的 `@change`（用户交互才触发）。回填也是改 modelValue，用 watch 会把刚加载的值抹掉。
- **`group` 的默认值两处相反**：`ParentReference.group` 省略 = `gateway.networking.k8s.io`（要 core 组必须显式 `""`）；`BackendObjectReference.group` 省略/空串 = core 组。任何"顺手统一"都是 bug。
- `port`（PortNumber）**不做输入框**：保持"UI 不编辑、原样透传"，只在有值时给只读提示。

## Review Focus

spec 没写、但最可能咬到用户的六类输入/条件，逐条钉到任务上：

1. **存量 Service 父引用（`group: ""` + `kind: Service`）编辑一次后不得发生任何变化** —— 这正是本批要修的静默损坏。→ Task 1 测试 3、Task 6 断言 6。
2. **未建模 kind（如 `ServiceImport`）与带 `port` 的父引用**：编辑器必须显示原值 + 只读提示，保存后 `kind`/`group`/`port` 一个不变。→ Task 3 模板分支、Task 6 断言 7/8。
3. **候选接口失败**（集群没装 Gateway API、租户角色没有 Service 读权限）：候选为空但**仍能手输并保存**，界面要说清"为什么没有候选"。→ Task 2 的 `failed` 分支、Task 3 `noDataText`、Task 6 断言 3。
4. **跨命名空间的父引用**（命名空间不在本租户候选列表里，靠 `allow-create` 手输）：值不得丢、候选为空也不阻断保存。→ Task 3 命名空间分支、Task 6 断言 3/6。
5. **回填不被"清空旧值"的逻辑误伤**：加载一条已有 Gateway 父引用（带 name + sectionName）的路由，两者都应原样显示。→ Task 3 `@change` 约束、Task 6 断言 11。
6. **手输的怪值**：大写/超长/带非法字符的 `sectionName` 要在提交前拦住（spec 的 SectionName pattern + ≤253）。→ Task 3 `validate()` 新增校验、Task 6 断言 4。

---

## 文件结构

**改**

| 文件 | 职责 |
|---|---|
| `k8s-core/.../converter/impl/gateway/GatewaySpecUtil.java` | 父引用 group 的写盘规则（唯一后端改动点）+ 防误改注释 |
| `platform-common/.../dto/ParentRefDTO.java` | `group` 的 null/`""` 语义注释 |
| `k8s-core/src/test/java/.../HttpRouteConverterTest.java` | group 写盘规则的 4 条断言（最全路径） |
| `k8s-core/src/test/java/.../TcpRouteConverterTest.java` | 同上 1 条（证明共享路径） |
| `platform-web/src/views/resource/{Http,Grpc,Tcp,Tls,Udp}RouteEditorView.vue` | 父引用块四字段联动 + 提交载荷 + 校验 |
| `platform-web/src/views/resource/{Http,Grpc,Tcp,Tls,Udp}RouteView.vue` | 父引用摘要与字段名去 Gateway 预设 |

**新增**

| 文件 | 职责 |
|---|---|
| `platform-web/src/composables/useParentCandidates.ts` | 父引用名称候选目录：按 `(kind, 命名空间)` 缓存，Gateway → gateways、Service → services（限 ClusterIP） |

---

### Task 1: 后端 group 写盘规则 + 回归测试

**Files:**
- Modify: `k8s-core/src/main/java/com/coding/k8score/converter/impl/gateway/GatewaySpecUtil.java`
- Modify: `platform-common/src/main/java/com/coding/common/models/k8s/dto/ParentRefDTO.java`
- Test: `k8s-core/src/test/java/com/coding/k8score/converter/impl/gateway/HttpRouteConverterTest.java`、`k8s-core/src/test/java/com/coding/k8score/converter/impl/gateway/TcpRouteConverterTest.java`

**Interfaces:**
- Produces（Task 3/4 依赖的行为，不是签名）：`group = null` + `kind = "Service"` 的父引用，写盘后 **存在** `group: ""`；`group = null` + 缺省 kind 时 **不写** group；`group` 非 null（含空串）原样写。
- Consumes：`ParentRefDTO`（现有字段 name/namespace/sectionName/group/kind/port）。

- [ ] **Step 1: 写失败的测试（HttpRouteConverterTest）**

在 `HttpRouteConverterTest` 末尾（`revert_null_resource_returns_empty_dto` 之后的任意位置）加 4 个测试。用本文件已有的 helper：`sample()`、`specOf`、`live`、`c`。

```java
    @Test
    @SuppressWarnings("unchecked")
    void convert_writes_explicit_empty_group_for_service_parent() {
        HttpRouteDTO dto = sample();
        ParentRefDTO svc = new ParentRefDTO();
        svc.setKind("Service");
        svc.setName("reviews");
        dto.setParentRefs(List.of(svc));

        List<Map<String, Object>> parents = (List<Map<String, Object>>) specOf(c.convert(dto)).get("parentRefs");

        assertThat(parents).hasSize(1);
        // Service 父引用必须显式写 group:""，否则 CRD 默认成 gateway.networking.k8s.io → 指向不存在的资源
        assertThat(parents.get(0))
                .containsEntry("group", "")
                .containsEntry("kind", "Service")
                .containsEntry("name", "reviews");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_keeps_explicit_empty_group_as_is() {
        HttpRouteDTO dto = sample();
        ParentRefDTO svc = new ParentRefDTO();
        svc.setKind("Service");
        svc.setGroup("");   // 显式空串（revert 读出来就是这个值）
        svc.setName("reviews");
        dto.setParentRefs(List.of(svc));

        List<Map<String, Object>> parents = (List<Map<String, Object>>) specOf(c.convert(dto)).get("parentRefs");

        assertThat(parents.get(0)).containsEntry("group", "");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_keeps_group_of_unmodeled_kind() {
        HttpRouteDTO dto = sample();
        ParentRefDTO imp = new ParentRefDTO();
        imp.setKind("ServiceImport");
        imp.setGroup("multicluster.x-k8s.io");
        imp.setName("reviews");
        dto.setParentRefs(List.of(imp));

        List<Map<String, Object>> parents = (List<Map<String, Object>>) specOf(c.convert(dto)).get("parentRefs");

        // 归一规则只对 kind=Service 生效：未建模 kind 的 group 原样写出（不得被抹成 ""）
        assertThat(parents.get(0))
                .containsEntry("group", "multicluster.x-k8s.io")
                .containsEntry("kind", "ServiceImport");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_keeps_service_parent_group_at_empty_string() {
        // live：一条已存在的 Service 父引用（group 为空串）
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("parentRefs",
                List.of(new LinkedHashMap<>(Map.of("group", "", "kind", "Service", "name", "reviews"))));

        HttpRouteDTO dto = sample();
        ParentRefDTO svc = new ParentRefDTO();
        svc.setKind("Service");
        svc.setGroup("");   // 前端回填原值后提交
        svc.setName("reviews");
        dto.setParentRefs(List.of(svc));

        List<Map<String, Object>> parents =
                (List<Map<String, Object>>) specOf(c.convertForUpdate(dto, live(liveSpec))).get("parentRefs");

        assertThat(parents).hasSize(1);
        // 「打开存量 Service 父引用路由 → 保存」不得改变父引用（今天这里会把 group 抹掉）
        assertThat(parents.get(0)).containsEntry("group", "").containsEntry("kind", "Service");
    }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -o -pl k8s-core -am test -Dtest=HttpRouteConverterTest
```

预期：`convert_writes_explicit_empty_group_for_service_parent` 与 `convertForUpdate_keeps_service_parent_group_at_empty_string` **FAIL**（`Expecting map to contain entry "group"=""` 之类）；另两条 PASS（`group` 有值时今天就会写出来）。

- [ ] **Step 3: 写 TcpRouteConverterTest 的对照测试**

在 `TcpRouteConverterTest` 末尾加（用该文件已有的 `sample()`、`specOf`、`v1`）：

```java
    @Test
    @SuppressWarnings("unchecked")
    void convert_writes_explicit_empty_group_for_service_parent() {
        TcpRouteDTO dto = sample();
        ParentRefDTO svc = new ParentRefDTO();
        svc.setKind("Service");
        svc.setName("mysql");
        dto.setParentRefs(List.of(svc));

        List<Map<String, Object>> parents = (List<Map<String, Object>>) specOf(v1.convert(dto)).get("parentRefs");

        assertThat(parents).hasSize(1);
        assertThat(parents.get(0))
                .containsEntry("group", "")
                .containsEntry("kind", "Service")
                .containsEntry("name", "mysql");
    }
```

- [ ] **Step 4: 实现（GatewaySpecUtil）**

4a. 在 `putStr` 下方新增一个"空串照写"的写入器：

```java
    /** 与 {@link #putStr} 的区别：**空串照写**（parentRefs 的 group 需要显式的 ""）。 */
    static void putStrAllowEmpty(Map<String, Object> m, String key, String val) {
        if (val != null) {
            m.put(key, val);
        }
    }
```

4b. 在 `toParentRefMaps` 上方新增归一函数：

```java
    /**
     * 父引用的 group 写盘值：
     * <ul>
     *   <li>{@code null} 且 {@code kind = "Service"} → {@code ""}（core 组，<b>必须显式写</b>，
     *       否则 CRD 默认成 {@code gateway.networking.k8s.io}，父引用指向不存在的资源、路由静默脱挂）</li>
     *   <li>非 null（含空串）→ 原样</li>
     *   <li>其余 {@code null} → 保持 {@code null}（省略，交 CRD 默认 Gateway）</li>
     * </ul>
     * 规则<b>只对 Service 生效</b>：其他 core 组 kind（ConfigMap 等）本平台未建模；将来若建模须一并扩。
     */
    static String parentGroup(ParentRefDTO p) {
        if (p.getGroup() != null) {
            return p.getGroup();
        }
        return "Service".equals(p.getKind()) ? "" : null;
    }
```

4c. 把 `toParentRefMaps` 里的一行改掉。**原文**：

```java
            putStr(m, "group", p.getGroup());
```

**改为**：

```java
            putStrAllowEmpty(m, "group", parentGroup(p));
```

4d. 在同一个 for 循环上方（`Map<String, Object> m = new LinkedHashMap<>();` 之后、第一行 putStr 之前）加防误改注释：

```java
            // group 的默认值两处相反：ParentReference.group 省略 = gateway.networking.k8s.io（要 core 组必须显式 ""），
            // 而 BackendObjectReference.group 省略/空串本身就是 core 组。**别照这里"统一" backendRefs**（见 toBackendRefMap）。
```

4e. 在 `toBackendRefMap` 里的 `putStr(m, "group", b.getGroup());` **上一行**加注释（锚点就是那一行，见文件内 `// ---------- backendRefs ...` 段）：

```java
        // 注意：BackendObjectReference.group 省略/空串 = core 组（Service），与 parentRefs 的 group 默认值相反，
        // 故这里维持 putStr（省略即正确），不要按 parentGroup 归一。
```

4f. `sameParent` 改为按归一值比较，并把注释改成：

```java
    /** overlay 用：命名空间内已有的 parentRef 元素 → 是否为"同一目标"（group/kind/namespace/name 四元组）。
     *  <p>group 按 {@link #parentGroup} 归一后比较 —— 否则「线上 group="" + 表单 group=null」会被判成不同目标。
     *  <p>当前零调用方（2026-10-14 核实）；留着是为了将来有人用它时它是对的。**不要**改回裸值比较。 */
    static boolean sameParent(Map<String, Object> live, ParentRefDTO dto) {
        return eq(asStr(live.get("name")), dto.getName())
                && eq(asStr(live.get("namespace")), dto.getNamespace())
                && eq(asStr(live.get("group")), parentGroup(dto))
                && eq(asStr(live.get("kind")), dto.getKind());
    }
```

（`sameParent` 零调用方、无可测路径，故**不给它写测试**；改动只有"比较前归一"与注释。）

- [ ] **Step 5: 补 DTO 注释（platform-common）**

`ParentRefDTO.group` 的字段注释**原文**：

```java
    /** 被引用资源的 group；缺省 gateway.networking.k8s.io（指向 Service 时须显式空串 ""） */
    private String group;
```

**改为**：

```java
    /** 被引用资源的 group。
     *  <p><b>null 与空串不是一回事</b>：{@code null} = 缺省（CRD 默认 {@code gateway.networking.k8s.io}）；
     *  {@code ""} = 显式 core 组（指向 Service 必须用这个）。写盘由 {@code GatewaySpecUtil.parentGroup} 归一：
     *  {@code null} 且 {@code kind = "Service"} 时自动补 {@code ""}。 */
    private String group;
```

- [ ] **Step 6: 跑测试确认全绿**

```bash
mvn -o -pl k8s-core -am test -Dtest='HttpRouteConverterTest,TcpRouteConverterTest,GrpcRouteConverterTest,TlsRouteConverterTest,UdpRouteConverterTest'
```

预期：全部 PASS（5 个 Route 转换器共用 `GatewaySpecUtil.toParentRefMaps`，改动一处即 5 类都生效）。再跑一次模块全量，确认没有别的断言依赖旧行为：

```bash
mvn -o -pl k8s-core test
```

预期：绿（唯一既有失败 `WorkloadConverterStaticIpTest` 若仍存在，与本次无关，不要动它）。

- [ ] **Step 7: 提交**

```bash
git add k8s-core/src/main/java/com/coding/k8score/converter/impl/gateway/GatewaySpecUtil.java \
        k8s-core/src/test/java/com/coding/k8score/converter/impl/gateway/HttpRouteConverterTest.java \
        k8s-core/src/test/java/com/coding/k8score/converter/impl/gateway/TcpRouteConverterTest.java \
        platform-common/src/main/java/com/coding/common/models/k8s/dto/ParentRefDTO.java
git commit -m "fix(gateway): 父引用 group 写盘归一 —— kind=Service 显式写空串，修 Service 父引用被抹掉"
```

---

### Task 2: 父引用候选目录 composable

**Files:**
- Create: `platform-web/src/composables/useParentCandidates.ts`

**Interfaces:**
- Produces（Task 3/4 依赖，逐字一致）：
  - `interface ParentCandidate { name: string; waypointFor: string | null; listeners: string[]; portNames: string[]; ports: number[] }`
  - `interface CandidateBucket { list: ParentCandidate[]; loading: boolean; failed: boolean }`
  - `function useParentCandidates(): { bucket(kind: string, namespace: string): CandidateBucket; ensure(kind: string, namespace: string): void }`
  - `kind`: `''` = 缺省 Gateway；`'Service'`；其他值 = 未建模 kind（不拉候选）
  - `namespace`: **生效命名空间**（调用方负责把"留空"折算成本命名空间），不得传空串
- Consumes：`gatewayApi` / `serviceApi`（`@/api`）、`useResourceContext()`（`@/stores/context`，取 tenantId/clusterId）。

- [ ] **Step 1: 写文件（完整内容）**

```ts
import { reactive } from 'vue'
import { gatewayApi, serviceApi } from '@/api'
import { useResourceContext } from '@/stores/context'
import type { K8sGateway, K8sService } from '@/types'

/**
 * Route 父引用（parentRefs）的**名称候选**目录：按 `(kind, 命名空间)` 缓存。
 *
 * <p><b>为什么要有它</b>：父资源可以是 Gateway 也可以是 Service（Gateway API 的两种 Core 父资源），
 * 候选来源、段名语义都随 kind 变。5 个路由编辑器原本各写一份"只查本命名空间 Gateway"的加载逻辑，
 * 本批把这块收口到这里，编辑器只负责渲染。
 *
 * <p><b>缓存键含 tenant/cluster</b>：切集群或切租户不会串数据。旧键不主动清理（量级 = 访问过的命名空间数）。
 *
 * <p><b>失败静默降级</b>：拉不到候选（CRD 未装 / 无权限 / 集群断开）只置 `failed`，由调用方在
 * `no-data-text` 里说明原因并让用户手输 —— 候选是便利，不是保存的前提。
 *
 * <p>Service 侧过滤掉 ExternalName 与 headless：Gateway API 的 Mesh profile 只认 ClusterIP。
 */
export interface ParentCandidate {
  name: string
  /** Gateway：`istio.io/waypoint-for` label 值（无则 null）；Service 恒 null */
  waypointFor: string | null
  /** Gateway：监听器名（去重保序）；Service 恒 [] */
  listeners: string[]
  /** Service：**有名字的**端口名（去重保序）；Gateway 恒 [] */
  portNames: string[]
  /** Service：端口号（用于区分"有端口但都没命名"）；Gateway 恒 [] */
  ports: number[]
}

export interface CandidateBucket {
  list: ParentCandidate[]
  loading: boolean
  failed: boolean
}

/** 未加载 / 未建模 kind 的统一空桶（共享只读对象，不要往里写） */
const EMPTY: CandidateBucket = { list: [], loading: false, failed: false }

const KIND_GATEWAY = ''
const KIND_SERVICE = 'Service'

const buckets = reactive(new Map<string, CandidateBucket>())
const { state } = useResourceContext()

function keyOf(kind: string, namespace: string): string {
  return `${state.clusterId ?? ''}|${state.tenantId ?? ''}|${kind}|${namespace}`
}

function gatewayCandidate(g: K8sGateway): ParentCandidate {
  const names = (g.listeners ?? []).map((l) => l.name?.trim() ?? '').filter(Boolean)
  return {
    name: g.name,
    waypointFor: g.labels?.['istio.io/waypoint-for'] ?? null,
    listeners: [...new Set(names)],
    portNames: [],
    ports: [],
  }
}

function serviceCandidate(s: K8sService): ParentCandidate {
  const ports = s.ports ?? []
  const names = ports.map((p) => p.name?.trim() ?? '').filter(Boolean)
  return {
    name: s.name,
    waypointFor: null,
    listeners: [],
    portNames: [...new Set(names)],
    ports: [...new Set(ports.map((p) => p.port).filter((n): n is number => typeof n === 'number'))],
  }
}

/** Mesh profile 只认 ClusterIP：排除 ExternalName 与 headless（clusterIp=None） */
function isClusterIpService(s: K8sService): boolean {
  return s.type !== 'ExternalName' && s.clusterIp !== 'None'
}

export function useParentCandidates() {
  /** 读：未加载过 / 未建模 kind → 空桶（不触发请求） */
  function bucket(kind: string, namespace: string): CandidateBucket {
    return buckets.get(keyOf(kind, namespace)) ?? EMPTY
  }

  /** 触发加载：同一个 (kind, 命名空间) 只发一次；未建模 kind 与空命名空间直接返回 */
  function ensure(kind: string, namespace: string): void {
    if (!namespace) return
    if (kind !== KIND_GATEWAY && kind !== KIND_SERVICE) return
    const key = keyOf(kind, namespace)
    if (buckets.has(key)) return

    buckets.set(key, { list: [], loading: true, failed: false })
    // 必须从 map 里取回来再用（reactive Map 的 get 返回响应式代理）：
    // 直接改 set 进去的那个裸对象不保证模板收到通知
    const b = buckets.get(key)!
    const ctx = { tenantId: state.tenantId!, clusterId: state.clusterId!, namespace }
    if (kind === KIND_SERVICE) {
      serviceApi
        .list(ctx)
        .then((items) => {
          b.list = items.filter(isClusterIpService).map(serviceCandidate)
        })
        .catch(() => {
          b.failed = true
        })
        .finally(() => {
          b.loading = false
        })
    } else {
      gatewayApi
        .list(ctx)
        .then((items) => {
          b.list = items.map(gatewayCandidate)
        })
        .catch(() => {
          b.failed = true
        })
        .finally(() => {
          b.loading = false
        })
    }
  }

  return { bucket, ensure }
}
```

- [ ] **Step 2: 类型检查**

```bash
npm --prefix platform-web run build
```

预期：`✓ built in ...`（vue-tsc 无错）。若报 `K8sService`/`K8sGateway` 字段名不符（例如 `clusterIp` 大小写），读 `platform-web/src/types.ts` 里对应 interface 后改字段名，**不要**改成 `any`。

- [ ] **Step 3: 提交**

```bash
git add platform-web/src/composables/useParentCandidates.ts
git commit -m "feat(web): 父引用候选目录 composable（按 kind+命名空间缓存，Service 限 ClusterIP）"
```

---

### Task 3: HttpRoute 编辑器父引用块（参考实现）

其余 4 个编辑器在 Task 4 照抄本任务的代码块。

**Files:**
- Modify: `platform-web/src/views/resource/HttpRouteEditorView.vue`

**Interfaces:**
- Consumes：Task 2 的 `useParentCandidates()`、`ParentCandidate`（`bucket(p.parentKind, effectiveNs(p)).list`）。
- Produces：`ParentRow` 结构（Task 4 逐字相同）：`{ parentKind: string; name: string; namespace: string; sectionName: string; group?: string | null; port?: number | null }`；提交载荷里 `kind: p.parentKind.trim() || null`。

- [ ] **Step 1: 加 import**

在 `import { gatewayApi, httpRouteApi } from '@/api'` 那行**之后**加：

```ts
import { useParentCandidates } from '@/composables/useParentCandidates'
```

并把上下文解构那一行（本文件第 30 行）**原文**：

```ts
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()
```

**改为**：

```ts
const { state, ready, currentTenant, currentCluster, load, namespacesOfCluster } = useResourceContext()
```

- [ ] **Step 2: 用 composable 替换候选加载**

把这一整段（本文件 58–72 行，`/** 本命名空间的 Gateway 候选（parentRefs 下拉） */` 到 `loadGateways` 的收尾 `}`）**整体替换**为：

```ts
/** 父引用名称候选（按 kind + 命名空间缓存；见 composables/useParentCandidates.ts） */
const { bucket, ensure } = useParentCandidates()

/** 父引用的生效命名空间：'' = 本命名空间 */
function effectiveNs(p: ParentRow): string {
  return p.namespace.trim() || state.namespace!
}

/** kind 不在 {缺省, Service} → 未建模：只读展示、无候选、名称与段名手输 */
function hasUnmodeledKind(p: ParentRow): boolean {
  return !!p.parentKind && p.parentKind !== 'Service'
}

function bucketOf(p: ParentRow): CandidateBucket {
  return bucket(p.parentKind, effectiveNs(p))
}

/**
 * 用户改了父资源类型：候选与段名语义都换了 → 清掉名称与段名；
 * group 属于旧 kind 的透传值（如 ServiceImport 的 multicluster.x-k8s.io），一并归位。
 * <b>必须挂 @change</b>：回填也会改 modelValue，写成 watch 会把刚加载的值抹掉。
 */
function onParentKindChange(p: ParentRow): void {
  p.name = ''
  p.sectionName = ''
  p.group = null
}

/** 用户改了命名空间：候选取自新命名空间，旧名字/段名必然失效 → 清掉（同样只挂 @change） */
function onParentNamespaceChange(p: ParentRow): void {
  p.name = ''
  p.sectionName = ''
}

function noDataText(p: ParentRow): string {
  if (!ready.value) return '上下文未就绪'
  if (hasUnmodeledKind(p)) return `「${p.parentKind}」类型的父资源不支持候选，请直接输入名称`
  const b = bucketOf(p)
  if (b.failed) return '候选拉取失败（权限或 CRD 未装），可直接输入名称'
  return p.parentKind === 'Service'
    ? '该命名空间暂无 Service（可直接输入名称）'
    : '该命名空间暂无 Gateway（可直接输入名称）'
}

function namePlaceholder(p: ParentRow): string {
  if (hasUnmodeledKind(p)) return `输入 ${p.parentKind} 名称`
  return p.parentKind === 'Service'
    ? '选择或输入该命名空间的 Service 名'
    : '选择或输入该命名空间的 Gateway 名'
}

/** 段名候选取自**所选中的那个**父资源（不是整个命名空间） */
function sectionOptions(p: ParentRow): string[] {
  const name = p.name.trim()
  if (!name) return []
  const c = bucketOf(p).list.find((x) => x.name === name)
  if (!c) return []
  return p.parentKind === 'Service' ? c.portNames : c.listeners
}

function sectionNoDataText(p: ParentRow): string {
  if (!ready.value) return '上下文未就绪'
  if (hasUnmodeledKind(p)) return `「${p.parentKind}」类型的父资源不支持候选，请直接输入`
  const name = p.name.trim()
  if (!name) return '请先选择父资源'
  const c = bucketOf(p).list.find((x) => x.name === name)
  if (!c) return '该名称不在候选里（手输的无法列出它的段），可自行输入'
  if (p.parentKind === 'Service' && !c.portNames.length) return '该服务的端口没有命名，留空即作用于全部端口'
  return p.parentKind === 'Service' ? '该服务没有可选的命名端口' : '该 Gateway 没有监听器'
}
```

并在 import 段补类型引用：把 `import type { K8sGateway, ... } from '@/types'` 里的 `K8sGateway` 删掉（不再用），并加一行：

```ts
import type { CandidateBucket } from '@/composables/useParentCandidates'
```

（若 `K8sGateway` 在本文件其他地方仍被引用，则保留它。）

- [ ] **Step 3: `ParentRow` 与默认行**

`ParentRow` 接口**原文**：

```ts
interface ParentRow {
  name: string
  namespace: string
  sectionName: string
  /** UI 不编辑，仅回填透传 */
  group?: string | null
  kind?: string | null
  port?: number | null
}
```

**改为**（注意 `kind` 从"透传字段"变成"UI 字段 `parentKind`"）：

```ts
interface ParentRow {
  /** '' = 缺省 Gateway；'Service'；其他值 = 未建模 kind（原样透传，本页不改） */
  parentKind: string
  name: string
  namespace: string
  sectionName: string
  /** UI 不编辑，仅回填透传 */
  group?: string | null
  port?: number | null
}
```

三处默认行字面量都要加 `parentKind: ''`：
- `parentRefs: [{ name: '', namespace: '', sectionName: '' }] as ParentRow[]`（form 初始化）
- `function addParent(): void { form.parentRefs.push({ name: '', namespace: '', sectionName: '' }) }`
- 回填里的 `if (!form.parentRefs.length) form.parentRefs = [{ name: '', namespace: '', sectionName: '' }]`

统一改成 `{ parentKind: '', name: '', namespace: '', sectionName: '' }`。

- [ ] **Step 4: 回填函数**

`fromParent`**原文**：

```ts
function fromParent(p: K8sRouteParentRef): ParentRow {
  return { name: p.name ?? '', namespace: p.namespace ?? '', sectionName: p.sectionName ?? '', group: p.group, kind: p.kind, port: p.port }
}
```

**改为**：

```ts
function fromParent(p: K8sRouteParentRef): ParentRow {
  return {
    parentKind: p.kind ?? '',
    name: p.name ?? '',
    namespace: p.namespace ?? '',
    sectionName: p.sectionName ?? '',
    group: p.group,
    port: p.port,
  }
}
```

- [ ] **Step 5: 拆 watch（详情回填 / 候选预热分离）**

**原文**：

```ts
watch([() => ready.value, () => state.namespace], ([r]) => {
  if (!r) return
  void loadGateways()
  if (editing.value) void loadDetail()
}, { immediate: true })
```

**改为**：

```ts
watch([() => ready.value, () => state.namespace], ([r]) => {
  if (!r) return
  if (editing.value) void loadDetail()
}, { immediate: true })

/** 父引用候选预热：kind / 命名空间任一变化即按需拉取（命中缓存则不发请求）。
 *  串进 `form.parentRefs` 的 key 是为了回填之后也预热到加载进来的那些行。 */
watch(
  () => `${ready.value}|${state.namespace ?? ''}|${form.parentRefs.map((p) => `${p.parentKind}|${p.namespace}`).join('|')}`,
  () => {
    if (!ready.value) return
    for (const p of form.parentRefs) ensure(p.parentKind, effectiveNs(p))
  },
  { immediate: true },
)
```

- [ ] **Step 6: 校验**

6a. 把「至少需要一个父 Gateway」那句**原文**：

```ts
  if (!form.parentRefs.some((p) => p.name.trim())) return '至少需要一个父 Gateway（parentRefs）'
```

**改为**（并紧随其后加 sectionName 校验）：

```ts
  if (!form.parentRefs.some((p) => p.name.trim())) return '至少需要一个父资源（parentRefs）'
  for (const [pi, p] of form.parentRefs.entries()) {
    const sec = p.sectionName.trim()
    if (!sec) continue
    // spec 的 SectionName：pattern + MaxLength 253（填错监听器名/端口名是"静默不挂载"，故提交前拦一道）
    if (sec.length > 253 || !SECTION_NAME_RE.test(sec)) {
      return `父引用 #${pi + 1}：sectionName 须为合法段名（小写字母/数字/'-'/'.'，≤253）：「${sec}」`
    }
  }
```

6b. 在 `RFC1123_RE` 那一行附近加常量：

```ts
/** SectionName 的 CRD pattern（≤253，见 Gateway API shared_types） */
const SECTION_NAME_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$/
```

- [ ] **Step 7: 提交载荷**

`parentRefs` 映射**原文**（5 个编辑器完全一致）：

```ts
    parentRefs: form.parentRefs
      .filter((p) => p.name.trim())
      .map((p) => ({
        name: p.name.trim(),
        namespace: p.namespace.trim() || null,
        sectionName: p.sectionName.trim() || null,
        group: p.group ?? null,
        kind: p.kind ?? null,
        port: p.port ?? null,
      })),
```

**改为**：

```ts
    // kind 由「父资源类型」选择器决定（'' → null = 缺省 Gateway）；group 仍是透传
    // （只有未建模 kind 才带值），kind=Service 时的显式空串由后端写盘侧归一
    parentRefs: form.parentRefs
      .filter((p) => p.name.trim())
      .map((p) => ({
        name: p.name.trim(),
        namespace: p.namespace.trim() || null,
        sectionName: p.sectionName.trim() || null,
        group: p.group ?? null,
        kind: p.parentKind.trim() || null,
        port: p.port ?? null,
      })),
```

- [ ] **Step 8: 模板块**

把从 `<el-divider content-position="left">父 Gateway（parentRefs）</el-divider>` 到 `<el-button size="small" @click="addParent">添加父引用</el-button>` 的**整段**替换为下面这段（本任务给的是 HttpRoute 的完整版，Gateway 的两处 FieldHelp 用本文件原文）：

```vue
        <el-divider content-position="left">父资源（parentRefs）</el-divider>

        <div v-for="(p, i) in form.parentRefs" :key="i" class="sub-block">
          <div class="sub-head">
            <span class="sub-title">父引用 #{{ i + 1 }}</span>
            <span v-if="p.port != null" class="parent-hint">port={{ p.port }}（本页不改，可在 YAML 里改）</span>
            <el-button v-if="form.parentRefs.length > 1" link type="danger" @click="removeParent(i)">删除</el-button>
          </div>

          <el-form-item label="父资源类型" required>
            <el-select
              v-model="p.parentKind"
              placeholder="Gateway（缺省）"
              style="max-width: 240px"
              @change="onParentKindChange(p)"
            >
              <el-option label="Gateway（缺省）" value="" />
              <el-option label="Service" value="Service" />
            </el-select>
            <span v-if="hasUnmodeledKind(p)" class="parent-hint">
              kind={{ p.parentKind }} 未建模，本页不改它（可在 YAML 里改）
            </span>
            <FieldHelp tip="父资源可以是 Gateway，也可以是 Service（Istio mesh：把本路由的规则挂到该服务上）。缺省 Gateway；指向 Service 时 group 必须显式为空串，由平台自动写入。" />
          </el-form-item>

          <el-form-item label="命名空间">
            <el-select
              v-model="p.namespace"
              filterable
              allow-create
              default-first-option
              placeholder="本命名空间（留空）"
              style="max-width: 300px"
              @change="onParentNamespaceChange(p)"
            >
              <el-option label="本命名空间（留空）" value="" />
              <el-option v-for="ns in namespacesOfCluster" :key="ns" :label="ns" :value="ns" />
            </el-select>
            <FieldHelp tip="父资源所在命名空间。候选取本租户在当前集群的命名空间；留空 = 本命名空间。跨命名空间引用需目标侧放行（Gateway 的 allowedRoutes + ReferenceGrant），否则引用不会被接受。" />
          </el-form-item>

          <el-form-item :label="p.parentKind === 'Service' ? 'Service' : 'Gateway'" required>
            <el-select
              v-model="p.name"
              filterable
              allow-create
              default-first-option
              :loading="bucketOf(p).loading"
              :no-data-text="noDataText(p)"
              :placeholder="namePlaceholder(p)"
              style="max-width: 360px"
            >
              <el-option
                v-for="c in bucketOf(p).list"
                :key="c.name"
                :label="c.waypointFor ? `${c.name} · waypoint` : c.name"
                :value="c.name"
              />
            </el-select>
            <FieldHelp
              v-if="p.parentKind === 'Service'"
              tip="把本路由的规则挂到该 Service 上（Istio mesh）。跨命名空间同样需要目标命名空间放行。"
            />
            <FieldHelp
              v-else
              tip="要挂载到的 Gateway（缺省 kind=Gateway、group=gateway.networking.k8s.io）。跨命名空间挂载需在目标 Gateway 的 allowedRoutes 放行，且目标命名空间有 ReferenceGrant。"
            />
          </el-form-item>

          <el-form-item :label="p.parentKind === 'Service' ? '端口名（sectionName）' : '监听器（sectionName）'">
            <el-select
              v-model="p.sectionName"
              filterable
              allow-create
              default-first-option
              :no-data-text="sectionNoDataText(p)"
              :placeholder="
                p.parentKind === 'Service'
                  ? '留空 = 该服务全部端口'
                  : '留空 = 该 Gateway 上全部匹配的监听器'
              "
              style="max-width: 300px"
            >
              <el-option v-for="s in sectionOptions(p)" :key="s" :label="s" :value="s" />
            </el-select>
            <FieldHelp
              v-if="p.parentKind === 'Service'"
              tip="只作用于该 Service 的指定端口（填端口名）。留空 = 作用于该服务全部端口。"
            />
            <FieldHelp
              v-else
              tip="只挂到指定的那一个监听器（填监听器名）。留空表示挂到所有匹配的监听器。"
            />
          </el-form-item>
        </div>
        <el-button size="small" @click="addParent">添加父引用</el-button>
```

- [ ] **Step 9: 样式**

在本文件 `<style scoped>` 块里 `.sub-title` 那行**之后**加：

```css
.parent-hint { margin-left: 8px; font-size: 12px; color: var(--text-3); }
```

- [ ] **Step 10: 构建**

```bash
npm --prefix platform-web run build
```

预期：`✓ built in ...`，vue-tsc 无错。

- [ ] **Step 11: 冒烟（浏览器）**

用 `preview_start` 起 dev server（`.claude/launch.json` 若无 web 项则新建 `{"name":"web","runtimeExecutable":"npm","runtimeArgs":["--prefix","platform-web","run","dev"],"port":5173}`），打开 HTTPRoute 编辑页，确认：
- 父引用块标题为「父资源（parentRefs）」，四行分别是 父资源类型 / 命名空间 / Gateway / 监听器（sectionName）；
- `preview_console_logs` 无 error；
- 改父资源类型为 Service 后，第三行 label 变「Service」、第四行变「端口名（sectionName）」。

（功能断言在 Task 6 统一做；这里只要页面能开、没有报错。）

- [ ] **Step 12: 提交**

```bash
git add platform-web/src/views/resource/HttpRouteEditorView.vue
git commit -m "feat(web): HTTPRoute 父引用块支持 Gateway/Service（kind/命名空间/名称/段名联动）"
```

---

### Task 4: 其余 4 个编辑器（Grpc / Tcp / Tls / Udp）

**Files:**
- Modify: `platform-web/src/views/resource/GrpcRouteEditorView.vue`、`TcpRouteEditorView.vue`、`TlsRouteEditorView.vue`、`UdpRouteEditorView.vue`

**Interfaces:**
- Consumes：Task 2 的 composable、Task 3 的 `ParentRow` 结构与辅助函数名（逐字相同）。
- Produces：5 个编辑器行为一致。

- [ ] **Step 1: 逐文件照抄 Task 3**

对 4 个文件各做 Task 3 的 Step 1–9，**代码逐字相同**，只有两处按文件替换：

1. `@change` 等辅助函数里用不到的 import 不要留（例如 Tcp/Tls/Udp 未必用了 `K8sGateway`，按 Task 3 Step 2 的说明处理）。
2. 模板里**两处 Gateway 的 FieldHelp 原文照抄本文件**（下面是各文件原文，一个字都不要改）：

| 文件 | Gateway 行的 FieldHelp（`v-else` 分支） | sectionName 行的 FieldHelp（`v-else` 分支） |
|---|---|---|
| HttpRoute | 要挂载到的 Gateway（缺省 kind=Gateway、group=gateway.networking.k8s.io）。跨命名空间挂载需在目标 Gateway 的 allowedRoutes 放行，且目标命名空间有 ReferenceGrant。 | 只挂到指定的那一个监听器（填监听器名）。留空表示挂到所有匹配的监听器。 |
| Grpc | 要挂载到的 Gateway。它需要一个支持 GRPCRoute 的监听器（HTTP/HTTPS 协议的监听器通常都能挂）。 | 只挂到指定的那一个监听器（填监听器名）。留空表示挂到所有匹配的监听器。 |
| Tcp | 要挂载到的 Gateway。该 Gateway 必须有一个 TCP 协议的监听器，否则规则不会被接受（父资源状态里会出现 Accepted=False）。 | 只挂到指定的那一个监听器（填监听器名）。留空表示挂到所有匹配的监听器。 |
| Tls | 要挂载到的 Gateway。它需要一个 TLS 协议（Passthrough）的监听器，否则规则不会被接受。 | 只挂到指定的那一个监听器（填监听器名）。留空表示挂到所有匹配的监听器。 |
| Udp | 要挂载到的 Gateway。该 Gateway 必须有一个 UDP 协议的监听器，否则规则不会被接受（父资源状态里会出现 Accepted=False）。 | 只挂到指定的那一个监听器（填监听器名）。留空表示挂到所有匹配的监听器。 |

- [ ] **Step 2: 各文件锚点速查**

| 文件 | `loadGateways` 段 | `parentRefs` 默认行 | `validate` 那句 | 提交载荷 |
|---|---|---|---|---|
| Grpc | 45–56 | `form.parentRefs`/`addParent` 内联字面量 | 262 行 | ~300–310 行 |
| Tcp | 40–52 | `form.parentRefs`/`addParent` 内联字面量 | 132 行 | 165–175 行 |
| Tls | 44–55 | `newParent()` 辅助函数（81 行）+ 回填重置 | 148 行 | 179–189 行 |
| Udp | 41–52 | `newParent()` 辅助函数（76 行）+ 回填重置 | 132 行 | 167–177 行 |

（行号仅供定位；以字符串锚点为准。Tls/Udp 的默认行集中在 `newParent()`，改那一处即可，另外两处调用点不用动。）

- [ ] **Step 3: 构建**

```bash
npm --prefix platform-web run build
```

预期：`✓ built in ...`。

- [ ] **Step 4: 冒烟（浏览器）**

打开 TCPRoute 编辑页（最简结构），确认父资源类型/命名空间/名称/段名四行都在、切 kind 后行名与候选来源跟着变、console 无 error。

- [ ] **Step 5: 提交**

```bash
git add platform-web/src/views/resource/GrpcRouteEditorView.vue \
        platform-web/src/views/resource/TcpRouteEditorView.vue \
        platform-web/src/views/resource/TlsRouteEditorView.vue \
        platform-web/src/views/resource/UdpRouteEditorView.vue
git commit -m "feat(web): GRPC/TCP/TLS/UDP 父引用块同步支持 Gateway/Service"
```

---

### Task 5: 列表页 / 详情页展示去 Gateway 预设

**Files:**
- Modify: `platform-web/src/views/resource/{Http,Grpc,Tcp,Tls,Udp}RouteView.vue`

**Interfaces:**
- Consumes：`K8sRouteParentRef`（现有类型）。
- Produces：`parentText(p)` 输出 `Gateway infra/gw:http` / `Service team-a/api:http` 形态；列头与详情字段名为「父资源」。

- [ ] **Step 1: 改 `parentText`（5 个文件同一改法）**

**原文**（5 个文件逐字相同）：

```ts
/** 父引用摘要：namespace/name[:sectionName] */
function parentText(p: K8sRouteParentRef): string {
  const ns = p.namespace ? `${p.namespace}/` : ''
  const sec = p.sectionName ? `:${p.sectionName}` : ''
  return `${ns}${p.name ?? '?'}${sec}`
}
```

**改为**：

```ts
/** 父引用摘要：kind namespace/name[:sectionName 或 :port]。kind 缺省即 Gateway（Gateway API 的 CRD 默认值）。 */
function parentText(p: K8sRouteParentRef): string {
  const kind = p.kind || 'Gateway'
  const ns = p.namespace ? `${p.namespace}/` : ''
  const qual = p.sectionName || (p.port != null ? String(p.port) : '')
  return `${kind} ${ns}${p.name ?? '?'}${qual ? `:${qual}` : ''}`
}
```

- [ ] **Step 2: 改字段名（每个文件两处）**

- `<el-table-column label="父 Gateway" min-width="180">` → `<el-table-column label="父资源" min-width="200">`
- `<el-descriptions-item label="父 Gateway">` → `<el-descriptions-item label="父资源">`

- [ ] **Step 3: 构建**

```bash
npm --prefix platform-web run build
```

预期：`✓ built in ...`。

- [ ] **Step 4: 提交**

```bash
git add platform-web/src/views/resource/HttpRouteView.vue \
        platform-web/src/views/resource/GrpcRouteView.vue \
        platform-web/src/views/resource/TcpRouteView.vue \
        platform-web/src/views/resource/TlsRouteView.vue \
        platform-web/src/views/resource/UdpRouteView.vue
git commit -m "feat(web): Route 列表/详情父引用展示带 kind（Service 不再显示成 Gateway）"
```

---

### Task 6: 端到端功能验证（11 条）+ 落地记录

**Files:**
- Modify: 本计划文件（追加「落地记录」一节）

**Interfaces:**
- Consumes：Task 1–5 的全部产物。
- Produces：可复核的验收证据。

- [ ] **Step 1: 准备可用的上下文**

用 `preview_start` 起 dev server。**优先用真后端 + 真集群**（唯一能证明"落盘正确"的方式）；若环境里没有装了 Gateway API 的集群，则用 XHR 桩（见 Step 3 的降级说明），并在结论里写明降级。

- [ ] **Step 2: 逐条跑断言并记录证据**

每条记「怎么造的数据 / 看到什么 / 在哪看到的（截图或 YAML 片段）」。**不允许只写"通过"**。

| # | 断言 | 造数方式 |
|---|---|---|
| 1 | 新建路由时父资源类型显示「Gateway（缺省）」；名称候选 = 该命名空间的 Gateway | 真实上下文 |
| 2 | 切 kind → Service：名称候选换成 Service（ExternalName/headless 不在内）、第四行变「端口名（sectionName）」且候选是该 Service 的**命名端口**、旧 name/sectionName 被清空 | 造一个含 ExternalName/headless/多端口(1 个命名) 的 Service 集合 |
| 3 | 命名空间下拉出现"本命名空间（留空）"+ 本租户命名空间；手输本租户范围外的命名空间仍可填；该命名空间候选为空（失败降级）但不阻断保存 | 手输一个不存在的命名空间 |
| 4 | Gateway 下段名候选 = 该 Gateway 的 listeners[].name；留空保存后 parentRef 里没有 sectionName；手输非法段名（大写/超长）被 `validate` 拦住 | 真实 Gateway |
| 5 | **Service 父引用建出来，YAML tab 里能看到 `group: ""` 与 `kind: Service`** | 真集群（降级时见 Step 3） |
| 6 | **存量 Service 父引用路由：打开 → 保存 → YAML tab 里父引用一字不变** | 先用 YAML tab/kubectl 造一条 `parentRefs: [{group: "", kind: Service, name: X}]` 的路由 |
| 7 | 未建模 kind（如 `ServiceImport`）：kind 显示原值 + 只读小字提示；名称/段名手输；保存后 kind/group 不变 | 造一条 `kind: ServiceImport, group: multicluster.x-k8s.io` 的父引用 |
| 8 | 带 `port` 的父引用：块头出现 `port=<n>（本页不改，可在 YAML 里改）`；保存后 port 不变 | 造一条带 port 的父引用 |
| 9 | 列表页与详情页：Gateway 行显示 `Gateway ns/name:sectionName`，Service 行显示 `Service ns/name:端口名`，字段名均为「父资源」 | 断言 5/6 留下的数据 |
| 10 | waypoint Gateway（labels 带 `istio.io/waypoint-for`）的选项文案带 ` · waypoint` | 真实 waypoint 或桩数据 |
| 11 | **回填不被清空逻辑误伤**：打开一条带 name + sectionName 的 Gateway 父引用路由，两者原样显示；打开 Service 父引用，kind 显示 Service 且 name 不被清空 | 断言 6 的数据 |

- [ ] **Step 3: 无集群时的降级（只在没有可用集群时执行）**

无法验证落盘时，改为验证**提交载荷**（编辑器发出的 body）与**后端单测**（Task 1 已证 DTO→CRD）的组合，并在结论里写明"未做落盘验证"。

先用 `preview_eval` 装上"只录不改"的 XHR 记录器（**不伪造响应**，所以不需要知道响应体结构；请求本身失败也不影响已录到的 body）：

```js
(() => {
  if (window.__capPatched) return 'already patched'
  window.__capPatched = true
  window.__cap = []
  const origOpen = XMLHttpRequest.prototype.open
  const origSend = XMLHttpRequest.prototype.send
  XMLHttpRequest.prototype.open = function (m, u, ...rest) { this.__u = u; return origOpen.call(this, m, u, ...rest) }
  XMLHttpRequest.prototype.send = function (body) {
    window.__cap.push({ url: String(this.__u), body: typeof body === 'string' ? body : null })
    return origSend.call(this, body)
  }
  return 'patched'
})()
```

然后**先**在编辑页填好一个 Service 父引用并点保存，再读回载荷：

```js
JSON.parse(window.__cap.at(-1).body).parentRefs
```

期望：`kind: "Service"`、`group: null`、`name` 为填的服务名（`group` 的显式空串由后端写盘侧归一 —— 见 Task 1 测试 1）。把这段 JSON 贴进落地记录作为证据。

- [ ] **Step 4: 写落地记录（追加到本文件末尾）**

```markdown
## 落地记录（YYYY-MM-DD）

- 实际改动文件：（逐条列）
- 与计划的偏差：（逐条列，含原因）
- 验证证据：（11 条断言的实跑结果；哪几条降级了、降级原因）
- 未验证：（如实列出，例如"无可用集群，断言 5/6 降级为载荷断言"）
```

- [ ] **Step 5: 提交**

```bash
git add docs/superpowers/plans/2026-10-14-route-parentrefs.md
git commit -m "docs(plan): route 父引用跨 kind 支持落地记录"
```

---

## 自查（作者已核）

- **spec 覆盖**：§4.1→Task 1；§4.2/4.3/4.4→Task 1 Step 4/5；§5.1→Task 2；§5.2/5.3/5.4→Task 3；5 个编辑器→Task 3+4；§5.5→Task 5；§7→Task 1 Step 1–6 + Task 6；§9 分期与任务划分一致。
- **无占位**：所有步骤都带可粘贴代码或精确锚点。
- **命名一致**：`parentKind` / `bucketOf` / `effectiveNs` / `hasUnmodeledKind` / `sectionOptions` / `noDataText` / `sectionNoDataText` / `onParentKindChange` / `onParentNamespaceChange` / `useParentCandidates` / `bucket` / `ensure` / `ParentCandidate` / `CandidateBucket` 全文一致。
- **Review Focus 逐条有落点**：见文首六条各自的任务指向。
