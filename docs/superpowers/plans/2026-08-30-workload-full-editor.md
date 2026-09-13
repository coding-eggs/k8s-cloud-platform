# 工作负载完整编辑器 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把工作负载新建/编辑从「kind+名称+镜像行+副本数+端口」的简单弹窗，升级为覆盖 Deployment/StatefulSet/DaemonSet 全字段（PodSpec 22 组）的独立整页结构化编辑器。

**Architecture:** 分层红线——业务校验一律在 `platform-api`（新增 `WorkloadService` + `WorkloadValidator`），k8s-server/k8s-core 是纯适配器（converter 只做 DTO↔K8s 1:1 全字段映射，operations 只调 fabric8 + 透传 K8s 错误，无业务 if）。数据流：`platform-web 编辑器 → WorkloadController → WorkloadService(校验) → K8sResourceClient(通用透传) → k8s-server → WorkloadOperations(fabric8) → WorkloadConverter(1:1) → fabric8 → K8s`。

**Tech Stack:** Vue 3 + Element Plus + Vite + vue-tsc（前端）；Maven 多模块 + Spring + fabric8 kubernetes-client **7.6.1**（后端）。DTO 用 Lombok `@Data` + swagger `@Schema`。

**Spec:** `docs/superpowers/specs/2026-08-30-workload-full-editor-design.md`（本计划实现该 spec；执行者应同时读 spec §4 DTO 树、§5 冲突表、§6 改动清单）

## Global Constraints

以下约束对每个任务都隐式生效，值逐字取自 spec：

- **架构红线（最高优先级）**：业务校验规则一律在 `platform-api`。k8s-server + k8s-core 是纯适配器——converter 只做 DTO↔K8s 对象的忠实 1:1 全字段映射（含 `selector={app:name}`、template labels 补 `app=name` 这类「如何构造合法 K8s 对象」的适配正确性约定，属非业务），operations 只调 fabric8 + 透传 K8s 错误。**k8s-core/k8s-server 不含任何业务 if 判断**。
- **quantity 一律 `String`**：maxSurge / maxUnavailable / resources(limits/requests) / storage / sizeLimit / partition 等值用 String（K8s quantity 支持 `"25%"`/`"100m"`/`"1Gi"`）。
- **B4 requests>limits 严格校验，前后端都要**：前端 `ResourcesEditor` 实时标红并阻止提交；后端 `WorkloadValidator` 逐资源解析 K8s quantity 转 BigDecimal 比较，超限或无法解析即抛错（不交 K8s 兜底）。
- **名称 RFC1123、容器名 DNS_LABEL**：`^[a-z0-9]([-a-z0-9]*[a-z0-9])?$`。
- **fabric8 版本 7.6.1**：`SleepAction`/`IntOrString` 可用；枚举字段（`NodeSelectorRequirementOperator`、`TolerationOperator`）需显式 `valueOf`，其余 operator/effect/protocol/type/scheme/medium/volumeMode/accessModes 在 fabric8 中为 String。
- **无测试框架**：后端每任务用 `mvn -q -pl <module> compile`（或整仓 `mvn -q compile`）把关；前端用 `npm run type-check` + `npm run build`；集成/curl/preview 手动验证集中在最后 Task 27。
- **提交隔离**：工作区当前含一批与本功能无关的已暂存改动（k8s-core rbac/workload 重构，约 93 文件）。每个任务的 `git add` 只加该任务的文件；见下方 Precondition。

**Precondition（执行前一次性）**：开始前先隔离无关改动——`git stash push -u -m "wip-unrelated"`（或先把它们 commit），使本计划提交彼此干净；全部完成后再 `git stash pop` 恢复。每个任务的提交步骤只 `git add` 该任务列出的文件。

---

## Phase A — platform-common（DTO 层）

依赖方向最底层，先做。验证门 = `mvn -q -pl platform-common compile`。

### Task 1: 核心 Pod/容器 DTO + WorkloadDTO 扩展

**Files:**
- Modify: `platform-common/src/main/java/com/coding/common/models/k8s/dto/WorkloadDTO.java`
- Modify: `.../dto/ContainerDTO.java`、`.../dto/EnvDTO.java`、`.../dto/PortDTO.java`（就地 additive 扩展，不破坏现有字段）
- Create（package `com.coding.common.models.k8s.dto`，均 `@Data @Schema`）：
  `PodTemplateDTO · PodSpecDTO · ResourcesDTO · ProbeDTO · HttpGetActionDTO · TCPSocketActionDTO · ExecActionDTO · SleepActionDTO · LifecycleDTO · HandlerDTO · VolumeMountDTO · EnvFromDTO · ValueFromDTO · ConfigMapKeySelectorDTO · SecretKeySelectorDTO · ObjectFieldSelectorDTO`

**Interfaces:**
- Produces（后续 converter/validator 依赖的字段名，逐字）：见下方「字段定义表」。所有类 `package com.coding.common.models.k8s.dto;` + `import lombok.Data; import io.swagger.v3.oas.annotations.media.Schema;`。

**Step 1: WorkloadDTO 加 5 个字段**（保留现有 replicas/readyReplicas/images/ports/creationTime）：

```java
    /** 描述 → metadata.annotations["description"] */
    @Schema(description = "描述")
    private String description;

    /** STS 专属 headless service 名，默认 = name */
    @Schema(description = "serviceName（StatefulSet 专属）")
    private String serviceName;

    /** 更新策略（kind 感知） */
    @Schema(description = "更新策略")
    private StrategyDTO strategy;

    /** STS 专属卷声明模板 */
    @Schema(description = "volumeClaimTemplates（StatefulSet 专属）")
    private java.util.List<PvcTemplateDTO> volumeClaimTemplates;

    /** 完整 PodSpec（三种 kind 共享）★核心 */
    @Schema(description = "Pod 模板（完整 PodSpec）")
    private PodTemplateDTO podTemplate;
```

**Step 2: 就地扩展三个已有 DTO**（additive，只加字段不改旧字段）：

`ContainerDTO` 追加：
```java
    private java.util.List<String> command;
    private java.util.List<String> args;
    private String workingDir;
    private String imagePullPolicy;
    private java.util.List<EnvFromDTO> envFrom;
    private ResourcesDTO resources;
    private LifecycleDTO lifecycle;
    private ProbeDTO livenessProbe;
    private ProbeDTO readinessProbe;
    private ProbeDTO startupProbe;
    private java.util.List<VolumeMountDTO> volumeMounts;
```

`EnvDTO` 追加：`private ValueFromDTO valueFrom;`
`PortDTO` 追加：`private String protocol; private String name;`

**Step 3: 新建本任务 16 个 DTO 类。** 每个类的字段（逐字，类型即 Java 类型）：

| 类 | 字段 |
|---|---|
| `PodTemplateDTO` | `Map<String,String> labels` · `Map<String,Object> annotations` · `PodSpecDTO spec` |
| `PodSpecDTO` | `List<ContainerDTO> containers` · `List<ContainerDTO> initContainers` · `String restartPolicy` · `String serviceAccountName` · `String nodeName` · `Map<String,String> nodeSelector` · `AffinityDTO affinity` · `List<TolerationDTO> tolerations` · `List<VolumeDTO> volumes` · `List<ImagePullSecretRefDTO> imagePullSecrets` |
| `ResourcesDTO` | `Map<String,String> limits` · `Map<String,String> requests` |
| `ProbeDTO` | `HttpGetActionDTO httpGet` · `TCPSocketActionDTO tcpSocket` · `ExecActionDTO exec` · `Integer initialDelaySeconds` · `Integer periodSeconds` · `Integer timeoutSeconds` · `Integer successThreshold` · `Integer failureThreshold` |
| `HttpGetActionDTO` | `String port`（IntOrString，命名端口用 String）· `String path` · `String scheme` |
| `TCPSocketActionDTO` | `String port` |
| `ExecActionDTO` | `List<String> command` |
| `SleepActionDTO` | `Long seconds` |
| `LifecycleDTO` | `HandlerDTO postStart` · `HandlerDTO preStop` |
| `HandlerDTO` | `ExecActionDTO exec` · `HttpGetActionDTO httpGet` · `SleepActionDTO sleep`（无 tcpSocket） |
| `VolumeMountDTO` | `String name` · `String mountPath` · `Boolean readOnly` · `String subPath` |
| `EnvFromDTO` | `String prefix` · `ConfigMapRefDTO configMapRef` · `SecretRefDTO secretRef` |
| `ValueFromDTO` | `ConfigMapKeySelectorDTO configMapKeyRef` · `SecretKeySelectorDTO secretKeyRef` · `ObjectFieldSelectorDTO fieldRef` |
| `ConfigMapKeySelectorDTO` | `String name` · `String key` · `Boolean optional` |
| `SecretKeySelectorDTO` | `String name` · `String key` · `Boolean optional` |
| `ObjectFieldSelectorDTO` | `String apiVersion` · `String fieldPath` |

> 说明：`ImagePullSecretRefDTO`（`String name`）、`ConfigMapRefDTO`/`SecretRefDTO`（各 `String name` + `Boolean optional`）在本任务一并建出（供 PodSpecDTO/EnvFromDTO 引用），避免引用未定义类型。

**Step 4: 编译说明（Phase A 整体门）**
本任务新建的 `PodSpecDTO` 引用了 Task 2 的 `AffinityDTO/TolerationDTO/VolumeDTO`，`WorkloadDTO` 又引用了 Task 3 的 `StrategyDTO/PvcTemplateDTO`——三者同模块交叉引用，**单独编译本任务不会绿**。这是预期的：Phase A 的编译门在 **Task 3 Step 2**（全部 ~36 个 DTO 就位后统一验证）。本步只需确认无语法/拼写错误即可继续。

**Step 5: Commit**
```bash
git add platform-common/src/main/java/com/coding/common/models/k8s/dto/
git commit -m "feat(workload): extend WorkloadDTO + core pod/container DTOs"
```

### Task 2: 亲和 / 容忍 / 卷 DTO

**Files:**
- Create（同 package，`@Data @Schema`）：
  `AffinityDTO · NodeAffinityDTO · PodAntiAffinityDTO · NodeSelectorDTO · NodeSelectorTermDTO · NodeSelectorRequirementDTO · PreferredSchedulingTermDTO · PodAffinityTermDTO · WeightedPodAffinityTermDTO · TolerationDTO · VolumeDTO · EmptyDirVolumeDTO · ConfigMapVolumeDTO · SecretVolumeDTO · PvcVolumeDTO · HostPathVolumeDTO · KeyToPathDTO`

**Step 1: 建 17 个类，字段（逐字）：**

| 类 | 字段 |
|---|---|
| `AffinityDTO` | `NodeAffinityDTO nodeAffinity` · `PodAntiAffinityDTO podAntiAffinity` |
| `NodeAffinityDTO` | `NodeSelectorDTO required` · `List<PreferredSchedulingTermDTO> preferred` |
| `PodAntiAffinityDTO` | `List<PodAffinityTermDTO> required` · `List<WeightedPodAffinityTermDTO> preferred` |
| `NodeSelectorDTO` | `List<NodeSelectorTermDTO> nodeSelectorTerms` |
| `NodeSelectorTermDTO` | `List<NodeSelectorRequirementDTO> matchExpressions` · `List<NodeSelectorRequirementDTO> matchFields` |
| `NodeSelectorRequirementDTO` | `String key` · `String operator`（In/NotIn/Exists/DoesNotExist/Gt/Lt）· `List<String> values` |
| `PreferredSchedulingTermDTO` | `Integer weight` · `NodeSelectorTermDTO preference` |
| `PodAffinityTermDTO` | `List<String> namespaces` · `String topologyKey` · `Map<String,String> matchLabels` |
| `WeightedPodAffinityTermDTO` | `Integer weight` · `PodAffinityTermDTO podAffinityTerm` |
| `TolerationDTO` | `String key` · `String operator`（Equal/Exists）· `String value` · `String effect`（NoSchedule/PreferNoSchedule/NoExecute）· `Long tolerationSeconds` |
| `VolumeDTO` | `String name` · `String type`（emptyDir/configMap/secret/persistentVolumeClaim/hostPath）· `EmptyDirVolumeDTO emptyDir` · `ConfigMapVolumeDTO configMap` · `SecretVolumeDTO secret` · `PvcVolumeDTO persistentVolumeClaim` · `HostPathVolumeDTO hostPath` |
| `EmptyDirVolumeDTO` | `String medium`（Memory/空）· `String sizeLimit` |
| `ConfigMapVolumeDTO` | `String name` · `List<KeyToPathDTO> items` |
| `SecretVolumeDTO` | `String secretName` · `List<KeyToPathDTO> items` |
| `PvcVolumeDTO` | `String claimName` |
| `HostPathVolumeDTO` | `String path` · `String type`（Directory/DirectoryOrCreate/File 等） |
| `KeyToPathDTO` | `String key` · `String path` · `Integer mode`（八进制整数，如 420=0644） |

**Step 2: 编译说明（Phase A 整体门）**
此时 `WorkloadDTO` 仍引用 Task 3 的 `StrategyDTO/PvcTemplateDTO`，故单独编译本模块尚不会绿——属预期。继续 Task 3，在 **Task 3 Step 2** 统一验证 Phase A 编译通过。

**Step 3: Commit**
```bash
git add platform-common/src/main/java/com/coding/common/models/k8s/dto/
git commit -m "feat(workload): affinity/toleration/volume DTOs"
```

### Task 3: 策略 / STS 卷模板 DTO

**Files:**
- Create（同 package）：`StrategyDTO · RollingUpdateDTO · PvcTemplateDTO`

**Step 1: 建 3 个类，字段（逐字）：**

