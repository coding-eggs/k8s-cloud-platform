# B2 · HPA 重构 + 工作负载联动 + metrics 门禁 实施计划

> **执行方式（必需）**：配合 superpowers 的 subagent-driven-development 技能逐任务使用。若不存在该技能或等价机制，STOP 并在开始执行前与人类伙伴协商。

**目标：** 把 HPA 从 dialog 式页面重构为独立编辑页（工作负载下拉 + FieldHelp 全覆盖 + 一负载一 HPA 双保险 + capability 精确门禁 + behavior 动态启用），并在工作负载列表加 HPA badge 与「添加 HPA」跳转。

**架构：** 后端镜像 ServiceMonitorService 落位——新增 `HpaService` 承载单绑定校验（`k8s.list` + kind/name 过滤），`ClusterService.getCapability` 只读解析 `k8s_cluster.capability` 列（语义对齐 factory `readApiVersions`）；前端新增 `useClusterCapability` composable 派生门禁布尔，`HpaEditorView.vue` 镜像 ServiceMonitorEditorView 骨架、转换器从 HpaView 原样迁移。

**技术栈：** Java 21 / Spring Boot 多模块 Maven；Vue 3 + TS + Element Plus（无前端单测 runner，门控 = type-check）。

## 全局约束（绑定每个任务）

- canonical 绑定键 = **小写** `${kind.toLowerCase()}/${name}`；`scaleTargetRef.kind` 首字母大写（`Deployment`），workload `kind` 小写（`deployment`）——交叉引用一律小写归一，只在组装 body 时转回大写；后端比较用 `equalsIgnoreCase`。
- capability 空/未探测 ⇒ `hpaSupportsBehavior=true`（对齐 `KubernetesOperationsFactory.buildHpa` 默认 V2）；软提示、永不硬阻断未探测态。
- `scaleTargetRef.namespace` 从不 round-trip（两 converter 均不回填）→ 绑定比较只在 HPA 自身 namespace 内按 kind+name。
- 门禁只禁「创建」与「新选指标类型」；存量已选值回显不拦。
- platform-api 测试命令模板：`mvn -q -pl platform-api -am test -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false`（上游模块无匹配测试会报错，flag 必带）。
- 提交：只 `git add` 各任务列出的文件，绝不 `git add -A`。
- DaemonSet 不可被 HPA 伸缩（无 Scale 子资源）→ 下拉不含、行操作不显示。

---

### Task 1 ·  platform-common：错误码

**文件：**
- 修改: `platform-common/src/main/java/com/coding/common/exception/EnumResponseType.java:46`

- [ ] **步骤 1：加枚举值**

在 `HPA_V1_ONLY_CPU(10021, …),`（L46）之后、`ERROR(5000,…)` 之前插入：

```java
    HPA_TARGET_ALREADY_BOUND(10022, "该工作负载已绑定 HPA，一个工作负载只能绑定一个 HPA"),
```

- [ ] **步骤 2：编译门控**

运行: `mvn -q -pl platform-common -am compile` → BUILD SUCCESS

- [ ] **步骤 3：提交**

`git add platform-common/src/main/java/com/coding/common/exception/EnumResponseType.java` → `feat(hpa): add HPA_TARGET_ALREADY_BOUND error code (10022)`

---

### Task 2 ·  capability 读取端点（后端）

**文件：**
- 修改: `platform-api/src/main/java/com/coding/platformapi/services/ClusterService.java`（`refreshCapabilityStrict` 之后加方法）
- 修改: `platform-api/src/main/java/com/coding/platformapi/controllers/ClusterController.java`（`/capability/refresh` 端点后加）

- [ ] **步骤 1：ClusterService.getCapability**

