# 工作负载完整编辑器设计（Deployment / StatefulSet / DaemonSet 全字段表单）

日期：2026-08-30　状态：**设计中（spec 待用户确认，确认后进入 writing-plans）**

## 1. 背景与目标

- **现状**：`platform-web/src/views/resource/WorkloadView.vue` 只有「kind + 名称 + 镜像行 + 副本数 + 端口」的简单创建弹窗；编辑仅能伸缩副本数。后端 `WorkloadDTO` 是简化形态（`images[]` / `ports[]`），`WorkloadConverter` 只映射这些字段，`WorkloadOperations.update()` 只改 `replicas`。
- **目标**：把新建 / 编辑升级为**整页全字段编辑器**，覆盖 K8s Deployment / PodSpec 的 22 个字段组（§4），三种 kind（deployment / statefulset / daemonset）全覆盖；复杂子结构（亲和/反亲和、卷、三种探针、生命周期、env/envFrom、tolerations）**全结构化**（非 YAML 兜底）。
- **架构红线（用户钦定，最高优先级）**：
  - **业务校验规则一律在 `platform-api` 侧**（新增 `WorkloadService`）。
  - **k8s-server + k8s-core 是纯适配器**：converter 只做 DTO↔K8s 对象的忠实转换（1:1 全字段映射，沿用既有 D8「converter 不做数据处理」原则），operations 只调 fabric8 + 透传 K8s 错误。**不含任何业务 if 判断**。
- **接口依据**：字段与约束以 Apifox `createAppsV1NamespacedDeployment`（`POST /apis/apps/v1/namespaces/{namespace}/deployments`，请求体 `io.k8s.api.apps.v1.Deployment`）的 schema 描述为准；StatefulSet/DaemonSet 对应 spec 已一并核对。

## 2. 已确认的设计决策

| # | 决策 | 说明 |
|---|------|------|
| D1 | **三种 kind 全覆盖** | deployment / statefulset / daemonset 共用 pod-template 编辑器；kind 特有字段（replicas、strategy/updateStrategy、serviceName、volumeClaimTemplates）按 kind 条件显示 |
| D2 | **独立整页编辑器** | 新路由 `/resources/workloads/editor`（`meta.context:'full'` 保留顶栏 chip）。创建 = 空表单；编辑 = `?name=` 进页面先 `get` 回填。原简单创建弹窗移除；列表保留「伸缩」快捷 + 新增「编辑」 |
| D3 | **复杂子结构全结构化** | 亲和/反亲和、卷、三种探针、生命周期、env/envFrom、tolerations 均做可视化编辑器 |
| D4 | **业务校验在 platform-api** | 新增 `WorkloadService`：create/update 前先跑 §5 全部硬约束，通过再调 `K8sResourceClient`；k8s-core converter 只做 1:1 全字段映射（遵循「k8s 侧不做业务处理」红线），operations 只调 fabric8 + 透传错误 |
| D5 | **DTO 就地扩展** | `WorkloadDTO` 增嵌套 `podTemplate` + kind 特有字段；list/get/create/update 共用同一 DTO，通用 client（`K8sResourceClient`）与 k8s-server controller **零改动**（泛型透传自动生效）。`images[]`/`ports[]` 保留为 list 摘要字段 |
| D6 | **description → annotation** | K8s 无原生描述字段，存 `metadata.annotations["description"]` |
| D7 | **quantity 用 String** | maxSurge / maxUnavailable / resources / storage 等值一律 `String`（K8s quantity 支持 `"25%"`/`"100m"`/`"1Gi"`） |
| D8 | **serviceName / volumeClaimTemplates 纳入范围（STS 专属）** | serviceName 暴露为 STS 字段（默认 = 名称，converter 改读 dto 值、编辑回填不重置）；volumeClaimTemplates 独立表单块（STS 专属），每项 name/accessModes/storage/storageClassName/volumeMode |

## 3. 架构分层（红线落点）

```
platform-web  整页编辑器 + ~15 子组件
  → platform-api / WorkloadController          （薄，create/update 委托 service；list/get/yaml/delete 直连 client）
    → platform-api / WorkloadService           ★业务层：§5 硬约束校验（kind 感知）+ 编排
      → K8sResourceClient.create / update       （通用透传，路径取 dto.getApiPath()）
        → k8s-server / WorkloadController        （适配器基类，零改动）
          → k8s-core / WorkloadOperations         （fabric8 CRUD + 透传错误）
            → k8s-core / WorkloadConverter        （DTO↔K8s 对象 1:1 全字段映射，无业务规则）
              → fabric8 → K8s
```