| 类 | 字段 |
|---|---|
| `StrategyDTO` | `String type`（Dep: RollingUpdate/Recreate；STS/DS: RollingUpdate/OnDelete）· `RollingUpdateDTO rollingUpdate` |
| `RollingUpdateDTO` | `String maxSurge`（quantity，仅 Dep/… DS 无）· `String maxUnavailable`（quantity）· `Integer partition`（仅 STS） |
| `PvcTemplateDTO` | `String name` · `List<String> accessModes`（ReadWriteOnce/ReadOnlyMany/ReadWriteMany/ReadWriteOncePod）· `String storage`（quantity）· `String storageClassName` · `String volumeMode`（Filesystem/Block） |

**Step 2: 编译验证**
Run: `mvn -q -pl platform-common compile`
Expected: BUILD SUCCESS（DTO 层全部完成，约 36 个新类 + 4 处扩展）

**Step 3: Commit**
```bash
git add platform-common/src/main/java/com/coding/common/models/k8s/dto/
git commit -m "feat(workload): strategy/pvc-template DTOs"
```

---

## Phase B — k8s-core（纯适配器：converter + operations）

验证门 = `mvn -q -pl k8s-core compile`。**禁止在此写业务判断**；只做 1:1 映射。fabric8 导入前缀 `io.fabric8.kubernetes.api.model.*`、`...model.apps.*`。

### Task 4: WorkloadConverter convert 侧（DTO → K8s）全字段

**Files:**
- Modify: `k8s-core/src/main/java/com/coding/k8score/converter/impl/workload/WorkloadConverter.java`

**Interfaces:**
- Consumes: Phase A 全部 DTO。
- Produces: `convertDeployment/convertStatefulSet/convertDaemonSet(WorkloadDTO)`（签名不变，内部改为全字段）；私有 helper `buildPodSpec/toContainer/toEnv/toValueFrom/toEnvFrom/toPort/toResources/toLifecycle/toHandler/toProbe/toHttpGet/toVolumeMount/toAffinity/toNodeAffinity/toNodeSelectorTerm/toRequirement/toPodAntiAffinity/toPodAffinityTerm/toToleration/toVolume/toKeyToPath/toDeploymentStrategy/toStatefulSetUpdateStrategy/toDaemonSetUpdateStrategy/toPvcTemplates`。

**Step 1: 顶部加 import**（按需）：
```java
import com.coding.common.models.k8s.dto.*;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.*;
import java.util.Objects;
```
保留现有 `KIND_*` 常量与 `selectorLabels()/effectiveLabels()`。删除旧的 `buildTemplate(WorkloadDTO)`（镜像行逻辑）。

**Step 2: 三个 convert 方法改为全字段**（selector/labels 约定保留）：

```java
public Deployment convertDeployment(WorkloadDTO dto) {
    return new DeploymentBuilder()
        .withNewMetadata().withName(dto.getName()).withNamespace(dto.getNamespace())
            .withLabels(effectiveLabels(dto)).endMetadata()
        .withNewSpec()
            .withReplicas(dto.getReplicas() != null ? dto.getReplicas() : 1)
            .withSelector(new LabelSelectorBuilder().withMatchLabels(selectorLabels(dto)).build())
            .withStrategy(toDeploymentStrategy(dto.getStrategy()))
            .withTemplate(buildTemplate(dto))
        .endSpec().build();
}

public StatefulSet convertStatefulSet(WorkloadDTO dto) {
    return new StatefulSetBuilder()
        .withNewMetadata().withName(dto.getName()).withNamespace(dto.getNamespace())
            .withLabels(effectiveLabels(dto)).endMetadata()
        .withNewSpec()
            .withReplicas(dto.getReplicas() != null ? dto.getReplicas() : 1)
            .withServiceName(hasText(dto.getServiceName()) ? dto.getServiceName() : dto.getName())
            .withSelector(new LabelSelectorBuilder().withMatchLabels(selectorLabels(dto)).build())
            .withUpdateStrategy(toStatefulSetUpdateStrategy(dto.getStrategy()))
            .withTemplate(buildTemplate(dto))
            .withVolumeClaimTemplates(toPvcTemplates(dto.getVolumeClaimTemplates()))
        .endSpec().build();
}

public DaemonSet convertDaemonSet(WorkloadDTO dto) {
    return new DaemonSetBuilder()
        .withNewMetadata().withName(dto.getName()).withNamespace(dto.getNamespace())
            .withLabels(effectiveLabels(dto)).endMetadata()
        .withNewSpec()
            .withSelector(new LabelSelectorBuilder().withMatchLabels(selectorLabels(dto)).build())
            .withUpdateStrategy(toDaemonSetUpdateStrategy(dto.getStrategy()))
            .withTemplate(buildTemplate(dto))
        .endSpec().build();
}

private PodTemplateSpec buildTemplate(WorkloadDTO dto) {
    PodTemplateDTO pt = dto.getPodTemplate() != null ? dto.getPodTemplate() : new PodTemplateDTO();
    Map<String, String> tplLabels = effectiveLabels(dto); // app=name + 用户 labels
    if (pt.getLabels() != null) tplLabels.putAll(pt.getLabels());
    return new PodTemplateSpecBuilder()
        .withNewMetadata().withLabels(tplLabels).endMetadata()
        .withSpec(buildPodSpec(pt.getSpec() != null ? pt.getSpec() : new PodSpecDTO()))
        .build();
}
```

**Step 3: convert 侧 helper（全字段，1:1）。** 判空用 `hasText`/`notEmpty`（文件底部加两个私有工具：`private static boolean hasText(String s){return s!=null&&!s.trim().isEmpty();}`、`private static boolean notEmpty(java.util.Collection<?> c){return c!=null&&!c.isEmpty();}`）：

