# B3 · 命名空间管理增强(ResourceQuota / LimitRange / 描述 / 概览)实施计划

> **执行方式(必需)**:配合 superpowers 的 subagent-driven-development 技能逐任务使用。若不存在该技能或等价机制,STOP 并在开始执行前与人类伙伴协商。

**目标:** 命名空间补齐「创建 / 编辑 / 概览」三件套,从零建模 `ResourceQuota` + `LimitRange`(含用量与 fetch-overlay),并把命名空间升级为一等公民资源(顺带清除 k8s-server 内联映射的零业务违规)。

**架构:** platform-common 三个 DTO → k8s-core 类型化 core/v1 converter + operations(复用闲置的 `QuantityUtil.toBaseMap/fromBaseMap`)→ k8s-server `/admin/**` 零逻辑边界(Namespace 走既有 `AbstractClusterResourceController`;quota/limitrange 走新 `AbstractAdminNamespacedResourceController`)→ platform-api `NamespaceService` 独揽 DB 关联/守卫/upsert 编排 → Vue3 页面复用 Node/HPA 范式。

**技术栈:** Java 21 / Maven 多模块 / fabric8 7.9.0 类型化模型 + SSA;Vue 3 + TS + Element Plus(无前端测试 runner)。

**Spec:** `docs/superpowers/specs/2026-09-14-namespace-enhancement-design.md` §1–10(§11 延期,见 D10)。

## 用户裁决(2026-09-24,覆盖 spec 原文)

| # | 裁决 | 依据 |
|---|---|---|
| D1 | **访问路径 = `/admin/**`**。三个新边界 `/admin/namespaces`、`/admin/resourcequotas`、`/admin/limitranges` | `ResourceAccessResolver.resolveNamespacedAccess:63-85` 无 tenantId 直接抛「管理员代操作需显式指定 tenantId」、分配表校验不可跳过 → 租户边界机制对平台级流程结构性不可用;先例 `ClusterRoleController`(`@RequestMapping("/admin/clusterroles")` + `ClusterRoleDTO.getApiPath()="/admin/clusterroles"`);`K8sResourceClient` 路径无关(拼 `dto.getApiPath()`);k8s-server `ResourceServerConfig:68-70` 对 `/admin/**` 要求 `PLATFORM:admin` |
| D2 | **配额数值输入 = 数字框 + 固定单位 `#append`**(cpu→`m`、memory→`Mi`、计数→无后缀),**不是** spec §6.2 自由文本 `8Gi`/`500m`;线路仍 BigDecimal 基础单位 | 仿 `ResourcesEditor.vue:12-17,105-111`(仓库唯一资源量编辑先例) |
| D3 | **Namespace 一等公民化,现有 list/delete 一并迁移**;managed-by 由 `CoreV1NamespaceConverter.convert()` 服务端盖章;isManaged 守卫从 delete 扩到 update | 现状映射在 k8s-server `K8sProvisioningService.toNamespaceDTO:162-169`(违背零业务铁律),且 YAML tab 无实现路径 |
| D4 | **单份寻址 = 固定对象名 `default`**(错误码 10023);存量非 default → `multiple=true` 详情页琥珀告警,平台不接管不并建 | 生命周期最干净;避免「取第一个」的不稳定寻址 |
| D5 | spec §5.3 端点表补 `get`/`yaml`/`quota/delete`/`limitrange/delete` | §6.3 详情回填 + YAML tab + 编辑器「清空」语义必需 |
| D6 | 编辑器配额/限制范围两区块各带**启用开关**,区分「未配置」与「主动清空全部」 | 否则无法表达删除整个约束对象 |
| D7 | 三 DTO 只读回传 `resourceVersion`;**DTO 字段名自此冻结为 B4 跨批次契约** | B4 spec 明写「配额列结构预留待 B3」 |
| D8 | `NamespaceDTO` 保留字段名 `creationTimestamp`(新 quota/LR 用 `creationTime`) | 前端 `types.ts:68` 依赖,勿「统一」 |
| D9 | RBAC 细粒度(`PermissionAuthorizationManager`)是并行工作未实现;新端点继承双闸(platform-api 全量 `PLATFORM:admin` + k8s-server `/admin/**`),本批不播种 | `platform-api/configs/ResourceServerConfig.java:65-67` |
| D10 | **§11(能力开关)整体延期至 B6/B7** | 依赖 calico/istio 地基,`grep` 证实全部不存在 |

## 对上游设计稿的两处技术纠正(写代码前必读)

1. **K8s 配额资源键是全小写 `persistentvolumeclaims`**(不是驼峰 `persistentVolumeClaims`)。Java/TS 字段名用驼峰,但 converter 写入 `spec.hard` 的 **key 必须是小写形式**。`MODELED_HARD_KEYS` 常量以此为准。
2. **`@JsonProperty("default")` 不需要**。DTO 从不直接序列化给 K8s —— converter 逐字段显式读写 fabric8 模型,而 `LimitRangeItem` 自身已带该注解。故 DTO 字段就叫 `defaultValue`,**不给 platform-common 新增任何 Jackson 依赖**。

## 规范不变量(每任务自检)

1. managed-by 仅由 `CoreV1NamespaceConverter` 写;`ensureNamespace`(provisioning 用,`K8sProvisioningService:128-140`)不动。
2. upsert = platform-api 层 get-then-create-or-update + 强制 `name=default`;k8s-server 保持哑;幂等可重试(编辑器重试 UX 依赖此)。
3. 基础单位 BigDecimal 线路契约;换算只在 `QuantityUtil`(服务端)与 `quantityUnits.ts`(展示端)。
4. `creationTimestamp`(ns)vs `creationTime`(quota/LR)是刻意的,勿统一。
5. overlay 建模键清单以 converter 常量为权威、测试逐条断言;`convertForUpdate` 绝不产出 `status`。
6. 三新边界不带租户上下文参数;quota/LR 无需 capability 门禁(v1 恒可用)。
7. DB 关联/守卫只在 `NamespaceService`;`K8sProvisioningService` 只留 `ensureNamespace`。
8. SSA 一律 `ServerSideApply.FIELD_MANAGER` + `forceConflicts()`;**typed 资源必须先 overlay 再 SSA** —— `platform-system` 拥有整个 `hard` map,省略键=删除(`ServerSideApply` javadoc 明示)。
9. 迁移排序:T3 只增不删,T7 成对切换(k8s-server 先部署、platform-api 后),每任务门控自洽。
10. 前端门控 `type-check`,**批次最终门控 = 全量 `npm run build`**(B2 实证增量 tsbuildinfo 漏检跨文件类型错)。
11. 提交纪律:只 `git add` 各任务列出的文件,绝不 `git add -A`。
12. **ENV-NOTE**:worktree 无 `node_modules` → 前端门控前先建 junction(见「工作区准备」);测试命令必带 `-Dsurefire.failIfNoSpecifiedTests=false`(上游模块无匹配测试会红)。

## 工作区准备(控制器执行,非任务)

```bash
# 已建:worktree .claude/worktrees/namespace-b3,branch worktree-namespace-b3,base 0063119
# 前端门控前置(B2 教训:package.json 与基线逐字节同才可复用主检出 node_modules)
cmd //c rmdir D:\coding\k8s-cloud-platform\.claude\worktrees\namespace-b3\platform-web\node_modules   # 收尾时先拆链再删目录
powershell -NoProfile -Command "(Get-Item -LiteralPath '.claude\worktrees\namespace-b3\platform-web\node_modules').Delete()"
```
收尾删 worktree 前**必须先单独拆 junction**(否则 `rm -rf` 穿进主检出),并杀掉占端口的 vite(它 hold 住目录会 `Device busy`)。

---

### Task 1 ·  platform-common:三个 DTO + ResourceType + 错误码

**文件:**
- 创建: `platform-common/src/main/java/com/coding/common/models/k8s/dto/ResourceQuotaDTO.java`
- 创建: `.../dto/ResourceQuotaUsedDTO.java`
- 创建: `.../dto/LimitRangeDTO.java`
- 创建: `.../dto/LimitRangeItemDTO.java`
- 创建: `.../dto/ResourcePairDTO.java`
- 修改: `.../dto/NamespaceDTO.java`(继承 BaseResources)
- 修改: `platform-common/src/main/java/com/coding/common/models/k8s/ResourceType.java`
- 修改: `platform-common/src/main/java/com/coding/common/exception/EnumResponseType.java`

- [ ] **步骤 1:`ResourcePairDTO`**(quota/limitrange 的 cpu+memory 对,B 类不继承 BaseResources)

```java
package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 资源量对（cpu + memory），基础单位：cpu=核数、memory=字节。
 * 用于 LimitRange 的 max/min/default/defaultRequest/maxLimitRequestRatio。
 * 字段为 null = 该项未设置（converter 不写入该 key；overlay 语义下 null = 删除）。
 */
@Data
@Schema(description = "资源量对（cpu 核 / memory 字节，基础单位）")
public class ResourcePairDTO {

    @Schema(description = "CPU 核数，如 0.5 = 500m")
    private BigDecimal cpu;

    @Schema(description = "内存字节数，如 536870912 = 512Mi")
    private BigDecimal memory;
}
```

- [ ] **步骤 2:`ResourceQuotaUsedDTO`**(只读用量,与 quota 同 9 字段)

```java
package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * ResourceQuota 已用量（只读，来自 spec 侧的 status.used），字段与 {@link ResourceQuotaDTO} 的约束项一一对应。
 * 基础单位：cpu=核、memory=字节；计数=个。未建模的 hard key（如 count/deployments）不在此列，
 * 由 converter revert 时丢弃（平台只回显已建模项）。
 */
@Data
@Schema(description = "命名空间资源配额已用量（只读）")
public class ResourceQuotaUsedDTO {

    @Schema(description = "已用 CPU（核）")
    private BigDecimal cpu;

    @Schema(description = "已用内存（字节）")
    private BigDecimal memory;

    @Schema(description = "已用 Pod 数")
    private Integer pods;

    @Schema(description = "已用 Service 数")
    private Integer services;

    @Schema(description = "已用容器 limit CPU 总和（核）")
    private BigDecimal limitsCpu;

    @Schema(description = "已用容器 limit 内存总和（字节）")
    private BigDecimal limitsMemory;

    @Schema(description = "已用容器 request CPU 总和（核）")
    private BigDecimal requestsCpu;

    @Schema(description = "已用容器 request 内存总和（字节）")
    private BigDecimal requestsMemory;

    @Schema(description = "已用 PVC 数")
    private Integer persistentVolumeClaims;
}
```

- [ ] **步骤 3:`ResourceQuotaDTO`**

```java
package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 命名空间资源配额（core/v1 ResourceQuota）。
 * <p>
 * 平台单份约定：对象名固定 {@code default}（见 EnumResponseType.QUOTA_NAME_NOT_DEFAULT）。
 * 值为基础单位（cpu=核、memory=字节），Quantity↔BigDecimal 换算在 k8s-core QuantityUtil。
 * 字段为 null = 不约束该项；{@link #convertForUpdate} 的 overlay 语义据此删除 hard 中对应 key，
 * 未建模 key（count/deployments、services.nodeports 等）原样保留。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "命名空间资源配额")
public class ResourceQuotaDTO extends BaseResources {

    @Schema(description = "CPU 上限（核）→ hard.cpu")
    private BigDecimal cpu;

    @Schema(description = "内存上限（字节）→ hard.memory")
    private BigDecimal memory;

    @Schema(description = "Pod 数上限 → hard.pods")
    private Integer pods;

    @Schema(description = "Service 数上限 → hard.services")
    private Integer services;

    @Schema(description = "容器 limit CPU 总和上限（核）→ hard.\"limits.cpu\"")
    private BigDecimal limitsCpu;

    @Schema(description = "容器 limit 内存总和上限（字节）→ hard.\"limits.memory\"")
    private BigDecimal limitsMemory;

    @Schema(description = "容器 request CPU 总和上限（核）→ hard.\"requests.cpu\"")
    private BigDecimal requestsCpu;

    @Schema(description = "容器 request 内存总和上限（字节）→ hard.\"requests.memory\"")
    private BigDecimal requestsMemory;

    @Schema(description = "PVC 数上限 → hard.persistentvolumeclaims")
    private Integer persistentVolumeClaims;

    @Schema(description = "已用量（只读，来自 status.used）")
    private ResourceQuotaUsedDTO used;

    @Schema(description = "该命名空间存在多份 ResourceQuota（只读；平台只管理名为 default 的那份，前端据此告警）")
    private Boolean multiple;

    @Schema(description = "资源版本（只读回传）")
    private String resourceVersion;

    @Schema(description = "创建时间（只读）")
    private String creationTime;

    /** 走 k8s-server admin 命名空间域边界（平台级流程，无租户上下文） */
    @Override
    public String getApiPath() {
        return "/admin/resourcequotas";
    }
}
```

- [ ] **步骤 4:`LimitRangeItemDTO` + `LimitRangeDTO`**

```java
package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * LimitRange 单条限制项（core/v1 LimitRangeItem）。
 * 字段为 null / 内部 cpu、memory 为 null = 该项不约束（overlay 时删除对应 key）。
 * 注：K8s 侧字段名为 default（Java 关键字），故 DTO 用 defaultValue —— 由 converter 显式映射，
 * 无需 JSON 注解（DTO 不直接序列化给 K8s）。
 */
@Data
@Schema(description = "LimitRange 限制项")
public class LimitRangeItemDTO {

    @Schema(description = "作用类型：Container / Pod / PersistentVolumeClaim")
    private String type;

    @Schema(description = "上限 → max")
    private ResourcePairDTO max;

    @Schema(description = "下限 → min")
    private ResourcePairDTO min;

    @Schema(description = "默认值 → default")
    private ResourcePairDTO defaultValue;

    @Schema(description = "默认请求量 → defaultRequest")
    private ResourcePairDTO defaultRequest;

    @Schema(description = "limit/request 最大比值 → maxLimitRequestRatio")
    private ResourcePairDTO maxLimitRequestRatio;
}
```

```java
package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 命名空间限制范围（core/v1 LimitRange）。平台单份约定：对象名固定 default。
 * v1 只建模 Container / Pod / PersistentVolumeClaim 三类；其余（ContainerFixed）由
 * convertForUpdate 的 overlay 按 type 对齐原样保留。spec.limits 是 atomic list，
 * 故必须 overlay-before-SSA（省略即删）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "命名空间限制范围")
public class LimitRangeDTO extends BaseResources {

    @Schema(description = "限制项列表（按 type 唯一）")
    private List<LimitRangeItemDTO> limits;

    @Schema(description = "该命名空间存在多份 LimitRange（只读告警旗标）")
    private Boolean multiple;

    @Schema(description = "资源版本（只读回传）")
    private String resourceVersion;

    @Schema(description = "创建时间（只读）")
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/admin/limitranges";
    }
}
```

- [ ] **步骤 5:`NamespaceDTO` 一等公民化**

整文件替换为(注意 `@EqualsAndHashCode(callSuper = true)`、保留 `creationTimestamp`、`labels` 来自基类**不再重复声明**):