```java
    /**
     * 读集群 API 能力（k8s_cluster.capability 列，JSON：group→versions[]）。
     * 无行/空列/解析失败 → 空 map（前端据此判「未探测」，不硬阻断）。零推导：只读+反序列化。
     * 语义对齐 KubernetesOperationsFactory.readApiVersions。
     */
    public Map<String, List<String>> getCapability(String clusterId) {
        K8sCluster cluster = clusterMapper.selectByPrimaryKey(clusterId);
        if (cluster == null || !StringUtils.hasText(cluster.getCapability())) {
            return Map.of();
        }
        try {
            return jsonMapper.readValue(cluster.getCapability(),
                    new TypeReference<Map<String, List<String>>>() {});
        } catch (Exception e) {
            log.warn("解析集群 {} capability 失败（按未探测处理）: {}", clusterId, e.getMessage());
            return Map.of();
        }
    }
```

需补 import：`com.fasterxml.jackson.core.type.TypeReference` 或 **Jackson 3 对应类**——注意本类已用 `tools.jackson.databind.json.JsonMapper`（Jackson 3 命名空间），`TypeReference` 取 `tools.jackson.core.type.TypeReference`。以编译通过为准（参照 `KubernetesOperationsFactory.readApiVersions` L142-157 的写法，它用 fabric8 的 `Serialization.jsonMapper()`；此处用注入的 `jsonMapper` 实例）。

- [ ] **步骤 2：ClusterController 端点**

在 `refreshCapability` 端点（L71-76）后加，风格与其成对：

```java
    @PostMapping("/capability/get")
    @Operation(summary = "读取集群 API 能力", description = "返回 k8s_cluster.capability 持久化的 group→versions 快照；未探测返回空对象")
    public ResponseData<Map<String, List<String>>> getCapability(@RequestBody ClusterKeyRequest request) {
        return new ResponseData<>(clusterService.getCapability(request.getClusterId()));
    }
```

补 import `java.util.Map`（`List` 已有）。

- [ ] **步骤 3：编译门控** `mvn -q -pl platform-api -am compile`

- [ ] **步骤 4：提交** 两文件 → `feat(cluster): read-only capability endpoint POST /cluster/capability/get`

---

### Task 3 ·  HpaService + 单测 [TDD]

**文件：**
- 创建: `platform-api/src/main/java/com/coding/platformapi/services/HpaService.java`
- 创建: `platform-api/src/test/java/com/coding/platformapi/services/HpaServiceTest.java`

- [ ] **步骤 1：写失败测试**（JUnit5+Mockito+AssertJ，镜像 `PodMonitorServiceTest` 结构）

```java
package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.HpaDTO;
import com.coding.common.models.k8s.dto.HpaCrossVersionObjectReferenceDTO;
import com.coding.platformapi.k8s.K8sResourceClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HpaServiceTest {

    private final K8sResourceClient k8s = mock(K8sResourceClient.class);
    private final HpaService svc = new HpaService(k8s);

    private HpaDTO hpa(String name, String kind, String targetName) {
        HpaDTO d = new HpaDTO();
        d.setName(name);
        d.setNamespace("ns1");
        d.setTenantId("t1");
        d.setClusterId("c1");
        if (kind != null) {
            HpaCrossVersionObjectReferenceDTO ref = new HpaCrossVersionObjectReferenceDTO();
            ref.setKind(kind);
            ref.setName(targetName);
            d.setScaleTargetRef(ref);
        }
        return d;
    }

    @Test
    void create_blocked_when_target_already_bound() {
        when(k8s.list(any(HpaDTO.class))).thenReturn(List.of(hpa("hpa-existing", "Deployment", "app")));
        when(k8s.create(any(HpaDTO.class))).thenAnswer(i -> i.getArgument(0));
        HpaDTO body = hpa("hpa-new", "Deployment", "app");
        assertThatThrownBy(() -> svc.create(body))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("hpa-existing");
        verify(k8s, never()).create(any(HpaDTO.class));
    }

    @Test
    void update_allowed_for_self() {
        when(k8s.list(any(HpaDTO.class))).thenReturn(List.of(hpa("hpa-1", "Deployment", "app")));
        when(k8s.update(any(HpaDTO.class))).thenAnswer(i -> i.getArgument(0));
        HpaDTO body = hpa("hpa-1", "Deployment", "app");
        assertThat(svc.update(body).getName()).isEqualTo("hpa-1");
    }

    @Test
    void update_blocked_when_bound_to_other_hpa() {
        when(k8s.list(any(HpaDTO.class))).thenReturn(List.of(hpa("hpa-other", "StatefulSet", "db")));
        when(k8s.update(any(HpaDTO.class))).thenAnswer(i -> i.getArgument(0));
        HpaDTO body = hpa("hpa-1", "StatefulSet", "db");
        assertThatThrownBy(() -> svc.update(body))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("hpa-other");
        verify(k8s, never()).update(any(HpaDTO.class));
    }

    @Test
    void kind_matched_case_insensitively() {
        // 线上存的是 "Deployment"，body 传 "deployment" —— 仍须判重
        when(k8s.list(any(HpaDTO.class))).thenReturn(List.of(hpa("hpa-existing", "Deployment", "app")));
        HpaDTO body = hpa("hpa-new", "deployment", "app");
        assertThatThrownBy(() -> svc.create(body)).isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void null_scaleTargetRef_passthrough_without_list() {
        when(k8s.create(any(HpaDTO.class))).thenAnswer(i -> i.getArgument(0));
        HpaDTO body = hpa("hpa-x", null, null);
        assertThat(svc.create(body).getName()).isEqualTo("hpa-x");
        verify(k8s, never()).list(any(HpaDTO.class));
    }
}
```