```java
private PodSpec buildPodSpec(PodSpecDTO s) {
    PodSpecBuilder b = new PodSpecBuilder();
    if (notEmpty(s.getContainers())) b.withContainers(s.getContainers().stream().map(this::toContainer).toList());
    if (notEmpty(s.getInitContainers())) b.withInitContainers(s.getInitContainers().stream().map(this::toContainer).toList());
    if (hasText(s.getRestartPolicy())) b.withRestartPolicy(s.getRestartPolicy());
    if (hasText(s.getServiceAccountName())) b.withServiceAccountName(s.getServiceAccountName());
    if (hasText(s.getNodeName())) b.withNodeName(s.getNodeName());
    if (s.getNodeSelector() != null && !s.getNodeSelector().isEmpty()) b.withNodeSelector(s.getNodeSelector());
    if (s.getAffinity() != null) b.withAffinity(toAffinity(s.getAffinity()));
    if (notEmpty(s.getTolerations())) b.withTolerations(s.getTolerations().stream().map(this::toToleration).toList());
    if (notEmpty(s.getVolumes())) b.withVolumes(s.getVolumes().stream().map(this::toVolume).toList());
    if (notEmpty(s.getImagePullSecrets()))
        b.withImagePullSecrets(s.getImagePullSecrets().stream()
            .map(r -> new LocalObjectReferenceBuilder().withName(r.getName()).build()).toList());
    return b.build();
}

private Container toContainer(ContainerDTO c) {
    ContainerBuilder b = new ContainerBuilder();
    if (hasText(c.getName())) b.withName(c.getName());
    if (hasText(c.getImage())) b.withImage(c.getImage());
    if (notEmpty(c.getCommand())) b.withCommand(c.getCommand());
    if (notEmpty(c.getArgs())) b.withArgs(c.getArgs());
    if (hasText(c.getWorkingDir())) b.withWorkingDir(c.getWorkingDir());
    if (hasText(c.getImagePullPolicy())) b.withImagePullPolicy(c.getImagePullPolicy());
    if (notEmpty(c.getEnvs())) b.withEnv(c.getEnvs().stream().map(this::toEnv).toList());
    if (notEmpty(c.getEnvFrom())) b.withEnvFrom(c.getEnvFrom().stream().map(this::toEnvFrom).toList());
    if (notEmpty(c.getPorts())) b.withPorts(c.getPorts().stream().map(this::toPort).toList());
    if (c.getResources() != null) b.withResources(toResources(c.getResources()));
    if (c.getLifecycle() != null) b.withLifecycle(toLifecycle(c.getLifecycle()));
    if (c.getLivenessProbe() != null) b.withLivenessProbe(toProbe(c.getLivenessProbe()));
    if (c.getReadinessProbe() != null) b.withReadinessProbe(toProbe(c.getReadinessProbe()));
    if (c.getStartupProbe() != null) b.withStartupProbe(toProbe(c.getStartupProbe()));
    if (notEmpty(c.getVolumeMounts())) b.withVolumeMounts(c.getVolumeMounts().stream().map(this::toVolumeMount).toList());
    return b.build();
}

private EnvVar toEnv(EnvDTO e) {
    EnvVarBuilder b = new EnvVarBuilder();
    if (hasText(e.getName())) b.withName(e.getName());
    if (e.getValue() != null) b.withValue(e.getValue());
    if (e.getValueFrom() != null) b.withValueFrom(toValueFrom(e.getValueFrom()));
    return b.build();
}

private EnvVarSource toValueFrom(ValueFromDTO v) {
    EnvVarSourceBuilder b = new EnvVarSourceBuilder();
    if (v.getConfigMapKeyRef() != null)
        b.withConfigMapKeyRef(new ConfigMapKeySelectorBuilder().withName(v.getConfigMapKeyRef().getName())
            .withKey(v.getConfigMapKeyRef().getKey()).withOptional(v.getConfigMapKeyRef().getOptional()).build());
    if (v.getSecretKeyRef() != null)
        b.withSecretKeyRef(new SecretKeySelectorBuilder().withName(v.getSecretKeyRef().getName())
            .withKey(v.getSecretKeyRef().getKey()).withOptional(v.getSecretKeyRef().getOptional()).build());
    if (v.getFieldRef() != null)
        b.withFieldRef(new ObjectFieldSelectorBuilder().withApiVersion(v.getFieldRef().getApiVersion())
            .withFieldPath(v.getFieldRef().getFieldPath()).build());
    return b.build();
}

private EnvFromSource toEnvFrom(EnvFromDTO f) {
    EnvFromSourceBuilder b = new EnvFromSourceBuilder();
    if (hasText(f.getPrefix())) b.withPrefix(f.getPrefix());
    if (f.getConfigMapRef() != null)
        b.withConfigMapRef(new ConfigMapEnvSourceBuilder().withName(f.getConfigMapRef().getName())
            .withOptional(f.getConfigMapRef().getOptional()).build());
    if (f.getSecretRef() != null)
        b.withSecretRef(new SecretEnvSourceBuilder().withName(f.getSecretRef().getName())
            .withOptional(f.getSecretRef().getOptional()).build());
    return b.build();
}

private ContainerPort toPort(PortDTO p) {
    ContainerPortBuilder b = new ContainerPortBuilder();
    if (p.getContainerPort() != null) b.withContainerPort(p.getContainerPort());
    if (hasText(p.getProtocol())) b.withProtocol(p.getProtocol());
    if (hasText(p.getName())) b.withName(p.getName());
    return b.build();
}

private ResourceRequirements toResources(ResourcesDTO r) {
    ResourceRequirementsBuilder b = new ResourceRequirementsBuilder();
    if (r.getLimits() != null && !r.getLimits().isEmpty()) b.withLimits(r.getLimits());
    if (r.getRequests() != null && !r.getRequests().isEmpty()) b.withRequests(r.getRequests());
    return b.build();
}

private Lifecycle toLifecycle(LifecycleDTO l) {
    LifecycleBuilder b = new LifecycleBuilder();
    if (l.getPostStart() != null) b.withPostStart(toHandler(l.getPostStart()));
    if (l.getPreStop() != null) b.withPreStop(toHandler(l.getPreStop()));
    return b.build();
}

private Handler toHandler(HandlerDTO h) {
    HandlerBuilder b = new HandlerBuilder();
    if (h.getExec() != null) b.withExec(new ExecActionBuilder().withCommand(h.getExec().getCommand()).build());
    if (h.getHttpGet() != null) b.withHttpGet(toHttpGet(h.getHttpGet()));
    if (h.getSleep() != null) b.withSleep(new SleepActionBuilder().withSeconds(h.getSleep().getSeconds()).build());
    return b.build();
}

private Probe toProbe(ProbeDTO p) {
    ProbeBuilder b = new ProbeBuilder();
    if (p.getHttpGet() != null) b.withHttpGet(toHttpGet(p.getHttpGet()));
    if (p.getTcpSocket() != null) b.withTcpSocket(new TCPSocketActionBuilder().withPort(new IntOrString(p.getTcpSocket().getPort())).build());
    if (p.getExec() != null) b.withExec(new ExecActionBuilder().withCommand(p.getExec().getCommand()).build());
    if (p.getInitialDelaySeconds() != null) b.withInitialDelaySeconds(p.getInitialDelaySeconds());
    if (p.getPeriodSeconds() != null) b.withPeriodSeconds(p.getPeriodSeconds());
    if (p.getTimeoutSeconds() != null) b.withTimeoutSeconds(p.getTimeoutSeconds());
    if (p.getSuccessThreshold() != null) b.withSuccessThreshold(p.getSuccessThreshold());
    if (p.getFailureThreshold() != null) b.withFailureThreshold(p.getFailureThreshold());
    return b.build();
}

private HTTPGetAction toHttpGet(HttpGetActionDTO h) {
    HTTPGetActionBuilder b = new HTTPGetActionBuilder();
    if (hasText(h.getPort())) b.withPort(new IntOrString(h.getPort()));
    if (hasText(h.getPath())) b.withPath(h.getPath());
    if (hasText(h.getScheme())) b.withScheme(h.getScheme());
    return b.build();
}

private VolumeMount toVolumeMount(VolumeMountDTO m) {
    VolumeMountBuilder b = new VolumeMountBuilder();
    if (hasText(m.getName())) b.withName(m.getName());
    if (hasText(m.getMountPath())) b.withMountPath(m.getMountPath());
    if (m.getReadOnly() != null) b.withReadOnly(m.getReadOnly());
    if (hasText(m.getSubPath())) b.withSubPath(m.getSubPath());
    return b.build();
}

private Affinity toAffinity(AffinityDTO a) {
    AffinityBuilder b = new AffinityBuilder();
    if (a.getNodeAffinity() != null) b.withNodeAffinity(toNodeAffinity(a.getNodeAffinity()));
    if (a.getPodAntiAffinity() != null) b.withPodAntiAffinity(toPodAntiAffinity(a.getPodAntiAffinity()));
    return b.build();
}

private NodeAffinity toNodeAffinity(NodeAffinityDTO n) {
    NodeAffinityBuilder b = new NodeAffinityBuilder();
    if (n.getRequired() != null && notEmpty(n.getRequired().getNodeSelectorTerms()))
        b.withNewRequiredDuringSchedulingIgnoredDuringExecution()
            .withNodeSelectorTerms(n.getRequired().getNodeSelectorTerms().stream().map(this::toNodeSelectorTerm).toList())
            .endRequiredDuringSchedulingIgnoredDuringExecution();
    if (notEmpty(n.getPreferred()))
        b.withPreferredDuringSchedulingIgnoredDuringExecution(n.getPreferred().stream()
            .map(p -> new PreferredSchedulingTermBuilder().withWeight(p.getWeight())
                .withPreference(toNodeSelectorTerm(p.getPreference())).build()).toList());
    return b.build();
}

private NodeSelectorTerm toNodeSelectorTerm(NodeSelectorTermDTO t) {
    NodeSelectorTermBuilder b = new NodeSelectorTermBuilder();
    if (notEmpty(t.getMatchExpressions())) b.withMatchExpressions(t.getMatchExpressions().stream().map(this::toRequirement).toList());
    if (notEmpty(t.getMatchFields())) b.withMatchFields(t.getMatchFields().stream().map(this::toRequirement).toList());
    return b.build();
}

private NodeSelectorRequirement toRequirement(NodeSelectorRequirementDTO r) {
    NodeSelectorRequirementBuilder b = new NodeSelectorRequirementBuilder();
    if (hasText(r.getKey())) b.withKey(r.getKey());
    if (hasText(r.getOperator())) b.withOperator(NodeSelectorRequirementOperator.valueOf(r.getOperator()));
    if (r.getValues() != null) b.withValues(r.getValues());
    return b.build();
}

private PodAntiAffinity toPodAntiAffinity(PodAntiAffinityDTO p) {
    PodAntiAffinityBuilder b = new PodAntiAffinityBuilder();
    if (notEmpty(p.getRequired()))
        b.withRequiredDuringSchedulingIgnoredDuringExecution(p.getRequired().stream().map(this::toPodAffinityTerm).toList());
    if (notEmpty(p.getPreferred()))
        b.withPreferredDuringSchedulingIgnoredDuringExecution(p.getPreferred().stream()
            .map(w -> new WeightedPodAffinityTermBuilder().withWeight(w.getWeight())
                .withPodAffinityTerm(toPodAffinityTerm(w.getPodAffinityTerm())).build()).toList());
    return b.build();
}

private PodAffinityTerm toPodAffinityTerm(PodAffinityTermDTO t) {
    PodAffinityTermBuilder b = new PodAffinityTermBuilder();
    if (notEmpty(t.getNamespaces())) b.withNamespaces(t.getNamespaces());
    if (hasText(t.getTopologyKey())) b.withTopologyKey(t.getTopologyKey());
    if (t.getMatchLabels() != null && !t.getMatchLabels().isEmpty())
        b.withLabelSelector(new LabelSelectorBuilder().withMatchLabels(t.getMatchLabels()).build());
    return b.build();
}

private Toleration toToleration(TolerationDTO t) {
    TolerationBuilder b = new TolerationBuilder();
    if (hasText(t.getKey())) b.withKey(t.getKey());
    if (hasText(t.getOperator())) b.withOperator(TolerationOperator.valueOf(t.getOperator()));
    if (t.getValue() != null) b.withValue(t.getValue());
    if (hasText(t.getEffect())) b.withEffect(t.getEffect());
    if (t.getTolerationSeconds() != null) b.withTolerationSeconds(t.getTolerationSeconds());
    return b.build();
}

private Volume toVolume(VolumeDTO v) {
    VolumeBuilder b = new VolumeBuilder().withName(v.getName());
    if (v.getEmptyDir() != null) {
        EmptyDirBuilder e = new EmptyDirBuilder();
        if (hasText(v.getEmptyDir().getMedium())) e.withMedium(v.getEmptyDir().getMedium());
        if (hasText(v.getEmptyDir().getSizeLimit())) e.withSizeLimit(v.getEmptyDir().getSizeLimit());
        b.withEmptyDir(e.build());
    } else if (v.getConfigMap() != null) {
        ConfigMapVolumeSourceBuilder c = new ConfigMapVolumeSourceBuilder().withName(v.getConfigMap().getName());
        if (notEmpty(v.getConfigMap().getItems())) c.withItems(toKeyToPath(v.getConfigMap().getItems()));
        b.withConfigMap(c.build());
    } else if (v.getSecret() != null) {
        SecretVolumeSourceBuilder s = new SecretVolumeSourceBuilder().withSecretName(v.getSecret().getSecretName());
        if (notEmpty(v.getSecret().getItems())) s.withItems(toKeyToPath(v.getSecret().getItems()));
        b.withSecret(s.build());
    } else if (v.getPersistentVolumeClaim() != null) {
        b.withPersistentVolumeClaim(new PersistentVolumeClaimVolumeSourceBuilder()
            .withClaimName(v.getPersistentVolumeClaim().getClaimName()).build());
    } else if (v.getHostPath() != null) {
        HostPathVolumeSourceBuilder h = new HostPathVolumeSourceBuilder().withPath(v.getHostPath().getPath());
        if (hasText(v.getHostPath().getType())) h.withType(v.getHostPath().getType());
        b.withHostPath(h.build());
    }
    return b.build();
}

private java.util.List<KeyToPath> toKeyToPath(java.util.List<KeyToPathDTO> items) {
    return items.stream().map(i -> new KeyToPathBuilder()
        .withKey(i.getKey()).withPath(i.getPath()).withMode(i.getMode()).build()).toList();
}

private DeploymentStrategy toDeploymentStrategy(StrategyDTO s) {
    if (s == null) return null;
    DeploymentStrategyBuilder b = new DeploymentStrategyBuilder();
    if (hasText(s.getType())) b.withType(s.getType());
    RollingUpdateDTO ru = s.getRollingUpdate();
    if (ru != null && (hasText(ru.getMaxSurge()) || hasText(ru.getMaxUnavailable()))) {
        RollingUpdateDeploymentBuilder r = new RollingUpdateDeploymentBuilder();
        if (hasText(ru.getMaxSurge())) r.withMaxSurge(new IntOrString(ru.getMaxSurge()));
        if (hasText(ru.getMaxUnavailable())) r.withMaxUnavailable(new IntOrString(ru.getMaxUnavailable()));
        b.withRollingUpdate(r.build());
    }
    return b.build();
}

private StatefulSetUpdateStrategy toStatefulSetUpdateStrategy(StrategyDTO s) {
    if (s == null) return null;
    StatefulSetUpdateStrategyBuilder b = new StatefulSetUpdateStrategyBuilder();
    if (hasText(s.getType())) b.withType(s.getType());
    RollingUpdateDTO ru = s.getRollingUpdate();
    if (ru != null && ru.getPartition() != null)
        b.withRollingUpdate(new StatefulSetRollingUpdateBuilder().withPartition(ru.getPartition()).build());
    return b.build();
}

private DaemonSetUpdateStrategy toDaemonSetUpdateStrategy(StrategyDTO s) {
    if (s == null) return null;
    DaemonSetUpdateStrategyBuilder b = new DaemonSetUpdateStrategyBuilder();
    if (hasText(s.getType())) b.withType(s.getType());
    RollingUpdateDTO ru = s.getRollingUpdate();
    if (ru != null && hasText(ru.getMaxUnavailable()))
        b.withRollingUpdate(new DaemonSetRollingUpdateBuilder()
            .withMaxUnavailable(new IntOrString(ru.getMaxUnavailable())).build());
    return b.build();
}

private java.util.List<PersistentVolumeClaim> toPvcTemplates(java.util.List<PvcTemplateDTO> list) {
    if (list == null || list.isEmpty()) return null;
    return list.stream().map(t -> new PersistentVolumeClaimBuilder()
        .withNewMetadata().withName(t.getName()).endMetadata()
        .withNewSpec()
            .withAccessModes(t.getAccessModes())
            .withStorageClassName(t.getStorageClassName())
            .withVolumeMode(t.getVolumeMode())
            .withResources(new ResourceRequirementsBuilder()
                .withRequests(java.util.Map.of("storage", t.getStorage())).build())
        .endSpec().build()).toList();
}
```

**Step 4: 编译验证**（revert 侧暂用旧逻辑，本步只保证 convert 侧 + 新 import 编译通过；若 revert 仍引用已删的 `buildTemplate` 旧签名会报错——旧的 `buildTemplate(WorkloadDTO)` 已被 Step 2 的新版替换，签名兼容，故应通过）
Run: `mvn -q -pl k8s-core compile`
Expected: BUILD SUCCESS

**Step 5: Commit**
```bash
git add k8s-core/src/main/java/com/coding/k8score/converter/impl/workload/WorkloadConverter.java
git commit -m "feat(workload): converter full-field DTO->K8s mapping"
```

### Task 5: WorkloadConverter revert 侧（K8s → DTO）全字段回填

**Files:**
- Modify: `.../converter/impl/workload/WorkloadConverter.java`

**Interfaces:**
- Produces: `revert(Deployment/StatefulSet/DaemonSet)`（签名不变，内部改为全字段回填）；私有 helper `fromContainer/fromEnv/fromValueFrom/fromEnvFrom/fromPort/fromResources/fromLifecycle/fromProbe/fromVolumeMount/fromAffinity/fromNodeAffinity/fromPodAntiAffinity/fromToleration/fromVolume/fromStrategy.../fromPvcTemplates` + 重写 `base()`。

**Step 1: 重写 `base()` 与三个 revert 方法。** 保留 images[] 摘要（list 用）+ 全量 podTemplate 回填：

```java
public WorkloadDTO revert(Deployment d) {
    WorkloadDTO dto = base(d.getMetadata(), KIND_DEPLOYMENT, d.getSpec() != null ? d.getSpec().getTemplate() : null);
    if (d.getSpec() != null) {
        dto.setReplicas(d.getSpec().getReplicas());
        dto.setStrategy(fromDeploymentStrategy(d.getSpec().getStrategy()));
    }
    if (d.getStatus() != null) dto.setReadyReplicas(d.getStatus().getReadyReplicas());
    return dto;
}

public WorkloadDTO revert(StatefulSet s) {
    WorkloadDTO dto = base(s.getMetadata(), KIND_STATEFULSET, s.getSpec() != null ? s.getSpec().getTemplate() : null);
    if (s.getSpec() != null) {
        dto.setReplicas(s.getSpec().getReplicas());
        dto.setServiceName(s.getSpec().getServiceName());
        dto.setStrategy(fromStatefulSetUpdateStrategy(s.getSpec().getUpdateStrategy()));
        dto.setVolumeClaimTemplates(fromPvcTemplates(s.getSpec().getVolumeClaimTemplates()));
    }
    if (s.getStatus() != null) dto.setReadyReplicas(s.getStatus().getReadyReplicas());
    return dto;
}

public WorkloadDTO revert(DaemonSet ds) {
    WorkloadDTO dto = base(ds.getMetadata(), KIND_DAEMONSET, ds.getSpec() != null ? ds.getSpec().getTemplate() : null);
    if (ds.getSpec() != null) dto.setStrategy(fromDaemonSetUpdateStrategy(ds.getSpec().getUpdateStrategy()));
    if (ds.getStatus() != null && ds.getStatus().getNumberReady() != null)
        dto.setReadyReplicas(ds.getStatus().getNumberReady());
    return dto;
}

private WorkloadDTO base(io.fabric8.kubernetes.api.model.ObjectMeta meta, String kind, PodTemplateSpec template) {
    WorkloadDTO dto = new WorkloadDTO();
    if (meta != null) {
        dto.setName(meta.getName());
        dto.setNamespace(meta.getNamespace());
        dto.setLabels(meta.getLabels());
        if (meta.getAnnotations() != null && meta.getAnnotations().get("description") != null)
            dto.setDescription(String.valueOf(meta.getAnnotations().get("description")));
        if (meta.getCreationTimestamp() != null) dto.setCreationTime(meta.getCreationTimestamp().toString());
    }
    dto.setKind(kind);
    if (template != null && template.getSpec() != null) {
        // images[] 摘要（list 用）
        if (template.getSpec().getContainers() != null)
            dto.setImages(template.getSpec().getContainers().stream().map(Container::getImage).toList());
        PodTemplateDTO pt = new PodTemplateDTO();
        if (template.getMetadata() != null) {
            pt.setLabels(template.getMetadata().getLabels());
            pt.setAnnotations(template.getMetadata().getAnnotations());
        }
        pt.setSpec(fromPodSpec(template.getSpec()));
        dto.setPodTemplate(pt);
    }
    return dto;
}
```

**Step 2: revert 侧 helper（1:1 反向）。** 判空同上：

