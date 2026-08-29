# 统一 DTO 资源调用设计（api → k8s-server 通用 client + ListOptions）

日期：2026-08-28　状态：**已实施（2026-08-29，全模块编译通过；回归验证待用户重启两后端后执行）**

## 1. 背景与目标

- k8s-server 已是"约定本身"：两种 controller 基类（集群域 `/admin/**`、命名空间域 `/resources/**`）+ 固定六端点形态。但 platform-api 侧调用仍是手拼路径 + query 字符串的裸 `request(POST, ...)`，约定散落在字符串里。
- list 目前只支持全量 / 按名查询。K8s ListOptions 中 **labelSelector** 是控制台真实需求（按标签筛 Pod 等），需要打通管道。
- 目标：api 侧抽象通用 client（list/get/yaml/create/update/delete，签名只收 DTO）；DTO 成为统一请求载体并自带 apiPath；list 支持 labelSelector 原样透传。

## 2. 已确认的设计决策

| # | 决策 | 说明 |
|---|------|------|
| D1 | **DTO 即统一请求载体** | 11 个资源 DTO 全部 `extends BaseResources`（现状仅 ClusterRole/RoleBinding/Deployment/ServiceAccount 4 个继承，其余 7 个扁平 implements NamespacedResourceDTO） |
| D2 | **apiPath 放 DTO** | `BaseResources` 加抽象方法 `getApiPath()`，每个 DTO 一行覆写（如 `return "/resources/pods"`）。client 从 DTO 取路径，调用签名不再需要 ResourceType 参数。`ResourceType` 枚举保持不变，继续服务 k8s-core factory |
| D3 | **ListOptions v1 只做 labelSelector** | `BaseResources` 加 `String labelSelector`（K8s 原生选择器语法，服务端零解释透传）。k8s-core `list` 签名从 `Map<String,String> labels` 改为 `String labelSelector`（fabric8 `withLabelSelector(String)` 原生；Map 表达不了 in/notin/exists） |
| D4 | **wire shape：仅 list 变化** | list 改 `POST /{apiPath}/list`，条件全在 body（clusterId/namespace/labelSelector），同 `/admin/namespace/list` 先例。get/yaml/create/update/delete 保持现有 verb + query 参数形态不动；client 方法签名统一"DTO 进"，线上协议保持 RESTful |
| D5 | **对象可往返** | k8s-server 在 list/get/create/update 返回前把 `clusterId`/`namespace`（租户模式含 `tenantId`）回填到每个 item。UI 从列表拿一行原样发回 update/delete 即可用 |
| D6 | **client 合成一个类** | 集群域 / 命名空间域差异只是"namespace 为 null 不拼该参数、tenantId 有值才拼"，无需两个 client |
| D7 | **特殊端点归独立 client** | `/admin/cluster/**`（probe/provision）、`/admin/namespace/**`、`/admin/tenant/**`、未来 pod log/exec 归 `K8sAdminClient`（纯传输）；编排逻辑留在瘦身后的 `K8sProvisioningService` |
| D8 | **不做原生 K8s 对象直通** | 前端 schema 爆炸、Secret.data 泄漏面、api 侧要引 fabric8、版本漂移。现有 converter 1:1 全字段映射已满足"k8s-server 不做数据处理" |
| D9 | **不做 fieldSelector / limit / continue / resourceVersion / watch** | fieldSelector 是白名单能力覆盖窄且主要用例已被 labelSelector + DTO 字段本地过滤覆盖；K8s 分页是游标式（continue token 会 410、不能跳页、无可靠总数），做不出页码 UI，控制台规模用不上；watch 管理台轮询足够。结构上均留扩展位 |

## 3. 改动清单

### 3.1 platform-common（DTO 层）

- [ ] `models/k8s/BaseResources.java`
  - 改 `abstract class`；新增抽象方法 `public abstract String getApiPath();`
  - 新增字段 `private String labelSelector;`（javadoc：仅 list 生效，K8s 原生选择器语法）