```java
package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 命名空间（core/v1，集群级资源）。
 * name/labels 继承自 {@link BaseResources}；namespace 恒为 null（集群级）。
 * 描述存于 metadata.annotations["description"]（同 WorkloadDTO 约定）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "命名空间")
public class NamespaceDTO extends BaseResources {

    @Schema(description = "状态（Active/Terminating）")
    private String phase;

    @Schema(description = "创建时间（ISO-8601）")
    private String creationTimestamp;

    @Schema(description = "描述 → metadata.annotations[\"description\"]")
    private String description;

    @Schema(description = "资源版本（只读回传）")
    private String resourceVersion;

    /** 平台级命名空间管理，走 admin 边界（无租户上下文） */
    @Override
    public String getApiPath() {
        return "/admin/namespaces";
    }
}
```

- [ ] **步骤 6:`ResourceType` 追加三项**

`NODE(NodeDTO.class)` 改为 `NODE(NodeDTO.class),` 并追加(需 import 三个新 DTO):

```java
    NAMESPACE(NamespaceDTO.class),
    RESOURCE_QUOTA(ResourceQuotaDTO.class),
    LIMIT_RANGE(LimitRangeDTO.class)
```

> 已验证 `clazz` 字段无 getter、无消费者,不存在会因新枚举项 break 的 exhaustive switch。

- [ ] **步骤 7:`EnumResponseType` 追加三个错误码**

在 `HPA_TARGET_ALREADY_BOUND(10022, …),` 之后、`ERROR(5000,…)` 之前:

```java
    QUOTA_NAME_NOT_DEFAULT(10023, "命名空间内平台管理的配额/限制范围对象名必须为 default（平台单份约定）"),
    LIMIT_RANGE_TYPE_UNSUPPORTED(10024, "限制范围类型仅支持 Container / Pod / PersistentVolumeClaim"),
    LIMIT_RANGE_VALUE_INVALID(10025, "限制范围取值非法：max 不得小于 min，defaultRequest 不得大于 default，maxLimitRequestRatio 不得小于 1"),
```

- [ ] **步骤 8:编译门控** `mvn -q -pl platform-common -am compile` → BUILD SUCCESS
- [ ] **步骤 9:提交** 8 个文件 → `feat(namespace): DTO layer — NamespaceDTO first-classing + ResourceQuota/LimitRange models + error codes 10023-10025`

---

### Task 2 ·  k8s-core:Namespace converter + operations + factory [TDD]

**文件:**
- 创建: `k8s-core/src/main/java/com/coding/k8score/converter/impl/core/CoreV1NamespaceConverter.java`
- 创建: `k8s-core/src/test/java/com/coding/k8score/converter/impl/core/CoreV1NamespaceConverterTest.java`
- 创建: `k8s-core/src/main/java/com/coding/k8score/operations/core/CoreV1NamespaceOperations.java`
- 创建: `k8s-core/src/test/java/com/coding/k8score/operations/core/CoreV1NamespaceOperationsTest.java`
- 修改: `k8s-core/src/main/java/com/coding/k8score/factory/KubernetesOperationsFactory.java`

- [ ] **步骤 1:写失败测试**(镜像 `CoreV1NodeConverterTest` 风格:包级私有类、无 Spring、fabric8 Builder 造夹具、AssertJ `isEqualByComparingTo`、必带 null 输入测试)

```java
package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.NamespaceDTO;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CoreV1NamespaceConverterTest {

    private final CoreV1NamespaceConverter c = new CoreV1NamespaceConverter();

    private Namespace live(String name, Map<String, String> labels, Map<String, String> annotations, String phase) {
        return new NamespaceBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withResourceVersion("1234")
                    .withCreationTimestamp("2026-09-01T00:00:00Z")
                    .withLabels(labels)
                    .withAnnotations(annotations)
                .endMetadata()
                .withNewStatus().withPhase(phase).endStatus()
                .build();
    }

    @Test
    void revert_maps_identity_phase_description_and_resource_version() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("env", "prod");
        Map<String, String> ann = new LinkedHashMap<>();
        ann.put("description", "订单域");
        NamespaceDTO d = c.revert(live("ns1", labels, ann, "Active"));
        assertThat(d.getName()).isEqualTo("ns1");
        assertThat(d.getPhase()).isEqualTo("Active");
        assertThat(d.getCreationTimestamp()).isEqualTo("2026-09-01T00:00:00Z");
        assertThat(d.getResourceVersion()).isEqualTo("1234");
        assertThat(d.getDescription()).isEqualTo("订单域");
        assertThat(d.getLabels()).containsEntry("env", "prod");
    }

    @Test
    void revert_null_returns_null_and_missing_status_yields_null_phase() {
        assertThat(c.revert(null)).isNull();
        NamespaceDTO d = c.revert(live("ns2", Map.of(), Map.of(), null));
        assertThat(d.getPhase()).isNull();
        assertThat(d.getDescription()).isNull();
    }

    @Test
    void convert_stamps_managed_by_and_maps_description_to_annotation() {
        NamespaceDTO dto = new NamespaceDTO();
        dto.setName("ns3");
        dto.setDescription("支付域");
        dto.setLabels(new LinkedHashMap<>(Map.of("env", "dev")));
        Namespace out = c.convert(dto);
        assertThat(out.getMetadata().getName()).isEqualTo("ns3");
        assertThat(out.getMetadata().getLabels())
                .containsEntry("env", "dev")
                .containsEntry(CoreV1NamespaceConverter.MANAGED_BY_LABEL, CoreV1NamespaceConverter.MANAGED_BY_VALUE);
        assertThat(out.getMetadata().getAnnotations()).containsEntry("description", "支付域");
    }

    @Test
    void convert_with_blank_description_does_not_emit_empty_annotations_block() {
        NamespaceDTO dto = new NamespaceDTO();
        dto.setName("ns4");
        dto.setDescription("   ");
        assertThat(c.convert(dto).getMetadata().getAnnotations()).isNullOrEmpty();
    }

    @Test
    void convert_for_update_preserves_foreign_labels_and_annotations() {
        Map<String, String> liveLabels = new LinkedHashMap<>();
        liveLabels.put("kubernetes.io/metadata.name", "ns5");   // K8s 自动打的保留 label
        liveLabels.put("team", "sre");                            // 外部打的
        liveLabels.put(CoreV1NamespaceConverter.MANAGED_BY_LABEL, CoreV1NamespaceConverter.MANAGED_BY_VALUE);
        Map<String, String> liveAnn = new LinkedHashMap<>();
        liveAnn.put("field.cattle.io/description", "external-tool");  // 外部 annotation 必须存活
        liveAnn.put("description", "旧描述");
        Namespace live = live("ns5", liveLabels, liveAnn, "Active");

        NamespaceDTO dto = new NamespaceDTO();
        dto.setName("ns5");
        dto.setDescription("新描述");
        dto.setLabels(new LinkedHashMap<>(Map.of("env", "prod")));

        Namespace out = c.convertForUpdate(dto, live);
        assertThat(out.getMetadata().getAnnotations())
                .containsEntry("field.cattle.io/description", "external-tool")   // 外来存活
                .containsEntry("description", "新描述");                          // 覆盖
        assertThat(out.getMetadata().getLabels())
                .containsEntry("kubernetes.io/metadata.name", "ns5")              // 系统保留存活
                .containsEntry("team", "sre")                                     // 外部存活
                .containsEntry("env", "prod")                                     // DTO 新增
                .containsEntry(CoreV1NamespaceConverter.MANAGED_BY_LABEL, CoreV1NamespaceConverter.MANAGED_BY_VALUE);
    }

    @Test
    void convert_for_update_removes_description_when_cleared_but_keeps_others() {
        Map<String, String> liveAnn = new LinkedHashMap<>();
        liveAnn.put("description", "将被清空");
        liveAnn.put("keep", "me");
        Namespace live = live("ns6", new LinkedHashMap<>(), liveAnn, "Active");
        NamespaceDTO dto = new NamespaceDTO();
        dto.setName("ns6");
        Namespace out = c.convertForUpdate(dto, live);
        assertThat(out.getMetadata().getAnnotations())
                .doesNotContainKey("description")
                .containsEntry("keep", "me");
    }
}
```

- [ ] **步骤 2:跑测试确认失败**
`mvn -q -pl k8s-core -am test -Dtest=CoreV1NamespaceConverterTest -Dsurefire.failIfNoSpecifiedTests=false` → 编译错误(类不存在)

- [ ] **步骤 3:`CoreV1NamespaceConverter`**

```java
package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.NamespaceDTO;
import com.coding.k8score.converter.CommonConverter;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * core/v1 Namespace ⇄ NamespaceDTO。
 * <p>
 * 两个不变量：
 * <ul>
 *   <li>managed-by 标签只在此盖章（D3）；provisioning 侧 ensureNamespace 自成一路，不经本类。</li>
 *   <li>description ⇄ annotations["description"]，且 <b>update 走 merge 而非替换</b> ——
 *       WorkloadConverter 的 effectiveAnnotations 是替换式（只写 description 一个键），
 *       对命名空间不安全：集群里常有第三方工具打的 annotation（field.cattle.io/* 等），
 *       替换会静默清掉它们。故 convertForUpdate 以线上 metadata 为底、只增删本类建模的键。</li>
 * </ul>
 */
public class CoreV1NamespaceConverter implements CommonConverter<Namespace, NamespaceDTO> {

    /** 与 k8s-core KubernetesClientFactory.MANAGED_BY_LABEL/_VALUE 同值（此处内联避免反向依赖工厂） */
    public static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
    public static final String MANAGED_BY_VALUE = "k8s-cloud-platform";
    public static final String DESCRIPTION_ANNOTATION = "description";

    @Override
    public Namespace convert(NamespaceDTO dto) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (dto.getLabels() != null) {
            labels.putAll(dto.getLabels());
        }
        labels.put(MANAGED_BY_LABEL, MANAGED_BY_VALUE);   // 平台拥有标记

        NamespaceBuilder b = new NamespaceBuilder()
                .withNewMetadata()
                    .withName(dto.getName())
                    .withLabels(labels)
                .endMetadata();
        if (StringUtils.hasText(dto.getDescription())) {
            b.editMetadata().addToAnnotations(DESCRIPTION_ANNOTATION, dto.getDescription()).endMetadata();
        }
        return b.build();
    }

    @Override
    public NamespaceDTO revert(Namespace ns) {
        if (ns == null) {
            return null;
        }
        NamespaceDTO dto = new NamespaceDTO();
        if (ns.getMetadata() != null) {
            dto.setName(ns.getMetadata().getName());
            dto.setLabels(ns.getMetadata().getLabels());
            dto.setCreationTimestamp(ns.getMetadata().getCreationTimestamp());
            dto.setResourceVersion(ns.getMetadata().getResourceVersion());
            Map<String, String> ann = ns.getMetadata().getAnnotations();
            if (ann != null) {
                dto.setDescription(ann.get(DESCRIPTION_ANNOTATION));
            }
        }
        if (ns.getStatus() != null) {
            dto.setPhase(ns.getStatus().getPhase());
        }
        return dto;
    }

    /**
     * update 专用：以线上对象为底合并 metadata（label/annotation 的键级 overlay）。
     * 建模键（description、managed-by）present→覆写、absent→删除；其余键一律存活。
     */
    public Namespace convertForUpdate(NamespaceDTO dto, Namespace live) {
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, String> annotations = new LinkedHashMap<>();
        if (live != null && live.getMetadata() != null) {
            if (live.getMetadata().getLabels() != null) {
                labels.putAll(live.getMetadata().getLabels());
            }
            if (live.getMetadata().getAnnotations() != null) {
                annotations.putAll(live.getMetadata().getAnnotations());
            }
        }
        // labels：DTO 全量覆盖建模域，但 managed-by 由本类独占
        labels.remove(MANAGED_BY_LABEL);
        if (dto.getLabels() != null) {
            dto.getLabels().forEach((k, v) -> {
                if (!MANAGED_BY_LABEL.equals(k)) {
                    labels.put(k, v);
                }
            });
        }
        labels.put(MANAGED_BY_LABEL, MANAGED_BY_VALUE);
        // annotation：description 有值→覆写；无值→删除（清空描述）
        if (StringUtils.hasText(dto.getDescription())) {
            annotations.put(DESCRIPTION_ANNOTATION, dto.getDescription());
        } else {
            annotations.remove(DESCRIPTION_ANNOTATION);
        }
        return new NamespaceBuilder()
                .withNewMetadata()
                    .withName(dto.getName())
                    .withLabels(labels)
                    .withAnnotations(annotations.isEmpty() ? null : annotations)
                .endMetadata()
                .build();
    }
}
```

> **注**:用户自定义 label 的删除语义 —— 本实现是「DTO 覆盖 + 其余存活」,故从 UI 删一个 label 不会真的删掉它。这是刻意的保守选择(避免误删 K8s 系统 label / 第三方 label);若产品要「UI 所见即全部」,需引入显式删除集,留终审裁定。计划按保守实现,javadoc 已写明。

- [ ] **步骤 4:`CoreV1NamespaceOperations`**(骨架照 `RbacV1ClusterRoleOperations`;converter 字段用**具体类型**以便调 `convertForUpdate`,先例 `ServiceMonitorOperations`)

```java
package com.coding.k8score.operations.core;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.NamespaceDTO;
import com.coding.k8score.converter.impl.core.CoreV1NamespaceConverter;
import com.coding.k8score.operations.ClusterOperations;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 命名空间操作（core/v1，集群级资源）。平台级命名空间管理用，一律 admin client（由调用侧工厂决定）。
 */
@Slf4j
@RequiredArgsConstructor
public class CoreV1NamespaceOperations implements ClusterOperations<NamespaceDTO> {

    public final static String apiVersion = "v1";

    private final KubernetesClient client;

    private final CoreV1NamespaceConverter converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    @Override
    public List<NamespaceDTO> list(String labelSelector, String fieldSelector) {
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<Namespace> items = client.namespaces().list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    @Override
    public NamespaceDTO get(String name) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        return converter.revert(client.namespaces().withName(name).get());
    }

    @Override
    public NamespaceDTO create(NamespaceDTO dto) {
        if (checkExist(dto.getName())) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        Namespace created = client.namespaces().resource(converter.convert(dto)).create();
        return converter.revert(created);
    }

    @Override
    public NamespaceDTO update(NamespaceDTO dto) {
        Namespace live = client.namespaces().withName(dto.getName()).get();
        if (live == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        Namespace in = converter.convertForUpdate(dto, live);
        Namespace updated = client.namespaces().resource(in)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return converter.revert(updated);
    }

    @Override
    public void delete(String name) {
        if (!checkExist(name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.namespaces().withName(name).delete();
    }

    @Override
    public String yaml(String name) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        Namespace ns = client.namespaces().withName(name).get();
        if (ns != null && ns.getMetadata() != null) {
            ns.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(ns);
    }

    public boolean checkExist(String name) {
        return client.namespaces().withName(name).get() != null;
    }
}
```

- [ ] **步骤 5:operations 冒烟测试**(镜像 `CoreV1NodeOperationsTest`:mock client,只测不触达 apiserver 的路径)