```java
private PodSpecDTO fromPodSpec(io.fabric8.kubernetes.api.model.PodSpec s) {
    PodSpecDTO d = new PodSpecDTO();
    if (s.getContainers() != null) d.setContainers(s.getContainers().stream().map(this::fromContainer).toList());
    if (s.getInitContainers() != null) d.setInitContainers(s.getInitContainers().stream().map(this::fromContainer).toList());
    d.setRestartPolicy(s.getRestartPolicy());
    d.setServiceAccountName(s.getServiceAccountName());
    d.setNodeName(s.getNodeName());
    d.setNodeSelector(s.getNodeSelector());
    if (s.getAffinity() != null) d.setAffinity(fromAffinity(s.getAffinity()));
    if (s.getTolerations() != null) d.setTolerations(s.getTolerations().stream().map(this::fromToleration).toList());
    if (s.getVolumes() != null) d.setVolumes(s.getVolumes().stream().map(this::fromVolume).toList());
    if (s.getImagePullSecrets() != null)
        d.setImagePullSecrets(s.getImagePullSecrets().stream()
            .map(r -> { ImagePullSecretRefDTO x = new ImagePullSecretRefDTO(); x.setName(r.getName()); return x; }).toList());
    return d;
}

private ContainerDTO fromContainer(Container c) {
    ContainerDTO d = new ContainerDTO();
    d.setName(c.getName()); d.setImage(c.getImage());
    d.setCommand(c.getCommand()); d.setArgs(c.getArgs());
    d.setWorkingDir(c.getWorkingDir()); d.setImagePullPolicy(c.getImagePullPolicy());
    if (c.getEnv() != null) d.setEnvs(c.getEnv().stream().map(this::fromEnv).toList());
    if (c.getEnvFrom() != null) d.setEnvFrom(c.getEnvFrom().stream().map(this::fromEnvFrom).toList());
    if (c.getPorts() != null) d.setPorts(c.getPorts().stream().map(this::fromPort).toList());
    if (c.getResources() != null) d.setResources(fromResources(c.getResources()));
    if (c.getLifecycle() != null) d.setLifecycle(fromLifecycle(c.getLifecycle()));
    if (c.getLivenessProbe() != null) d.setLivenessProbe(fromProbe(c.getLivenessProbe()));
    if (c.getReadinessProbe() != null) d.setReadinessProbe(fromProbe(c.getReadinessProbe()));
    if (c.getStartupProbe() != null) d.setStartupProbe(fromProbe(c.getStartupProbe()));
    if (c.getVolumeMounts() != null) d.setVolumeMounts(c.getVolumeMounts().stream().map(this::fromVolumeMount).toList());
    return d;
}

private EnvDTO fromEnv(EnvVar e) {
    EnvDTO d = new EnvDTO(); d.setName(e.getName()); d.setValue(e.getValue());
    if (e.getValueFrom() != null) d.setValueFrom(fromValueFrom(e.getValueFrom()));
    return d;
}

private ValueFromDTO fromValueFrom(EnvVarSource v) {
    ValueFromDTO d = new ValueFromDTO();
    if (v.getConfigMapKeyRef() != null) { ConfigMapKeySelectorDTO x = new ConfigMapKeySelectorDTO();
        x.setName(v.getConfigMapKeyRef().getName()); x.setKey(v.getConfigMapKeyRef().getKey());
        x.setOptional(v.getConfigMapKeyRef().getOptional()); d.setConfigMapKeyRef(x); }
    if (v.getSecretKeyRef() != null) { SecretKeySelectorDTO x = new SecretKeySelectorDTO();
        x.setName(v.getSecretKeyRef().getName()); x.setKey(v.getSecretKeyRef().getKey());
        x.setOptional(v.getSecretKeyRef().getOptional()); d.setSecretKeyRef(x); }
    if (v.getFieldRef() != null) { ObjectFieldSelectorDTO x = new ObjectFieldSelectorDTO();
        x.setApiVersion(v.getFieldRef().getApiVersion()); x.setFieldPath(v.getFieldRef().getFieldPath()); d.setFieldRef(x); }
    return d;
}

private EnvFromDTO fromEnvFrom(EnvFromSource f) {
    EnvFromDTO d = new EnvFromDTO(); d.setPrefix(f.getPrefix());
    if (f.getConfigMapRef() != null) { ConfigMapRefDTO x = new ConfigMapRefDTO();
        x.setName(f.getConfigMapRef().getName()); x.setOptional(f.getConfigMapRef().getOptional()); d.setConfigMapRef(x); }
    if (f.getSecretRef() != null) { SecretRefDTO x = new SecretRefDTO();
        x.setName(f.getSecretRef().getName()); x.setOptional(f.getSecretRef().getOptional()); d.setSecretRef(x); }
    return d;
}

private PortDTO fromPort(ContainerPort p) {
    PortDTO d = new PortDTO(); d.setContainerPort(p.getContainerPort());
    d.setProtocol(p.getProtocol()); d.setName(p.getName()); return d;
}

private ResourcesDTO fromResources(ResourceRequirements r) {
    ResourcesDTO d = new ResourcesDTO(); d.setLimits(r.getLimits()); d.setRequests(r.getRequests()); return d;
}

private LifecycleDTO fromLifecycle(Lifecycle l) {
    LifecycleDTO d = new LifecycleDTO();
    if (l.getPostStart() != null) d.setPostStart(fromHandler(l.getPostStart()));
    if (l.getPreStop() != null) d.setPreStop(fromHandler(l.getPreStop()));
    return d;
}

private HandlerDTO fromHandler(Handler h) {
    HandlerDTO d = new HandlerDTO();
    if (h.getExec() != null) { ExecActionDTO x = new ExecActionDTO(); x.setCommand(h.getExec().getCommand()); d.setExec(x); }
    if (h.getHttpGet() != null) { HttpGetActionDTO x = new HttpGetActionDTO();
        x.setPort(h.getHttpGet().getPort() != null ? h.getHttpGet().getPort().toString() : null);
        x.setPath(h.getHttpGet().getPath()); x.setScheme(h.getHttpGet().getScheme()); d.setHttpGet(x); }
    if (h.getSleep() != null) { SleepActionDTO x = new SleepActionDTO(); x.setSeconds(h.getSleep().getSeconds()); d.setSleep(x); }
    return d;
}

private ProbeDTO fromProbe(Probe p) {
    ProbeDTO d = new ProbeDTO();
    if (p.getHttpGet() != null) { HttpGetActionDTO x = new HttpGetActionDTO();
        x.setPort(p.getHttpGet().getPort() != null ? p.getHttpGet().getPort().toString() : null);
        x.setPath(p.getHttpGet().getPath()); x.setScheme(p.getHttpGet().getScheme()); d.setHttpGet(x); }
    if (p.getTcpSocket() != null) { TCPSocketActionDTO x = new TCPSocketActionDTO();
        x.setPort(p.getTcpSocket().getPort() != null ? p.getTcpSocket().getPort().toString() : null); d.setTcpSocket(x); }
    if (p.getExec() != null) { ExecActionDTO x = new ExecActionDTO(); x.setCommand(p.getExec().getCommand()); d.setExec(x); }
    d.setInitialDelaySeconds(p.getInitialDelaySeconds()); d.setPeriodSeconds(p.getPeriodSeconds());
    d.setTimeoutSeconds(p.getTimeoutSeconds()); d.setSuccessThreshold(p.getSuccessThreshold());
    d.setFailureThreshold(p.getFailureThreshold());
    return d;
}

private VolumeMountDTO fromVolumeMount(VolumeMount m) {
    VolumeMountDTO d = new VolumeMountDTO();
    d.setName(m.getName()); d.setMountPath(m.getMountPath());
    d.setReadOnly(m.getReadOnly()); d.setSubPath(m.getSubPath()); return d;
}

private AffinityDTO fromAffinity(Affinity a) {
    AffinityDTO d = new AffinityDTO();
    if (a.getNodeAffinity() != null) d.setNodeAffinity(fromNodeAffinity(a.getNodeAffinity()));
    if (a.getPodAntiAffinity() != null) d.setPodAntiAffinity(fromPodAntiAffinity(a.getPodAntiAffinity()));
    return d;
}

private NodeAffinityDTO fromNodeAffinity(NodeAffinity n) {
    NodeAffinityDTO d = new NodeAffinityDTO();
    if (n.getRequiredDuringSchedulingIgnoredDuringExecution() != null) {
        NodeSelectorDTO req = new NodeSelectorDTO();
        req.setNodeSelectorTerms(n.getRequiredDuringSchedulingIgnoredDuringExecution().getNodeSelectorTerms()
            .stream().map(this::fromNodeSelectorTerm).toList());
        d.setRequired(req);
    }
    if (n.getPreferredDuringSchedulingIgnoredDuringExecution() != null)
        d.setPreferred(n.getPreferredDuringSchedulingIgnoredDuringExecution().stream().map(p -> {
            PreferredSchedulingTermDTO x = new PreferredSchedulingTermDTO();
            x.setWeight(p.getWeight()); x.setPreference(fromNodeSelectorTerm(p.getPreference())); return x; }).toList());
    return d;
}

private NodeSelectorTermDTO fromNodeSelectorTerm(NodeSelectorTerm t) {
    NodeSelectorTermDTO d = new NodeSelectorTermDTO();
    if (t.getMatchExpressions() != null) d.setMatchExpressions(t.getMatchExpressions().stream().map(this::fromRequirement).toList());
    if (t.getMatchFields() != null) d.setMatchFields(t.getMatchFields().stream().map(this::fromRequirement).toList());
    return d;
}

private NodeSelectorRequirementDTO fromRequirement(NodeSelectorRequirement r) {
    NodeSelectorRequirementDTO d = new NodeSelectorRequirementDTO();
    d.setKey(r.getKey());
    d.setOperator(r.getOperator() != null ? r.getOperator().name() : null);
    d.setValues(r.getValues()); return d;
}

private PodAntiAffinityDTO fromPodAntiAffinity(PodAntiAffinity p) {
    PodAntiAffinityDTO d = new PodAntiAffinityDTO();
    if (p.getRequiredDuringSchedulingIgnoredDuringExecution() != null)
        d.setRequired(p.getRequiredDuringSchedulingIgnoredDuringExecution().stream().map(this::fromPodAffinityTerm).toList());
    if (p.getPreferredDuringSchedulingIgnoredDuringExecution() != null)
        d.setPreferred(p.getPreferredDuringSchedulingIgnoredDuringExecution().stream().map(w -> {
            WeightedPodAffinityTermDTO x = new WeightedPodAffinityTermDTO();
            x.setWeight(w.getWeight()); x.setPodAffinityTerm(fromPodAffinityTerm(w.getPodAffinityTerm())); return x; }).toList());
    return d;
}

private PodAffinityTermDTO fromPodAffinityTerm(PodAffinityTerm t) {
    PodAffinityTermDTO d = new PodAffinityTermDTO();
    d.setNamespaces(t.getNamespaces()); d.setTopologyKey(t.getTopologyKey());
    if (t.getLabelSelector() != null && t.getLabelSelector().getMatchLabels() != null)
        d.setMatchLabels(t.getLabelSelector().getMatchLabels());
    return d;
}

private TolerationDTO fromToleration(Toleration t) {
    TolerationDTO d = new TolerationDTO();
    d.setKey(t.getKey());
    d.setOperator(t.getOperator() != null ? t.getOperator().name() : null);
    d.setValue(t.getValue()); d.setEffect(t.getEffect()); d.setTolerationSeconds(t.getTolerationSeconds());
    return d;
}

private VolumeDTO fromVolume(Volume v) {
    VolumeDTO d = new VolumeDTO(); d.setName(v.getName());
    if (v.getEmptyDir() != null) { EmptyDirVolumeDTO x = new EmptyDirVolumeDTO();
        x.setMedium(v.getEmptyDir().getMedium()); x.setSizeLimit(v.getEmptyDir().getSizeLimit());
        d.setType("emptyDir"); d.setEmptyDir(x); }
    else if (v.getConfigMap() != null) { ConfigMapVolumeDTO x = new ConfigMapVolumeDTO();
        x.setName(v.getConfigMap().getName());
        if (v.getConfigMap().getItems() != null) x.setItems(v.getConfigMap().getItems().stream().map(this::fromKeyToPath).toList());
        d.setType("configMap"); d.setConfigMap(x); }
    else if (v.getSecret() != null) { SecretVolumeDTO x = new SecretVolumeDTO();
        x.setSecretName(v.getSecret().getSecretName());
        if (v.getSecret().getItems() != null) x.setItems(v.getSecret().getItems().stream().map(this::fromKeyToPath).toList());
        d.setType("secret"); d.setSecret(x); }
    else if (v.getPersistentVolumeClaim() != null) { PvcVolumeDTO x = new PvcVolumeDTO();
        x.setClaimName(v.getPersistentVolumeClaim().getClaimName()); d.setType("persistentVolumeClaim"); d.setPersistentVolumeClaim(x); }
    else if (v.getHostPath() != null) { HostPathVolumeDTO x = new HostPathVolumeDTO();
        x.setPath(v.getHostPath().getPath()); x.setType(v.getHostPath().getType()); d.setType("hostPath"); d.setHostPath(x); }
    return d;
}

private KeyToPathDTO fromKeyToPath(KeyToPath i) {
    KeyToPathDTO d = new KeyToPathDTO(); d.setKey(i.getKey()); d.setPath(i.getPath()); d.setMode(i.getMode()); return d;
}

private StrategyDTO fromDeploymentStrategy(DeploymentStrategy s) {
    if (s == null) return null;
    StrategyDTO d = new StrategyDTO(); d.setType(s.getType());
    if (s.getRollingUpdate() != null) { RollingUpdateDTO r = new RollingUpdateDTO();
        r.setMaxSurge(s.getRollingUpdate().getMaxSurge() != null ? s.getRollingUpdate().getMaxSurge().toString() : null);
        r.setMaxUnavailable(s.getRollingUpdate().getMaxUnavailable() != null ? s.getRollingUpdate().getMaxUnavailable().toString() : null);
        d.setRollingUpdate(r); }
    return d;
}

private StrategyDTO fromStatefulSetUpdateStrategy(StatefulSetUpdateStrategy s) {
    if (s == null) return null;
    StrategyDTO d = new StrategyDTO(); d.setType(s.getType());
    if (s.getRollingUpdate() != null && s.getRollingUpdate().getPartition() != null) {
        RollingUpdateDTO r = new RollingUpdateDTO(); r.setPartition(s.getRollingUpdate().getPartition()); d.setRollingUpdate(r); }
    return d;
}

private StrategyDTO fromDaemonSetUpdateStrategy(DaemonSetUpdateStrategy s) {
    if (s == null) return null;
    StrategyDTO d = new StrategyDTO(); d.setType(s.getType());
    if (s.getRollingUpdate() != null && s.getRollingUpdate().getMaxUnavailable() != null) {
        RollingUpdateDTO r = new RollingUpdateDTO();
        r.setMaxUnavailable(s.getRollingUpdate().getMaxUnavailable().toString()); d.setRollingUpdate(r); }
    return d;
}

private java.util.List<PvcTemplateDTO> fromPvcTemplates(java.util.List<PersistentVolumeClaim> list) {
    if (list == null || list.isEmpty()) return null;
    return list.stream().map(t -> { PvcTemplateDTO d = new PvcTemplateDTO();
        d.setName(t.getMetadata() != null ? t.getMetadata().getName() : null);
        if (t.getSpec() != null) {
            d.setAccessModes(t.getSpec().getAccessModes());
            d.setStorageClassName(t.getSpec().getStorageClassName());
            d.setVolumeMode(t.getSpec().getVolumeMode());
            if (t.getSpec().getResources() != null && t.getSpec().getResources().getRequests() != null)
                d.setStorage(t.getSpec().getResources().getRequests().get("storage"));
        } return d; }).toList();
}
```