- [ ] **步骤 2：跑测试确认失败**（类不存在 → 编译错误即"失败"）

`mvn -q -pl platform-api -am test -Dtest=HpaServiceTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **步骤 3：实现 HpaService**

```java
package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.HpaDTO;
import com.coding.common.models.k8s.dto.HpaCrossVersionObjectReferenceDTO;
import com.coding.platformapi.k8s.K8sResourceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * HPA 业务层：create/update 前做「一负载一 HPA」硬校验（条目 3 后端半；前端禁选是 UX 半，双保险）。
 * list/get/yaml/delete 无业务规则，controller 直连 client。
 * scaleTargetRef.namespace 不经 converter round-trip，比较只按 kind+name（HPA 自身 ns 内，list 已按 ns 圈定）。
 */
@Service
@RequiredArgsConstructor
public class HpaService {

    private final K8sResourceClient k8s;

    public HpaDTO create(HpaDTO body) {
        validateSingleBinding(body, null);
        return k8s.create(body);
    }

    public HpaDTO update(HpaDTO body) {
        validateSingleBinding(body, body.getName());
        return k8s.update(body);
    }

    /** 目标 ref 缺失 → 放行（真非法由 apiserver 拒；本校验只管重复绑定） */
    private void validateSingleBinding(HpaDTO body, String excludeName) {
        HpaCrossVersionObjectReferenceDTO ref = body.getScaleTargetRef();
        if (ref == null || !StringUtils.hasText(ref.getName())) {
            return;
        }
        HpaDTO q = new HpaDTO();
        q.setTenantId(body.getTenantId());
        q.setClusterId(body.getClusterId());
        q.setNamespace(body.getNamespace());
        for (HpaDTO existing : k8s.list(q)) {
            HpaCrossVersionObjectReferenceDTO er = existing.getScaleTargetRef();
            if (er == null || !ref.getName().equals(er.getName())) {
                continue;
            }
            if (ref.getKind() == null ? er.getKind() == null : ref.getKind().equalsIgnoreCase(er.getKind())) {
                if (excludeName == null || !excludeName.equals(existing.getName())) {
                    throw new CloudPlatformException(EnumResponseType.HPA_TARGET_ALREADY_BOUND,
                            "工作负载「" + ref.getKind() + "/" + ref.getName() + "」已绑定 HPA「" + existing.getName()
                                    + "」，一个工作负载只能绑定一个 HPA");
                }
            }
        }
    }
}
```

（`CloudPlatformException(EnumResponseType, String)` 2 参构造存在，先例 ServiceMonitorService L71。）

- [ ] **步骤 4：跑测试确认通过** 5/5 PASS（同步骤 2 命令）
- [ ] **步骤 5：提交** 两文件 → `feat(hpa): HpaService single-binding validation + tests`

---

### Task 4 ·  HpaController 改接线

**文件：**
- 修改: `platform-api/src/main/java/com/coding/platformapi/controllers/resource/HpaController.java`

- [ ] **步骤 1：** 加字段 `private final HpaService hpaService;`（`@RequiredArgsConstructor` 已有）；`create` 里 `k8s.create(body)` → `hpaService.create(body)`（注释补一句「scaleTargetRef 重复绑定由 service 校验」）；`update` 里 `k8s.update(body)` → `hpaService.update(body)`（`body.setName(name)` 保留在前）。list/get/yaml/delete 不动。
- [ ] **步骤 2：门控** `mvn -q -pl platform-api -am test -Dtest=HpaServiceTest -Dsurefire.failIfNoSpecifiedTests=false` + `mvn -q -pl platform-api -am compile`
- [ ] **步骤 3：提交** `refactor(hpa): route create/update through HpaService`

---

### Task 5 ·  前端 api client + types

**文件：**
- 修改: `platform-web/src/types.ts`（K8sHpa 块 L404 前）
- 修改: `platform-web/src/api/index.ts`（clusterApi L27-45 内）

- [ ] **步骤 1：types.ts 加**

```ts
/** 集群 API 能力快照（group → versions[]）；空对象 = 未探测。后端 POST /cluster/capability/get */
export type K8sClusterCapability = Record<string, string[]>
```

- [ ] **步骤 2：clusterApi 加方法**（`refreshCapability` 旁）

```ts
  getCapability: (clusterId: string) =>
    http.post<never, K8sClusterCapability>('/cluster/capability/get', { clusterId }),