- **platform-api（业务）**：`WorkloadService.validate(dto)` 执行 §5 全部硬约束；失败抛 `CloudPlatformException`（中文消息）直接返回前端，**不打到 k8s-server**。kind 从 `dto.getKind()` 读取，做 kind 感知校验。
- **k8s-core / k8s-server（适配器）**：converter 忠实映射，含 `selector={app:name}`、template labels 补 `app=name` 这类「如何构造合法 K8s 对象」的约定——属适配正确性（K8s 要求 selector ⊆ template labels），**非业务**。operations 调 fabric8、透传 K8s 错误。

## 4. 数据模型（platform-common，DTO）

`WorkloadDTO extends BaseResources`（已有 kind/name/namespace/labels/annotations）。新增字段：

```
WorkloadDTO
├─ description            // → metadata.annotations["description"]（D6）
├─ replicas               // Integer；daemonset 忽略（沿用）
├─ serviceName            // String；STS 专属，默认 = name（D8）
├─ strategy               // StrategyDTO；kind 感知（见下）
├─ volumeClaimTemplates   // List<PvcTemplateDTO>；STS 专属（D8）
├─ podTemplate            // PodTemplateDTO ★核心：完整 PodSpec，三种 kind 共享
│   ├─ metadata           // { labels, annotations }
│   └─ spec: PodSpecDTO
│       ├─ containers[]         // ContainerDTO
│       ├─ initContainers[]     // 复用 ContainerDTO（受 A1 限制）
│       ├─ restartPolicy        // String；工作负载固定 Always（B1）
│       ├─ serviceAccountName   // String
│       ├─ nodeName             // String（D1 提示类：填了则忽略 nodeSelector/affinity）
│       ├─ nodeSelector         // Map<String,String>
│       ├─ affinity             // AffinityDTO
│       │   ├─ nodeAffinity     // { required: NodeSelector, preferred: List<PreferredSchedulingTerm{weight, preference:NodeSelectorTerm}> }
│       │   └─ podAntiAffinity  // { required: List<PodAffinityTerm>, preferred: List<{weight,PodAffinityTerm}> }
│       │                        // PodAffinityTerm = { namespaces[], topologyKey, matchLabels{} }
│       ├─ tolerations[]        // TolerationDTO { key, operator, value, effect, tolerationSeconds }
│       ├─ volumes[]            // VolumeDTO（type 判别，见下）
│       └─ imagePullSecrets[]   // { name }
└─ (查询回填) readyReplicas / creationTime / images[] / ports[]   // images/ports 仅作 list 摘要

ContainerDTO（就地扩展；主容器 & init 容器共用；字段可空，additive 不破坏 AppsV1DeploymentConverter）
├─ name, image            // （已有）
├─ command[], args[]      // #5 启动命令 / 参数
├─ workingDir             // #16
├─ imagePullPolicy        // #11 默认 IfNotPresent（D2 提示 :latest 联动）
├─ envs[]                 // EnvDTO（已有；扩展 valueFrom）
├─ envFrom[]              // EnvFromDTO { prefix, configMapRef{name,optional}, secretRef{name,optional} }
├─ ports[]                // PortDTO（已有；扩展 protocol/name）
├─ resources              // ResourcesDTO { limits: Map<String,String>, requests: Map<String,String> }
├─ lifecycle              // LifecycleDTO { postStart: HandlerDTO, preStop: HandlerDTO }
│                          //   HandlerDTO = { exec{command[]}, httpGet{...}, sleep{seconds} }（无 tcpSocket）
├─ livenessProbe / readinessProbe / startupProbe   // ProbeDTO
│                          //   ProbeDTO = { httpGet|tcpSocket|exec, initialDelaySeconds, periodSeconds,
│                          //                timeoutSeconds, successThreshold, failureThreshold }
└─ volumeMounts[]         // VolumeMountDTO { name, mountPath, readOnly, subPath }

EnvDTO（扩展）：name, value（已有）+ valueFrom { configMapKeyRef{name,key,optional}, secretKeyRef{name,key,optional}, fieldRef{fieldPath,apiVersion} }
PortDTO（扩展）：containerPort（已有）+ protocol + name
VolumeDTO（type 判别，全结构化覆盖常用类型）：
├─ name
├─ type                   // emptyDir | configMap | secret | persistentVolumeClaim | hostPath
├─ emptyDir{ medium?, sizeLimit? }
├─ configMap{ name, items[]{key,path,mode} }
├─ secret{ secretName, items[]{key,path,mode} }
├─ persistentVolumeClaim{ claimName }
└─ hostPath{ path, type? }

StrategyDTO（kind 感知）：type + rollingUpdate
├─ deployment:    type ∈ { RollingUpdate, Recreate }；rollingUpdate{ maxSurge?, maxUnavailable? }（String，B5）
├─ statefulset:   type ∈ { RollingUpdate, OnDelete }；rollingUpdate{ partition? }
└─ daemonset:     type ∈ { RollingUpdate, OnDelete }；rollingUpdate{ maxUnavailable? }（无 maxSurge，A5）

PvcTemplateDTO（STS 专属，D8）：name + accessModes[]（ReadWriteOnce/ReadOnlyMany/ReadWriteMany/ReadWriteOncePod）
                                   + storage（quantity String）+ storageClassName? + volumeMode?（Filesystem/Block）
```