```java
package com.coding.k8score.operations.core;

import com.coding.k8score.converter.impl.core.CoreV1NamespaceConverter;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CoreV1NamespaceOperationsTest {

    private final KubernetesClient client = mock(KubernetesClient.class);
    private final CoreV1NamespaceOperations ops =
            new CoreV1NamespaceOperations(client, new CoreV1NamespaceConverter());

    @Test
    void api_version_is_core_v1() {
        assertThat(ops.apiVersion()).isEqualTo("v1");
    }
}
```

- [ ] **步骤 6:factory 注册** `KubernetesOperationsFactory.build()` switch 内追加(import 两处):

```java
        case NAMESPACE -> new CoreV1NamespaceOperations(client, new CoreV1NamespaceConverter());
```

- [ ] **步骤 7:门控**
`mvn -q -pl k8s-core -am test -Dtest=CoreV1NamespaceConverterTest -Dsurefire.failIfNoSpecifiedTests=false` → 6/6 PASS
`mvn -q -pl k8s-core -am test -Dtest=CoreV1NamespaceOperationsTest -Dsurefire.failIfNoSpecifiedTests=false` → 1/1
`mvn -q -pl k8s-server -am compile` → SUCCESS

- [ ] **步骤 8:提交** 5 文件 → `feat(namespace): Namespace converter/operations in k8s-core + factory registration`

---

### Task 3 ·  k8s-server:`/admin/namespaces` 边界(只增)

**文件:**
- 创建: `k8s-server/src/main/java/com/coding/k8sserver/controllers/cluster/NamespaceController.java`

- [ ] **步骤 1:四行 controller**(镜像 `ClusterRoleController`)

```java
package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.NamespaceDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractClusterResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 集群域 - 命名空间（core/v1，平台级管理，边界=集群注册表）。
 * 六个标准端点由基类提供；仅 PLATFORM:admin 可访问（SecurityFilterChain 对 /admin/** 统一要求）。
 * 业务规则（managed-by / 分配表守卫 / 视图叠加）全在 platform-api 侧，本层零业务逻辑。
 */
@Tag(name = "资源管理-Namespace", description = "集群级命名空间（平台管理，边界=集群注册表）")
@RestController
@RequestMapping("/admin/namespaces")
public class NamespaceController extends AbstractClusterResourceController<NamespaceDTO> {

    public NamespaceController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.NAMESPACE;
    }
}
```

- [ ] **步骤 2:门控** `mvn -q -pl k8s-server -am compile`
- [ ] **步骤 3:提交** → `feat(namespace): /admin/namespaces boundary controller`

> **刻意只增不删**:旧 `/admin/namespace/list|delete` 与 `NamespaceAdminController` 保留至任务 7,使两服务可独立部署(k8s-server 先上、platform-api 后切),切换窗口内新旧并存无冲突。

---

### Task 4 ·  k8s-server:`AbstractAdminNamespacedResourceController` 基类

**文件:**
- 创建: `k8s-server/src/main/java/com/coding/k8sserver/controllers/base/AbstractAdminNamespacedResourceController.java`

- [ ] **步骤 1:实现**(端点形状克隆 `AbstractNamespacedResourceController`,身份层换集群边界 + 命名空间透传)

```java
package com.coding.k8sserver.controllers.base;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.BaseResources;
import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8sserver.components.ResourceAccessResolver;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 平台级（admin）命名空间域资源 controller 统一基类：6 个标准端点 + 集群边界（平台已注册该集群）校验。
 * <p>
 * 与 {@link AbstractNamespacedResourceController} 的区别只在身份层：本基类**不做分配表校验**，
 * namespace 是透传参数（调用方为平台管理员，非租户），故一律 admin client。
 * 用于命名空间自身的约束资源（ResourceQuota / LimitRange）等平台管理流程 —— 这些对象由平台在
 * 「尚未分配给任何租户」的命名空间上创建，走租户边界会被 ResourceAccessResolver 直接拒。
 * <p>
 * 刻意不与双模基类合并：两者的安全语义不同（一个必须校验分配表、一个必须不校验），
 * 合并会把「跳过校验」变成可配置项 —— 那是更危险的设计。
 * 仅 PLATFORM:admin 可访问（SecurityFilterChain 对 /admin/** 统一要求）。
 */
public abstract class AbstractAdminNamespacedResourceController<T extends BaseResources> {

    protected final KubernetesOperationsFactory operationsFactory;

    protected final ResourceAccessResolver accessResolver;

    protected AbstractAdminNamespacedResourceController(KubernetesOperationsFactory operationsFactory,
                                                        ResourceAccessResolver accessResolver) {
        this.operationsFactory = operationsFactory;
        this.accessResolver = accessResolver;
    }

    /**本 controller 管理的资源类型 */
    protected abstract ResourceType resourceType();

    @PostMapping("/list")
    @Operation(summary = "列出命名空间内资源（body 传 clusterId/namespace/labelSelector）")
    public ResponseData<List<T>> list(@RequestBody T body) {
        String clusterId = body.getClusterId();
        String namespace = requireNamespace(body.getNamespace());
        accessResolver.assertClusterAccess(clusterId);
        List<T> items = ops(clusterId).list(namespace, body.getLabelSelector(), body.getFieldSelector());
        items.forEach(item -> stamp(item, clusterId, namespace));
        return new ResponseData<>(items);
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询单个资源")
    public ResponseData<T> get(@PathVariable String name,
                               @RequestParam String clusterId,
                               @RequestParam String namespace) {
        String ns = requireNamespace(namespace);
        accessResolver.assertClusterAccess(clusterId);
        T item = ops(clusterId).get(ns, name);
        stamp(item, clusterId, ns);
        return new ResponseData<>(item);
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询资源 YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        String ns = requireNamespace(namespace);
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(ops(clusterId).yaml(ns, name));
    }

    @PostMapping
    @Operation(summary = "创建资源（namespace 取 body）")
    public ResponseData<T> create(@RequestParam String clusterId,
                                  @RequestBody T body) {
        String ns = requireNamespace(body.getNamespace());
        accessResolver.assertClusterAccess(clusterId);
        T item = ops(clusterId).create(body);
        stamp(item, clusterId, ns);
        return new ResponseData<>(item);
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新资源（namespace 取 body）")
    public ResponseData<T> update(@PathVariable String name,
                                  @RequestParam String clusterId,
                                  @RequestBody T body) {
        String ns = requireNamespace(body.getNamespace());
        accessResolver.assertClusterAccess(clusterId);
        T item = ops(clusterId).update(body);
        stamp(item, clusterId, ns);
        return new ResponseData<>(item);
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除资源")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        String ns = requireNamespace(namespace);
        accessResolver.assertClusterAccess(clusterId);
        ops(clusterId).delete(ns, name);
        return new ResponseData<>();
    }

    /**平台流程：一律 admin client（无租户身份可用） */
    protected NamespacedOperations<T> ops(String clusterId) {
        return operationsFactory.getAdminNamespacedOperation(resourceType(), clusterId);
    }

    /**命名空间是本基类的必带定位参数（区别于租户基类：那里由分配表隐含约束） */
    private String requireNamespace(String namespace) {
        if (!StringUtils.hasText(namespace)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "缺少集群/命名空间参数");
        }
        return namespace;
    }

    /**回填请求上下文，使列表/查询拿到的对象可原样发回后续 update/delete；tenantId 刻意不回填 */
    private void stamp(T item, String clusterId, String namespace) {
        if (item != null) {
            item.setClusterId(clusterId);
            item.setNamespace(namespace);
        }
    }
}
```

- [ ] **步骤 2:门控** `mvn -q -pl k8s-server -am compile`
- [ ] **步骤 3:提交** → `feat(k8s-server): admin-namespaced boundary base (cluster access, namespace passthrough)`

---

### Task 5 ·  ResourceQuota 建模 [TDD]

**文件:**
- 创建: `k8s-core/src/main/java/com/coding/k8score/converter/impl/core/CoreV1ResourceQuotaConverter.java`(+ Test)
- 创建: `k8s-core/src/main/java/com/coding/k8score/operations/core/CoreV1ResourceQuotaOperations.java`(+ 冒烟 Test)
- 创建: `k8s-server/src/main/java/com/coding/k8sserver/controllers/namespace/ResourceQuotaController.java`
- 修改: `KubernetesOperationsFactory.java`

- [ ] **步骤 1:写失败测试**(overlay 三态是本任务核心资产)

```java
package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.ResourceQuotaDTO;
import com.coding.common.models.k8s.dto.ResourceQuotaUsedDTO;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CoreV1ResourceQuotaConverterTest {

    private final CoreV1ResourceQuotaConverter c = new CoreV1ResourceQuotaConverter();

    private ResourceQuota withHard(Map<String, Quantity> hard) {
        return new ResourceQuotaBuilder()
                .withNewMetadata().withName("default").withNamespace("ns1")
                    .withResourceVersion("9").withCreationTimestamp("2026-09-01T00:00:00Z").endMetadata()
                .withNewSpec().withHard(hard).endSpec()
                .build();
    }

    @Test
    void revert_converts_hard_to_base_units() {
        Map<String, Quantity> hard = new LinkedHashMap<>();
        hard.put("cpu", Quantity.parse("1500m"));
        hard.put("memory", Quantity.parse("2Gi"));
        hard.put("pods", Quantity.parse("10"));
        ResourceQuotaDTO d = c.revert(withHard(hard));
        assertThat(d.getCpu()).isEqualByComparingTo("1.5");           // 1500m → 核
        assertThat(d.getMemory()).isEqualByComparingTo("2147483648"); // 2Gi → 字节
        assertThat(d.getPods()).isEqualTo(10);
        assertThat(d.getName()).isEqualTo("default");
        assertThat(d.getResourceVersion()).isEqualTo("9");
        assertThat(d.getCreationTime()).isEqualTo("2026-09-01T00:00:00Z");
    }

    @Test
    void revert_reads_status_used_and_drops_unmodeled_keys() {
        Map<String, Quantity> hard = new LinkedHashMap<>();
        hard.put("cpu", Quantity.parse("1"));
        hard.put("count/deployments.apps", Quantity.parse("3"));   // 未建模：revert 丢弃
        ResourceQuota rq = withHard(hard);
        rq.setStatus(new io.fabric8.kubernetes.api.model.ResourceQuotaStatusBuilder()
                .addToUsed("cpu", Quantity.parse("700m")).build());
        ResourceQuotaDTO d = c.revert(rq);
        assertThat(d.getUsed()).isNotNull();
        assertThat(d.getUsed().getCpu()).isEqualByComparingTo("0.7");
        assertThat(d.getCpu()).isEqualByComparingTo("1");
    }

    @Test
    void revert_null_returns_null() {
        assertThat(c.revert(null)).isNull();
    }

    @Test
    void convert_omits_null_fields_from_hard() {
        ResourceQuotaDTO d = new ResourceQuotaDTO();
        d.setName("default");
        d.setNamespace("ns1");
        d.setCpu(new BigDecimal("2"));
        ResourceQuota out = c.convert(d);
        assertThat(out.getSpec().getHard()).containsOnlyKeys("cpu");
        assertThat(out.getSpec().getHard().get("cpu")).isEqualTo(Quantity.parse("2"));
        assertThat(out.getStatus()).isNull();
    }

    @Test
    void convert_maps_dotted_and_lowercase_keys_exactly() {
        ResourceQuotaDTO d = new ResourceQuotaDTO();
        d.setName("default");
        d.setPersistentVolumeClaims(5);      // → hard.persistentvolumeclaims（全小写！）
        d.setLimitsCpu(new BigDecimal("4")); // → hard."limits.cpu"
        d.setRequestsMemory(new BigDecimal("1073741824"));
        Map<String, Quantity> hard = c.convert(d).getSpec().getHard();
        assertThat(hard).containsOnlyKeys("persistentvolumeclaims", "limits.cpu", "requests.memory");
        assertThat(hard.get("persistentvolumeclaims")).isEqualTo(Quantity.parse("5"));
    }

    @Test
    void convert_for_update_keeps_unmodeled_removes_cleared_modeled_overwrites_touched() {
        Map<String, Quantity> liveHard = new LinkedHashMap<>();
        liveHard.put("cpu", Quantity.parse("1"));
        liveHard.put("pods", Quantity.parse("5"));                       // 建模键，DTO 未给 → 应删除
        liveHard.put("count/deployments.apps", Quantity.parse("3"));     // 未建模 → 必须存活
        liveHard.put("services.nodeports", Quantity.parse("0"));         // 未建模 → 必须存活
        ResourceQuota live = withHard(liveHard);

        ResourceQuotaDTO d = new ResourceQuotaDTO();
        d.setName("default");
        d.setNamespace("ns1");
        d.setCpu(new BigDecimal("2"));                                   // 覆写
        d.setMemory(new BigDecimal("3221225472"));                       // 新增 3Gi

        Map<String, Quantity> merged = c.convertForUpdate(d, live).getSpec().getHard();
        assertThat(merged)
                .containsEntry("cpu", Quantity.parse("2"))
                .containsEntry("memory", Quantity.parse("3221225472"))
                .containsEntry("count/deployments.apps", Quantity.parse("3"))
                .containsEntry("services.nodeports", Quantity.parse("0"))
                .doesNotContainKey("pods");
    }

    @Test
    void convert_for_update_with_empty_dto_yields_empty_hard_but_keeps_unmodeled() {
        Map<String, Quantity> liveHard = new LinkedHashMap<>();
        liveHard.put("cpu", Quantity.parse("1"));
        liveHard.put("count/pods", Quantity.parse("9"));
        ResourceQuotaDTO d = new ResourceQuotaDTO();
        d.setName("default");
        Map<String, Quantity> merged = c.convertForUpdate(d, withHard(liveHard)).getSpec().getHard();
        assertThat(merged).doesNotContainKey("cpu").containsEntry("count/pods", Quantity.parse("9"));
    }

    @Test
    void convert_for_update_never_emits_status() {
        ResourceQuota live = withHard(Map.of("cpu", Quantity.parse("1")));
        live.setStatus(new io.fabric8.kubernetes.api.model.ResourceQuotaStatusBuilder()
                .addToUsed("cpu", Quantity.parse("1")).build());
        ResourceQuotaDTO d = new ResourceQuotaDTO();
        d.setName("default");
        assertThat(c.convertForUpdate(d, live).getStatus()).isNull();
    }
}
```

- [ ] **步骤 2:跑测试确认失败**
`mvn -q -pl k8s-core -am test -Dtest=CoreV1ResourceQuotaConverterTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **步骤 3:`CoreV1ResourceQuotaConverter`**

```java
package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.ResourceQuotaDTO;
import com.coding.common.models.k8s.dto.ResourceQuotaUsedDTO;
import com.coding.k8score.converter.CommonConverter;
import com.coding.k8score.util.QuantityUtil;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * core/v1 ResourceQuota ⇄ ResourceQuotaDTO。
 * <p>
 * hard 是普通 Map&lt;String,Quantity&gt;，故 overlay 比 ServiceMonitor 的 atomic list 简单（无需索引对齐）：
 * 以线上 hard 为底 → 9 个建模键 present→覆写 / absent→删除 → 未建模键（count/*、limits.ephemeral-storage 等）存活。
 * 这一步是<b>必需</b>的：SSA 下 platform-system 拥有整个 hard map，省略一个已拥有的键 = 删除该键。
 * <p>
 * K8s 资源名一律全小写（persistentvolumeclaims 不是 persistentVolumeClaims）；带点号的是合法 key，无需转义。
 */