```

并在文件顶部 type import 加 `K8sClusterCapability`。

- [ ] **步骤 3：门控** `npm --prefix platform-web run type-check`
- [ ] **步骤 4：提交** 两文件 → `feat(hpa-web): capability api client + type`

---

### Task 6 ·  useClusterCapability composable

**文件：**
- 创建: `platform-web/src/composables/useClusterCapability.ts`（目录先例 `useResourceOptions.ts`）

- [ ] **步骤 1：实现**

```ts
import { computed, ref, watch, type Ref } from 'vue'
import { clusterApi } from '@/api'
import type { K8sClusterCapability } from '@/types'

/**
 * 集群 API 能力（metrics-server / prometheus-adapter / autoscaling 版本）派生门禁。
 * 入参 clusterId ref；clusterId 变化自动重读。空快照 = 未探测：不硬阻断，behavior 默认启用
 * （对齐后端 KubernetesOperationsFactory.buildHpa 无 capability 时默认 V2）。
 */
export function useClusterCapability(clusterId: Ref<string | undefined>) {
  const cap = ref<K8sClusterCapability>({})
  const loading = ref(false)

  async function load(): Promise<void> {
    const id = clusterId.value
    if (!id) { cap.value = {}; return }
    loading.value = true
    try {
      cap.value = await clusterApi.getCapability(id)
    } catch {
      cap.value = {} // 拦截器已提示；按未探测降级
    } finally {
      loading.value = false
    }
  }

  /** 手动触发后端重新 discovery 并回读（HPA 页「刷新能力」按钮用） */
  async function refresh(): Promise<void> {
    const id = clusterId.value
    if (!id) return
    await clusterApi.refreshCapability(id)
    await load()
  }

  const probed = computed(() => Object.keys(cap.value).length > 0)
  const hasMetricsServer = computed(() => 'metrics.k8s.io' in cap.value)
  const hasCustomMetrics = computed(() => 'custom.metrics.k8s.io' in cap.value)
  const hasExternalMetrics = computed(() => 'external.metrics.k8s.io' in cap.value)
  /** 探测到 autoscaling 且无 v2 → false；未探测 → true（默认 V2） */
  const hpaSupportsBehavior = computed(() =>
    !probed.value ? true : (cap.value['autoscaling'] ?? []).includes('v2'))
  /** 页面级门禁：探测过且三类 metrics 全无 → 禁创建 */
  const metricsAvailable = computed(() =>
    !probed.value || hasMetricsServer.value || hasCustomMetrics.value || hasExternalMetrics.value)

  watch(clusterId, () => { void load() }, { immediate: true })

  return { cap, loading, probed, hasMetricsServer, hasCustomMetrics, hasExternalMetrics, hpaSupportsBehavior, metricsAvailable, load, refresh }
}
```

- [ ] **步骤 2：门控** `npm --prefix platform-web run type-check`
- [ ] **步骤 3：提交** → `feat(hpa-web): useClusterCapability composable`

---

### Task 7 ·  HpaEditorView.vue（独立编辑页；条目 2/6/7）

**文件：**
- 创建: `platform-web/src/views/resource/HpaEditorView.vue`
- 参照（只读）: `platform-web/src/views/resource/ServiceMonitorEditorView.vue`（骨架）、`HpaView.vue` L32-212（模型+转换器，**原样迁移**）、L379-493（表单模板，改造）

骨架约定（同 SM 编辑器）：`editing = route.query.name`；`?targetKind&targetName` 预填（可切换，用户裁决）；`loadDetail()` 用 **`hpaApi.get(editing, ctx3)`**（编辑回填必须走 get——列表行深度不足）+ `detailState` + `formVisible`；`PageHeader`+返回；`EmptyState` 未选上下文；提交校验链沿用 HpaView L219-233 文案逻辑；成功 `router.push('/resources/hpas')`。

关键改造点（相对 HpaView dialog 表单）：

1. **目标工作负载下拉**（替代 targetKind select + targetName 自由文本）：
```ts
const workloadOptions = ref<WorkloadDetail[]>([])
const boundKeys = ref<Set<string>>(new Set())
/** canonical 键：小写 kind/name —— 全局不变量 */
function canonicalKey(kind: string | null | undefined, name: string | null | undefined): string {
  return `${(kind ?? '').toLowerCase()}/${name ?? ''}`
}
async function loadOptions(): Promise<void> {
  if (!ready.value) return
  const ctx = { tenantId: state.tenantId!, clusterId: state.clusterId!, namespace: state.namespace! }
  const [ws, hs] = await Promise.all([workloadApi.list(ctx), hpaApi.list(ctx)])
  workloadOptions.value = ws.filter((w) => w.kind === 'deployment' || w.kind === 'statefulset')
  const s = new Set(hs.map((h) => canonicalKey(h.scaleTargetRef?.kind, h.scaleTargetRef?.name)))
  if (editing.value) {
    // 编辑态：自身占用键不算「已绑定别人」——回填后按当前选择动态排除，见 boundFor 计算
  }
  boundKeys.value = s
}
const form = reactive({ targetKey: '', ... })  // 替代 targetKind/targetName
/** 自身键（编辑态回填的原始目标）从禁用集排除 */
const ownKey = ref('')
const isBound = (w: WorkloadDetail) =>
  boundKeys.value.has(canonicalKey(w.kind, w.name)) && canonicalKey(w.kind, w.name) !== ownKey.value