- [ ] **BaseResources 无用字段清理**（读写面实测结论）：
  - **删 `apiVersion`** —— 请求方向零读取（k8s-server / k8s-core 无任何 `getApiVersion()` 调用），前端未使用，响应方向仅一处写入。连带：
    - ~~`ServiceMonitorConverter.java:22` 删 `res.setApiVersion(API_VERSION)`~~ **不动**：该行写入的是原生 `GenericKubernetesResource` 对象（CRD 创建必须带 apiVersion），不是 DTO 字段。真正的死写入在两处：platform-api `syncClusterRole` 与 k8s-server `syncTemplateClusterRole`（均已删）
    - `platform-api K8sProvisioningService.syncClusterRole` 删 `dto.setApiVersion("rbac.authorization.k8s.io/v1")`（死写入）
    - 注意：`ResourceOperations.apiVersion()` 接口方法属 operations 层，与 DTO 字段无关，不动
  - **保留 `kind`** —— WorkloadDTO 的 create/update 按 kind 分发（`WorkloadOperations.java:75/117`），前端 WorkloadView 展示/过滤依赖它。但非 workload 资源服务端不读该字段：清理死写入（`syncClusterRole` 删 `dto.setKind("ClusterRole")`）
  - **保留 `labels`** —— 双向使用（create/update → metadata.labels；查询响应 ← metadata.labels，11 个 converter 均在用）
  - `clusterId` / `tenantId` / `name` / `namespace` 均在用，保留
- [ ] 7 个扁平 DTO 改为 `extends BaseResources`（删掉各自重复的 name/namespace/labels 字段与 implements 声明，特有字段保留）：
  - `dto/PodDTO.java` → `getApiPath() = "/resources/pods"`
  - `dto/ConfigMapDTO.java` → `"/resources/configmaps"`
  - `dto/SecretDTO.java` → `"/resources/secrets"`
  - `dto/ServiceDTO.java` → `"/resources/services"`
  - `dto/PersistentVolumeClaimDTO.java` → `"/resources/persistentvolumeclaims"`
  - `dto/ServiceMonitorDTO.java` → `"/resources/servicemonitors"`
  - `dto/WorkloadDTO.java` → `"/resources/workloads"`
- [ ] 4 个已继承的 DTO 补 `getApiPath()` 覆写：
  - `ClusterRoleDTO` → `"/admin/clusterroles"`
  - `RoleBindingDTO` → `"/resources/rolebindings"`
  - `ServiceAccountDTO` → `"/resources/serviceaccounts"`
  - `DeploymentDTO` → `return null; // 无独立 HTTP 端点（走 workloads / k8s-core 内部）`

### 3.2 k8s-core（operations 层，机械替换 ×11）

- [ ] `operations/ClusterOperations.java`：`list(Map<String,String> labels)` → `list(String labelSelector)`
- [ ] `operations/NamespacedOperations.java`：`list(String namespace, Map<String,String> labels)` → `list(String namespace, String labelSelector)`
- [ ] 11 个实现类：删 `Map lb = ...` 空值兜底，`.withLabels(lb)` → selector 非空时 `.withLabelSelector(selector)`（空/blank 不加条件）：
  - cluster 域：`operations/rbac/RbacV1ClusterRoleOperations.java`
  - namespaced 域：`AppV1DeploymentOperations`、`WorkloadOperations`（三种 workload 各加一次）、`ServiceMonitorOperations`、`CoreV1SecretOperations`、`CoreV1ConfigMapOperations`、`CoreV1PersistentVolumeClaimOperations`、`CoreV1PodOperations`、`CoreV1ServiceOperations`、`CoreV1ServiceAccountOperations`、`RbacV1RoleBindingOperations`

### 3.3 k8s-server（controller 基类 ×2）

- [ ] `controllers/base/AbstractClusterResourceController.java`
  - list：`GET` + `@RequestParam clusterId` → `POST /list` + `@RequestBody T body`；边界校验取 `body.getClusterId()`；调 `ops(clusterId).list(body.getLabelSelector())`