public class CoreV1ResourceQuotaConverter implements CommonConverter<ResourceQuota, ResourceQuotaDTO> {

    /** 建模的 hard 键（权威清单；测试逐条断言其语义）。值 = DTO 字段名映射目标 */
    public static final List<String> MODELED_HARD_KEYS = List.of(
            "cpu", "memory", "pods", "services",
            "limits.cpu", "limits.memory", "requests.cpu", "requests.memory",
            "persistentvolumeclaims");

    @Override
    public ResourceQuota convert(ResourceQuotaDTO dto) {
        ResourceQuotaBuilder b = new ResourceQuotaBuilder()
                .withNewMetadata().withName(dto.getName()).withNamespace(dto.getNamespace()).endMetadata()
                .withNewSpec().endSpec();
        Map<String, Quantity> hard = toHard(dto);
        if (!hard.isEmpty()) {
            b.editSpec().withHard(hard).endSpec();
        }
        return b.build();
    }

    @Override
    public ResourceQuotaDTO revert(ResourceQuota rq) {
        if (rq == null) {
            return null;
        }
        ResourceQuotaDTO dto = new ResourceQuotaDTO();
        if (rq.getMetadata() != null) {
            dto.setName(rq.getMetadata().getName());
            dto.setNamespace(rq.getMetadata().getNamespace());
            dto.setResourceVersion(rq.getMetadata().getResourceVersion());
            dto.setCreationTime(rq.getMetadata().getCreationTimestamp());
            dto.setLabels(rq.getMetadata().getLabels());
        }
        if (rq.getSpec() != null) {
            fromHard(rq.getSpec().getHard(), dto);
        }
        if (rq.getStatus() != null && rq.getStatus().getUsed() != null) {
            dto.setUsed(usedFrom(rq.getStatus().getUsed()));
        }
        return dto;
    }

    /**
     * update 专用 fetch-overlay：以线上 hard 为底做键级增删改，绝不产出 status（那是 controller 的地盘）。
     */
    public ResourceQuota convertForUpdate(ResourceQuotaDTO dto, ResourceQuota live) {
        Map<String, Quantity> merged = new LinkedHashMap<>();
        if (live != null && live.getSpec() != null && live.getSpec().getHard() != null) {
            merged.putAll(live.getSpec().getHard());
        }
        MODELED_HARD_KEYS.forEach(merged::remove);   // 先清掉全部建模键，再按 DTO 回填 = present 覆写 / absent 删除
        merged.putAll(toHard(dto));
        ResourceQuotaBuilder b = new ResourceQuotaBuilder()
                .withNewMetadata().withName(dto.getName()).withNamespace(dto.getNamespace()).endMetadata()
                .withNewSpec().endSpec();
        if (!merged.isEmpty()) {
            b.editSpec().withHard(merged).endSpec();
        }
        return b.build();
    }

    /** DTO → hard（只写非 null 项；顺序与 MODELED_HARD_KEYS 一致便于比对） */
    private Map<String, Quantity> toHard(ResourceQuotaDTO dto) {
        Map<String, Quantity> hard = new LinkedHashMap<>();
        putDecimal(hard, "cpu", dto.getCpu());
        putDecimal(hard, "memory", dto.getMemory());
        putCount(hard, "pods", dto.getPods());
        putCount(hard, "services", dto.getServices());
        putDecimal(hard, "limits.cpu", dto.getLimitsCpu());
        putDecimal(hard, "limits.memory", dto.getLimitsMemory());
        putDecimal(hard, "requests.cpu", dto.getRequestsCpu());
        putDecimal(hard, "requests.memory", dto.getRequestsMemory());
        putCount(hard, "persistentvolumeclaims", dto.getPersistentVolumeClaims());
        return hard;
    }

    /** hard → DTO（未建模键忽略） */
    private void fromHard(Map<String, Quantity> hard, ResourceQuotaDTO dto) {
        Map<String, BigDecimal> base = QuantityUtil.toBaseMap(hard);
        if (base == null) {
            return;
        }
        dto.setCpu(base.get("cpu"));
        dto.setMemory(base.get("memory"));
        dto.setLimitsCpu(base.get("limits.cpu"));
        dto.setLimitsMemory(base.get("limits.memory"));
        dto.setRequestsCpu(base.get("requests.cpu"));
        dto.setRequestsMemory(base.get("requests.memory"));
        dto.setPods(count(base, "pods"));
        dto.setServices(count(base, "services"));
        dto.setPersistentVolumeClaims(count(base, "persistentvolumeclaims"));
    }

    private ResourceQuotaUsedDTO usedFrom(Map<String, Quantity> used) {
        Map<String, BigDecimal> base = QuantityUtil.toBaseMap(used);
        if (base == null) {
            return null;
        }
        ResourceQuotaUsedDTO u = new ResourceQuotaUsedDTO();
        u.setCpu(base.get("cpu"));
        u.setMemory(base.get("memory"));
        u.setLimitsCpu(base.get("limits.cpu"));
        u.setLimitsMemory(base.get("limits.memory"));
        u.setRequestsCpu(base.get("requests.cpu"));
        u.setRequestsMemory(base.get("requests.memory"));
        u.setPods(count(base, "pods"));
        u.setServices(count(base, "services"));
        u.setPersistentVolumeClaims(count(base, "persistentvolumeclaims"));
        return u;
    }

    /** 计数项：基础单位 BigDecimal → Integer（null 安全） */
    private Integer count(Map<String, BigDecimal> base, String key) {
        BigDecimal v = base.get(key);
        return v == null ? null : v.intValue();
    }

    private void putDecimal(Map<String, Quantity> hard, String key, BigDecimal v) {
        if (v != null) {
            hard.put(key, QuantityUtil.fromBase(v));
        }
    }

    private void putCount(Map<String, Quantity> hard, String key, Integer v) {
        if (v != null) {
            hard.put(key, new Quantity(String.valueOf(v)));
        }
    }
}
```

> `QuantityUtil.toBaseMap/fromBaseMap` 已 null-safe 且保序(LinkedHashMap),此前全库无消费者 —— 本任务正是其设计用途。

- [ ] **步骤 4:`CoreV1ResourceQuotaOperations implements NamespacedOperations<ResourceQuotaDTO>`**
骨架照 `CoreV1PvcOperations`(`client.resourceQuotas()` 类型化访问器,已 javap 确认存在),但 update 走 **fetch-overlay**(照 `ServiceMonitorOperations:90-110`:get live → null 抛 RESOURCE_NOT_EXIST → `converter.convertForUpdate(dto, live)` 于**具体 converter 类型** → `.fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply()` → revert)。
**`revert` 需回填 `multiple`?** 不在 operations 层做 —— 交给 platform-api T8(那里能拿到 list 结果),保持 operations 纯粹。
list/get/delete/yaml/checkExist/apiVersion(`"v1"`)全部照 PVC 版逐条搬。
冒烟测试:mock client + `apiVersion()` 断言(照任务 2 步骤 5)。

- [ ] **步骤 5:factory 注册** `case RESOURCE_QUOTA -> new CoreV1ResourceQuotaOperations(client, new CoreV1ResourceQuotaConverter());`

- [ ] **步骤 6:边界 controller** `k8s-server/.../controllers/namespace/ResourceQuotaController.java`
`@RequestMapping("/admin/resourcequotas")` extends `AbstractAdminNamespacedResourceController<ResourceQuotaDTO>`,`resourceType()` → `RESOURCE_QUOTA`;`@Tag(name = "资源管理-ResourceQuota", description = "命名空间内资源配额（平台管理，边界=集群注册表）")`;javadoc 说明「平台级流程,namespace 透传不做分配校验」。

- [ ] **步骤 7:门控**
`mvn -q -pl k8s-core -am test -Dtest=CoreV1ResourceQuotaConverterTest -Dsurefire.failIfNoSpecifiedTests=false` → 8/8
`mvn -q -pl k8s-server -am compile`

- [ ] **步骤 8:提交** 5 文件 → `feat(quota): ResourceQuota modeling — hard-map overlay, operations, factory, admin boundary`

---

### Task 6 ·  LimitRange 建模 [TDD] —— 按 type 对齐的 overlay

**文件:** 同任务 5 形状,换 `CoreV1LimitRangeConverter` / `CoreV1LimitRangeOperations` / `LimitRangeController`(`/admin/limitranges`)+ factory `case LIMIT_RANGE`。

- [ ] **步骤 1:写失败测试**(重点:按 type 对齐、未建模类型存活、`default` 空 map 陷阱)

```java
package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.LimitRangeDTO;
import com.coding.common.models.k8s.dto.LimitRangeItemDTO;
import com.coding.common.models.k8s.dto.ResourcePairDTO;
import io.fabric8.kubernetes.api.model.LimitRange;
import io.fabric8.kubernetes.api.model.LimitRangeBuilder;
import io.fabric8.kubernetes.api.model.LimitRangeItem;
import io.fabric8.kubernetes.api.model.Quantity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CoreV1LimitRangeConverterTest {

    private final CoreV1LimitRangeConverter c = new CoreV1LimitRangeConverter();

    private LimitRange live(LimitRangeItem... items) {
        return new LimitRangeBuilder()
                .withNewMetadata().withName("default").withNamespace("ns1").endMetadata()
                .withNewSpec().withLimits(List.of(items)).endSpec()
                .build();
    }

    private LimitRangeItem containerItem(String maxCpu, String minCpu) {
        return new io.fabric8.kubernetes.api.model.LimitRangeItemBuilder()
                .withType("Container")
                .withMax(Map.of("cpu", Quantity.parse(maxCpu)))
                .withMin(Map.of("cpu", Quantity.parse(minCpu)))
                .build();
    }

    @Test
    void revert_maps_items_and_base_units() {
        LimitRangeItem item = new io.fabric8.kubernetes.api.model.LimitRangeItemBuilder()
                .withType("Container")
                .withMax(Map.of("cpu", Quantity.parse("2"), "memory", Quantity.parse("1Gi")))
                .build();
        LimitRangeDTO d = c.revert(live(item));
        assertThat(d.getLimits()).hasSize(1);
        LimitRangeItemDTO it = d.getLimits().get(0);
        assertThat(it.getType()).isEqualTo("Container");
        assertThat(it.getMax().getCpu()).isEqualByComparingTo("2");
        assertThat(it.getMax().getMemory()).isEqualByComparingTo("1073741824");
    }

    @Test
    void revert_treats_empty_maps_as_null_not_empty_pairs() {
        // fabric8 坑：LimitRangeItem.getMax()/getDefault() 返回空可变 map 而非 null
        LimitRangeItem item = new io.fabric8.kubernetes.api.model.LimitRangeItemBuilder()
                .withType("Pod").withMax(Map.of()).build();
        LimitRangeItemDTO it = c.revert(live(item)).getLimits().get(0);
        assertThat(it.getMax()).isNull();
        assertThat(it.getDefaultValue()).isNull();
    }

    @Test
    void revert_null_returns_null() {
        assertThat(c.revert(null)).isNull();
    }

    @Test
    void convert_skips_blank_types_and_writes_default_key() {
        LimitRangeDTO d = new LimitRangeDTO();
        d.setName("default");
        d.setNamespace("ns1");
        LimitRangeItemDTO blank = new LimitRangeItemDTO();
        LimitRangeItemDTO ok = new LimitRangeItemDTO();
        ok.setType("Container");
        ResourcePairDTO def = new ResourcePairDTO();
        def.setCpu(new BigDecimal("0.5"));
        ok.setDefaultValue(def);
        d.setLimits(List.of(blank, ok));
        List<LimitRangeItem> out = c.convert(d).getSpec().getLimits();
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getType()).isEqualTo("Container");
        assertThat(out.get(0).getDefault()).containsEntry("cpu", Quantity.parse("0.5"));
    }

    @Test
    void convert_for_update_aligns_by_type_not_index() {
        LimitRange live = live(
                containerItem("4", "1"),                                                   // Container（旧值；DTO 也给 → 按 type 匹配覆写）
                new io.fabric8.kubernetes.api.model.LimitRangeItemBuilder()
                        .withType("Pod").withMax(Map.of("cpu", Quantity.parse("8"))).build(),
                new io.fabric8.kubernetes.api.model.LimitRangeItemBuilder()
                        .withType("ContainerFixed").withMin(Map.of("cpu", Quantity.parse("100m"))).build()); // 未建模

        LimitRangeDTO d = new LimitRangeDTO();
        d.setName("default");
        d.setNamespace("ns1");
        // 两个建模类型都给，且 DTO 顺序 Pod 在前（≠ live 的 Container 在前）→ 证明按 type 对齐、非按 index
        LimitRangeItemDTO pod = new LimitRangeItemDTO();
        pod.setType("Pod");
        ResourcePairDTO podMax = new ResourcePairDTO();
        podMax.setCpu(new BigDecimal("16"));
        pod.setMax(podMax);
        LimitRangeItemDTO container = new LimitRangeItemDTO();
        container.setType("Container");
        ResourcePairDTO cMax = new ResourcePairDTO();
        cMax.setCpu(new BigDecimal("2"));
        container.setMax(cMax);            // 只给 max → live 的 min 应被字段级删除
        d.setLimits(List.of(pod, container));

        List<LimitRangeItem> out = c.convertForUpdate(d, live).getSpec().getLimits();
        // 规则：建模类型按 DTO 顺序（Pod, Container）→ 未建模类型按 live 顺序追加（ContainerFixed）
        assertThat(out).extracting(LimitRangeItem::getType).containsExactly("Pod", "Container", "ContainerFixed");
        assertThat(out.get(0).getMax()).containsEntry("cpu", Quantity.parse("16"));           // Pod 按 type 匹配覆写（live[0] 本是 Container，index 对齐会错拿它）
        assertThat(out.get(1).getMax()).containsEntry("cpu", Quantity.parse("2"));            // Container 覆写
        assertThat(out.get(1).getMin()).isNullOrEmpty();                                      // Container.min DTO 未给 → 字段级删除
        assertThat(out.get(2).getMin()).containsEntry("cpu", Quantity.parse("100m"));         // ContainerFixed 整体存活
    }

    @Test
    void convert_for_update_drops_modeled_type_not_mentioned_in_dto() {
        // T11 场景：用户关掉 Container、保留 Pod → DTO 只含 Pod → Container 必须删（统一删除语义，非「保留未提及」）
        LimitRange live = live(containerItem("4", "1"),
                new io.fabric8.kubernetes.api.model.LimitRangeItemBuilder().withType("Pod")
                        .withMax(Map.of("cpu", Quantity.parse("8"))).build());
        LimitRangeDTO d = new LimitRangeDTO();
        d.setName("default");
        d.setNamespace("ns1");
        LimitRangeItemDTO pod = new LimitRangeItemDTO();
        pod.setType("Pod");
        ResourcePairDTO m = new ResourcePairDTO();
        m.setCpu(new BigDecimal("8"));
        pod.setMax(m);
        d.setLimits(List.of(pod));   // Container 未提及
        List<LimitRangeItem> out = c.convertForUpdate(d, live).getSpec().getLimits();
        assertThat(out).extracting(LimitRangeItem::getType).containsExactly("Pod");   // Container 删除
    }

    @Test
    void convert_for_update_null_field_removes_key_within_same_type() {
        LimitRange live = live(containerItem("4", "1"));   // Container 有 max.cpu + min.cpu
        LimitRangeDTO d = new LimitRangeDTO();
        d.setName("default");
        LimitRangeItemDTO it = new LimitRangeItemDTO();
        it.setType("Container");
        ResourcePairDTO max = new ResourcePairDTO();
        max.setCpu(new BigDecimal("2"));   // 只给 cpu
        it.setMax(max);                    // min 不给 → 删除
        d.setLimits(List.of(it));

        LimitRangeItem out = c.convertForUpdate(d, live).getSpec().getLimits().get(0);
        assertThat(out.getMax()).containsEntry("cpu", Quantity.parse("2"));
        assertThat(out.getMin()).isNullOrEmpty();   // 与 quota 对称：null = 删除
    }

    @Test
    void convert_for_update_drops_modeled_type_absent_from_dto() {
        LimitRange live = live(containerItem("4", "1"),
                new io.fabric8.kubernetes.api.model.LimitRangeItemBuilder().withType("Pod")
                        .withMax(Map.of("cpu", Quantity.parse("8"))).build());
        LimitRangeDTO d = new LimitRangeDTO();
        d.setName("default");
        d.setLimits(List.of());   // 用户清空全部建模类型
        List<LimitRangeItem> out = c.convertForUpdate(d, live).getSpec().getLimits();
        assertThat(out).extracting(LimitRangeItem::getType).doesNotContain("Container", "Pod");
    }
}
```

- [ ] **步骤 2:确认失败** `-Dtest=CoreV1LimitRangeConverterTest`

- [ ] **步骤 3:实现 `CoreV1LimitRangeConverter`**

关键结构(完整实现由实现者按测试写,以下给出必须遵守的骨架与常量):

```java
public class CoreV1LimitRangeConverter implements CommonConverter<LimitRange, LimitRangeDTO> {