**新增 DTO 类清单**（platform-common `models/k8s/dto`，均 `@Data` + `@Schema`）：
`PodTemplateDTO · PodSpecDTO · AffinityDTO · NodeAffinityDTO · PodAntiAffinityDTO · NodeSelectorDTO · NodeSelectorTermDTO · NodeSelectorRequirementDTO · PreferredSchedulingTermDTO · PodAffinityTermDTO · WeightedPodAffinityTermDTO · TolerationDTO · VolumeDTO · EmptyDirVolumeDTO · ConfigMapVolumeDTO · SecretVolumeDTO · PvcVolumeDTO · HostPathVolumeDTO · KeyToPathDTO · VolumeMountDTO · ResourcesDTO · LifecycleDTO · HandlerDTO · ProbeDTO · HttpGetActionDTO · TCPSocketActionDTO · ExecActionDTO · SleepActionDTO · EnvFromDTO · ValueFromDTO · ConfigMapKeySelectorDTO · SecretKeySelectorDTO · ObjectFieldSelectorDTO · StrategyDTO · RollingUpdateDTO · PvcTemplateDTO`

> 说明：action 类（HttpGet/TCPSocket/Exec/Sleep）探针与生命周期共用；lifecycle 的 HandlerDTO 不含 tcpSocket（文档 LifecycleHandler 仅 exec/httpGet/sleep），ProbeDTO 含 tcpSocket——两者形状不同，分别建模。约 36 个新类。

## 5. 字段冲突与校验（依据 K8s API 文档；执行点 = platform-api `WorkloadService`）

硬约束违反 → 抛 `CloudPlatformException`（中文消息）返回前端；「忽略类」静默丢弃字段避免 K8s warn。kind 感知（读 `dto.kind`）。

### A. 互斥 / 条件字段
| # | 冲突（文档依据） | UI 展示 | 后端校验（platform-api） |
|---|---|---|---|
| A1 | **init 容器禁止** lifecycle、readiness/liveness/startup 探针（"Init containers may not have Lifecycle actions, Readiness/Liveness/Startup probes"） | init 容器编辑器隐藏这 4 项 | 设置了即报错 |
| A2 | **probe handler**（httpGet/tcpSocket/exec）只能取一个 | radio 选类型，只显示对应字段 | 校验恰好一个 |
| A3 | **lifecycle 钩子 handler**（exec/httpGet/sleep）只能取一个 | radio 选类型 | 校验恰好一个 |
| A4 | **strategy.type=Recreate**(Dep)/`OnDelete`(STS,DS) 时 rollingUpdate 不生效（"Present only if … RollingUpdate"） | type 非滚动更新时隐藏 rollingUpdate 子字段 | 丢弃该字段 |
| A5 | **DaemonSet** rollingUpdate 只有 maxUnavailable，无 maxSurge | daemonset 下隐藏 maxSurge | 忽略 maxSurge |

