# Quantity DTO 全面改为 BigDecimal（基础单位）— 实施方案

> 状态：**已实施（后端 + 前端）**。后端全 reactor `mvn test` 绿（7 模块编译通过，k8s-core 12 测试通过）。
> 前端已迁移为「基础单位数字」模型：编辑器输入仍带单位（cpu=m、memory=Mi、容量=Gi），提交前换算成基础单位数字；展示时格式化回 `16Gi`/`7.92`。所有涉及文件 `vue-tsc` 类型检查通过。
> 目标：让所有 K8s Quantity 相关的 DTO 字段用 `BigDecimal`（基础单位）承载，
> 便于后端做数据汇总/利用率计算；前端保留带单位的输入体验，提交前换算成基础单位数字。

> **实施中附带修复**：`NodeService.fillPodStatus`（WIP 新文件）引用了 `NodeDTO` 上尚不存在的
> `podCount` / `cpuRequestMillicores` / `memRequestBytes` 三个字段导致 platform-api 无法编译。
> 已在 `NodeDTO` 补上这三个 `Long` 字段（节点 Pod 数 + CPU/内存 requests 合计，运行时统计）。
> 这是**独立于本方案**的既有 WIP 缺口，仅为解锁编译而补；如类型/命名不符预期可单独调整。

> **前端迁移范围**：`types/workload.ts`（Resources/PvcTemplate.storage/emptyDir.sizeLimit/divisor 改 number）、
> `utils/quantity.ts`（新增 formatBytes/formatQuantity）、`utils/metrics.ts`（parseCpuCores/parseMemBytes 兼容数字）、
> 编辑器 ResourcesEditor / PvcTemplateEditor / VolumeEditor / DownwardAPIFilesEditor，
> 展示 ContainerDetailView / WorkloadDetailView / NodeView / NodeDetailView / HpaView / PvcView、`types.ts`。
> **遗留（非本方案）**：`ProbeEditor`/`LifecycleEditor` 的 `port: string→number` 是另一处既有 WIP 改动
> （对应已改的 HttpGetActionDTO/TCPSocketActionDTO），与本 Quantity 迁移无关，单独处理。

## 1. 背景与动机

当前所有 quantity → DTO 的转换都用 `Quantity.toString()`，得到带单位的字符串（`"500m"`、`"16Gi"`）。
一旦要做数据汇总（如节点利用率 = Σ requests / allocatable），前后端都要解析这些带单位字符串，很费劲。

fabric8 7.x 提供了现成能力：
- **读**：`Quantity.getNumericalAmount()` → 基础单位 `BigDecimal`。CPU=核数（`"7920m"`→`7.92`），内存/存储=字节（`"16Gi"`→`17179869184`）。
- **写**：`new Quantity(bd.toPlainString())` → 裸基础单位数字（CPU `"0.5"`、内存 `"17179869184"`），总是合法、精确、无舍入。
  - 备选 `Quantity.fromNumericalAmount(bd, "Gi"/"m")` 能美化 manifest，但内部用 DECIMAL64 除法，大数值有舍入风险。**不采用**。

已确认：
- 后端除 converter 外**没有别的地方**把这些字段当 String 用（爆炸半径限定在 converter + `WorkloadValidator` + 测试）。
- `platform-common` **不依赖 fabric8**，故 DTO 只放 `java.math.BigDecimal`；Quantity↔BigDecimal 工具必须放 `k8s-core`。

## 2. 核心约定

| 项 | 约定 |
|---|---|
| 字段类型 | `java.math.BigDecimal`，基础单位（CPU=核数、内存/存储=字节） |
| 读路径 | converter 调 `q.getNumericalAmount()` |
| 写路径 | 请求 DTO 收 BigDecimal，converter 用 `new Quantity(bd.toPlainString())` 重建 |
| JSON | BigDecimal 序列化为数字；内存字节在 JS 安全整数范围（<2^53≈9e15）内，无需字符串兜底 |
| null 处理 | 全程 null-safe |
| 字符串化 | 一律 `toPlainString()`（避免科学计数法 `1E+10`） |

## 3. 新增工具类（k8s-core）

**文件**：`k8s-core/src/main/java/com/coding/k8score/util/QuantityUtil.java`

```java
public final class QuantityUtil {
    private QuantityUtil() {}

    /** Quantity → 基础单位 BigDecimal；null 入参返回 null。 */
    public static BigDecimal toBase(Quantity q) {
        return q == null ? null : q.getNumericalAmount();
    }

    /** 基础单位 BigDecimal → Quantity（裸基础单位数字）；null 入参返回 null。 */
    public static Quantity fromBase(BigDecimal bd) {
        return bd == null ? null : new Quantity(bd.toPlainString());
    }

    public static Map<String, BigDecimal> toBaseMap(Map<String, Quantity> m) { /* null→null，逐值 toBase */ }
    public static Map<String, Quantity> fromBaseMap(Map<String, BigDecimal> m) { /* null→null，逐值 fromBase */ }
}
```

## 4. DTO 改动（platform-common，纯类型替换 + import）

| 类 | 字段 | 变更 |
|---|---|---|
| `NodeDTO` | cpuCapacity / memoryCapacity / cpuAllocatable / memoryAllocatable | `String`→`BigDecimal` ×4；更新类注释 |
| `ResourcesDTO` | limits、requests | `Map<String,String>`→`Map<String,BigDecimal>` |
| `EmptyDirVolumeDTO` | sizeLimit | `String`→`BigDecimal` |
| `ResourceFieldSelectorDTO` | divisor | `String`→`BigDecimal` |
| `PersistentVolumeClaimDTO` | storage | `String`→`BigDecimal` |
| `PersistentVolumeDTO` | capacity | `String`→`BigDecimal` |
| `PvcTemplateDTO` | storage | `String`→`BigDecimal` |
| `HpaMetricTargetDTO` | value、averageValue | `String`→`BigDecimal` ×2；更新字段注释 |