    /** 建模类型；其余（ContainerFixed）由 overlay 原样保留 */
    public static final List<String> MODELED_TYPES = List.of("Container", "Pod", "PersistentVolumeClaim");
    /** 每个 item 内建模的字段（present→覆写、absent→删除） */
    public static final List<String> MODELED_ITEM_FIELDS =
            List.of("max", "min", "default", "defaultRequest", "maxLimitRequestRatio");

    // convert: 跳过 type 空白的项；LimitRangeItemBuilder 逐字段 addToXxx(仅非 null 的 cpu/memory)
    // revert: null→null；metadata 回填 name/namespace/resourceVersion/creationTime/labels；
    //         每个 item → LimitRangeItemDTO，**空 map 视为 null**（helper pairFrom(Map) 内判 MapUtils.isEmpty）
    // convertForUpdate: 见下方算法
}
```

`convertForUpdate` 算法(顺序规则是契约,测试已钉死):
1. 从 live 取 `List<LimitRangeItem>`,建 `Map<type, item>`(仅未建模类型进 `unmodeled` 有序表,保 live 顺序)。
2. 结果表先按 **DTO 顺序**放建模类型:每个 dto item → 若 live 有同 type 则「以 live item 为底、按 `MODELED_ITEM_FIELDS` 做字段级覆写/删除」(同 quota.hard 的 present→覆写 / absent→删除),否则全新构建。
3. 追加未建模类型(live 顺序)。
4. DTO 里出现但 live 没有的建模类型 → 新增;live 有而 DTO 没有的建模类型 → 删除(步骤 2 未覆盖即不入结果)。
5. 绝不产出 status(LimitRange 无 status)。

- [ ] **步骤 4:`CoreV1LimitRangeOperations`** —— 同任务 5 步骤 4 形状(`client.limitRanges()`,fetch-overlay update),冒烟测试同上。
- [ ] **步骤 5:factory `case LIMIT_RANGE`** + **边界 `LimitRangeController`**(`@RequestMapping("/admin/limitranges")`,Tag「资源管理-LimitRange」)。
- [ ] **步骤 6:门控** `-Dtest=CoreV1LimitRangeConverterTest` → 7/7;`mvn -q -pl k8s-server -am compile`
- [ ] **步骤 7:提交** 5 文件 → `feat(limitrange): LimitRange modeling — type-aligned overlay, operations, admin boundary`

---

### Task 7 ·  platform-api:Namespace 一等公民切换(跨两模块的迁移对)

**文件:**
- 修改: `platform-api/.../k8s/K8sAdminClient.java`(删 `listNamespaces:76-81`、`deleteNamespace:84-89`;留 `ensureNamespace`)
- 修改: `platform-api/.../services/NamespaceService.java`
- 修改: `platform-api/.../controllers/NamespaceController.java`
- 修改: `platform-api/.../models/NamespaceView.java`(+description +labels)
- 创建: `platform-api/.../models/NamespaceUpsertRequest.java`
- 创建: `platform-api/src/test/java/com/coding/platformapi/services/NamespaceServiceTest.java`
- 修改: `k8s-server/.../controllers/cluster/NamespaceAdminController.java`(退役 `/list` + `/delete`,留 `/create`)
- 修改: `k8s-server/.../services/K8sProvisioningService.java`(删 `listNamespaces:145-151`、`deleteNamespace:156-160`、`toNamespaceDTO:162-169`)

- [ ] **步骤 1:写失败测试**(mock `K8sResourceClient` + 3 mapper,镜像 `PodMonitorServiceTest`/`HpaServiceTest`)

```java
package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.NamespaceDTO;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.k8s.K8sAdminClient;
import com.coding.platformapi.k8s.K8sResourceClient;
import com.coding.platformapi.models.NamespaceUpsertRequest;
import com.coding.platformapi.models.NamespaceView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NamespaceServiceTest {

    private final K8sResourceClient k8s = mock(K8sResourceClient.class);
    private final K8sAdminClient adminClient = mock(K8sAdminClient.class);
    private final K8sClusterMapper clusterMapper = mock(K8sClusterMapper.class);
    private final PlatformTenantNamespaceMapper allocationMapper = mock(PlatformTenantNamespaceMapper.class);
    private final PlatformTenantMapper tenantMapper = mock(PlatformTenantMapper.class);
    private final NamespaceService svc = new NamespaceService(
            k8s, adminClient, clusterMapper, allocationMapper, tenantMapper);

    private void clusterExists() {
        K8sCluster c = new K8sCluster();
        c.setClusterId("c1");
        when(clusterMapper.selectByPrimaryKey("c1")).thenReturn(c);
    }

    private NamespaceDTO ns(String name, boolean managed) {
        NamespaceDTO d = new NamespaceDTO();
        d.setName(name);
        d.setPhase("Active");
        d.setLabels(managed ? Map.of("app.kubernetes.io/managed-by", "k8s-cloud-platform") : Map.of("team", "sre"));
        return d;
    }

    @Test
    void create_validates_name_and_rejects_existing() {
        clusterExists();
        when(k8s.get(any(NamespaceDTO.class))).thenReturn(ns("app", true));
        NamespaceUpsertRequest req = new NamespaceUpsertRequest();
        req.setClusterId("c1");
        req.setName("app");
        assertThatThrownBy(() -> svc.create(req)).isInstanceOf(CloudPlatformException.class);
        verify(k8s, never()).create(any(NamespaceDTO.class));
    }

    @Test
    void create_rejects_illegal_name_before_touching_k8s() {
        clusterExists();
        NamespaceUpsertRequest req = new NamespaceUpsertRequest();
        req.setClusterId("c1");
        req.setName("Bad_Name");
        assertThatThrownBy(() -> svc.create(req)).isInstanceOf(CloudPlatformException.class);
        verify(k8s, never()).get(any(NamespaceDTO.class));
    }

    @Test
    void update_rejects_foreign_namespace_not_managed_by_platform() {
        clusterExists();
        when(k8s.get(any(NamespaceDTO.class))).thenReturn(ns("foreign", false));
        NamespaceUpsertRequest req = new NamespaceUpsertRequest();
        req.setClusterId("c1");
        req.setName("foreign");
        req.setDescription("想改别人的");
        assertThatThrownBy(() -> svc.update(req))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("平台");
        verify(k8s, never()).update(any(NamespaceDTO.class));
    }

    @Test
    void delete_rejects_allocated_namespace_existing_rule_regression_lock() {
        clusterExists();
        when(k8s.get(any(NamespaceDTO.class))).thenReturn(ns("app", true));
        com.coding.data.models.auth.PlatformTenantNamespace a = new com.coding.data.models.auth.PlatformTenantNamespace();
        a.setNamespace("app");
        when(allocationMapper.listByCluster("c1")).thenReturn(List.of(a));
        assertThatThrownBy(() -> svc.delete("c1", "app"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("取消分配");
        verify(k8s, never()).delete(any(NamespaceDTO.class));
    }

    @Test
    void list_merges_allocation_and_maps_description_labels_managed() {
        clusterExists();
        NamespaceDTO a = ns("a", true);
        a.setDescription("订单域");
        NamespaceDTO b = ns("b", false);
        // 乱序返回，验证 service 层排序（旧端点返回已排序，迁移须保平价）
        when(k8s.list(any(NamespaceDTO.class))).thenReturn(List.of(b, a));
        PlatformTenantNamespace alloc = new PlatformTenantNamespace();
        alloc.setNamespace("a");
        alloc.setTenantId("t1");
        when(allocationMapper.listByCluster("c1")).thenReturn(List.of(alloc));
        PlatformTenant t = new PlatformTenant();
        t.setId("t1");
        t.setName("租户一");
        when(tenantMapper.selectByPrimaryKey("t1")).thenReturn(t);

        List<NamespaceView> views = svc.list("c1");
        assertThat(views).extracting(NamespaceView::getName).containsExactly("a", "b");
        NamespaceView va = views.get(0);
        assertThat(va.isManagedBy()).isTrue();
        assertThat(va.getAllocatedTenantName()).isEqualTo("租户一");
        assertThat(va.getDescription()).isEqualTo("订单域");
        assertThat(views.get(1).isManagedBy()).isFalse();
        assertThat(views.get(1).getAllocatedTenantName()).isNull();
    }
}
```

> import 需含 `com.coding.data.models.auth.PlatformTenant` / `PlatformTenantNamespace`、`com.coding.common.models.k8s.dto.ResourceQuotaDTO` / `LimitRangeDTO` / `LimitRangeItemDTO`、`org.mockito.ArgumentCaptor`、`java.math.BigDecimal`（T8 追加用例时使用）。
> `NamespaceView.managedBy` 是 `boolean` → Lombok 生成 `isManagedBy()`。

- [ ] **步骤 2:确认失败**(编译错误:`NamespaceService` 构造器签名/新方法不存在)
- [ ] **步骤 3:`NamespaceUpsertRequest`**

```java
package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "命名空间创建/更新：集群 + 名称 + 描述 + 标签")
public class NamespaceUpsertRequest {

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "命名空间名（创建必填且不可变；更新定位用）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "描述（存 metadata.annotations[\"description\"]；空=清空）")
    private String description;

    @Schema(description = "标签")
    private Map<String, String> labels;
}
```

- [ ] **步骤 4:`NamespaceView`(platform-api model)加两字段**

```java
    @Schema(description = "描述（metadata.annotations[\"description\"]）")
    private String description;

    @Schema(description = "标签（不含 managed-by 等平台保留键由前端按需展示）")
    private Map<String, String> labels;
```

- [ ] **步骤 5:`NamespaceService` 改造**

- 构造器依赖:加 `private final K8sResourceClient k8s;`(置于首位),保留 `adminClient`(create 不再用它,但 `ensureNamespace` 的调用方是别处;若本类不再用 `ensureNamespace` 则移除该依赖 —— **实现者按最终引用决定,勿留未用依赖**)。
- `list(clusterId)`:改为 `k8s.list(q)`(`q` 只设 clusterId),**在 service 层按 name 排序**(旧端点返回已排序,保平价),再叠加分配信息;视图新增 `description`/`labels` 映射。
- `delete`:用 `k8s.get(nsDto)` 替代全量 list+filter(省一次集群全量拉取),守卫文案与逻辑逐字保留。
- 新增 `create(req)`:`requireCluster` → `K8sNaming.validateRawName(req.getName(), "", "命名空间")` → `k8s.get` 非 null 抛 `RESOURCE_EXIST, "命名空间「x」已存在"` → 组 DTO(name/description/labels,**不打 managed-by**,由 converter 盖章)→ `k8s.create`。
- 新增 `update(req)`:`requireCluster` → `k8s.get` null 抛 `RESOURCE_NOT_EXIST` → `isManaged` 守卫(文案「仅平台创建的命名空间（带 managed-by 标签）可编辑」)→ 组 DTO → `k8s.update`。
- 新增 `get(clusterId, namespace)` → `NamespaceView`(与 list 同叠加逻辑,复用私有 `toView`)。
- 新增 `yaml(clusterId, namespace)` → `k8s.yaml(dto)`。
- 私有 `toView(NamespaceDTO ns, Map<String,String> allocatedTenant)` 抽出共用。

- [ ] **步骤 6:`NamespaceController` 加四个 POST 端点**(`/get`、`/yaml`、`/create`、`/update`,全 body 传参,风格与既有两个一致;`@Operation` 中文 summary)。
- [ ] **步骤 7:退役旧路径** —— `NamespaceAdminController` 删 `/list` `/delete` 两个方法(留 `/create` 供 `ensureNamespace`);`K8sProvisioningService` 删 `listNamespaces`/`deleteNamespace`/`toNamespaceDTO` 三方法;`K8sAdminClient` 删对应两方法。
- [ ] **步骤 8:grep 审计** —— `adminClient.listNamespaces`、`adminClient.deleteNamespace`、`"/admin/namespace/list"`、`"/admin/namespace/delete"`、`toNamespaceDTO` 全部零命中。
- [ ] **步骤 9:门控**
`mvn -q -pl platform-api -am test -Dtest=NamespaceServiceTest -Dsurefire.failIfNoSpecifiedTests=false` → 5/5
`mvn -q -pl k8s-server,platform-api -am compile`
- [ ] **步骤 10:提交**(单提交跨两模块)→ `refactor(namespace): namespace becomes first-class resource; migrate list/delete to /admin/namespaces; add create/update/get/yaml`
commit body 注明:**部署顺序 k8s-server(先,含新边界)→ platform-api(后,切调用)**;两跳之间旧端点仍在,无中断窗口。

---

### Task 8 ·  platform-api:quota/limitrange 端点 [TDD]

**文件:**
- 创建: `platform-api/.../models/NamespaceQuotaUpsertRequest.java`、`NamespaceLimitRangeUpsertRequest.java`
- 修改: `NamespaceService.java`、`NamespaceController.java`
- 修改: `NamespaceServiceTest.java`(追加用例)

- [ ] **步骤 1:追加失败测试**

```java
    @Test
    void quota_upsert_creates_with_forced_default_name_when_absent() {
        clusterExists();
        when(k8s.get(any(ResourceQuotaDTO.class))).thenReturn(null);
        when(k8s.create(any(ResourceQuotaDTO.class))).thenAnswer(i -> i.getArgument(0));
        NamespaceQuotaUpsertRequest req = new NamespaceQuotaUpsertRequest();
        req.setClusterId("c1");
        req.setNamespace("ns1");
        ResourceQuotaDTO q = new ResourceQuotaDTO();
        q.setName("whatever-user-sent");   // 必须被强制改写为 default
        q.setCpu(new BigDecimal("2"));
        req.setQuota(q);
        svc.quotaUpsert(req);
        ArgumentCaptor<ResourceQuotaDTO> cap = ArgumentCaptor.forClass(ResourceQuotaDTO.class);
        verify(k8s).create(cap.capture());
        assertThat(cap.getValue().getName()).isEqualTo("default");
        assertThat(cap.getValue().getNamespace()).isEqualTo("ns1");
    }

    @Test
    void quota_upsert_updates_when_present() {
        clusterExists();
        when(k8s.get(any(ResourceQuotaDTO.class))).thenReturn(new ResourceQuotaDTO());
        when(k8s.update(any(ResourceQuotaDTO.class))).thenAnswer(i -> i.getArgument(0));
        NamespaceQuotaUpsertRequest req = new NamespaceQuotaUpsertRequest();
        req.setClusterId("c1");
        req.setNamespace("ns1");
        req.setQuota(new ResourceQuotaDTO());
        svc.quotaUpsert(req);
        verify(k8s).update(any(ResourceQuotaDTO.class));
        verify(k8s, never()).create(any(ResourceQuotaDTO.class));
    }

    @Test
    void quota_get_returns_null_when_absent_and_multiple_flag_when_extra_objects() {
        clusterExists();
        when(k8s.get(any(ResourceQuotaDTO.class))).thenReturn(null);
        assertThat(svc.quotaGet("c1", "ns1")).isNull();
        ResourceQuotaDTO one = new ResourceQuotaDTO();
        one.setName("default");
        when(k8s.get(any(ResourceQuotaDTO.class))).thenReturn(one);
        when(k8s.list(any(ResourceQuotaDTO.class))).thenReturn(List.of(one, new ResourceQuotaDTO(), new ResourceQuotaDTO()));
        assertThat(svc.quotaGet("c1", "ns1")).isNotNull();
        // multiple 旗标：见实现说明
    }

    @Test
    void limitrange_upsert_rejects_unsupported_type() {
        clusterExists();
        when(k8s.get(any(NamespaceDTO.class))).thenReturn(ns("ns1", true));
        when(k8s.get(any(LimitRangeDTO.class))).thenReturn(null);
        when(k8s.create(any(LimitRangeDTO.class))).thenAnswer(i -> i.getArgument(0));
        NamespaceLimitRangeUpsertRequest req = new NamespaceLimitRangeUpsertRequest();
        req.setClusterId("c1");
        req.setNamespace("ns1");
        LimitRangeDTO lr = new LimitRangeDTO();
        LimitRangeItemDTO it = new LimitRangeItemDTO();
        it.setType("ContainerFixed");     // 未建模类型不得经 API 写入
        lr.setLimits(List.of(it));
        req.setLimitRange(lr);
        assertThatThrownBy(() -> svc.limitRangeUpsert(req))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("Container / Pod / PersistentVolumeClaim");
    }

    @Test
    void limitrange_upsert_rejects_max_less_than_min() {
        clusterExists();
        when(k8s.get(any(NamespaceDTO.class))).thenReturn(ns("ns1", true));
        when(k8s.get(any(LimitRangeDTO.class))).thenReturn(null);
        when(k8s.create(any(LimitRangeDTO.class))).thenAnswer(i -> i.getArgument(0));
        NamespaceLimitRangeUpsertRequest req = new NamespaceLimitRangeUpsertRequest();
        req.setClusterId("c1");
        req.setNamespace("ns1");
        LimitRangeDTO lr = new LimitRangeDTO();
        LimitRangeItemDTO it = new LimitRangeItemDTO();
        it.setType("Container");
        ResourcePairDTO max = new ResourcePairDTO();
        max.setCpu(new BigDecimal("1"));
        ResourcePairDTO min = new ResourcePairDTO();
        min.setCpu(new BigDecimal("2"));      // min > max → 非法
        it.setMax(max);
        it.setMin(min);
        lr.setLimits(List.of(it));
        req.setLimitRange(lr);
        assertThatThrownBy(() -> svc.limitRangeUpsert(req))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("max 不得小于 min");
        verify(k8s, never()).create(any(LimitRangeDTO.class));
    }

    @Test
    void quota_delete_is_noop_when_absent() {
        clusterExists();
        when(k8s.get(any(ResourceQuotaDTO.class))).thenReturn(null);
        svc.quotaDelete("c1", "ns1");
        verify(k8s, never()).delete(any(ResourceQuotaDTO.class));
    }

    @Test
    void constraint_ops_reject_foreign_namespace() {
        clusterExists();
        when(k8s.get(any(NamespaceDTO.class))).thenReturn(ns("foreign", false));
        NamespaceQuotaUpsertRequest req = new NamespaceQuotaUpsertRequest();
        req.setClusterId("c1");
        req.setNamespace("foreign");
        req.setQuota(new ResourceQuotaDTO());
        assertThatThrownBy(() -> svc.quotaUpsert(req)).isInstanceOf(CloudPlatformException.class);
        verify(k8s, never()).create(any(ResourceQuotaDTO.class));
    }
```

> 空 body 用例(`limitrange_upsert_rejects_max_less_than_min`)由实现者补全,断言 `hasMessageContaining` 用错误码文案关键词,**不得放宽为只断异常类型**。

- [ ] **步骤 2:确认失败**
- [ ] **步骤 3:两个 request model**

```java
@Data
@Schema(description = "命名空间配额 upsert：集群 + 命名空间 + 配额内容（对象名固定 default）")
public class NamespaceQuotaUpsertRequest {
    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;
    @Schema(description = "命名空间", requiredMode = Schema.RequiredMode.REQUIRED)
    private String namespace;
    @Schema(description = "配额内容", requiredMode = Schema.RequiredMode.REQUIRED)
    private ResourceQuotaDTO quota;
}
```
(`NamespaceLimitRangeUpsertRequest` 同形,字段名 `limitRange`。)

- [ ] **步骤 4:`NamespaceService` 实现**

私有 helper:
```java
    /** 平台级约束操作前置：集群存在 + 命名空间存在 + 平台拥有（D3 同源策略） */
    private void requireManagedNamespace(String clusterId, String namespace) {
        requireCluster(clusterId);
        NamespaceDTO ns = k8s.get(nsQuery(clusterId, namespace));
        if (ns == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "命名空间不存在: " + namespace);
        }
        if (!isManaged(ns.getLabels())) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "仅平台创建的命名空间可设置配额/限制范围");
        }
    }