### B. 取值约束
| # | 约束（文档依据） | UI | 后端校验（platform-api） |
|---|---|---|---|
| B1 | **restartPolicy** 工作负载仅 `Always`（"In some contexts, only a subset … permitted"） | 只读固定 `Always` + 说明 | 非 Always 报错 |
| B2 | **liveness/startup 探针 successThreshold 必须=1**（"Must be 1 for liveness and startup"） | 这两项禁用该输入(=1)；readiness 可 >1 | 校验 |
| B3 | probe **periodSeconds / timeoutSeconds ≥1**（"Minimum value is 1"） | min=1 | 校验 |
| B4 | **resources requests ≤ limits**（"Requests cannot exceed Limits"） | request>limit 实时标红提示并阻止提交 | **严格校验**：逐资源把 request/limit 解析为 K8s quantity（SI m/k/M/G/T + 二进制 Ki/Mi/Gi/Ti）转 BigDecimal 比较，request>limit 或无法解析即报错（不交 K8s 兜底） |
| B5 | **maxSurge 与 maxUnavailable 不能同时为 0**（"can not be 0 if … is 0"，仅 Deployment） | 一个填 0 时提示另一个须 >0 | 校验 |
| B6 | **toleration**：key 空⇒operator=Exists；operator=Exists⇒value 空，否则 value 必填 | 联动禁用 / 必填 | 校验 |
| B7 | **tolerationSeconds 仅 effect=NoExecute 有效**（"otherwise this field is ignored"） | effect≠NoExecute 时提示"将被忽略"并禁用 | 非 NoExecute 丢弃该字段 |

### C. 唯一性 / 引用完整性
| # | 约束（文档依据） | UI | 后端校验（platform-api） |
|---|---|---|---|
| C1 | **至少一个容器**（"at least one container in a Pod"） | 不能删到 0 | 校验 |
| C2 | **所有容器名（init+普通）全局唯一** + DNS_LABEL（"must be unique among all containers"） | 重名标红 | 校验 |
| C3 | **volumeMounts[].name 必须引用已定义 volumes[].name** | 挂载下拉只列已有 volume；未定义标红 | 校验 |
| C4 | **每个 volumeClaimTemplate 的 name 必须至少被一个容器的 volumeMount.name 同名引用**（STS；"Every claim … must have at least one matching (by name) volumeMount in one container"） | 模板块下方提示"请确保有容器挂载了名为 X 的卷" | 遍历所有容器 volumeMounts 校验，缺失报错（仅 STS） |

### D. 优先级 / 忽略（UI 提示，后端不硬拦）
| # | 行为（文档依据） | UI | 后端 |
|---|---|---|---|
| D1 | **nodeName 设置后 nodeSelector/nodeAffinity 被忽略**（"should not be used to express a desire…"） | 填了 nodeName 提示"将忽略节点选择/亲和" | 不拦，保留原样交 K8s |
| D2 | **imagePullPolicy 与 image tag 联动**：`:latest` 未设策略默认 `Always`（"Defaults to Always if :latest … IfNotPresent otherwise"） | 提示该默认规则 | 不拦，交 K8s 默认 |
| D3 | **volumeClaimTemplate 与同名普通 volume 同时存在时模板优先**（"takes precedence over any volumes … with the same name"） | 提示即可 | 不拦 |

## 6. 改动清单

### 6.1 platform-common（DTO 层）
- [ ] `WorkloadDTO.java`：新增 `description / serviceName / strategy(StrategyDTO) / volumeClaimTemplates(List<PvcTemplateDTO>) / podTemplate(PodTemplateDTO)`；保留 `replicas/readyReplicas/images/ports/creationTime`（images/ports 降为 list 摘要）
- [ ] 就地扩展（additive，不破坏 `AppsV1DeploymentConverter`）：`ContainerDTO`（+command/args/workingDir/imagePullPolicy/envFrom/resources/lifecycle/三探针/volumeMounts）、`EnvDTO`（+valueFrom）、`PortDTO`（+protocol/name）
- [ ] 新增 §4「新增 DTO 类清单」中 ~36 个 `@Data @Schema` 类

### 6.2 k8s-core（适配器：converter + operations，无业务规则）
- [ ] `WorkloadConverter.java`
  - `convertDeployment / convertStatefulSet / convertDaemonSet`：改为从 `dto.podTemplate` 经共享 `buildPodSpec(PodSpecDTO)` 构建完整 PodSpec；deployment 加 `strategy`；sts 加 `serviceName(dto.serviceName ?: name)` + `volumeClaimTemplates` + `updateStrategy`；daemonset 加 `updateStrategy`
  - `revert(三种)`：反向回填完整 `podTemplate` + kind 字段（供编辑预填）；同时保留 `images[]/ports[]` 摘要（list 用）
  - 新增各子结构映射 helper（container/probe/affinity/volume/lifecycle/toleration…），机械 1:1
  - selector 维持 `{app:name}`、template labels 补 `app=name`（适配正确性，保留）
- [ ] `WorkloadOperations.java`
  - `update()`：从「只改 replicas」升级为**整 spec 替换**——取 existing → converter 重建对象 → **保留 existing 的 `spec.selector`（Deployment/STS 不可变）+ name** → update；daemonset 忽略 replicas
  - `create()`：converter 现产出完整 spec，逻辑基本不变