- [ ] `controllers/base/AbstractNamespacedResourceController.java`
  - list：同上改 `POST /list` + body；`tenantId` 来源从 query 参数改为 `body.getTenantId()`（租户 token 模式仍以 JWT claim 为准，admin 模式取 body）
  - **D5 回填**：list/get/create/update 返回前循环 stamp `clusterId`/`namespace`/`tenantId`（两基类各一处小工具方法）
- [ ] 具体 controller（ClusterRole + namespace 包下 9 个）**零改动**（形态全在基类）
- [ ] Swagger：list 端点描述更新为"body 传 clusterId/namespace/labelSelector"

### 3.4 platform-api（通用 client + 特殊 client + 调用点重构）

**类规划（四个角色，职责单一）**：

| 类 | 职责 |
|---|------|
| `K8sServerGateway`（新） | HTTP 内核：RestClient、Authorization 透传、空 body 判定、code≠200 → CloudPlatformException、类型化反序列化。两个 client 共用的底座 |
| `K8sResourceClient`（新） | 通用六操作，DTO 驱动（D6/D7） |
| `K8sAdminClient`（新） | 特殊端点 client：非六端点形态的 `/admin/**` 生命周期接口，一个方法一个端点，纯传输零业务；未来 pod log/exec 也归此类 |
| `K8sProvisioningService`（瘦身） | 只剩编排/业务规则（upsert 判断、best-effort 遍历启用集群、parseRules），不再是 client |

- [ ] 新包 `com.coding.platformapi.k8s`：
  - `K8sServerGateway.java`（@Component）
    - HTTP 内核从既有 `services/K8sResourceService` 下沉（含 `streamGet` 流式，pod log 用）：Authorization 透传、空 body 判定、code≠200 → CloudPlatformException（保留现有语义与日志）、超时配置（connect 10s / read 120s）
  - `K8sResourceClient.java`（@Component，注入 gateway）
    - 泛型方法（路径取 `dto.getApiPath()`，DTO 类型取 dto 运行时类，反序列化用 `JsonMapper.getTypeFactory().constructParametricType(ResponseData.class, clazz)` + `readValue(String, JavaType)`——Jackson 3.1.5 已验证可用）：
      - `<T extends BaseResources> List<T> list(T query)` → `POST {apiPath}/list`（body=query）
      - `<T extends BaseResources> T get(T dto)` → `GET {apiPath}/{name}?clusterId[&namespace][&tenantId]`
      - `<T extends BaseResources> String yaml(T dto)` → `GET {apiPath}/{name}/yaml?...`
      - `<T extends BaseResources> T create(T dto)` → `POST {apiPath}?...`（body=dto）
      - `<T extends BaseResources> T update(T dto)` → `PUT {apiPath}/{name}?...`
      - `<T extends BaseResources> void delete(T dto)` → `DELETE {apiPath}/{name}?...`
    - query 拼装规则：namespace/tenantId 仅在有值时拼；clusterId 必带
  - `K8sAdminClient.java`（@Component，注入 gateway）——特殊端点，从 K8sProvisioningService 原样搬家：
    - `probe(kubeconfig)` → POST `/admin/cluster/probe`
    - `provisionCluster(clusterId)` → POST `/admin/cluster/provision`
    - `ensureTenantSa(clusterId, serviceAccount)` → POST `/admin/tenant/sa/ensure`
    - `ensureNamespace(clusterId, namespace)` → POST `/admin/namespace/create`
    - `listNamespaces(clusterId)` → POST `/admin/namespace/list`
    - `deleteNamespace(clusterId, namespace)` → POST `/admin/namespace/delete`
    - `cleanupTenant(AdminCleanupRequest)` → POST `/admin/tenant/cleanup`