```

`quotaGet(clusterId, ns)`:前置校验 → `k8s.get(q)`;null → 返回 null(前端「未配置」);非 null → `q.setName("default")` 后 `k8s.list(q)` 计数,`>1` 则 `setMultiple(true)`。
`quotaUpsert(req)`:前置校验 → 强制 `quota.setName("default")`(入参非 default 且非空 → 抛 10023;空则直接设)→ `quotaGet` 语义的 get → null ? `k8s.create` : `k8s.update`;`setNamespace` 回填。
`quotaDelete(clusterId, ns)`:get null → no-op(幂等);否则 `k8s.delete`。
`limitRangeGet/Upsert/Delete`:同上形状,`limitRangeUpsert` 额外跑 `validateLimitRange(lr)`:
```java
    /** 业务校验在 platform-api（k8s-server 零逻辑）：类型集 + 同类型内数值序 */
    private void validateLimitRange(LimitRangeDTO lr) {
        for (LimitRangeItemDTO it : lr.getLimits() == null ? List.<LimitRangeItemDTO>of() : lr.getLimits()) {
            if (!CoreV1LimitRangeConverter.MODELED_TYPES.contains(it.getType())) {
                throw new CloudPlatformException(EnumResponseType.LIMIT_RANGE_TYPE_UNSUPPORTED,
                        EnumResponseType.LIMIT_RANGE_TYPE_UNSUPPORTED.getMsg() + "，收到: " + it.getType());
            }
            requireOrder(it.getMax(), it.getMin());                 // max ≥ min
            requireOrder(it.getDefaultValue(), it.getDefaultRequest()); // default ≥ defaultRequest
            requireRatioAtLeastOne(it.getMaxLimitRequestRatio());
        }
    }
```
> **注意(已验证)**:`platform-api` **不依赖 k8s-core**(pom 只有 platform-common + platform-data),故不能引用 `CoreV1LimitRangeConverter.MODELED_TYPES`。在本类内定义本地常量:
> ```java
> /** 与 k8s-core CoreV1LimitRangeConverter.MODELED_TYPES 同值；platform-api 不依赖 k8s-core 故重复声明，改一处需同步 */
> private static final List<String> MODELED_LIMIT_TYPES = List.of("Container", "Pod", "PersistentVolumeClaim");
> ```
> 这与既有 `MANAGED_BY_LABEL`(`NamespaceService:32-33` 注释「与 k8s-server 侧建 ns 时打的标签一致」)同属跨模块无共享层的结构性重复,是仓库既有惯例。
> 同理 `EnumResponseType.getMsg()` 拼文案可用;`requireOrder` 用 `BigDecimal.compareTo`,任一为 null 则跳过该项校验(未填=不约束)。

- [ ] **步骤 5:`NamespaceController` 加六个端点** `/quota/get`、`/quota/upsert`、`/quota/delete`、`/limitrange/get`、`/limitrange/upsert`、`/limitrange/delete`(get/delete 复用 `NamespaceKeyRequest{clusterId,namespace}`)。
- [ ] **步骤 6:门控** `mvn -q -pl platform-api -am test -Dtest=NamespaceServiceTest -Dsurefire.failIfNoSpecifiedTests=false` → 全绿(任务 7 的 5 + 本任务新增)
- [ ] **步骤 7:提交** → `feat(namespace): quota/limitrange get/upsert/delete with single-name and range validation`

---

### Task 9 ·  前端基础设施:types + namespaceApi + quantityUnits

**文件:**
- 修改: `platform-web/src/types.ts`
- 修改: `platform-web/src/api/index.ts`
- 创建: `platform-web/src/utils/quantityUnits.ts`

- [ ] **步骤 1:`types.ts` 追加**(放在 `NamespaceView`(:64-71)之后;`NamespaceView` 本体加两字段)

```ts
/** 资源量对（cpu 核 / memory 字节，基础单位）—— 与后端 ResourcePairDTO 对齐（D7 冻结契约） */
export interface ResourcePair {
  cpu?: number | null
  memory?: number | null
}

/** LimitRange 单条限制项；defaultValue ⇄ K8s spec.limits[].default */
export interface K8sLimitRangeItem {
  type: 'Container' | 'Pod' | 'PersistentVolumeClaim'
  max?: ResourcePair | null
  min?: ResourcePair | null
  defaultValue?: ResourcePair | null
  defaultRequest?: ResourcePair | null
  maxLimitRequestRatio?: ResourcePair | null
}

/** LimitRange（平台单份，对象名固定 default） */
export interface K8sLimitRange {
  name?: string | null
  namespace?: string | null
  limits?: K8sLimitRangeItem[] | null
  /** 该 ns 存在多份 LimitRange（只读告警旗标） */
  multiple?: boolean | null
  resourceVersion?: string | null
  creationTime?: string | null
}

/** 配额已用量（只读，来自 status.used），字段与约束项一一对应 */
export interface K8sResourceQuotaUsed {
  cpu?: number | null
  memory?: number | null
  pods?: number | null
  services?: number | null
  limitsCpu?: number | null
  limitsMemory?: number | null
  requestsCpu?: number | null
  requestsMemory?: number | null
  persistentVolumeClaims?: number | null
}

/** ResourceQuota（平台单份，对象名固定 default）。值为基础单位：cpu=核、memory=字节 */
export interface K8sResourceQuota {
  name?: string | null
  namespace?: string | null
  cpu?: number | null
  memory?: number | null
  pods?: number | null
  services?: number | null
  limitsCpu?: number | null
  limitsMemory?: number | null
  requestsCpu?: number | null
  requestsMemory?: number | null
  persistentVolumeClaims?: number | null
  used?: K8sResourceQuotaUsed | null
  multiple?: boolean | null
  resourceVersion?: string | null
  creationTime?: string | null
}
```

`NamespaceView` 内追加:
```ts
  /** 描述（metadata.annotations["description"]） */
  description?: string | null
  labels?: Record<string, string> | null
```

- [ ] **步骤 2:`api/index.ts` 扩 `namespaceApi`**(`list`/`delete` 两行**原样不动** —— `AllocateNamespaceDialog:41` 与 `useResourceOptions:43` 零改动)

```ts
export const namespaceApi = {
  list: (clusterId: string) => http.post<never, NamespaceView[]>('/namespace/list', { clusterId }),
  delete: (payload: { clusterId: string; namespace: string }) =>
    http.post<never, void>('/namespace/delete', payload),
  /** 单个命名空间视图（含 description/labels/分配信息）；不存在返回 null */
  get: (clusterId: string, namespace: string) =>
    http.post<never, NamespaceView | null>('/namespace/get', { clusterId, namespace }),
  /** 命名空间原始 YAML（只读展示） */
  yaml: (clusterId: string, namespace: string) =>
    http.post<never, string>('/namespace/yaml', { clusterId, namespace }),
  create: (payload: { clusterId: string; name: string; description?: string; labels?: Record<string, string> }) =>
    http.post<never, void>('/namespace/create', payload),
  update: (payload: { clusterId: string; name: string; description?: string; labels?: Record<string, string> }) =>
    http.post<never, void>('/namespace/update', payload),
  /** 配额：get 为 null = 未配置；upsert 幂等（对象名固定 default）；delete 缺失时 no-op */
  quotaGet: (clusterId: string, namespace: string) =>
    http.post<never, K8sResourceQuota | null>('/namespace/quota/get', { clusterId, namespace }),
  quotaUpsert: (clusterId: string, namespace: string, quota: K8sResourceQuota) =>
    http.post<never, void>('/namespace/quota/upsert', { clusterId, namespace, quota }),
  quotaDelete: (clusterId: string, namespace: string) =>
    http.post<never, void>('/namespace/quota/delete', { clusterId, namespace }),
  limitrangeGet: (clusterId: string, namespace: string) =>
    http.post<never, K8sLimitRange | null>('/namespace/limitrange/get', { clusterId, namespace }),
  limitrangeUpsert: (clusterId: string, namespace: string, limitRange: K8sLimitRange) =>
    http.post<never, void>('/namespace/limitrange/upsert', { clusterId, namespace, limitRange }),
  limitrangeDelete: (clusterId: string, namespace: string) =>
    http.post<never, void>('/namespace/limitrange/delete', { clusterId, namespace }),
}
```
顶部 type import 加 `K8sResourceQuota`, `K8sLimitRange`。

- [ ] **步骤 3:`utils/quantityUnits.ts`**(纯单位换算;展示格式化继续用既有 `utils/quantity.ts`)

```ts
/**
 * 配额/限制范围表单的单位换算：显示单位 ⇄ 基础单位（cpu=核、memory=字节）。
 * 线路与后端一律基础单位（BigDecimal→JSON number），换算只发生在 UI 边界，与
 * components/workload/ResourcesEditor.vue 的固定单位输入范式同构（cpu→m、memory→Mi、计数→原值）。
 * 展示格式化用 utils/quantity.ts 的 formatQuantity/formatBytes —— 本文件不做格式化、不解析后缀字符串。
 * 所有函数对 null / '' / NaN 一律返回 null（= 该项不约束）。
 */