### 6.3 k8s-server（适配器基类）
- [ ] **零改动**（泛型透传，DTO 加字段自动生效）

### 6.4 platform-api（业务层）
- [ ] 新增 `services/WorkloadService.java`（@Service，注入 `K8sResourceClient`）
  - `create(dto)`：`validate(dto)` → `client.create(dto)`
  - `update(dto)`：`validate(dto)` → `client.update(dto)`
  - `validate(WorkloadDTO)`：执行 §5 全部硬约束（A1–A5、B1–B7、C1–C4），kind 感知；失败抛 `CloudPlatformException`（中文）
  - quantity 严格解析工具：K8s quantity（整数 + SI 后缀 m/k/M/G/T/P/E + 二进制 Ki/Mi/Gi/Ti/Pi/Ei）→ BigDecimal 基础单位值；B4 逐资源比较 request≤limit，超限或无法解析均抛错（前后端各自实现同一规则，前端 `ResourcesEditor` 实时提示并阻止提交）
- [ ] `controllers/resource/WorkloadController.java`：create/update 改委托 `WorkloadService`；list/get/yaml/delete 不变

### 6.5 platform-web（整页编辑器）
- [ ] `types/workload.ts`：镜像 §4 DTO 的 TS interface（`WorkloadDetail` / `PodSpec` / `ContainerDef` / `Probe` / `Affinity` / `Volume` …）
- [ ] `router/index.ts`：新增 `{ path:'resources/workloads/editor', name:'workloadEditor', component: WorkloadEditorView, meta:{ title:'工作负载编辑', group:'资源管理', context:'full' } }`
- [ ] 新增 `views/resource/WorkloadEditorView.vue`：大 reactive form（镜像 DTO）+ 区块（基础信息 / 容器 / 初始化容器 / Pod 高级 / 存储卷模板[STS]）；创建=默认值、编辑=`?name` get 回填；提交走 create/update
- [ ] 新增子组件（各自 v-model，可复用/单测）：`LabelEditor · ContainerListEditor · ContainerEditor · EnvEditor · EnvFromEditor · PortEditor · ResourcesEditor · ProbeEditor · LifecycleEditor · VolumeMountEditor · VolumeEditor · AffinityEditor · TolerationEditor · StrategyEditor · PvcTemplateEditor`
- [ ] `views/resource/WorkloadView.vue`：「创建工作负载」→ 跳编辑器；新增「编辑」→ `?name=`；移除原创建弹窗；保留「伸缩」
- [ ] `api/index.ts`：`workloadApi` 六操作已具备，无需改（type 换成更完整的 `WorkloadDetail`）

## 7. 实施顺序与验证

依赖方向：common → core → api（server 无改动）→ web

1. **platform-common**：WorkloadDTO 扩展 + ~35 DTO 类（`mvn -q compile`）
2. **k8s-core**：converter 全字段映射 + operations.update 整 spec 替换（`mvn -q compile`）
3. **platform-api**：WorkloadService 校验 + controller 委托（`mvn -q compile`）
4. **platform-web**：types + 路由 + 编辑器页 + 子组件（`npm run type-check` / `build`；preview 回归）
5. **验证**（需用户重启 platform-api + k8s-server 后端）：
   - 三种 kind 各建一个全字段工作负载，回读 YAML 核对字段落库正确
   - 编辑回填 → 改一处 → 保存，确认 selector/name 不变、其余更新
   - 逐条触发 §5 硬约束（如 init 容器带探针、requests>limits、maxSurge=maxUnavailable=0、volumeClaimTemplate 无对应挂载）确认中文报错且不落到 k8s-server
   - D 类提示在 UI 出现但不阻断提交

## 8. 明确不做（本范围外，留扩展位）

- STS volumeClaimTemplates 冷门字段：dataSource / dataSourceRef / selector / volumeName / volumeAttributesClassName
- Deployment `minReadySeconds` / `revisionHistoryLimit` / `progressDeadlineSeconds` / `paused`（不在 22 项内）
- STS `podManagementPolicy` / `ordinals` / `persistentVolumeClaimRetentionPolicy`；DaemonSet 无额外
- 冷门卷类型：projected / downwardAPI / ephemeral / CSI（v1 只做 emptyDir/configMap/secret/pvc/hostPath）
- hostPort、securityContext、seccomp、topologySpreadConstraints 等未列字段
- YAML 兜底编辑（用户已选全结构化）