**Step 3: 编译验证**
Run: `mvn -q -pl k8s-core compile`
Expected: BUILD SUCCESS（converter convert + revert 双向全字段完成）

**Step 4: Commit**
```bash
git add k8s-core/src/main/java/com/coding/k8score/converter/impl/workload/WorkloadConverter.java
git commit -m "feat(workload): converter full-field K8s->DTO backfill"
```

### Task 6: WorkloadOperations.update 升级为整 spec 替换

**Files:**
- Modify: `k8s-core/src/main/java/com/coding/k8score/operations/workload/WorkloadOperations.java`（仅 `update()` 方法；create/list/get/delete/yaml 不动）

**Interfaces:**
- Consumes: Task 4/5 的 converter（convert* 现产出完整 spec，revert* 回填完整 podTemplate）。

**Step 1: 重写 `update()`。** 原则：converter 重建对象 → **保留 existing 的 `spec.selector`（Dep/STS 不可变）+ name/namespace + STS serviceName** → update。DaemonSet 忽略 replicas（converter 本就不设）：

```java
@Override
public WorkloadDTO update(WorkloadDTO workload) {
    String namespace = workload.getNamespace();
    String name = workload.getName();
    String kind = normalizeKind(workload.getKind());
    switch (kind) {
        case WorkloadConverter.KIND_DEPLOYMENT -> {
            Deployment existing = client.apps().deployments().inNamespace(namespace).withName(name).get();
            if (existing == null) throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
            Deployment updated = converter.convertDeployment(workload);
            keepIdentity(updated, existing.getMetadata(), existing.getSpec() != null ? existing.getSpec().getSelector() : null);
            return converter.revert(client.apps().deployments().inNamespace(namespace).resource(updated).update());
        }
        case WorkloadConverter.KIND_STATEFULSET -> {
            StatefulSet existing = client.apps().statefulSets().inNamespace(namespace).withName(name).get();
            if (existing == null) throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
            StatefulSet updated = converter.convertStatefulSet(workload);
            keepIdentity(updated, existing.getMetadata(), existing.getSpec() != null ? existing.getSpec().getSelector() : null);
            //serviceName 不可变：existing 有则沿用，避免用户未改时被重置为 name
            if (existing.getSpec() != null && hasText(existing.getSpec().getServiceName()))
                updated.getSpec().setServiceName(existing.getSpec().getServiceName());
            return converter.revert(client.apps().statefulSets().inNamespace(namespace).resource(updated).update());
        }
        default -> {
            DaemonSet existing = client.apps().daemonSets().inNamespace(namespace).withName(name).get();
            if (existing == null) throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
            DaemonSet updated = converter.convertDaemonSet(workload);
            keepIdentity(updated, existing.getMetadata(), existing.getSpec() != null ? existing.getSpec().getSelector() : null);
            return converter.revert(client.apps().daemonSets().inNamespace(namespace).resource(updated).update());
        }
    }
}

/**保留不可变身份：name/namespace + spec.selector（Deployment/StatefulSet selector 创建后不可改） */
private <T> void keepIdentity(T updated, io.fabric8.kubernetes.api.model.ObjectMeta existingMeta,
                              io.fabric8.kubernetes.api.model.LabelSelector existingSelector) {
    //泛型不便直接取 metadata/spec，改用具体类型重载更清晰——见下方说明
}
```

> **实现说明（避免泛型擦除取不到 metadata/spec）**：`keepIdentity` 用泛型无法访问 `getMetadata()/getSpec()`。改为**每个 case 内联三行**（更清晰、无擦除问题），删除上面的泛型 helper：
> ```java
> updated.getMetadata().setName(existing.getMetadata().getName());
> updated.getMetadata().setNamespace(existing.getMetadata().getNamespace());
> if (existingSelector != null) updated.getSpec().setSelector(existingSelector);
> ```
> 在三个 case 中各自替换 `keepIdentity(...)` 调用为这三行（STS case 额外保留 serviceName，见上）。

**Step 2: 编译验证**
Run: `mvn -q -pl k8s-core compile`
Expected: BUILD SUCCESS

**Step 3: Commit**
```bash
git add k8s-core/src/main/java/com/coding/k8score/operations/workload/WorkloadOperations.java
git commit -m "feat(workload): update replaces full spec, preserves immutable selector/name"
```

---

## Phase C — platform-api（业务层：校验 + 编排）

验证门 = `mvn -q -pl platform-api compile`。**所有 §5 硬约束在此实现**，失败抛 `CloudPlatformException(EnumResponseType.ERROR, "中文")`，不打到 k8s-server。kind 从 `dto.getKind()` 读取（小写）。

### Task 7: K8sQuantity 严格解析工具

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/services/validation/K8sQuantity.java`

**Interfaces:**
- Produces: `public static BigDecimal parse(String)` —— 把 K8s quantity（整数 + SI 后缀 m/k/M/G/T/P/E + 二进制 Ki/Mi/Gi/Ti/Pi/Ei，无前缀=1）转为基础单位 BigDecimal；无法解析抛 `CloudPlatformException(ERROR, "无法解析资源量: <raw>")`。供 B4 比较用。

**Step 1: 写工具类（final，私有构造）：**

```java
package com.coding.platformapi.services.validation;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;

import java.math.BigDecimal;
import java.util.Map;

/**K8s quantity 严格解析：整数/小数 + SI(m/k/M/G/T/P/E) / 二进制(Ki/Mi/Gi/Ti/Pi/Ei) 后缀 → 基础单位 BigDecimal */
public final class K8sQuantity {

    private static final Map<String, Integer> SI = Map.of("m", -3, "k", 3, "M", 6, "G", 9, "T", 12, "P", 15, "E", 18);
    private static final Map<String, Integer> BIN = Map.of("Ki", 10, "Mi", 20, "Gi", 30, "Ti", 40, "Pi", 50, "Ei", 60);

    private K8sQuantity() {}

    public static BigDecimal parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "资源量不能为空");
        }
        String s = raw.trim();
        int i = 0;
        boolean neg = false;
        if (s.charAt(0) == '-') { neg = true; i++; }
        else if (s.charAt(0) == '+') { i++; }
        int start = i;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
        if (i == start) throw new CloudPlatformException(EnumResponseType.ERROR, "无法解析资源量: " + raw);
        BigDecimal base = new BigDecimal(s.substring(start, i));
        String suffix = s.substring(i).trim();
        BigDecimal factor;
        if (suffix.isEmpty()) factor = BigDecimal.ONE;
        else if (SI.containsKey(suffix)) factor = BigDecimal.TEN.pow(SI.get(suffix));
        else if (BIN.containsKey(suffix)) factor = BigDecimal.valueOf(2).pow(BIN.get(suffix));
        else throw new CloudPlatformException(EnumResponseType.ERROR, "无法解析资源量: " + raw);
        return neg ? base.multiply(factor).negate() : base.multiply(factor);
    }

    /**比较 a ≤ b；任一无法解析抛错（由 parse 抛出） */
    public static int compare(String a, String b) {
        return parse(a).compareTo(parse(b));
    }
}
```

**Step 2: 编译验证**
Run: `mvn -q -pl platform-api compile`
Expected: BUILD SUCCESS

**Step 3: Commit**
```bash
git add platform-api/src/main/java/com/coding/platformapi/services/validation/K8sQuantity.java
git commit -m "feat(workload): strict K8s quantity parser for resource validation"
```

### Task 8: WorkloadValidator — A 类（互斥/条件）+ B 类（取值约束，含 B4）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/services/validation/WorkloadValidator.java`

**Interfaces:**
- Produces: `@Component WorkloadValidator`，方法 `public void validate(WorkloadDTO dto)`（跑全部 §5 硬约束；本任务实现 A1–A5、B1–B7，C 类在 Task 9 追加到同类）。kind 感知（读 `dto.getKind()` 小写）。忽略类字段（A4/A5/B7）就地置 null。

**Step 1: 建类 + validate 主入口 + A/B 规则：**

```java
package com.coding.platformapi.services.validation;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**工作负载业务校验（§5 硬约束，kind 感知）。失败抛 CloudPlatformException(中文)，不打到 k8s-server */
@Component
public class WorkloadValidator {

    private static final String KIND_DEPLOYMENT = "deployment";
    private static final String KIND_STATEFULSET = "statefulset";
    private static final String KIND_DAEMONSET = "daemonset";
    private static final Set<String> VALID_KINDS = Set.of(KIND_DEPLOYMENT, KIND_STATEFULSET, KIND_DAEMONSET);

    public void validate(WorkloadDTO dto) {
        if (dto == null) throw err("工作负载不能为空");
        String kind = dto.getKind() != null ? dto.getKind().trim().toLowerCase() : "";
        if (!VALID_KINDS.contains(kind)) throw err("不支持的工作负载类型: " + dto.getKind());
        PodSpecDTO spec = podSpecOf(dto);

        validateA(spec, kind, dto);   // A 互斥/条件（含忽略类字段就地置 null）
        validateB(spec, kind, dto);   // B 取值约束（含 B4 严格 quantity）
        validateC(spec, kind, dto);   // C 唯一性/引用完整性（Task 9 实现）
    }

    private PodSpecDTO podSpecOf(WorkloadDTO dto) {
        if (dto.getPodTemplate() == null || dto.getPodTemplate().getSpec() == null)
            throw err("缺少 Pod 模板（至少需要一个容器）");
        return dto.getPodTemplate().getSpec();
    }

    // ---------- A 互斥 / 条件字段 ----------
    private void validateA(PodSpecDTO spec, String kind, WorkloadDTO dto) {
        for (ContainerDTO c : allContainers(spec)) {
            String ctx = "容器「" + c.getName() + "」";
            // A1 init 容器禁止 lifecycle / 三种探针
            if (isInit(spec, c) && (c.getLifecycle() != null || c.getLivenessProbe() != null
                    || c.getReadinessProbe() != null || c.getStartupProbe() != null))
                throw err(ctx + "（初始化容器）不允许配置生命周期或探针");
            // A2 probe handler 恰好一个
            checkExactlyOneHandler(c.getLivenessProbe(), ctx + " livenessProbe", true);
            checkExactlyOneHandler(c.getReadinessProbe(), ctx + " readinessProbe", true);
            checkExactlyOneHandler(c.getStartupProbe(), ctx + " startupProbe", true);
            // A3 lifecycle 钩子 handler 恰好一个
            if (c.getLifecycle() != null) {
                checkExactlyOneHook(c.getLifecycle().getPostStart(), ctx + " postStart");
                checkExactlyOneHook(c.getLifecycle().getPreStop(), ctx + " preStop");
            }
        }
        // A4 strategy 非滚动更新时丢弃 rollingUpdate；A5 daemonset 丢弃 maxSurge
        StrategyDTO st = dto.getStrategy();
        if (st != null && st.getRollingUpdate() != null) {
            boolean nonRolling = "Recreate".equals(st.getType()) || "OnDelete".equals(st.getType());
            if (nonRolling) { st.setRollingUpdate(null); return; }
            if (KIND_DAEMONSET.equals(kind)) st.getRollingUpdate().setMaxSurge(null);
        }
    }

    private void checkExactlyOneHandler(ProbeDTO p, String ctx, boolean withTcp) {
        if (p == null) return;
        int n = (p.getHttpGet() != null ? 1 : 0) + (p.getTcpSocket() != null ? 1 : 0) + (p.getExec() != null ? 1 : 0);
        if (n != 1) throw err(ctx + " 必须且只能配置一种探测方式（httpGet/tcpSocket/exec）");
    }

    private void checkExactlyOneHook(HandlerDTO h, String ctx) {
        if (h == null) return;
        int n = (h.getExec() != null ? 1 : 0) + (h.getHttpGet() != null ? 1 : 0) + (h.getSleep() != null ? 1 : 0);
        if (n != 1) throw err(ctx + " 必须且只能配置一种动作（exec/httpGet/sleep）");
    }

    // ---------- B 取值约束 ----------
    private void validateB(PodSpecDTO spec, String kind, WorkloadDTO dto) {
        // B1 restartPolicy 仅 Always
        if (StringUtils.hasText(spec.getRestartPolicy()) && !"Always".equals(spec.getRestartPolicy()))
            throw err("工作负载 restartPolicy 仅支持 Always");
        for (ContainerDTO c : allContainers(spec)) {
            String ctx = "容器「" + c.getName() + "」";
            // B2 liveness/startup successThreshold 必须=1
            if (c.getLivenessProbe() != null && c.getLivenessProbe().getSuccessThreshold() != null
                    && c.getLivenessProbe().getSuccessThreshold() != 1)
                throw err(ctx + " livenessProbe.successThreshold 必须为 1");
            if (c.getStartupProbe() != null && c.getStartupProbe().getSuccessThreshold() != null
                    && c.getStartupProbe().getSuccessThreshold() != 1)
                throw err(ctx + " startupProbe.successThreshold 必须为 1");
            // B3 period/timeout ≥ 1
            for (Map.Entry<String, ProbeDTO> e : probeEntries(c).entrySet()) {
                ProbeDTO p = e.getValue();
                if (p == null) continue;
                if (p.getPeriodSeconds() != null && p.getPeriodSeconds() < 1)
                    throw err(ctx + " " + e.getKey() + ".periodSeconds 最小为 1");
                if (p.getTimeoutSeconds() != null && p.getTimeoutSeconds() < 1)
                    throw err(ctx + " " + e.getKey() + ".timeoutSeconds 最小为 1");
            }
            // B4 resources requests ≤ limits（严格 quantity）
            checkResources(ctx, c.getResources());
        }
        // B5 deployment maxSurge 与 maxUnavailable 不能同时为 0
        if (KIND_DEPLOYMENT.equals(kind) && dto.getStrategy() != null && "RollingUpdate".equals(dto.getStrategy().getType())) {
            RollingUpdateDTO ru = dto.getStrategy().getRollingUpdate();
            if (ru != null && isZero(ru.getMaxSurge()) && isZero(ru.getMaxUnavailable()))
                throw err("Deployment 滚动更新 maxSurge 与 maxUnavailable 不能同时为 0");
        }
        // B6/B7 toleration
        if (spec.getTolerations() != null) {
            for (TolerationDTO t : spec.getTolerations()) {
                boolean keyBlank = !StringUtils.hasText(t.getKey());
                String op = StringUtils.hasText(t.getOperator()) ? t.getOperator() : "Equal";
                if (keyBlank && !"Exists".equals(op))
                    throw err("toleration 未填 key 时 operator 必须为 Exists");
                if ("Exists".equals(op) && StringUtils.hasText(t.getValue()))
                    throw err("toleration operator=Exists 时不能填 value");
                if (!"Exists".equals(op) && !StringUtils.hasText(t.getValue()))
                    throw err("toleration operator=" + op + " 时 value 必填");
                // B7 tolerationSeconds 仅 NoExecute 有效，否则丢弃
                if (t.getTolerationSeconds() != null && !"NoExecute".equals(t.getEffect()))
                    t.setTolerationSeconds(null);
            }
        }
    }

    private void checkResources(String ctx, ResourcesDTO r) {
        if (r == null || r.getRequests() == null || r.getRequests().isEmpty()) return;
        Map<String, String> lim = r.getLimits();
        for (Map.Entry<String, String> e : r.getRequests().entrySet()) {
            if (lim == null || !lim.containsKey(e.getKey())) continue; // 仅两者都有才比较
            if (K8sQuantity.compare(e.getValue(), lim.get(e.getKey())) > 0)
                throw err(ctx + " 资源「" + e.getKey() + "」的 requests(" + e.getValue()
                        + ") 不能大于 limits(" + lim.get(e.getKey()) + ")");
        }
    }

    // ---------- 工具 ----------
    private List<ContainerDTO> allContainers(PodSpecDTO spec) {
        List<ContainerDTO> all = new ArrayList<>();
        if (spec.getContainers() != null) all.addAll(spec.getContainers());
        return all;
    }

    private boolean isInit(PodSpecDTO spec, ContainerDTO c) {
        return spec.getInitContainers() != null && spec.getInitContainers().contains(c);
    }

    private Map<String, ProbeDTO> probeEntries(ContainerDTO c) {
        Map<String, ProbeDTO> m = new java.util.LinkedHashMap<>();
        m.put("livenessProbe", c.getLivenessProbe());
        m.put("readinessProbe", c.getReadinessProbe());
        m.put("startupProbe", c.getStartupProbe());
        return m;
    }

    private boolean isZero(String quantity) {
        if (!StringUtils.hasText(quantity)) return false; // 未填不算 0（K8s 有默认值）
        try { return K8sQuantity.parse(quantity).signum() == 0; }
        catch (CloudPlatformException ex) { throw err("无法解析数量: " + quantity); }
    }

    private CloudPlatformException err(String msg) {
        return new CloudPlatformException(EnumResponseType.ERROR, msg);
    }
}
```