/** 核 → 毫核（输入框显示值） */
export function coresToMilli(v: number | null | undefined): number | null {
  return v == null || Number.isNaN(v) ? null : Math.round(v * 1000)
}

/** 毫核 → 核（提交值）；先剥非数字字符，与 ResourcesEditor 一致 */
export function milliToCores(v: number | string | null | undefined): number | null {
  const n = Number(String(v ?? '').replace(/\D/g, ''))
  return Number.isFinite(n) && String(v ?? '').trim() !== '' ? n / 1000 : null
}

/** 字节 → MiB（显示值，取整） */
export function bytesToMi(v: number | null | undefined): number | null {
  return v == null || Number.isNaN(v) ? null : Math.round(v / 2 ** 20)
}

/** MiB → 字节（提交值） */
export function miToBytes(v: number | string | null | undefined): number | null {
  const n = Number(String(v ?? '').replace(/\D/g, ''))
  return Number.isFinite(n) && String(v ?? '').trim() !== '' ? n * 2 ** 20 : null
}

/** 计数：整数或 null */
export function intOrNull(v: number | string | null | undefined): number | null {
  const n = Number(v)
  return Number.isFinite(n) ? Math.trunc(n) : null
}
```

- [ ] **步骤 4:门控** `npm --prefix platform-web run type-check`
- [ ] **步骤 5:提交** 3 文件 → `feat(namespace-web): api client, quota/limitrange types, unit conversion helpers`

---

### Task 10 ·  NamespaceEditorView.vue 骨架 + 基础信息区块

**文件:**
- 创建: `platform-web/src/views/NamespaceEditorView.vue`
- 参照(只读): `NodeView.vue`(平台上下文范式)、`HpaEditorView.vue:260-317,412-446`(detailState/formVisible/sec-card/提交)、`ConfigMapEditorView.vue:123,130`(RFC1123)

- [ ] **步骤 1:平台上下文(关键差异,勿误用 useResourceContext)**

```ts
const route = useRoute()
const router = useRouter()
/** ?name= → 编辑回填；无 name → 创建 */
const editing = ref<string | null>((route.query.name as string) || null)
const clusters = ref<K8sCluster[]>([])
const clusterId = ref((route.query.clusterId as string) || '')
/** 平台级页面：ready = 集群已选（无租户/命名空间级联） */
const ready = computed(() => !!clusterId.value)

async function loadClusters(): Promise<void> {
  clusters.value = await clusterApi.list()
  if (!clusterId.value) {
    const first = clusters.value.find((c) => c.enabled === 1) ?? clusters.value[0]
    if (first) clusterId.value = first.clusterId
  }
}
```
PageHeader 内放集群下拉(切集群时:编辑态重新 `loadDetail()`、创建态清空目标),`返回` 按钮 `router.push('/namespaces')`。

- [ ] **步骤 2:表单模型 + 回填**

```ts
const form = reactive({
  name: '',
  description: '',
  labels: {} as Record<string, string>,
})
const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')
const saving = ref(false)
const formVisible = computed(() => !editing.value || detailState.value === 'loaded')

async function loadDetail(): Promise<void> {
  if (!editing.value || !ready.value) return
  detailState.value = 'loading'
  try {
    const ns = await namespaceApi.get(clusterId.value, editing.value)
    if (!ns) { detailState.value = 'error'; return }
    form.name = ns.name
    form.description = ns.description ?? ''
    form.labels = { ...(ns.labels ?? {}) }
    detailState.value = 'loaded'
  } catch {
    detailState.value = 'error'
  }
}
```
`onMounted(() => { void loadClusters().then(() => { if (editing.value) void loadDetail() }) })`;`watch(clusterId, ...)` 编辑态重拉。

- [ ] **步骤 3:区块结构**(本任务只落基础信息;配额/限制范围留 `<!-- T11 -->` 挂载点)

```html
<div class="res-editor">
  <PageHeader :title="pageTitle">
    <el-select v-model="clusterId" placeholder="选择集群" style="width: 220px">…</el-select>
    <el-button @click="goBack">返回</el-button>
  </PageHeader>
  <EmptyState v-if="!ready" title="尚未选择集群" description="请先在上方选择一个已启用的集群，再创建或编辑命名空间。" />
  <EmptyState v-else-if="detailState === 'error'" title="加载失败" description="该命名空间可能已被删除，或不在所选集群下。">
    <el-button type="primary" @click="goBack">返回列表</el-button>
  </EmptyState>
  <template v-else>
    <div v-if="formVisible" class="editor-body">
      <el-card shadow="never" class="sec-card">
        <template #header><span class="sec-title">基础信息</span></template>
        <el-form label-width="200px" label-position="left">
          <el-form-item required>
            <template #label>名称 <FieldHelp tip="K8s 命名空间名，需符合 RFC1123（小写字母/数字/-，字母或数字开头结尾），最长 63 字符；创建后不可修改。" /></template>
            <el-input v-model="form.name" :disabled="!!editing" placeholder="例如 order-prod" style="width: 360px" />
          </el-form-item>
          <el-form-item>
            <template #label>描述 <FieldHelp tip="说明该命名空间的用途。存于 metadata.annotations[\"description\"]（平台约定，同工作负载描述），仅用于展示、不影响 K8s 行为。" /></template>
            <el-input v-model="form.description" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="这个命名空间放什么服务、归哪个团队" style="width: 520px" />
          </el-form-item>
          <el-form-item>
            <template #label>标签 <FieldHelp tip="K8s 标签（metadata.labels），供选择器与第三方工具使用。平台保留标签 app.kubernetes.io/managed-by 由系统维护、不可编辑；编辑时其余既有标签自动保留。" /></template>
            <LabelEditor v-model="form.labels" class="sub-editor" style="max-width: 520px" />
          </el-form-item>
        </el-form>
      </el-card>
      <!-- T11 挂载点：QuotaSection / LimitRangeSection -->
      <div class="form-actions">
        <el-button @click="goBack">{{ editing ? '取消' : '返回' }}</el-button>
        <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
      </div>
    </div>
    <div v-else class="loading-tip">加载中…</div>
  </template>
</div>
```
scoped CSS 从 HpaEditorView 原样复制(`.res-editor/.editor-body/.sec-card/.sec-title/.form-actions/.loading-tip` —— 仓库无共享编辑器样式表,每个编辑器各自复制是既有惯例)。

- [ ] **步骤 4:提交链(含 spec §8 非原子处理)**

```ts
const RFC1123_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/

async function submit(): Promise<void> {
  if (!ready.value || saving.value) return
  const name = form.name.trim()
  if (!name) { ElMessage.warning('请输入命名空间名'); return }
  if (!RFC1123_RE.test(name)) { ElMessage.warning('名称需符合 RFC1123：小写字母/数字/-，且以字母或数字开头结尾'); return }
  if (name.length > 63) { ElMessage.warning('名称长度不得超过 63 字符'); return }
  // T11 在此追加约束校验：quotaSectionRef.value?.isValid() / limitRangeSectionRef…

  saving.value = true
  const payload = { clusterId: clusterId.value, name, description: form.description.trim(), labels: form.labels }
  try {
    if (editing.value) {
      await namespaceApi.update(payload)
    } else {
      await namespaceApi.create(payload)
    }
  } catch {
    saving.value = false
    return   // 命名空间本身失败：拦截器已提示，留在页面
  }
  // 命名空间成功 → 约束链（T11 提供）；部分失败不静默丢弃（spec §8）
  const constraintOk = await saveConstraints(clusterId.value, name)   // T11 实现，无约束区块时返回 true
  saving.value = false
  if (!constraintOk) {
    ElMessage.warning('命名空间已保存，但配额/限制范围设置失败，可重试编辑')
    if (!editing.value) {
      // 关键：切到编辑态（名字锁定），使重试能 upsert 三区块
      editing.value = name
      await router.replace({ name: 'namespace-editor', query: { clusterId: clusterId.value, name } })
    }
    return
  }
  ElMessage.success(editing.value ? '已更新' : '创建成功')
  await router.push({ name: 'namespace-detail', query: { clusterId: clusterId.value, name } })
}
```
> 本任务先实现 `saveConstraints` 为 `async () => true` 的桩(T11 替换为真实实现),并在文件内注释标注 —— 否则 T10 无法独立通过门控。

- [ ] **步骤 5:门控** `npm --prefix platform-web run type-check`
- [ ] **步骤 6:提交** → `feat(namespace-web): namespace editor page skeleton with basic-info section`

---

### Task 11 ·  编辑器:配额区块 + 限制范围区块

**文件:**
- 创建: `platform-web/src/components/namespace/QuotaSection.vue`
- 创建: `platform-web/src/components/namespace/LimitRangeSection.vue`
- 修改: `platform-web/src/views/NamespaceEditorView.vue`(挂载 + 回填 + 替换 `saveConstraints` 桩)

- [ ] **步骤 1:`QuotaSection.vue`**(9 字段,D2 固定单位)

```ts
/**
 * 配额编辑区块。模型值 = 基础单位（cpu 核 / memory 字节 / 计数个），
 * 显示经 quantityUnits.ts 换算为固定单位（cpu→m、memory→Mi），与 ResourcesEditor 同范式。
 * enabled=false → 编辑器提交时调 quotaDelete（区分「未配置」与「主动清空」，D6）。
 */
const props = defineProps<{ clusterId: string; namespace: string }>()
const enabled = ref(false)
const q = reactive({
  cpu: null as number | null, memory: null as number | null,
  pods: null as number | null, services: null as number | null,
  limitsCpu: null as number | null, limitsMemory: null as number | null,
  requestsCpu: null as number | null, requestsMemory: null as number | null,
  persistentVolumeClaims: null as number | null,
})

async function load(): Promise<void> {
  if (!props.namespace) { enabled.value = false; return }
  const cur = await namespaceApi.quotaGet(props.clusterId, props.namespace)
  if (!cur) { enabled.value = false; return }
  enabled.value = true
  q.cpu = cur.cpu ?? null; q.memory = cur.memory ?? null
  /* …逐字段回填… */
}

/** → 提交体（基础单位；null = 不约束 → 后端 overlay 删除该键） */
function toPayload(): K8sResourceQuota {
  return { name: 'default', namespace: props.namespace, cpu: q.cpu, memory: q.memory, /* … */ }
}
function hasAnyValue(): boolean {
  return Object.values(q).some((v) => v != null)
}
defineExpose({ load, toPayload, hasAnyValue, enabled })
```
模板:9 个 `el-form-item`,每个 `#label` 挂 FieldHelp(作用 + 单位换算说明,仿 `ResourcesEditor.vue:101-102` 文案风格),控件 `el-input-number`/`el-input` + `<template #append>m</template>`(cpu 类)/`Mi`(memory 类)/无后缀(计数)。区块 header 带启用开关:
```html
<template #header>
  <div class="sec-head">
    <span class="sec-title">资源配额（ResourceQuota）</span>
    <el-switch v-model="enabled" @change="onToggle" />
    <FieldHelp tip="关闭并保存 = 删除该命名空间的配额（含平台未建模的其它配额项，如 count/deployments）。开启才会创建/更新名为 default 的配额对象。" />
  </div>
</template>
```
`onToggle(false)` → 清空表单值 + 提示。

- [ ] **步骤 2:`LimitRangeSection.vue`**(三类型 × 四组值 × cpu/memory + ratio)

结构:`enabled` 开关 + 三类型子卡片(`Container`/`Pod`/`PersistentVolumeClaim`),每类型 4 行(max/min/default/defaultRequest)× 2 列(cpu `m` / memory `Mi`)+ 1 行 maxLimitRequestRatio(cpu/memory)。类型子卡片各带「启用该类型」小开关(未启用 → 该类型不进 payload → 后端 overlay 删除)。
跨字段校验(仿 `ResourcesEditor.vue:44-56,93`):
```ts
/** 同类型内数值序：max≥min、default≥defaultRequest、ratio≥1；未填项跳过 */
const typeErrors = computed(() => LR_TYPES.map((t) => { /* 返回 null 或错误文案 */ }))
defineExpose({ load, toPayload, hasAnyValue, enabled, isValid: () => typeErrors.value.every((e) => !e) })
```
违规单元格红字提示(`.cell-invalid` 样式照 ResourcesEditor:161-162)。

- [ ] **步骤 3:编辑器挂载**

```ts
const quotaRef = ref<InstanceType<typeof QuotaSection> | null>(null)
const lrRef = ref<InstanceType<typeof LimitRangeSection> | null>(null)
/** 替换 T10 桩：约束链，任一失败返回 false（幂等 upsert 使重试安全） */
async function saveConstraints(cid: string, name: string): Promise<boolean> {
  try {
    if (quotaRef.value) {
      if (quotaRef.value.enabled) await namespaceApi.quotaUpsert(cid, name, quotaRef.value.toPayload())
      else if (quotaRef.value.hasAnyValue() || editing.value) await namespaceApi.quotaDelete(cid, name)
    }
    if (lrRef.value) {
      if (lrRef.value.enabled) await namespaceApi.limitrangeUpsert(cid, name, lrRef.value.toPayload())
      else if (lrRef.value.hasAnyValue() || editing.value) await namespaceApi.limitrangeDelete(cid, name)
    }
    return true
  } catch { return false }   // 拦截器已提示具体错误
}
```
`loadDetail` 成功后追加 `await quotaRef.value?.load(); await lrRef.value?.load()`;submit 校验链加 `if (lrRef.value && !lrRef.value.isValid()) { ElMessage.warning('限制范围数值序有误…'); return }`。
**创建态且两区块都关** → 完全跳过约束调用(spec §6.2「至少一个约束项有值才提交对应对象」)。

- [ ] **步骤 4:门控** `npm --prefix platform-web run type-check`
- [ ] **步骤 5:提交** 3 文件 → `feat(namespace-web): quota + limitrange editor sections (unit-badge inputs, overlay semantics)`

---

### Task 12 ·  NamespaceDetailView.vue 四 tab

**文件:**
- 创建: `platform-web/src/views/NamespaceDetailView.vue`
- 参照: `NodeDetailView.vue`(tab 范式、lazy flags、overview-grid、StatusBadgeTip、EmptyState 错误态)

- [ ] **步骤 1:数据与 tab**