- [ ] `services/K8sProvisioningService.java` 重构（对外方法签名不变，调用方无感）：
  - 删除私有 `request()`/`post()` 与全部特殊端点方法（已分别下沉 gateway / 搬家 K8sAdminClient）
  - `syncClusterRole` → `resourceClient.get` + `create/update`（upsert 判断留本侧）
  - `ensureRoleBinding` / `deleteRoleBindingIfExists` → `resourceClient.get/create/delete`（RoleBindingDTO 携带 clusterId/namespace/tenantId/name，不再手拼 query）
  - `deleteTemplateClusterRoleEverywhere` → `resourceClient.delete`
  - 保留：`syncTemplateToAllEnabledClusters`（best-effort 循环）、`parseRules`、`enabledClusters` 等编排/业务方法
- [ ] `ClusterService` / `TenantService` / `NamespaceAllocationService` / `NamespaceService`：特殊端点调用改直注 `K8sAdminClient`（probe/provision/ensureNs/ensureSa/listNs/deleteNs/cleanup）；编排方法（syncClusterRole/parseRules/ensureRoleBinding/deleteRoleBindingIfExists/enabledClusters）仍走瘦身后的 provisioning
- [ ] **删 `services/K8sResourceService.java`**：传输能力被 `K8sServerGateway` 吸收（含 streamGet），六操作调用改由 `K8sResourceClient` 承担；pod log 流式改 PodController 直调 `gateway.streamGet`
- [ ] 7 个资源 controller（ConfigMap/Secret/Service/Pvc/Pod/Workload/ServiceMonitor）重写为 DTO 驱动：注入 `K8sResourceClient`；**对外形态** = list 为 `POST /list` + body（与内部 wire 同构，一行透传）、create/update 纯 body（tenantId/clusterId 由 body 承载，client 提取进 query）、get/yaml/delete 按名寻址走 query 且**显式命名**（Spring 7 未开 `-parameters` 时不推断参数名，无名 @RequestParam 直接绑定失败）

### 3.5 platform-web

- [x] **原"v1 零改动"假设不成立**：资源页面（7 个 View）已存在并调用这些端点。对外形态变更后同步改 `api/index.ts`：list → `POST /list` + body、create/update 将 ctx（tenantId/clusterId）合并进 body、get/yaml/delete query 不变；视图层签名未动。**labelSelector**：API 层 `ListCtx` 预留可选字段（不传 = 全量），页面筛选入口暂不加，后续按页需要再补

## 4. 待定项（实施前拍板）

| # | 事项 | 建议 |
|---|------|------|
| O1 | limit 安全阀（服务端封顶 + "已截断"提示） | **v1 不做**。做对需要回传 continue/remaining 元数据，超出"一个整数字段"的成本；真需要时再加 |
| O2 | apiPath 双处维护（controller `@RequestMapping` 注解 vs DTO 方法） | 接受手工同步；注解需编译期常量无法从 DTO 读。11 行字符串，风险低 |

## 5. 实施顺序与验证

依赖方向：common → core → server → api

1. **platform-common**：BaseResources + 11 DTO（`mvn -q compile`）
2. **k8s-core**：接口签名 + 11 实现（`mvn -q compile`）
3. **k8s-server**：两基类 list 改造 + D5 回填（`mvn -q compile`）
4. **platform-api**：新 client + provisioning 重构（`mvn -q compile`）
5. 回归验证（需用户重启两个后端）：
   - 分配/取消分配流程（走 `/resources/rolebindings` get/create/delete 新路径）
   - 模板保存 → 全集群 ClusterRole upsert（走 `/admin/clusterroles`）
   - curl k8s-server `POST /resources/pods/list` 带 `labelSelector` 验证过滤生效（有真实集群时）
   - 前端 type-check 不受影响（无改动），preview 回归租户/命名空间页面

## 6. 明确不做（本范围外）

- fieldSelector、limit/continue、resourceVersion、watch（D9，留扩展位）
- 原生 K8s 对象直通（D8）
- get/delete 等端点的 wire 形态 DTO 化（D4：仅 list 变 body）
- pod log/exec 流式端点（归特殊类，另行设计）