> **注意**：本任务引用的 `validateC(...)` 尚未实现（Task 9）。为使本任务可独立编译，先在类底部加占位实现 `private void validateC(PodSpecDTO spec, String kind, WorkloadDTO dto) { /* Task 9 */ }`，Task 9 替换为真实逻辑。

**Step 2: 编译验证**
Run: `mvn -q -pl platform-api compile`
Expected: BUILD SUCCESS

**Step 3: Commit**
```bash
git add platform-api/src/main/java/com/coding/platformapi/services/validation/WorkloadValidator.java
git commit -m "feat(workload): validator A/B rules (mutual-exclusion + value constraints incl B4)"
```

### Task 9: WorkloadValidator — C 类（唯一性 / 引用完整性，含 C4）

**Files:**
- Modify: `.../services/validation/WorkloadValidator.java`（替换 `validateC` 占位实现；追加辅助方法）

**Interfaces:**
- Consumes: Task 8 的类结构、`err()`、`allContainers()`。

**Step 1: 用真实逻辑替换 `validateC` 占位，并加辅助：**

```java
    // ---------- C 唯一性 / 引用完整性 ----------
    private void validateC(PodSpecDTO spec, String kind, WorkloadDTO dto) {
        List<ContainerDTO> main = spec.getContainers() != null ? spec.getContainers() : List.of();
        List<ContainerDTO> init = spec.getInitContainers() != null ? spec.getInitContainers() : List.of();

        // C1 至少一个容器
        if (main.isEmpty()) throw err("至少需要一个容器");

        // C2 所有容器名（init+普通）全局唯一 + DNS_LABEL
        Set<String> seen = new HashSet<>();
        for (ContainerDTO c : main) checkName(c, "容器", seen);
        for (ContainerDTO c : init) checkName(c, "初始化容器", seen);

        // C3 volumeMounts[].name 必须引用已定义 volumes[].name
        Set<String> volNames = new HashSet<>();
        if (spec.getVolumes() != null) for (VolumeDTO v : spec.getVolumes()) if (v.getName() != null) volNames.add(v.getName());
        for (ContainerDTO c : allContainers(spec)) {
            if (c.getVolumeMounts() == null) continue;
            for (VolumeMountDTO m : c.getVolumeMounts()) {
                if (!StringUtils.hasText(m.getName())) throw err("容器「" + c.getName() + "」存在未命名卷挂载");
                if (!volNames.contains(m.getName()))
                    throw err("容器「" + c.getName() + "」挂载的卷「" + m.getName() + "」未在 volumes 中定义");
            }
        }

        // C4 STS：每个 volumeClaimTemplate.name 必须被至少一个容器的 volumeMount 同名引用
        if (KIND_STATEFULSET.equals(kind) && dto.getVolumeClaimTemplates() != null) {
            Set<String> mounted = new HashSet<>();
            for (ContainerDTO c : allContainers(spec)) {
                if (c.getVolumeMounts() == null) continue;
                for (VolumeMountDTO m : c.getVolumeMounts()) if (m.getName() != null) mounted.add(m.getName());
            }
            for (PvcTemplateDTO t : dto.getVolumeClaimTemplates()) {
                if (!mounted.contains(t.getName()))
                    throw err("volumeClaimTemplate「" + t.getName() + "」需至少一个容器以同名 volumeMount 引用");
            }
        }
    }

    private void checkName(ContainerDTO c, String label, Set<String> seen) {
        String n = c.getName();
        if (!StringUtils.hasText(n)) throw err(label + " 名称不能为空");
        if (!n.matches("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$"))
            throw err(label + " 名称「" + n + "」需符合 DNS_LABEL（小写字母/数字/-，字母或数字开头结尾）");
        if (!seen.add(n)) throw err("容器名重复：「" + n + "」（所有容器名须全局唯一）");
    }
```

**Step 2: 编译验证**
Run: `mvn -q -pl platform-api compile`
Expected: BUILD SUCCESS（A/B/C 全部硬约束就位）

**Step 3: Commit**
```bash
git add platform-api/src/main/java/com/coding/platformapi/services/validation/WorkloadValidator.java
git commit -m "feat(workload): validator C rules (uniqueness + reference integrity incl STS C4)"
```

### Task 10: WorkloadService + Controller 委托

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/services/WorkloadService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/controllers/resource/WorkloadController.java`（create/update 改委托 service；list/get/yaml/delete 不变）

**Interfaces:**
- Consumes: `K8sResourceClient`、`WorkloadValidator`。
- Produces: `WorkloadService.create(WorkloadDTO)` / `.update(WorkloadDTO)`（先 validate 再透传）。

**Step 1: 新建 WorkloadService：**

```java
package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.WorkloadDTO;
import com.coding.platformapi.k8s.K8sResourceClient;
import com.coding.platformapi.services.validation.WorkloadValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**工作负载业务层：create/update 先跑 §5 硬约束，通过再透传 k8s-server（list/get/yaml/delete 无业务规则，直连 client） */
@Service
@RequiredArgsConstructor
public class WorkloadService {

    private final K8sResourceClient k8s;
    private final WorkloadValidator validator;

    public WorkloadDTO create(WorkloadDTO dto) {
        validator.validate(dto);
        return k8s.create(dto);
    }

    public WorkloadDTO update(WorkloadDTO dto) {
        validator.validate(dto);
        return k8s.update(dto);
    }
}
```

**Step 2: Controller 注入 service，create/update 委托。** 改 `WorkloadController`：加字段 `private final WorkloadService workloadService;`（`K8sResourceClient k8s` 保留给 list/get/yaml/delete）；`create()` 方法体改为 `return new ResponseData<>(workloadService.create(body));`；`update()` 保持 `body.setName(name);` 后改 `return new ResponseData<>(workloadService.update(body));`。更新两个方法的 `@Operation(summary=...)` 文案（去掉「基础表单仅伸缩副本数」）。

**Step 3: 编译验证**
Run: `mvn -q -pl platform-api compile`
Expected: BUILD SUCCESS（后端全部完成）

**Step 4: Commit**
```bash
git add platform-api/src/main/java/com/coding/platformapi/services/WorkloadService.java \
        platform-api/src/main/java/com/coding/platformapi/controllers/resource/WorkloadController.java
git commit -m "feat(workload): WorkloadService validation gate + controller delegation"
```

---

## Phase D — platform-web（整页编辑器）

验证门 = `npm run type-check` + `npm run build`（在 `platform-web/` 下）。组件放 `src/components/workload/`。所有子组件用 `v-model`（`modelValue` prop + `update:modelValue` emit）。列表型编辑器的 add/remove 统一模式：`arr.push(空行)` / `arr.splice(i,1)`，仅剩一行时禁用删除。

### Task 11: types/workload.ts（镜像 DTO 的 TS interface）

**Files:**
- Create: `platform-web/src/types/workload.ts`

**Interfaces:**
- Produces: 下方全部 interface（后续组件/页面 import）。字段名与后端 DTO **逐字一致**。

**Step 1: 写完整类型文件：**

```ts
/** 工作负载编辑器类型 —— 镜像 platform-common WorkloadDTO（字段名逐字一致） */

export type WorkloadKind = 'deployment' | 'statefulset' | 'daemonset'

export interface EnvVar { name: string; value?: string | null; valueFrom?: ValueFrom | null }
export interface ValueFrom {
  configMapKeyRef?: { name: string; key: string; optional?: boolean } | null
  secretKeyRef?: { name: string; key: string; optional?: boolean } | null
  fieldRef?: { apiVersion?: string; fieldPath: string } | null
}
export interface EnvFrom { prefix?: string; configMapRef?: { name: string; optional?: boolean } | null; secretRef?: { name: string; optional?: boolean } | null }
export interface ContainerPort { containerPort?: number | null; protocol?: string | null; name?: string | null }
export interface Resources { limits?: Record<string, string>; requests?: Record<string, string> }
export interface HttpGetAction { port?: string; path?: string; scheme?: string }
export interface TCPSocketAction { port?: string }
export interface ExecAction { command: string[] }
export interface SleepAction { seconds?: number | null }
export interface Probe { httpGet?: HttpGetAction | null; tcpSocket?: TCPSocketAction | null; exec?: ExecAction | null; initialDelaySeconds?: number | null; periodSeconds?: number | null; timeoutSeconds?: number | null; successThreshold?: number | null; failureThreshold?: number | null }
export interface LifecycleHandler { exec?: ExecAction | null; httpGet?: HttpGetAction | null; sleep?: SleepAction | null }
export interface Lifecycle { postStart?: LifecycleHandler | null; preStop?: LifecycleHandler | null }
export interface VolumeMount { name: string; mountPath: string; readOnly?: boolean; subPath?: string | null }

export interface ContainerDef {
  name: string; image?: string | null
  command?: string[] | null; args?: string[] | null; workingDir?: string | null
  imagePullPolicy?: string | null
  envs?: EnvVar[] | null; envFrom?: EnvFrom[] | null; ports?: ContainerPort[] | null
  resources?: Resources | null; lifecycle?: Lifecycle | null
  livenessProbe?: Probe | null; readinessProbe?: Probe | null; startupProbe?: Probe | null
  volumeMounts?: VolumeMount[] | null
}

export interface NodeSelectorRequirement { key: string; operator: string; values: string[] }
export interface NodeSelectorTerm { matchExpressions?: NodeSelectorRequirement[] | null; matchFields?: NodeSelectorRequirement[] | null }
export interface NodeAffinity { required?: { nodeSelectorTerms: NodeSelectorTerm[] } | null; preferred?: { weight: number; preference: NodeSelectorTerm }[] | null }
export interface PodAffinityTerm { namespaces?: string[] | null; topologyKey?: string | null; matchLabels?: Record<string, string> | null }
export interface PodAntiAffinity { required?: PodAffinityTerm[] | null; preferred?: { weight: number; podAffinityTerm: PodAffinityTerm }[] | null }
export interface Affinity { nodeAffinity?: NodeAffinity | null; podAntiAffinity?: PodAntiAffinity | null }