```ts
const clusterId = ref((route.query.clusterId as string) || '')
const name = computed(() => (route.query.name as string) || '')
const ns = ref<NamespaceView | null>(null)
const quota = ref<K8sResourceQuota | null>(null)
const lr = ref<K8sLimitRange | null>(null)
const loadError = ref(false)
const activeTab = ref('overview')
const yamlText = ref(''); const yamlLoaded = ref(false)
const quotaLoaded = ref(false); const lrLoaded = ref(false)

async function refresh(): Promise<void> { /* namespaceApi.get；null → loadError */ }
watch(activeTab, (t) => {
  if (t === 'quota' && !quotaLoaded.value) void loadQuota()
  if (t === 'limitrange' && !lrLoaded.value) void loadLimitRange()
  if (t === 'yaml' && !yamlLoaded.value) void loadYaml()
})
```
lazy-load 与 `watch(clusterId)` 联动:切集群 → 清空三块数据 + `refresh()` + 已加载 tab 重拉。

- [ ] **步骤 2:概览 tab** —— `overview-grid` 两栏:左「基本信息」(名称 / 状态 `StatusBadgeTip`(Active→success、Terminating→warning) / 描述 / 标签 KvTags / 管理方式 / 已分配租户 / 创建时间 / resourceVersion),右「用量摘要」:quota 有值则 cpu/memory/pods 的 `used / hard` 三行,否则 `EmptyState` 风格提示「未设置配额」+ 编辑入口。

- [ ] **步骤 3:资源配额 tab** —— `quotaGet` null → 「未配置」+「设置配额」按钮;有值 → 表格(列:约束项 / 已用 / 上限 / 使用率),9 行按 `MODELED` 顺序,**用量百分比为文本**(`NodeView.vue:64-81` 惯例;`el-progress` 全库零使用 → 不引入),>100% 标红;`multiple=true` → 顶部 `el-alert type="warning"`「该命名空间存在多份 ResourceQuota,平台仅管理名为 default 的这份,建议自行收敛」。展示经 `formatQuantity(v,'cpu'|'memory'|'count')`(既有 `utils/quantity.ts`)。

- [ ] **步骤 4:限制范围 tab** —— 按类型分组只读表(行 max/min/default/defaultRequest,列 cpu/memory,基础单位→展示经 formatQuantity);`multiple=true` 同上告警;null → 「未配置」。
- [ ] **步骤 5:YAML tab** —— `namespaceApi.yaml` + `<pre class="yaml-block">`(样式照 NodeDetailView:429)。
- [ ] **步骤 6:操作** —— PageHeader:`返回`(→ `/namespaces`)+ `刷新` + `编辑`(→ `namespace-editor?clusterId&name`)。
- [ ] **步骤 7:门控** type-check;**步骤 8:提交** → `feat(namespace-web): namespace detail overview page (quota usage / limitrange / yaml tabs)`

---

### Task 13 ·  NamespaceView.vue 列表改造

**文件:** 修改 `platform-web/src/views/NamespaceView.vue`

- [ ] **步骤 1:PageHeader 替换裸 toolbar**

```html
<PageHeader title="命名空间管理" description="集群级命名空间：创建 / 编辑（描述、标签、配额、限制范围）/ 概览">
  <el-select v-model="clusterId" placeholder="选择启用状态的集群" style="width: 240px">…</el-select>
  <el-button :icon="Refresh" circle @click="load" :disabled="!clusterId" />
  <el-button type="primary" :disabled="!clusterId" @click="goCreate">创建命名空间</el-button>
</PageHeader>
```
- [ ] **步骤 2:导航**
```ts
function goCreate(): void { router.push({ name: 'namespace-editor', query: { clusterId: clusterId.value } }) }
function goDetail(row: NamespaceView): void { router.push({ name: 'namespace-detail', query: { clusterId: clusterId.value, name: row.name } }) }
function goEdit(row: NamespaceView): void { router.push({ name: 'namespace-editor', query: { clusterId: clusterId.value, name: row.name } }) }
```
- [ ] **步骤 3:列调整** —— 名称列改可点 `<code class="res-name name-link" @click="goDetail(row)">{{ row.name }}</code>`(仿 `HpaView.vue:177`);新增**描述列**(`min-width="180"`,截断 + `el-tooltip` 全文,空显示 `-`);其余列保留。
- [ ] **步骤 4:行操作下拉** —— 64px `el-dropdown`(照 `HpaView.vue:196-208`):查看 / 编辑 / 删除。
**删除约束从「隐藏」改「禁用 + tooltip 说明原因」**(仓库惯例 `WorkloadView.vue:319`):
```html
<el-tooltip :disabled="canDelete(row)" :content="deleteHint(row)" placement="left">
  <span><el-dropdown-item command="delete" :disabled="!canDelete(row)" divided style="color: var(--el-color-danger)">删除</el-dropdown-item></span>
</el-tooltip>
```
`canDelete(row) = row.managedBy && !row.allocatedTenantName`;`deleteHint` 返回「仅平台创建的命名空间可删除」/「该命名空间已分配给租户「x」,请先取消分配」。编辑同理:`:disabled="!row.managedBy"` + tooltip「仅平台创建的命名空间可编辑」(D3)。
- [ ] **步骤 5:空态** —— 加 `EmptyState`(`v-if="!loading && list.length===0"`,描述含「点击右上「创建命名空间」…」),保留现有 `onDelete` 确认与刷新逻辑。
- [ ] **步骤 6:门控** type-check;**步骤 7:提交** → `feat(namespace-web): list rework — create button, row dropdown, description column`

---

### Task 14 ·  路由 + 前端全量门控

**文件:** 修改 `platform-web/src/router/index.ts`

- [ ] **步骤 1:两条路由**(紧跟 `:22` 的 `namespaces` 行,与 `nodes/detail`(:20)同风格)

```ts
{ path: 'namespaces/editor', name: 'namespace-editor', component: () => import('@/views/NamespaceEditorView.vue'), meta: { title: '命名空间编辑', group: '平台管理' } },
{ path: 'namespaces/detail', name: 'namespace-detail', component: () => import('@/views/NamespaceDetailView.vue'), meta: { title: '命名空间概览', group: '平台管理' } },
```
**刻意不加 `context` 键** —— `MainLayout.vue:73` 依 `context === 'full'` 挂租户级联,平台级页面不能出现它。侧栏高亮在子路由丢失 = 既有 `/nodes/detail` 行为,接受。菜单无需改(平铺分区已含「命名空间管理」)。
- [ ] **步骤 2:全量门控** `npm --prefix platform-web run build`(必须全量,B2 实证增量 type-check 漏检跨文件类型错)→ 成功
- [ ] **步骤 3:提交** → `feat(namespace-web): editor/detail routes + full build green`

---

### Task 15 ·  后端全量回归 + 审计

- [ ] **步骤 1:** `mvn -q -T1C compile` → BUILD SUCCESS
- [ ] **步骤 2:** `mvn -q -pl k8s-core,platform-api -am test -Dsurefire.failIfNoSpecifiedTests=false` → 新测试(CoreV1NamespaceConverterTest 6 / CoreV1ResourceQuotaConverterTest 8 / CoreV1LimitRangeConverterTest 7 / 两个冒烟 / NamespaceServiceTest 全量)+ 既有(QuantityUtilTest 8 / CoreV1NodeConverterTest 2 / PodMonitor* / HpaServiceTest 5)全绿零回归。报告每模块总数。
- [ ] **步骤 3:审计 grep(零命中为通过)**
`adminClient.listNamespaces` · `adminClient.deleteNamespace` · `/admin/namespace/list` · `/admin/namespace/delete` · `toNamespaceDTO`
- [ ] **步骤 4:** 确认 `K8sProvisioningService` 仅剩 `ensureNamespace` 相关命名空间逻辑;`ResourceType` 三项已注册且 factory 三 case 齐。
- 本任务无源码改动则不提交。

---

### Task 16 ·  浏览器预览 + spec 勘误

- [ ] **步骤 1:起对 server(必做,勿跳)**
用 Bash 在 **worktree 的 platform-web** 起 vite 到专属端口(`--strictPort`),然后**先探测 serve 的是哪棵树**再截图 —— B2 曾因连到主检出旧代码误判回归:
```bash
cd .claude/worktrees/namespace-b3/platform-web && nohup npx vite --port 5198 --strictPort > /tmp/vite-b3.log 2>&1 &
```
探测:`fetch('/src/views/NamespaceEditorView.vue')` 必须 200(该文件仅存在于本 worktree);`fetch('/src/views/NamespaceView.vue')` 内容须含 `创建命名空间`。工具报的端口不可信,以 vite 日志为准。
- [ ] **步骤 2:桩矩阵**(假 token + XHR/fetch 双桩 + 真实 `$router` 跳转,记忆 `ui-preview-verification`)
`/namespace/list` 三行:托管+已分配 / 托管未分配 / 外部(验删除与编辑的禁用 tooltip 差异);`/namespace/get`;`/namespace/yaml`;`/namespace/quota/get` 三夹具:A `null`(未配置)、B 全量含一项 `used > hard`(验 >100% 标红)、C `multiple:true`(验琥珀告警);`/namespace/limitrange/get` 两夹具(含一个未建模类型回显)。
- [ ] **步骤 3:清单逐项验**
列表:PageHeader + 描述列截断 tooltip + 名称可点 + 下拉三命令 + 禁用理由 tooltip;
编辑器创建态(区块关 → 无约束请求)/ 编辑态(回填 description/labels/quota/limitrange + 名称锁定 + 单位后缀 `m`/`Mi` + 数值序红字 + 关区块触发 delete);
详情:四 tab lazy 加载、用量文本 `used / hard` + 百分比、跳编辑 query 保留 clusterId+name;
console 零错误;深浅色各截配额表一张。
- [ ] **步骤 4:spec 勘误回写**(独立提交)
`docs/superpowers/specs/2026-09-14-namespace-enhancement-design.md`:
§6.2 输入风格 → 数字框 + 固定单位后缀(D2);§5.3 端点表 → 补 get/yaml/quota/delete/limitrange/delete(D5);§4.2 `default` → DTO 字段 `defaultValue`(无 JSON 注解,converter 显式映射);§10「编辑器取第一个」→ 固定名 `default` + `multiple` 告警(D4);§6.4 quantity 工具 → 复用既有 `utils/quantity.ts` + 新增 `utils/quantityUnits.ts`,并声明不引入第三套;§6.4 `NamespaceView` 加 labels;§5.4 访问路径 → 定论 `/admin/**` + 新增 `AbstractAdminNamespacedResourceController`(D1);§11 标题标注「延期至 B6/B7」(D10);§8 补 managed-by 守卫扩展到 update/create(D3);末尾 RBAC 交接备注(D9)。
提交 → `docs(namespace): B3 spec errata — rulings D1-D10`
- [ ] **步骤 5:人工活集群 E2E(交回人类伙伴,spec §9 六条)**
创建带配额+限制范围 → `kubectl get ns <n> -o jsonpath` 验 description annotation + managed-by;`kubectl get resourcequota default -o yaml` 验基础单位换算正确(`2` 核 / `3221225472` 字节);**手工播种**含 `count/deployments.apps` 的 quota 与含 `ContainerFixed` 的 limitrange → 平台编辑后**两键存活(这是两个 overlay 的实战证明)**;外部命名空间编辑/删除被禁;清空整区块 → 对象删除;`delete` 已分配命名空间仍被拒。

---

## 依赖顺序

```
T1 → T2 → T3 ─┐
   → T4 ───────┼→ T5 → T6 ─┐
              └→ T7 → T8 ───┴→ T9 → {T10 → T11, T12, T13} → T14 → T15 → T16
```
后端链先行(T1–T8 可独立部署);T5/T6 在 T4 后可并行;T10–T13 在 T9 后可并行(T11 需 T10 挂载点)。仅 T7 跨两个 Maven 模块 —— 保持为单个可评审的迁移对。

## 跨任务接缝(评审重点)

1. **`MODELED_HARD_KEYS` 的 `persistentvolumeclaims` 必须全小写** —— 写成驼峰会静默生成 K8s 不认的键(配额不生效但无报错)。T5 测试 `convert_maps_dotted_and_lowercase_keys_exactly` 是防线。
2. **`defaultValue` ⇄ K8s `default` 三处一致**:Java DTO 字段、TS 字段、converter 里的 `addToDefault`/`getDefault`。T6 测试覆盖。
3. **`multiple` 旗标只在 platform-api 设**(它才拿得到 list 结果),operations/converter 不碰。
4. **`name=default` 强制在 platform-api 层**(T8),k8s-server 与 converter 都不假设名字是 default。
5. **managed-by 只在 converter 写**(T2),`NamespaceService` 的 `isManaged` 判定读的是 revert 回来的 labels。
6. **基础单位线路**:前端 `quantityUnits.ts` 换算 + 后端 `QuantityUtil` 换算,中间任何一层不得二次换算(测试断言 `2147483648` 而非 `2Gi`)。
7. **平台上下文页面三件套一致**:editor/detail/list 都用 `clusterId` query 传递、都不 import `useResourceContext`、路由 meta 都无 `context` 键。

## 风险登记

| 风险 | 缓解 |
|---|---|
| 已上线 list/delete 迁移回归(两服务切换窗口) | T3 只增 + T7 成对切换 + commit body 部署顺序注记 + 响应形状不变 + T15 校验列表平价 |
| LimitRange 按 type overlay 无仓库先例(顺序/重复类型/ContainerFixed 存活) | T6 穷尽测试 + 顺序规则 javadoc 钉死 + 重复类型视为校验错 |
| 非原子 create 留下无约束的命名空间 | T10 警告 + `router.replace` 自动切编辑态(名锁死)+ upsert 幂等可重试 |
| quota「缺失」vs「存在但全空」语义混淆 | T8 仅对象缺失返回 null;前端 null→未配置、`{}`→已配置空;T16 两夹具都验 |
| SSA 下 platform-system 拥有整个 hard map → 省略即删 | overlay-before-SSA(T5/T6 强制);T16 活集群 `kubectl get quota -o jsonpath` 验未建模键存活 |
| `MODELED_TYPES` 在 k8s-core 与 platform-api 各存一份(无模块依赖) | javadoc 交叉引用 + T8 测试断言拒 `ContainerFixed`;与既有 MANAGED_BY 重复同性质 |
| 用户从 UI 删一个 label 不会真删(T2 保守 merge 语义) | javadoc 写明 + FieldHelp 文案「其余既有标签自动保留」;留终审裁定是否要显式删除集 |
| 前端门控误判(junction / 连错树 / 增量缓存) | 工作区准备拆链步骤 + T16 served-tree 探测 + T14 用全量 build |
| 平台级端点鉴权 | 双闸继承(platform-api 全量 `PLATFORM:admin` + k8s-server `/admin/**`),无需新配置;RBAC 细粒度落地后需补权限行 → 交接备注 |

## 验证总览

每任务自带门控(上列)。批次级:全 reactor compile + k8s-core/platform-api 全测试无回归 + 前端全量 build + 浏览器预览桩矩阵;**人工活集群 E2E** 见 T16 步骤 5。全部通过后按 finishing-a-development-branch 收尾。