```
模板：`<el-select v-model="form.targetKey" filterable :loading="optsLoading">` + `el-option :disabled="isBound(w)"`，label `${w.name}（${kindLabel(w.kind)}）`，value=canonicalKey。已绑定项外包 `<el-tooltip content="已绑定 HPA">`（option disabled 时 tooltip 用 `#label` 插槽或 title 属性，实现从简：`el-option` 内自定义 label 插槽带提示文字「已绑定」）。提交拆回：`const [kKind, kName] = form.targetKey.split('/')` → `scaleTargetRef: { kind: KIND_CAP[kKind], name: kName }`，`KIND_CAP = { deployment: 'Deployment', statefulset: 'StatefulSet' }`。
预填：`?targetKind&targetName`（小写原值）→ `form.targetKey = targetKind+'/'+targetName`；ownKey 置该键。编辑回填：`ownKey = canonicalKey(detail.scaleTargetRef)`、`form.targetKey` 同值。

2. **指标类型门禁**：类型下拉 `:disabled="metricTypeBlocked(m.type) && selectedType !== m.type"` 语义 = 选项级禁用：
```ts
const TYPE_GROUP: Record<MetricType, 'metrics' | 'custom' | 'external'> = {
  Resource: 'metrics', ContainerResource: 'metrics', Pods: 'custom', Object: 'custom', External: 'external',
}
const typeAvailable = (t: MetricType) =>
  !cap.probed.value || (TYPE_GROUP[t] === 'metrics' ? cap.hasMetricsServer.value : TYPE_GROUP[t] === 'custom' ? cap.hasCustomMetrics.value : cap.hasExternalMetrics.value)
```
`el-option :disabled="!typeAvailable(t)"` + 选项内提示「需 metrics-server / prometheus-adapter」；**当前已选值不禁**（disabled 只作用于下拉选项，v-model 现值照常回显）。未探测软提示 alert。