export interface Toleration { key?: string | null; operator?: string | null; value?: string | null; effect?: string | null; tolerationSeconds?: number | null }
export interface KeyToPath { key: string; path: string; mode?: number | null }
export interface VolumeDef {
  name: string; type: 'emptyDir' | 'configMap' | 'secret' | 'persistentVolumeClaim' | 'hostPath'
  emptyDir?: { medium?: string | null; sizeLimit?: string | null } | null
  configMap?: { name: string; items?: KeyToPath[] | null } | null
  secret?: { secretName: string; items?: KeyToPath[] | null } | null
  persistentVolumeClaim?: { claimName: string } | null
  hostPath?: { path: string; type?: string | null } | null
}

export interface RollingUpdate { maxSurge?: string | null; maxUnavailable?: string | null; partition?: number | null }
export interface Strategy { type?: string | null; rollingUpdate?: RollingUpdate | null }
export interface PvcTemplate { name: string; accessModes: string[]; storage: string; storageClassName?: string | null; volumeMode?: string | null }

export interface PodSpec {
  containers: ContainerDef[]
  initContainers?: ContainerDef[] | null
  restartPolicy?: string | null
  serviceAccountName?: string | null
  nodeName?: string | null
  nodeSelector?: Record<string, string> | null
  affinity?: Affinity | null
  tolerations?: Toleration[] | null
  volumes?: VolumeDef[] | null
  imagePullSecrets?: { name: string }[] | null
}

export interface WorkloadDetail {
  kind: WorkloadKind
  name: string
  namespace: string
  labels?: Record<string, string> | null
  description?: string | null
  replicas?: number | null
  readyReplicas?: number | null
  serviceName?: string | null
  strategy?: Strategy | null
  volumeClaimTemplates?: PvcTemplate[] | null
  podTemplate?: { labels?: Record<string, string> | null; annotations?: Record<string, any> | null; spec: PodSpec } | null
  images?: string[] | null
  ports?: ContainerPort[] | null
  creationTime?: string | null
}
```

**Step 2: type-check**
Run（在 `platform-web/`）: `npm run type-check`
Expected: 无错误（新文件自洽）

**Step 3: Commit**
```bash
git add platform-web/src/types/workload.ts
git commit -m "feat(workload): frontend TS types mirroring WorkloadDTO"
```

### Task 12: 路由 + api 类型替换

**Files:**
- Modify: `platform-web/src/router/index.ts`（加编辑器路由）
- Modify: `platform-web/src/api/index.ts`（`workloadApi` 泛型换成更完整类型；六操作签名不变）

**Step 1: router 加路由。** 在资源管理区块内、`resources/workloads` 之后加：
```ts
{ path: 'resources/workloads/editor', name: 'workloadEditor', component: () => import('@/views/resource/WorkloadEditorView.vue'), meta: { title: '工作负载编辑', group: '资源管理', context: 'full' } },
```

**Step 2: api 类型替换。** `api/index.ts` 顶部 import 加 `import type { WorkloadDetail } from '@/types/workload'`；把 `export const workloadApi = makeResourceApi<K8sWorkload>('workloads')` 改为 `makeResourceApi<WorkloadDetail>('workloads')`。其余资源不动。

**Step 3: type-check**
Run（在 `platform-web/`）: `npm run type-check`
Expected: 无错误

**Step 4: Commit**
```bash
git add platform-web/src/router/index.ts platform-web/src/api/index.ts
git commit -m "feat(workload): editor route + api type to WorkloadDetail"
```

### Task 13: 叶子编辑器 — PortEditor（列表型 exemplar）+ LabelEditor + EnvFromEditor

**Files:**
- Create: `src/components/workload/PortEditor.vue`、`LabelEditor.vue`、`EnvFromEditor.vue`

**Interfaces:**
- `PortEditor`：`v-model: ContainerPort[]`。
- `LabelEditor`：`v-model: Record<string,string>`（键值对增删）。
- `EnvFromEditor`：`v-model: EnvFrom[]`。
- 本任务确立「列表型编辑器」模板，后续 Port/Env/Toleration/VolumeMount/PvcTemplate 等复用同一 add/remove 结构。

**Step 1: PortEditor.vue（完整，作为列表型 exemplar）：**

```vue
<script setup lang="ts">
import type { ContainerPort } from '@/types/workload'
const model = defineModel<ContainerPort[]>()
const PROTOCOLS = ['TCP', 'UDP', 'SCTP']
function add(): void { model.value.push({ containerPort: null, protocol: 'TCP', name: '' }) }
function remove(i: number): void { model.value.splice(i, 1) }
</script>

<template>
  <div class="kv-editor">
    <div v-for="(p, i) in model" :key="i" class="kv-row">
      <el-input-number v-model="p.containerPort" :min="1" :max="65535" controls-position="right" placeholder="端口" style="width: 140px" />
      <el-select v-model="p.protocol" style="width: 100px">
        <el-option v-for="x in PROTOCOLS" :key="x" :label="x" :value="x" />
      </el-select>
      <el-input v-model="p.name" placeholder="名称（可选）" style="width: 160px" />
      <el-button link type="danger" :disabled="model.length <= 1" @click="remove(i)">删除</el-button>
    </div>
    <el-button class="add-row-btn" plain @click="add">+ 添加端口</el-button>
  </div>
</template>

<style scoped>
.kv-editor { width: 100% }
.kv-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center }
.add-row-btn { width: 100% }
</style>
```

**Step 2: LabelEditor.vue（键值对，`v-model: Record<string,string>`）。** 内部用 `ref<[string,string][]>` 双向映射到 object：
- script：`const model = defineModel<Record<string,string>>()`；`watch(model, obj => rows.value = Object.entries(obj ?? {}), { immediate:true })`；每行改 key/value 时 `emitUpdate()`（重建 object，空 key 跳过）；`add()` push `['','']`、`remove(i)` splice。
- template：每行两个 `el-input`（key / value）+ 删除按钮（无最小行数限制，标签可全删）+ 「+ 添加标签」。结构同 PortEditor 的 `.kv-row`/`.add-row-btn`。

**Step 3: EnvFromEditor.vue（`v-model: EnvFrom[]`）。** 每行：`prefix` 输入 + 来源 radio（configMap / secret）+ 对应 `name` 输入 + `optional` 开关；radio 切换时清空另一来源字段。add/remove 同 PortEditor 模式。

**Step 4: type-check**
Run（在 `platform-web/`）: `npm run type-check`
Expected: 无错误

**Step 5: Commit**
```bash
git add platform-web/src/components/workload/PortEditor.vue \
        platform-web/src/components/workload/LabelEditor.vue \
        platform-web/src/components/workload/EnvFromEditor.vue