每个文件补 `import java.math.BigDecimal;`。
（labels / selector / storageClassName 等**不是** quantity，不动。）

## 5. Converter 改动（k8s-core，6 个文件）

统一把 `.toString()` / `new Quantity(string)` 换成 `QuantityUtil`：

| 文件 | 读路径 | 写路径 |
|---|---|---|
| `CoreV1NodeConverter` | 4 个 cpu/内存字段改 `QuantityUtil.toBase(...)`；删私有 `quantityString` | —（节点不支持平台侧 create） |
| `WorkloadConverter` | `fromQuantities`→返回 `Map<String,BigDecimal>`（用 `toBaseMap`）；emptyDir sizeLimit、PVC storage、divisor 改 `toBase` | `toQuantities`→入参 `Map<String,BigDecimal>`（用 `fromBaseMap`）；sizeLimit/divisor/PVC storage 写回改 `fromBase`，判空从 `hasText(...)` 改成 `!= null` |
| `CoreV1PodConverter` | `fromQuantities`→`toBaseMap`；divisor 改 `toBase` | —（Pod 只读） |
| `HpaV2Converter` | value/averageValue 改 `toBase` | 判空从 `StringUtils.hasText(...)` 改成 `!= null`，写回用 `fromBase` |
| `CoreV1PvcConverter` | storage 改 `toBase` | storage 写回：默认值 `"1Gi"` 逻辑改为——`in.getStorage()==null` 时用 `new BigDecimal("1073741824")`，再 `fromBase` |
| `CoreV1PersistentVolumeConverter` | capacity 改 `toBase` | capacity 写回用 `fromBase`（`dto.getCapacity()!=null` 分支） |

> 注意：`WorkloadConverter.toQuantities` 当前对 null value 会 `new Quantity(null)`；改为 `fromBaseMap` 后需保持 null-safe（工具已处理）。
> 判空方式变化：String 字段原来用 `StringUtils.hasText` / `hasText`，BigDecimal 字段统一改成 `!= null`。

## 6. 受影响的其他后端代码

- **`WorkloadValidator.checkResources`**（platform-api）：requests/limits 变 `Map<String,BigDecimal>` →
  循环类型改 `Map.Entry<String, BigDecimal>`，比较从 `K8sQuantity.compare(a,b)>0` 改成 `e.getValue().compareTo(lim.get(key))>0`。
  副作用：报错文案里的数值从 `"500m"` 变 `"0.5"`（可接受；如需友好展示单位可另留 `K8sQuantity` 做格式化）。
- **`K8sQuantity`**（platform-api）保留，其它校验场景仍用；只是 `checkResources` 不再依赖它。

## 7. 测试

- **新增** `k8s-core/src/test/java/com/coding/k8score/util/QuantityUtilTest.java`：
  - cpu cores（7920m→7.92、8→8、0.5→0.5）、memory bytes（16Gi→17179869184、512Mi→536870912）
  - null-safe（toBase/fromBase/map 均 null→null）
  - 往返精确；`fromBase` 大数值不含科学计数法 `E`
- **更新** `CoreV1NodeConverterTest`：期望值改 BigDecimal ——
  - `getCpuCapacity()` → `8`
  - `getMemoryAllocatable()` → `17179869184`（原 `"15Gi"`… 注意测试里 allocatable memory 是 `15Gi`=16106127360，capacity memory 才是 `16Gi`）
  - `getCpuAllocatable()` → `7.92`
- 其余 converter 若有测试同步更新期望值。

## 8. 前端契约变更（**已实施**）

这些字段现在都是基础单位数字。前端：
- **输入**仍带单位（cpu→m、memory→Mi、容量/sizeLimit→Gi），**提交前换算成基础单位数字**（cpu→核数、内存/存储→字节）。
- **展示**把数字格式化回 `"16Gi"` / `"7.92"`。
- 涉及页面：节点详情、工作负载创建/编辑(resources)、HPA 编辑、PVC/PV。

## 9. 风险与注意

- **破坏性 API 变更**：前后端需同步上线。旧前端发字符串 `"500m"`，后端 BigDecimal 反序列化会直接报错 →
  建议后端本次改完，前端紧接着跟进；或灰度期后端临时兼容（不推荐，增加复杂度）。
- `toPlainString()` 避免科学计数法；全程 null-safe。
- HPA `Utilization` 类型的 value 为 null、走 `averageUtilization`(Integer)，不受影响。

## 10. 实施顺序（checklist）

1. [x] 新增 `QuantityUtil` + `QuantityUtilTest`，跑通（8 测试通过）
2. [x] 改 8 个 DTO（类型 + import + 注释）
3. [x] 改 6 个 converter（读/写路径 + 判空方式）
4. [x] 改 `WorkloadValidator.checkResources`
5. [x] 更新 `CoreV1NodeConverterTest`（其余受影响测试无需改）
6. [x] `mvn test` 全 reactor 绿（7 模块编译通过，k8s-core 12 测试通过）
7. [x] 前端迁移为基础单位数字模型（编辑器换算 + 展示格式化），`vue-tsc` 类型检查通过

**遗留（非本方案）**：`ProbeEditor`/`LifecycleEditor` 的 `port: string→number` 是另一处既有 WIP 改动
（对应已改的 HttpGetActionDTO/TCPSocketActionDTO），与本 Quantity 迁移无关，单独处理。前后端需同步上线。