3. **behavior 动态启用**：删除静态告警（HpaView L472-473 那条 alert 不迁移）。区块：
```html
<el-alert v-if="!cap.hpaSupportsBehavior.value" type="warning" :closable="false"
  title="该集群 autoscaling 无 v2（capability 探测），v1 HPA 不支持 behavior，仅支持 CPU 利用率" />
<div :class="{ 'behavior-disabled': !cap.hpaSupportsBehavior.value }">…原 behavior 网格…</div>
```
`behavior-disabled` = `pointer-events:none; opacity:.55`。信息性文案「留空则不设置 behavior；用于控制扩缩容速率与稳定期」并入区块 FieldHelp。

4. **FieldHelp 全字段**（`#label` 插槽模式，文案按 spec §7.2：名称/命名空间/目标工作负载/最小副本/最大副本/指标类型/资源名/容器名/指标名/被描述对象/target 类型与值/behavior 各字段）。

5. 校验链：沿用 HpaView L220-233 全部规则，仅「目标名称必填」改为「请选择目标工作负载」（`form.targetKey` 非空）。payload 组装沿用 L241-249（kind 用大写映射值）。

- [ ] 门控: `npm --prefix platform-web run type-check`
- [ ] 提交: 单文件 → `feat(hpa-web): standalone HPA editor (workload dropdown + FieldHelp + metric/behavior gating)`

---

### Task 8 ·  HpaView.vue 重构为纯列表页

**文件：**
- 修改: `platform-web/src/views/resource/HpaView.vue`

- [ ] **步骤 1：删除** dialog 模板（L377-499）、表单模型与转换器与 submit（L32-267 中除 `refresh/ctxParams` 外的表单部分）、`dialogVisible/saving/isEdit/openCreate/openEdit`。保留：列表加载、删除、详情抽屉、targetText/metricSummary、contextDesc、样式（表单专属样式 `.metric-block/.behavior-*` 等随迁移删或留编辑器）。
- [ ] **步骤 2：跳转**
```ts
const router = useRouter()
function goCreate(): void { router.push({ name: 'hpa-editor' }) }
function goEdit(row: K8sHpa): void { router.push({ name: 'hpa-editor', query: { name: row.name } }) }
```
行操作 3 按钮（L368-372）→ `el-dropdown`（查看/编辑/删除），模式抄 WorkloadView L287-304。
- [ ] **步骤 3：页面级门禁 + 刷新能力入口（用户裁决 2）**
```ts
const cap = useClusterCapability(computed(() => state.clusterId))
const refreshingCap = ref(false)
async function onRefreshCapability(): Promise<void> {
  refreshingCap.value = true
  try { await cap.refresh(); await refresh() } finally { refreshingCap.value = false }
}
```
PageHeader 下、表格上：`v-if="cap.probed.value && !cap.metricsAvailable.value"` → `el-alert type="warning"`「该集群未安装 metrics-server / prometheus-adapter，HPA 指标不可用，创建已禁用」内嵌「刷新能力」按钮；`v-else-if="!cap.probed.value && !cap.loading.value"` → 软提示 alert「集群 API 能力尚未探测…」（含刷新按钮）。「创建 HPA」按钮 `:disabled="!ready || !cap.metricsAvailable.value"`。
- [ ] **步骤 4：门控** type-check
- [ ] **步骤 5：提交** → `refactor(hpa-web): list-only page + capability-gated create with refresh shortcut`