git commit -m "feat(workload): leaf editors Port/Label/EnvFrom"
```

### Task 14: EnvEditor（env + valueFrom）

**Files:**
- Create: `src/components/workload/EnvEditor.vue`

**Interfaces:**
- Consumes: `EnvVar`、`ValueFrom`。
- Produces: `v-model: EnvVar[]`。

**Step 1: 实现。** `v-model: EnvVar[]`。每行：`name` 输入 + 值来源 radio（`value` 字面量 / `configMapKeyRef` / `secretKeyRef` / `fieldRef`）；按来源显示对应字段：
- value：`el-input` 绑 `value`
- configMapKeyRef：`name`+`key` 输入 + `optional` 开关
- secretKeyRef：`name`+`key` 输入 + `optional` 开关
- fieldRef：`fieldPath` 输入（下拉常用 metadata.name/namespace/uid/ip + 自定义）+ `apiVersion` 输入（默认 v1，可空）
切换来源时清空其它来源对象、保留 name。add/remove 同列表型模式（env 无最小行数）。

**Step 2: type-check** — Run: `npm run type-check` → 无错误
**Step 3: Commit**
```bash
git add platform-web/src/components/workload/EnvEditor.vue
git commit -m "feat(workload): EnvEditor with valueFrom sources"
```

### Task 15: ResourcesEditor（B4 实时校验）

**Files:**
- Create: `src/components/workload/ResourcesEditor.vue`
- Create: `platform-web/src/utils/quantity.ts`（前端 quantity 解析，与后端 K8sQuantity 同规则）

**Interfaces:**
- Consumes: `Resources`。
- Produces: `v-model: Resources`；当某资源 requests>limits 时 emit `invalid` 状态供父级阻止提交（用 `defineModel` + 一个 `const valid = computed(...)`，父级读 `ref` 或组件暴露）。

**Step 1: utils/quantity.ts（与后端同规则）：**
```ts
const SI: Record<string, number> = { m: -3, k: 3, M: 6, G: 9, T: 12, P: 15, E: 18 }
const BIN: Record<string, number> = { Ki: 10, Mi: 20, Gi: 30, Ti: 40, Pi: 50, Ei: 60 }
/**K8s quantity → 基础单位数值；无法解析返回 NaN */
export function parseQuantity(raw?: string | null): number {
  if (raw == null) return NaN
  const s = raw.trim()
  if (!s) return NaN
  let i = 0; let neg = false
  if (s[0] === '-') { neg = true; i++ } else if (s[0] === '+') i++
  const start = i
  while (i < s.length && (/[0-9.]/.test(s[i]))) i++
  if (i === start) return NaN
  const base = Number(s.slice(start, i))
  if (Number.isNaN(base)) return NaN
  const suffix = s.slice(i).trim()
  let factor = 1
  if (suffix) {
    if (SI[suffix] != null) factor = 10 ** SI[suffix]
    else if (BIN[suffix] != null) factor = 2 ** BIN[suffix]
    else return NaN
  }
  return neg ? -base * factor : base * factor
}
```

**Step 2: ResourcesEditor.vue。** `v-model: Resources`。UI：两列（requests / limits）× 固定资源行（cpu、memory）+ 可加自定义资源名。每格 `el-input`（placeholder 如 cpu `100m`/`0.5`，memory `128Mi`/`1Gi`）。
- B4 实时校验：`const bad = computed(() => { for each key in requests: if limits[key] present && parseQuantity(req)>parseQuantity(lim) → true; also NaN check })`。
- 当 `bad` 为 true：对应行标红（`:class="{ 'is-error': ... }"` + 底部红色提示「requests 不能大于 limits」），并 `defineExpose({ isValid: () => !bad.value })`；父级提交前调用。
- 同时暴露解析失败提示（NaN → 「无法解析该数量」）。

**Step 3: type-check** — Run: `npm run type-check` → 无错误
**Step 4: Commit**
```bash
git add platform-web/src/components/workload/ResourcesEditor.vue platform-web/src/utils/quantity.ts
git commit -m "feat(workload): ResourcesEditor with strict B4 live validation"
```

### Task 16: ProbeEditor（handler radio）+ LifecycleEditor

**Files:**
- Create: `src/components/workload/ProbeEditor.vue`、`LifecycleEditor.vue`

**Interfaces:**
- `ProbeEditor`：`v-model: Probe` + prop `kind: 'liveness'|'readiness'|'startup'`（liveness/startup 时 successThreshold 禁用固定=1）。
- `LifecycleEditor`：`v-model: Lifecycle`（postStart/preStop 两个 HandlerDTO，各自 exec/httpGet/sleep radio）。

**Step 1: ProbeEditor.vue。** `defineModel<Probe>()` + `const props = defineProps<{ kind: string }>()`。
- handler 类型 radio：无 / httpGet / tcpSocket / exec（A2：同时只一个；切换清空其它）。
- httpGet：port（输入，可命名端口）+ path + scheme（HTTP/HTTPS）。
- tcpSocket：port。
- exec：command（多行/逗号分隔 → 数组，用 `el-select multiple` 或简单 textarea 按行切）。
- 数字项：initialDelaySeconds / periodSeconds(min=1) / timeoutSeconds(min=1) / successThreshold（`kind!=='readiness'` 时禁用且强制 1）/ failureThreshold。
- 空 Probe（无 handler）视为未配置，父级据此不提交该探针。

**Step 2: LifecycleEditor.vue。** `defineModel<Lifecycle>()`。postStart / preStop 各一块：动作 radio（无/exec/httpGet/sleep）+ 对应字段（exec=command；httpGet=port/path/scheme；sleep=seconds）。切换清空其它。

**Step 3: type-check** — Run: `npm run type-check` → 无错误
**Step 4: Commit**
```bash
git add platform-web/src/components/workload/ProbeEditor.vue platform-web/src/components/workload/LifecycleEditor.vue
git commit -m "feat(workload): ProbeEditor (handler radio) + LifecycleEditor"
```

### Task 17: VolumeMountEditor + VolumeEditor（type 判别）

**Files:**
- Create: `src/components/workload/VolumeMountEditor.vue`、`VolumeEditor.vue`

**Interfaces:**
- `VolumeMountEditor`：`v-model: VolumeMount[]` + prop `volumeNames: string[]`（挂载下拉只列已定义 volume，C3）。
- `VolumeEditor`：`v-model: VolumeDef[]`。

**Step 1: VolumeMountEditor.vue。** `defineModel<VolumeMount[]>()` + `defineProps<{ volumeNames: string[] }>()`。每行：name（`el-select` 从 `volumeNames` 选，允许新增时提示）+ mountPath + readOnly 开关 + subPath。add/remove 列表型模式。若某行 name 不在 volumeNames → 标红（C3 前端提示）。

**Step 2: VolumeEditor.vue。** `defineModel<VolumeDef[]>()`。每块：name 输入 + type radio（emptyDir/configMap/secret/persistentVolumeClaim/hostPath）；按 type 显示对应字段：
- emptyDir：medium（空/Memory）+ sizeLimit
- configMap：name + items（KeyToPath：key/path/mode，可多行）
- secret：secretName + items
- persistentVolumeClaim：claimName
- hostPath：path + type
切换 type 时清空其它子对象、保留 name。add/remove 列表型模式。

**Step 3: type-check** — Run: `npm run type-check` → 无错误
**Step 4: Commit**
```bash
git add platform-web/src/components/workload/VolumeMountEditor.vue platform-web/src/components/workload/VolumeEditor.vue
git commit -m "feat(workload): VolumeMountEditor + VolumeEditor (type-discriminated)"
```

### Task 18: AffinityEditor（node + podAnti，required/preferred）

**Files:**
- Create: `src/components/workload/AffinityEditor.vue`

**Interfaces:**
- Consumes: `Affinity`、`NodeSelectorRequirement`、`PodAffinityTerm`。
- Produces: `v-model: Affinity`。

**Step 1: 实现。** `defineModel<Affinity>()`。两大块：
- **节点亲和 nodeAffinity**：required（一组 NodeSelectorTerm，每 term 有 matchExpressions/matchFields，每条 = key + operator 下拉[In/NotIn/Exists/DoesNotExist/Gt/Lt] + values 多值）+ preferred（列表，每项 weight + preference=NodeSelectorTerm）。
- **Pod 反亲和 podAntiAffinity**：required（PodAffinityTerm 列表：namespaces 多值 + topologyKey + matchLabels 键值对）+ preferred（weight + PodAffinityTerm）。
用嵌套 `el-collapse`/卡片分区；每层可增删。空结构视为未配置。

**Step 2: type-check** — Run: `npm run type-check` → 无错误
**Step 3: Commit**
```bash
git add platform-web/src/components/workload/AffinityEditor.vue
git commit -m "feat(workload): AffinityEditor (node + pod anti-affinity)"
```

### Task 19: TolerationEditor（B6/B7 联动）

**Files:**
- Create: `src/components/workload/TolerationEditor.vue`

**Interfaces:**
- Consumes: `Toleration`。
- Produces: `v-model: Toleration[]`。

**Step 1: 实现。** `defineModel<Toleration[]>()`。每行：key + operator 下拉[Equal/Exists]（B6：key 空时强制 Exists 并禁用 value；operator=Exists 时禁用 value）+ value（operator≠Exists 必填）+ effect 下拉[NoSchedule/PreferNoSchedule/NoExecute] + tolerationSeconds（B7：effect≠NoExecute 时禁用并提示「将被忽略」）。add/remove 列表型模式。

**Step 2: type-check** — Run: `npm run type-check` → 无错误
**Step 3: Commit**
```bash
git add platform-web/src/components/workload/TolerationEditor.vue
git commit -m "feat(workload): TolerationEditor with B6/B7 linkage"
```

### Task 20: StrategyEditor（kind 感知）+ PvcTemplateEditor

**Files:**
- Create: `src/components/workload/StrategyEditor.vue`、`PvcTemplateEditor.vue`

**Interfaces:**
- `StrategyEditor`：`v-model: Strategy` + prop `kind: WorkloadKind`。
- `PvcTemplateEditor`：`v-model: PvcTemplate[]`（STS 专属）。

**Step 1: StrategyEditor.vue。** `defineModel<Strategy>()` + `defineProps<{ kind }>()`。
- type 下拉按 kind：deployment=[RollingUpdate, Recreate]；statefulset/daemonset=[RollingUpdate, OnDelete]。
- type≠滚动更新时隐藏 rollingUpdate（A4）。
- rollingUpdate 字段按 kind：deployment=maxSurge+maxUnavailable（quantity 输入，B5：一个为 0 提示另一个须>0）；statefulset=partition；daemonset=maxUnavailable（无 maxSurge，A5）。

**Step 2: PvcTemplateEditor.vue。** `defineModel<PvcTemplate[]>()`。每块：name + accessModes（多选 ReadWriteOnce/ReadOnlyMany/ReadWriteMany/ReadWriteOncePod）+ storage（quantity）+ storageClassName + volumeMode（Filesystem/Block）。add/remove 列表型模式。模板块下方提示「请确保有容器以同名 volumeMount 引用」（C4 前端提示）。

**Step 3: type-check** — Run: `npm run type-check` → 无错误
**Step 4: Commit**
```bash
git add platform-web/src/components/workload/StrategyEditor.vue platform-web/src/components/workload/PvcTemplateEditor.vue
git commit -m "feat(workload): StrategyEditor (kind-aware) + PvcTemplateEditor"
```

### Task 21: ContainerEditor（组合叶子编辑器）

**Files:**
- Create: `src/components/workload/ContainerEditor.vue`

**Interfaces:**
- Consumes: `ContainerDef` + Task 13–20 全部子组件。
- Produces: `v-model: ContainerDef` + prop `isInit: boolean`（init 容器隐藏 lifecycle + 三种探针，A1）+ prop `volumeNames: string[]`。

**Step 1: 实现。** `defineModel<ContainerDef>()` + `defineProps<{ isInit: boolean; volumeNames: string[] }>()`。分区：
- 基础：name（DNS_LABEL，重名标红由父级/列表校验）+ image + command（多值）+ args（多值）+ workingDir + imagePullPolicy 下拉[IfNotPresent/Always/Never]（D2：image 含 `:latest` 且未设策略时提示默认 Always）。
- EnvEditor（envs）+ EnvFromEditor（envFrom）+ PortEditor（ports）。
- ResourcesEditor（resources，提交前读其 isValid 阻止 B4 违规）。
- **非 init**：LifecycleEditor + 三个 ProbeEditor（liveness/readiness/startup）。
- VolumeMountEditor（volumeMounts，传 volumeNames）。

**Step 2: type-check** — Run: `npm run type-check` → 无错误
**Step 3: Commit**
```bash
git add platform-web/src/components/workload/ContainerEditor.vue
git commit -m "feat(workload): ContainerEditor composing leaf editors"
```

### Task 22: ContainerListEditor（主容器 + init 容器）

**Files:**
- Create: `src/components/workload/ContainerListEditor.vue`

**Interfaces:**
- Consumes: `ContainerDef`、`ContainerEditor`。
- Produces：两个 `v-model`——`main: ContainerDef[]`、`init: ContainerDef[]`；prop `volumeNames: string[]`。

**Step 1: 实现。** props/`defineModel`：`const main = defineModel<ContainerDef[]>('main')`、`const init = defineModel<ContainerDef[]>('init')`（Vue 3.4 多 model）。
- 主容器列表：每块一个 `ContainerEditor :is-init="false"`；「+ 添加容器」push 空 ContainerDef；删除按钮 **禁用当 main.length<=1**（C1 至少一个容器）。
- init 容器列表（可选区）：`ContainerEditor :is-init="true"`；可全删。
- 容器名全局唯一 + DNS_LABEL：computed 收集所有 name，重复或非法标红（C2 前端提示）。

**Step 2: type-check** — Run: `npm run type-check` → 无错误
**Step 3: Commit**
```bash
git add platform-web/src/components/workload/ContainerListEditor.vue
git commit -m "feat(workload): ContainerListEditor (main + init, C1/C2)"
```

### Task 23: WorkloadEditorView（整页：form state + 区块 + 提交校验 + 创建/编辑）

**Files:**
- Create: `platform-web/src/views/resource/WorkloadEditorView.vue`

**Interfaces:**
- Consumes: 全部子组件、`workloadApi`、`useResourceContext`、`WorkloadDetail`。
- Produces: 路由 `/resources/workloads/editor`（创建）与 `?name=`（编辑回填）。

**Step 1: 页面骨架 + form state。** `<script setup lang="ts">`：
- `const route = useRoute()`；`const editing = ref<string | null>(route.query.name as string | null)`。
- `const { state, ready } = useResourceContext()`。
- `form = reactive<WorkloadDetail>`（创建时给默认值：kind='deployment'、name=''、replicas=1、podTemplate={spec:{containers:[{name:'',image:''}],restartPolicy:'Always'}}、strategy={type:'RollingUpdate'}）。
- 编辑模式：`onMounted` 若 `editing` → `workloadApi.get(name, ctx)` 回填 form（deep-merge 到默认结构，缺省字段补空）。
- `volumeNames = computed(() => form.podTemplate?.spec?.volumes?.map(v=>v.name) ?? [])`。

**Step 2: 模板区块。** 用 `el-card`/分区：
1. **基础信息**：kind 下拉（编辑时禁用）+ name（编辑时禁用，RFC1123 提示）+ description + LabelEditor(labels) + replicas（非 daemonset）+ serviceName（仅 statefulset）。
2. **更新策略**：StrategyEditor(:kind)。
3. **容器 / 初始化容器**：ContainerListEditor(main/init, :volume-names)。
4. **Pod 高级**：restartPolicy（只读固定 Always + 说明，B1）+ serviceAccountName + nodeName（D1：填了提示「将忽略节点选择/亲和」）+ nodeSelector(LabelEditor) + AffinityEditor + TolerationEditor + VolumeEditor + imagePullSecrets（简单 name 列表）。
5. **存储卷模板**（仅 statefulset）：PvcTemplateEditor。

**Step 3: 提交。** `submit()`：
- 前端本地校验（与后端 §5 同规则，快速失败）：name RFC1123；至少一个容器；容器名唯一；volumeMount 引用存在；STS C4；ResourcesEditor.isValid()（B4）。任一失败 `ElMessage.warning` 并 return。
- 组装 body：`{ ...form, namespace: state.namespace!, kind: form.kind }`；daemonset 时 `replicas=null`。
- 创建 → `workloadApi.create({tenantId,clusterId}, body)`；编辑 → `workloadApi.update(name,{tenantId,clusterId}, body)`。
- 成功 `ElMessage.success` + `router.push('/resources/workloads')`。

**Step 4: type-check + build**
Run（在 `platform-web/`）: `npm run type-check && npm run build`
Expected: 均通过

**Step 5: Commit**
```bash
git add platform-web/src/views/resource/WorkloadEditorView.vue
git commit -m "feat(workload): full-page editor view (form + sections + submit validation)"
```

### Task 24: WorkloadView 接线（创建→编辑器，加「编辑」，移除旧弹窗）

**Files:**
- Modify: `platform-web/src/views/resource/WorkloadView.vue`

**Step 1: 改按钮与动作。**
- 「创建工作负载」按钮：`@click="openCreate"` 改为 `@click="goEditor(null)"`。
- 表格操作列加「编辑」按钮：`<el-button link type="primary" @click="goEditor(row.name)">编辑</el-button>`（放在「查看」后）。
- 新增 `function goEditor(name: string | null): void { router.push(name ? \`/resources/workloads/editor?name=\${encodeURIComponent(name)}\` : '/resources/workloads/editor') }`（顶部 `import { useRouter } from 'vue-router'` + `const router = useRouter()`）。
- **删除**：旧创建对话框整块 `<el-dialog v-model="dialogVisible" title="创建工作负载">…</el-dialog>` 及其 script（`dialogVisible/openCreate/form/images/ports/addImage/removePort/submit/isDaemonSet` 中仅创建用的部分）。**保留**「伸缩」对话框 + `openScale/submitScale`（列表快捷伸缩不变）+ 详情抽屉。
- 清理不再使用的 import / 变量（避免 type-check 报未使用）。

**Step 2: type-check + build**
Run（在 `platform-web/`）: `npm run type-check && npm run build`
Expected: 均通过

**Step 3: Commit**
```bash
git add platform-web/src/views/resource/WorkloadView.vue
git commit -m "feat(workload): list create->editor, add edit action, drop legacy dialog"
```

---

## Phase E — 集成验证（需用户重启两后端）

### Task 25: 全量编译 + 手动回归

**Files:** 无代码改动（验证任务）。

**Step 1: 后端整仓编译**
Run: `mvn -q compile`
Expected: BUILD SUCCESS（common/core/api 全通过）

**Step 2: 前端构建**
Run（在 `platform-web/`）: `npm run type-check && npm run build`
Expected: 均通过

**Step 3: 请用户重启 platform-api + k8s-server，然后手动验证：**
- [ ] 三种 kind 各建一个全字段工作负载（含亲和、卷、三探针、lifecycle、env/envFrom、tolerations、resources）→ 回读 YAML 核对字段落库正确。
- [ ] 编辑回填 → 改一处（如某 env）→ 保存：确认 `spec.selector` 与 name 不变、其余更新；STS serviceName 不被重置。
- [ ] 逐条触发 §5 硬约束，确认**中文报错且不落到 k8s-server**：init 容器带探针(A1)、probe 两个 handler(A2)、restartPolicy≠Always(B1)、requests>limits(B4)、maxSurge=maxUnavailable=0(B5)、toleration key 空但 operator=Equal(B6)、volumeMount 引用未定义卷(C3)、STS volumeClaimTemplate 无对应挂载(C4)。
- [ ] D 类提示在 UI 出现但不阻断提交：nodeName 填了→「将忽略节点选择/亲和」；image `:latest` 未设策略→「默认 Always」。
- [ ] 列表页「伸缩」快捷仍可用；「编辑」进编辑器回填正确。

**Step 4: Commit（如验证有微调）**
```bash
git add -A
git commit -m "chore(workload): post-verification tweaks"
```

---

## Self-Review（计划自检结论）

- **Spec 覆盖**：§4 DTO 树 → Task 1–3；§6.2 converter/operations → Task 4–6；§6.4 WorkloadService+校验 → Task 7–10；§6.5 web 全部 → Task 11–24；§5 A/B/C/D → A/B 在 Task 8、C 在 Task 9、D 类仅 UI 提示在 Task 23/各组件；§7 验证 → Task 25。无遗漏。
- **占位符**：Task 8 的 `validateC` 占位在 Task 9 明确替换（已注明）；前端叶子组件给出完整 exemplar + 逐字段结构，非「TBD」。
- **类型一致**：DTO 字段名（Phase A 表）= converter 引用（Phase B）= validator 引用（Phase C）= TS interface（Task 11）= 组件 v-model（Task 13–24），逐字对齐。