---

### Task 9 ·  WorkloadView.vue 联动（条目 4/5）

**文件：**
- 修改: `platform-web/src/views/resource/WorkloadView.vue`

- [ ] **步骤 1：数据**
```ts
import { hpaApi } from '@/api'
import type { K8sHpa } from '@/types'
/** canonical 键（小写 kind/name）→ 绑定的 HPA 名 */
const hpaBound = ref<Map<string, string>>(new Map())
// refresh() 内并行：
const [ws, hs] = await Promise.all([workloadApi.list(ctxParams.value), hpaApi.list(ctxParams.value)])
list.value = ws
hpaBound.value = new Map(hs.map((h) => [`${(h.scaleTargetRef?.kind ?? '').toLowerCase()}/${h.scaleTargetRef?.name ?? ''}`, h.name]))
const hpaNameOf = (row: K8sWorkload) => hpaBound.value.get(`${row.kind.toLowerCase()}/${row.name}`)
```
- [ ] **步骤 2：badge**（名称列 L254「已暂停」tag 后）：
```html
<el-tooltip v-if="hpaNameOf(row)" :content="`已绑定 HPA「${hpaNameOf(row)}」`" placement="top">
  <el-tag type="success" size="small" effect="plain" class="op-tag">HPA</el-tag>
</el-tooltip>
```
- [ ] **步骤 3：行操作**（L297「伸缩」后加）：
```html
<el-dropdown-item v-if="row.kind !== 'daemonset' && !hpaNameOf(row)" command="addHpa">添加 HPA</el-dropdown-item>
```
`onRowCommand` 加 `case 'addHpa': router.push({ name: 'hpa-editor', query: { targetKind: row.kind, targetName: row.name } }); break`（kind 传**小写原值**，编辑器组装时转大写）。
- [ ] **步骤 4：门控** type-check
- [ ] **步骤 5：提交** → `feat(hpa-web): workload HPA badge + add-HPA row action`

---

### Task 10 ·  路由 + 全量验证 + spec 勘误

**文件：**
- 修改: `platform-web/src/router/index.ts:43`
- 修改: `docs/superpowers/specs/2026-09-14-hpa-refactor-design.md`（勘误回写）

- [ ] **步骤 1：路由**（L43 hpas 行后）：
```ts
{ path: 'resources/hpas/editor', name: 'hpa-editor', component: () => import('@/views/resource/HpaEditorView.vue'), meta: { title: 'HPA 编辑', group: '资源管理', context: 'full' } },
```
菜单无需动（GROUP_OF_PATH 已含 `hpas: 'g-workload'`）。
- [ ] **步骤 2：全量门控**
  - `mvn -q -T1C compile`
  - `mvn -q -pl k8s-core,platform-api -am test -Dsurefire.failIfNoSpecifiedTests=false`（HpaServiceTest 5 + 既有全绿）
  - `npm --prefix platform-web run build`
- [ ] **步骤 3：浏览器预览验证**（假 token+XHR 桩+SPA push 法，记忆 ui-preview-verification）：编辑器渲染、目标下拉禁选、指标类型禁用、behavior 两态、列表页门禁 banner、WorkloadView badge。
- [ ] **步骤 4：spec 勘误**（§4.1 路径改 `POST /cluster/capability/get`；§10 记录两裁决：HPA 页加刷新入口=做、预选可切换=维持）
- [ ] **步骤 5：提交** router 一笔；spec 勘误单独一笔 `docs(hpa): spec errata — capability endpoint path + §10 rulings`
- [ ] **步骤 6：人工 E2E 清单（交回人类伙伴，活集群）**：spec §9 七条 + 双保险后端拒绝文案 + 刷新能力后门禁变化 + behavior 落 `spec.behavior`。

---

## 依赖顺序

T1→T3→T4（后端链）；T2→T5→T6→{T7,T8,T9}（前端链）；T7→T8→T9→T10。T7 定义 canonical 键与 `?targetKind` 读取，T9 消费——两处必须逐字一致。
