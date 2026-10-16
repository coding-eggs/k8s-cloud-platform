# 告警（S2.2）设计

> **状态**：设计待评审 · **日期**：2026-10-17
> **台账条目**：`docs/task-ledger-2026-10-12.md` §S2.2
> **前身**：`docs/gap-analysis-2026-10-12.md` §T1-3、§5.1 #4、§5.4
> **规模**：L（三块，可分批上线）

---

## 1. 目标与范围

把「集群里发生了什么告警」变成平台里看得见、可归属、有历史的一件事。三块：

1. **PrometheusRule 的 CRUD** —— 租户在自己命名空间里定义告警/记录规则（与 ServiceMonitor 完全同构）。
2. **规则运行状态回读** —— 让「规则写了但没生效」和「expr 写错了」当场可见。
3. **告警接收 · 落库 · 列表** —— Alertmanager 推过来，平台落库，按租户归属展示。

### 做

- `monitoring.coreos.com/v1` 的 `PrometheusRule` 全生命周期（列表 / 详情 / YAML / 建 / 改 / 删）
- 规则运行状态的只读附着（来自 Prometheus 的 `/api/v1/rules`）
- `POST /alert/webhook` 接收端点 + 落库 + 告警列表页
- 权限行 + 迁移

### 不做（已定，勿擅自加）

| 不做 | 依据 |
|---|---|
| Alertmanager 的管理面（告警列表代理 / 静默 / 配置 / 状态） | gap-analysis §5.1 #4：**平台不做其管理面、不对外暴露** |
| 告警的发送渠道（邮件 / IM / webhook 转发） | 用户明确「后续如何发送待定」，不提前做 |
| 平台自己下发 `AlertmanagerConfig` | 同上 —— 那是 Alertmanager 管理面 |
| 规则表单的「简单档」（指标下拉 + 阈值 + 时长 → 生成 expr） | 本批不做；`expr` 是自由 PromQL 输入框 |
| Thanos Ruler | 见 §3 决策 3 |

---

## 2. 现场勘查（真集群，2026-10-16/17，只读）

设计前实地核对过的事实。**这一节是后面所有判断的根据，改动设计前先看这里。**

### 2.1 组件与拓扑

```
Prometheus v3.14.0 ──求值 280 条规则、发告警──▶ Alertmanager v0.34.0
 (monitor/ps-kube-prometheus-stack-prometheus)   (monitor/ps-kube-prometheus-stack-alertmanager:9093)
      │ remote_write（writeRelabelConfigs 打 cluster_name=coding-cluster）
      ▼
 Thanos Receive ──▶ Thanos Query v0.42.4 (:19090) ◀── Thanos StoreGateway
                                          └── Thanos Compactor
```

- **没有 Thanos Ruler**（`kubectl get thanosruler` 空，也没有 ruler 容器）。
- Thanos Query 的 `--endpoint` 只连 `th-thanos-receive-headless.monitor.svc.cluster.local:10901`。
- 两个 kube-prometheus-stack 之外的 Thanos 组件由 `th` release 管（Bitnami chart），与 `ps` release 相互独立。

### 2.2 实测到的关键数值

| 项 | 值 | 怎么得到的 |
|---|---|---|
| PrometheusRule CRD 版本 | **`v1`** | `kubectl get crd ... -o jsonpath` |
| 存量 PrometheusRule | **24 条**，全在 `monitor`，全是 chart 自带 | `kubectl get prometheusrule -A` |
| `/api/v1/rules` 规模 | **40 组 / 280 条规则**（186 alerting + 94 recording） | 见下 |
| 当前告警 | **2 firing + 4 pending**，`health` 全 ok | 见下 |
| Alertmanager 当前路由 | `receivers: [{name: "null"}]` —— **所有告警都进 null，全丢** | `/api/v2/status` |

**当前在 firing 的**（这就是「告警为零」的字面含义）：

```
[firing ] AlertmanagerClusterCrashlooping   severity=critical
          （该 pod 3 天内重启 42 次）
[firing ] Watchdog                          severity=none
[pending] ThanosCompactIsDown / ThanosQueryIsDown
          ThanosReceiveIsDown / ThanosStoreIsDown    全是 severity=critical
```

后四条 pending 的原因是这几个 Thanos 组件**没被 Prometheus 抓取**（`absent(up{job=~".*thanos.*compact.*"} == 1)`），不是它们真挂了。

### 2.3 读 Prometheus 的路径

| 端点 | 是什么 | `/api/v1/rules` |
|---|---|---|
| `192.168.31.80:19090` | Thanos Query 0.42.4（平台 `thanos.query.url` 用的） | **返回 0 组** |
| `192.168.31.80:30909` | Prometheus 3.14.0（external-gateway 的 `http-30909`） | **返回 216KB**，健康/状态/lastError 全有 |

Thanos Query 返回 0 组的原因：**它的 `/api/v1/rules` 是代理给它连着的 Thanos Ruler 的**，一个 Ruler 都没有。

`k8s_cluster.prometheus_url` **已经是** `http://192.168.31.80:30909`，且已有两个消费方（`ServiceMonitorService` / `PodMonitorService` 的 `relabel-labels` 与 `metric-names` 候选）。

### 2.4 `release: ps` —— 会静默吃掉规则的标签选择器

```
Prometheus.ruleSelector          = {matchLabels: {release: ps}}
Prometheus.ruleNamespaceSelector = {}          ← 全命名空间，租户 ns 在范围内
```

来自 Helm release `ps`（kube-prometheus-stack **88.6.2**，monitor 命名空间，已升到 v15）的 chart 默认值。**values 里 `ruleSelector` 本来就是 `{}`**，是旁边的 `ruleSelectorNilUsesHelmValues: true` 让模板忽略了它、改用 release 名生成选择器 —— **要关的是那个 flag**。

同样的 flag 有四个：`ruleSelectorNilUsesHelmValues` / `serviceMonitorSelectorNilUsesHelmValues` / `podMonitorSelectorNilUsesHelmValues` / `probeSelectorNilUsesHelmValues`（`helm get values ps -a` 实测）。

**后果**：不带 `release: ps` 的 PrometheusRule 被 Prometheus 静默忽略 —— 不报错、不挂 condition、规则永不生效。

**这个坑平台现在就踩着**：ServiceMonitor 编辑器的 `labels` 是自由填写（`ServiceMonitorEditorView.vue` 一个空 map），平台不代打这个标。集群里 14 条 ServiceMonitor 全是 chart 的、全带 `release: ps`，**没有一条是租户建的** —— 所以至今没人踩到。

### 2.5 租户 SA 对所有 CRD group 都没有权限

```
kubectl auth can-i list servicemonitors.monitoring.coreos.com  --as=…:tn-default -n default  → no
kubectl auth can-i list prometheusrules.monitoring.coreos.com  --as=…:tn-default -n default  → no
kubectl auth can-i list gateways.gateway.networking.k8s.io     --as=…:tn-default -n default  → no
kubectl auth can-i list pipelineruns.tekton.dev                --as=…:tn-default -n default  → no
```

租户 SA 是 `platform-system/tn-default`（**注意不在业务命名空间里**），通过各命名空间的 RoleBinding `tn-default` 绑到 `ClusterRole/tn-tpl-tenant-namespace-admin`，该 ClusterRole 只有 `'' / apps / batch / networking.k8s.io` 四个 group；该 SA 没有任何 ClusterRoleBinding。

`ResourceAccessResolver` 的注释写明「租户→tenant client（最小权限，K8s RBAC 第二道闸）」，所以**租户侧所有 CRD 页面都应当 403**，只是长期用管理员视角测试才没暴露。

> **这是既有问题，不是本批引入的**，但它挡住本批块 1 的租户路径。已单列为独立任务；本批把它列进 §13 前提。

### 2.6 AlertmanagerConfig 的拾取条件（实测 CRD 文档）

```
Alertmanager.spec.alertmanagerConfigSelector          = {}     ← 空选择器 = 匹配全部 AMC ✓
Alertmanager.spec.alertmanagerConfigNamespaceSelector = {}     ← 非 nil 的空选择器 = 所有命名空间 ✓
Alertmanager.spec.alertmanagerConfigMatcherStrategy   = 未设置 ← ⚠️ 默认 OnNamespace
```

CRD 原文（`kubectl explain`）：

> `alertmanagerConfigNamespaceSelector`：*"defines the namespaces to be selected for AlertmanagerConfig discovery. **If nil, only check own namespace.**"*

> `route.matchers`：*"For the first level route, the operator **removes any existing equality and regexp matcher on the `namespace` label and adds a `namespace: <object namespace>` matcher**."*

> `alertmanagerConfigMatcherStrategy.type`：*"The default value is `OnNamespace`."*

**后果**：AlertmanagerConfig 放在哪个命名空间，就只能收到那个命名空间的告警。放在 `monitor` 的话，只有 `monitor` 的告警会被转发，**其余全部继续进 `null` receiver 静默丢掉** —— 而当前唯一那条 critical 恰好就是 `monitor` 的，所以它会「看起来能用」。

配套改法与完整配置见 **附录 A**。

---

## 3. 决策记录

### 决策 1：告警事件由 Alertmanager **推**，平台不主动拉

Alertmanager 才是告警事件的权威源 —— 分组、抑制、静默、**什么时候恢复**都是它算的。Prometheus 的 `/api/v1/rules` 只给「此刻还在不在 firing」，**已经恢复的它就查不到了**，而且轮询有延迟、恢复事件要靠 diff 推断。

### 决策 2：规则运行状态**如实探测**，不猜 selector

平台不推理「`ruleSelector` 匹不匹配」这种配置问题，而是读 `/api/v1/rules` **看这条规则在不在返回里**。这个判据同时盖住：label 没被拾取、group 名写重、expr 语法错、CRD 版本不对 —— 读 selector 只盖住第一种。

延续 S4.1「存储类写着允许扩容但没人执行」那一课的立场：**别让用户对着一个不生效的东西猜**。

### 决策 3：现在**不部署 Thanos Ruler**

「纳管多集群」≠「跨集群规则」，是两件事：

| | 怎么做 | 要不要 Ruler |
|---|---|---|
| **纳管 N 个集群** | 规则下发到各集群（`PrometheusRule` 带 clusterId）→ 各集群的 Prometheus 各算各的 → 每个 Alertmanager 推到平台，落库带 `cluster_id` | **不需要**。汇聚层是平台自己，不是 Ruler |
| **跨集群规则** | 一条规则的**输入数据跨越多个集群** | **只有它能做**。数据不在一处，任何单集群的 Prometheus 都算不出来 |

指标层已经是多集群架构（`thanos.query.url` 的注释：*"全局单端点…**各集群** Prometheus remote_write 汇入并统一打 cluster_name 标签"*），所以跨集群规则这条路随时能上。

**切换成本也确认过**：平台代码零改动 —— 平台管的是「规则对象」，不管「谁求值」；而 Thanos Ruler 暴露的是**同一个 Prometheus API**（`/api/v1/rules`）。切换只有四件运维动作，见 §12。

### 决策 4：规则清单以 **K8s 对象**为唯一真源

`PrometheusRule` 对象是规则本体的唯一真源（有哪些规则、叫什么、`expr` 是什么）。Prometheus 的 `/api/v1/rules` **只补运行状态**，是附着在 K8s 对象上的只读字段，**不是第二个列表**。拉不到就是「状态未知」，CRUD 照常。

### 决策 5：平台代打 `labels.namespace`

Alertmanager 的 payload 里**没有「这条规则是谁建的」这个字段**；`labels.namespace` 是规则作者自己填的，不可信。所以平台在**下发规则时**往每条 `alert` 规则的 `labels` 写 `namespace: <规则所在命名空间>`，用户写的同名 label **以平台的为准**。

### 决策 6：告警的租户归属在**查询时**按分配表 join，不冗余 `tenant_id`

表里只存 `namespace`。租户可见性 = 该 namespace 在本集群的分配表里**且属于该租户**。

理由：不冗余就不会漂（分配表变了可见性跟着变）；而且**一个命名空间可以分配给多个租户**（`platform_tenant_namespace` 的主键是 `(tenant, cluster, ns)`），定死 `tenant_id` 反而表达不了。

### 决策 7：不加 TTL 缓存

`/api/v1/rules` 每次 list 打一次（一个请求拉全量再 join，不是每条规则一次）。理由同 B6 的 mesh-status：**附近就有「刷新」按钮，缓存会让刷新显示旧值**。

---

## 4. 架构总览

```
块 1  PrometheusRule CRUD         块 2  运行状态回读        块 3  告警接收
─────────────────────────────    ────────────────────    ──────────────────────────
platform-web  列表页/编辑页        （附着在块 1 的 DTO 上）   告警列表页
     │                                  │                        │
platform-api  PrometheusRuleService ──▶ PromRulesClient      AlertController
     │             │ 代打 namespace label      │                 │
     │             └───────────────────────────┘                 ▼
     │  K8sClient                              ┌────── AlertIngestService ──▶ MySQL
     ▼                                        │              ▲
k8s-server  PrometheusRuleController      Prometheus API   Alertmanager
     │      (分配表边界)                    /api/v1/rules    (webhook push)
     ▼
k8s-core  PrometheusRuleOperations + Converter
```

---

## 5. 块 1 · PrometheusRule CRUD

与 ServiceMonitor **逐层同构**。这一仓的惯例是「同类资源不抽公共基类，宁可逐资源各写一份」（见 `standing-architecture-principles`），所以逐层各写一份，不抽基类。

### 5.1 新文件

| 层 | 文件 | 参照 |
|---|---|---|
| platform-common | `dto/PrometheusRuleDTO.java` | `ServiceMonitorDTO` |
| k8s-core | `converter/impl/monitoring/PrometheusRuleConverter.java` | `ServiceMonitorConverter` |
| k8s-core | `operations/monitoring/PrometheusRuleOperations.java` | `ServiceMonitorOperations` |
| k8s-core | `ResourceType` 加 `PROMETHEUS_RULE` + factory 加 case | `SERVICE_MONITOR` 那行 |
| k8s-server | `controllers/namespace/PrometheusRuleController.java` | `ServiceMonitorController` |
| platform-api | `controllers/PrometheusRuleController.java` | `ServiceMonitorController` |
| platform-api | `services/PrometheusRuleService.java` | `ServiceMonitorService` |
| platform-web | `views/resource/PrometheusRuleView.vue` / `PrometheusRuleEditorView.vue` | `ServiceMonitorView` / `ServiceMonitorEditorView` |
| platform-data | `db/migration/V2026_10_17_1__alerting.sql` | `V2026_09_29_1` + `V2026_10_07_1` |

前端另需：`api/index.ts` 的 `prometheusRuleApi`、`types.ts` 的 `K8sPrometheusRule`、`router`、`apiCodes`、`pageCodes`。

### 5.2 DTO 形态

CRD 字段已逐一核对（`kubectl explain prometheusrule`），**全部建模**：

```
PrometheusRuleDTO
  ├ name / labels / namespace / tenantId / clusterId   (BaseResources)
  ├ groups: List<Group>
  ├ creationTime: String                               (仅查询返回)
  └ runtime: RuleRuntime                               (仅查询返回，见 §6)

Group
  ├ name                        (必填)
  ├ interval / queryOffset / limit
  ├ labels: Map<String,String>
  ├ partialResponseStrategy     (仅 ThanosRuler 用，Prometheus 忽略)
  └ rules: List<Rule>

Rule
  ├ alert                       (与 record 二选一)
  ├ record                      (与 alert 二选一)
  ├ expr                        (必填；CRD 里是 IntOrString)
  ├ for / keepFiringFor
  ├ labels: Map<String,String>
  └ annotations: Map<String,String>
```

> ⚠️ `expr` 在 CRD 里是 `IntOrString` —— 上游允许裸数字（`expr: 1`，等价于 `vector(1)`）。DTO 用 `String`，converter 写出时按字符串写回；**读回来若是数字，转成字符串**，否则 round-trip 会丢类型信息。

### 5.3 ⚠️ `spec.groups` 与 `group.rules` 都是 atomic list

CRD 里两者都没有 `listType=map` → **默认 atomic**。SSA 会把整个列表当一个值拥有，漏一个字段就抹掉（memory `fabric8-ssa-generic-crd` 记的同一个坑；HTTPRoute 的 `spec.rules` 同形）。

**必须显式 fetch-overlay**，不能靠 schema 语义推断：

- **组的对齐键**：`group.name`（Prometheus 要求同一文件内组名唯一）
- **规则的对齐键**：`rule.alert` 或 `rule.record`（Prometheus 要求同一组内规则名唯一）
- 线上有、本次没带的组/规则 → 保留（不做静默删除；删除由用户在 UI 上显式删）
- 未建模的**新字段**（将来 CRD 版本新增）→ 按上述键对齐后原样带回

即便本版已建模全部已知字段，**overlay 仍要写**：理由同 `GatewayConverter` —— 显式 round-trip 比依赖 schema 语义推断更可测，也对 CRD 版本漂移更稳健。

### 5.4 本层唯一的业务规则

`PrometheusRuleService` 里（**不放 k8s-server**，那里的职责是「提供集群事实」，不是「按产品规则改内容」）：

> 在 `create` / `update` 时，遍历所有 `alert` 规则，把 `labels["namespace"]` 覆写为**该规则所在命名空间**。`record` 规则不改（记录规则不产生告警，写进去只污染指标标签）。

用户自己写的同名 label 以平台的为准 —— 这是唯一能保证归属可信的写法（依据见决策 5）。

**这条规则要能解释清楚**：编辑页在 `labels` 输入区旁放一句说明，YAML tab 保持全保真（能看到平台代打的 label）。

---

## 6. 块 2 · 规则运行状态回读

### 6.1 新组件

`platform-api/.../metrics/PromRulesClient.java`，形状与 `PromDiscoveryClient` 完全一致：

- 按集群 `baseUrl` 传入（**不是全局单端点**）
- `SimpleClientHttpRequestFactory`，connect 5s / read 10s
- lenient `JsonMapper`（`FAIL_ON_UNKNOWN_PROPERTIES=false`，默认命名，不套全局 snake_case）
- **只负责「拉 + 解析」，不掺业务**（选哪条、怎么 join 由 `PrometheusRuleService` 决定）
- 失败一律降级为空 + `log.warn`，**不抛**

> 为什么不塞进 `PromDiscoveryClient`：那个类的名字与职责是「服务发现」，塞 `/api/v1/rules` 进去名不副实。

地址取 `clusterMapper.selectByPrimaryKey(clusterId).getPrometheusUrl()`，与 `ServiceMonitorService` 同一写法（`replaceAll("/+$", "")` 去尾斜杠；空则直接降级）。

### 6.2 探测与对齐

**探测**：`GET <prometheusUrl>/api/v1/rules` → `data.groups[]`。

**对齐**（关键 —— 不解析 Prometheus 的字符串，而是拿 K8s 对象去匹配）：

`/api/v1/rules` 的每条 group 只带 `name` 和 `file`，而 `file` 形如：

```
/etc/prometheus/rules/prometheus-ps-…-rulefiles-0/monitor-ps-kube-prometheus-stack-alertmanager.rules-21737e75-….yaml
```

那是 `<namespace>-<CR名>-<uuid>.yaml`，**盲解析是错的** —— 命名空间和 CR 名都含 `-`：

```
monitor-ps-kube-prometheus-stack-alertmanager.rules-<uuid>   → 按第一个 - 切：ns=monitor ✓
my-app-rules-<uuid>                                          → 按第一个 - 切：ns=my      ✗
```

**正确做法**：平台手上有 `(namespace, CR名)` 的**完整清单**（块 1 的 list 结果），为每个生成前缀 `<ns>-<CR名>-`，用**候选集精确匹配** group 的 `file`。永远在已知集合里对，不存在切错的可能。

组内再按 `(group.name, rule.name)` 对齐到具体那条规则。

### 6.3 附着到 DTO 的只读字段

```java
RuleRuntime {
    String loadState;        // loaded | missing | unknown
    List<RuleHealth> rules;
}
RuleHealth {
    String group;            // spec.groups[].name
    String name;             // alert 的 alertname / record 的 record 名
    String health;           // ok | err | unknown
    String lastError;        // health=err 时 Prometheus 给的原文
    String state;            // inactive | pending | firing（recording 规则为 null）
    Integer activeAlerts;    // alerting 规则的活跃告警数
}
```

`loadState` 三态：

| 值 | 判据 | UI |
|---|---|---|
| `loaded` | 在 `/api/v1/rules` 里按 §6.2 对齐上了 | 「已加载」 |
| `missing` | 没对齐上，且 `now - creationTimestamp` 超过宽限期（**5 分钟**，覆盖 prometheus-operator 的 reload 延迟） | 「**未加载（已 N 分钟）**」+ 悬停解释可能原因 |
| `unknown` | Prometheus 拉不到 / 没配 `prometheus_url` / 解析失败 | 「状态未知」 |

宽限期内一律显示「加载中」，**不报 missing** —— 刚建完的规则本来就还没被 reload。

### 6.4 为什么这个判据比读 `ruleSelector` 好

| 失效原因 | 读 `/api/v1/rules` | 读 `ruleSelector` 推理 |
|---|---|---|
| 缺 `release: ps` 等标签 | ✅ 能看出 | ✅ 能看出 |
| group 名写重 | ✅ | ❌ |
| expr 语法错 | ✅（`health: err` + `lastError` 原文） | ❌ |
| CRD 版本不对 / operator 没装 | ✅ | ❌ |
| Prometheus 还没 reload | ✅（宽限期） | ❌ |

而且它读的是**事实**，不是对配置的推理 —— 与决策 2 的立场一致。

---

## 7. 块 3 · 告警接收 · 落库 · 列表

### 7.1 接收端点 `POST /alert/webhook`

**平台第一个非用户身份的入站端点。** 两条配套约束：

1. **进 `ExemptPaths`** —— 它不是某个登录用户调的，没有 JWT。先例：`/mesh/gatewayclass-refs`。
2. **自校验共享密钥** —— `Authorization: Bearer <配置项>`。密钥**没配就拒启**（`@PostConstruct` 检查 + 抛异常），而不是默默放行。理由同 `PermissionCrossCheckRunner` 的 fail-closed 作风：一个"忘了配就等于全开"的入站端点不该存在。

配置项：`platform.alert.webhook.token`（env `ALERT_WEBHOOK_TOKEN`）。

**请求体**是 Alertmanager webhook 的 `[]Alert`（即 `/api/v2/alerts` 的形状），每项含：

```
status      { state: firing|resolved, silencedBy: [], inhibitedBy: [] }
labels      { alertname, severity, namespace, … }
annotations { summary, description, … }
startsAt / endsAt / generatorURL / fingerprint
```

> ⚠️ **每次推的是「当前批次快照」，不是增量**。而且 Alertmanager 在投递失败时会重试、`repeat_interval` 到点会重推。所以落库**必须幂等** —— 见 §8。

**响应**：`200` + 一个极简体（如 `{"accepted": N}`）。**不要返回业务错误码语义**：Alertmanager 只看 HTTP 状态码，4xx/5xx 会触发重试。所以业务上的「这条我不认识」也返回 200。

### 7.2 落库 `AlertIngestService`

1. 逐条读 `labels.namespace`（可能为空）。
2. **校验**：该 namespace 在本集群的分配表里吗？不在 → **仍然落库**（管理员要看得见），但记 `log.warn`，列表页对管理员标出「不属于任何租户」。
3. 按 §8 的幂等键 upsert：`status` / `endsAt` / `annotations` / `severity` 以**本次 payload 为准**覆盖。

### 7.3 查询端点 `POST /alert/list`

服务端按 token 收窄（同 `NamespaceAllocationService.list` 的写法）：

- token 带 `tenantInfo` → 取该租户在本集群的 ns 集合（`listByTenantAndCluster`，见 §8.2），只返回 `namespace` 落在其中的行；**token 里的 tenantId 是唯一来源**，入参一律被覆盖
- 无租户 claim 的平台管理员 → 返回全部，并**标出** `namespace` 不在任何分配表里的行（那些就是「不属于任何租户」的）

筛选：`starts_at` 时间范围、`status`、`severity`、`namespace`、`alertname` 模糊。

分页：**游标**，与 `/audit/list` 同口径（游标键 `(starts_at, id)`）。

**`Watchdog` 的展示**：它是 kube-prometheus-stack 装的心跳告警（`expr: vector(1)`），**永远处于 firing**，不过滤的话列表首屏就挂着一条永不消失的告警。做法：

> 列表**默认过滤掉 `alertname = Watchdog`**，页面上给一个「显示心跳告警」的开关（默认关）。

**在平台侧过滤，不在集群侧排除** —— 集群侧排除的话平台永远不知道它存在，将来想看也看不到，而且那条 404 之外的链路证据也就没了。

### 7.4 自检端点 `POST /alert/self-test`

平台用 `RestClient` 打**自己的回环地址** `http://127.0.0.1:${server.port}/alert/webhook`，带正确的 Bearer，投一条合成的 `firing → resolved`。

走完整 HTTP 链路（含鉴权、解析、落库），**不用等真告警就能把整条链验一遍**。返回：HTTP 状态、落库行数、生成的行 id。

再提供「按 id 删掉测试数据」的能力（复用删除端点或列表页的行操作），免得测试告警混进真实列表。

> 这个端点是为了解决「链路通不通」只能等真告警的问题 —— 与 §13 前提 2/5 有关：集群侧配好之后，平台这边得有个**自证的**手段。

---

## 8. 数据模型

### 8.1 新表 `platform_alert_event`

```sql
CREATE TABLE IF NOT EXISTS `platform_alert_event` (
  `id`          VARCHAR(64)  NOT NULL COMMENT 'ULID',
  `cluster_id`  VARCHAR(64)  NOT NULL,
  `namespace`   VARCHAR(253)          DEFAULT NULL COMMENT '来自 labels.namespace，规则作者自报；归属靠查询时 join 分配表',
  `alertname`   VARCHAR(253) NOT NULL,
  `severity`    VARCHAR(64)           DEFAULT NULL COMMENT 'labels.severity',
  `status`      VARCHAR(16)  NOT NULL COMMENT 'firing | resolved',
  `fingerprint` VARCHAR(64)  NOT NULL COMMENT 'Alertmanager 给，标签集的哈希',
  `labels`      JSON                  DEFAULT NULL,
  `annotations` JSON                  DEFAULT NULL,
  `starts_at`   DATETIME(3)  NOT NULL COMMENT '本次 firing 会话开始时刻',
  `ends_at`     DATETIME(3)           DEFAULT NULL COMMENT 'resolved 才有',
  `received_at` DATETIME(3)  NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_alert_episode` (`cluster_id`, `fingerprint`, `starts_at`),
  KEY `idx_alert_ns_time` (`cluster_id`, `namespace`, `starts_at`),
  KEY `idx_alert_time`    (`cluster_id`, `starts_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警事件（一次 firing 会话一行）';
```

**幂等键 = `(cluster_id, fingerprint, starts_at)`**，理由：

- 只用 `fingerprint` 不行 —— Alertmanager 的 fingerprint 是**标签集的哈希**，一条告警「fire → resolve → 再 fire」fingerprint 不变，会把两次会话压成一行、丢掉历史。
- 加上 `starts_at` 就对了：Alertmanager 在同一次会话内**保持 `startsAt` 不变**（resolve 时只补 `endsAt` 并把 status 置为 `resolved`），重新 fire 才产生新的 `startsAt`。于是**一次会话一行**，`status` 由 `firing` 迁到 `resolved`。
- 时间统一按**毫秒**归一（`DATETIME(3)`）后再进键，避免精度差异导致唯一键失配、产生重复行。

**刻意不加 `tenant_id` 列**（决策 6）：归属在查询时 join `platform_tenant_namespace` 决定。

### 8.2 新增 mapper 方法

`PlatformTenantNamespaceMapper` 现有的五个查询都是**别的方向**的，本批需要两个新的（接口 + XML 各加一处）：

| 方法 | 用途 | 为什么现有的不够 |
|---|---|---|
| `List<PlatformTenantNamespace> listByTenantAndCluster(tenantId, clusterId)` | 租户侧告警列表的可见性过滤：先取该租户在本集群的 ns 集合，再 `where namespace in (...)` | `listByTenant(tenantId)` **不带 clusterId**，多集群下会把别的集群的 ns 也算进来 |
| `List<PlatformTenantNamespace> selectByClusterAndNs(clusterId, namespace)` | 落库时校验 `labels.namespace`；返回 List 是因为**一个 ns 可能分给多个租户** | `selectByTenantClusterNs` 需要先知道 tenantId —— 而我们要做的正是**反查** tenantId |

### 8.3 错误码

现有最大值 **10043**。本批新增从 **10044** 起：

- `ALERT_WEBHOOK_UNAUTHORIZED(10044)` —— 密钥不对
- `PROMETHEUS_RULE_INVALID(10045)` —— 规则内容不合法（如 `alert` 与 `record` 同时给、都没给）

> ⚠️ 本仓有 `EnumResponseTypeTest.business_error_codes_are_unique` 兜底，但 **S4.1 时正是漏跑了它才撞号**。改枚举后必须跑 `mvn -pl platform-common -am test`。

---

## 9. 权限模型

### 9.1 迁移 `V2026_10_17_1__alerting.sql`

**⚠️ 路径无 `/resource` 前缀** —— 那是 `V2026_10_08_1` 之前的旧写法，现存行形如 `/servicemonitors/list`、`/pvcs/{name}`。Page 域仍用 `/resources/...`。（S4.1 正是在这儿抄了过期迁移文本，导致 `PermissionCrossCheckRunner` 拒启。）

| id | domain | resource | action | code |
|---|---|---|---|---|
| `perm_res_pr_list` | API | `/prometheusrules/list` | POST | `tenant:prometheusrule:list` |
| `perm_res_pr_get` | API | `/prometheusrules/{name}` | GET | `tenant:prometheusrule:get` |
| `perm_res_pr_yaml` | API | `/prometheusrules/{name}/yaml` | GET | `tenant:prometheusrule:yaml` |
| `perm_res_pr_create` | API | `/prometheusrules` | POST | `tenant:prometheusrule:create` |
| `perm_res_pr_update` | API | `/prometheusrules/{name}` | PUT | `tenant:prometheusrule:update` |
| `perm_res_pr_delete` | API | `/prometheusrules/{name}` | DELETE | `tenant:prometheusrule:delete` |
| `perm_page_pr_list` | Page | `/resources/prometheusrules` | VIEW | `tenant:page:prometheusrule.list` |
| `perm_page_pr_edit` | Page | `/resources/prometheusrules/editor` | VIEW | `tenant:page:prometheusrule.edit` |
| `perm_alert_list_platform` | API | `/alert/list` | POST | `platform:alert:read` |
| `perm_alert_list_tenant` | API | `/alert/list` | POST | `tenant:alert:list` |
| `perm_alert_self_test` | API | `/alert/self-test` | POST | `platform:alert:test` |
| `perm_page_alert` | Page | `/alerts` | VIEW | `platform:page:alert` |

**一个端点两行权限**的先例：`perm_alloc_list_self`（`tenant:overview:view` 的成员也能进 `/allocation/list`）。

`POST /alert/webhook` **不建权限行** —— 它进 `ExemptPaths`，不是用户身份调的。

### 9.2 角色绑定

- `tenant:prometheusrule:*` + `tenant:page:prometheusrule.*` → `tenant_admin`（写动词）/ `tenant_member`（只读动词）
- `platform:alert:read` + `platform:alert:test` + `platform:page:alert` → `builtin_role_admin`
- `tenant:alert:list` → `tenant_admin`

**⚠️ 每一行都要显式绑角色**（memory `b3-namespace-ambient-toggle` 的教训：新权限行不按 code 继承）。

---

## 10. 错误与降级

| 场景 | 行为 |
|---|---|
| `prometheus_url` 为空 / 拉不到 / 解析失败 | 块 2 降级：`runtime = null` → UI 显示「状态未知」。CRUD 正常工作，只 `log.warn` |
| 规则还没被 reload（宽限期内） | 显示「加载中」，不报 missing |
| webhook 密钥不对 | `401`（让 Alertmanager 重试没意义，但也不泄露细节） |
| webhook 密钥没配 | **应用拒启** |
| webhook 体解析失败 | `400` |
| 落库单条失败 | 记 `log.error`，**继续处理同批其余条目**，最后返回 200 + 实际成功数 |
| `labels.namespace` 不在任何分配里 | 照常落库，`log.warn`，管理员列表里标出 |

---

## 11. 测试策略

### 块 1

- **converter 的 fetch-overlay 往返**（照 `HttpRouteConverterTest`）：atomic list 不丢未建模字段；组的对齐按 `name`、规则按 `alert`/`record`；线上有而本次没带的组/规则被保留。
- **代打 `namespace` 标签**：`alert` 规则被打上；用户写了别的值 → 以平台为准；`record` 规则**不打**。
- **`alert` 与 `record` 二选一**的校验（都没给 / 都给了 → `PROMETHEUS_RULE_INVALID`）。
- **`expr` 是 IntOrString**：裸数字读回不丢。

### 块 2

- **对齐算法**（纯函数，最容易错）：
  - `my-app` + `rules` → 前缀 `my-app-rules-`，**不能**被切成 ns=`my`
  - 一个 CR 的多个 group
  - group 里 recording 规则没有 `state` / `activeAlerts`
  - 对不上 → `missing`；宽限期内 → 不报 missing
- **降级**：`prometheusUrl` 空 / HTTP 500 / 超时 / JSON 畸形 → 一律 `unknown` + 不抛。

### 块 3

- **幂等 upsert**：同一 `(fingerprint, startsAt)` 连投三次 → 只有一行；`firing → resolved` 是**同一行**状态迁移；resolve 后重新 fire（新 `startsAt`）→ **新的一行**。
- **时间精度**：`startsAt` 带小数秒 → 归一后唯一键稳定。
- **密钥**：不配 → 拒启；配错 → 401。
- **租户过滤**：token 带 `tenantInfo` 时只返回本租户分配表内的 namespace；一个 namespace 分给两个租户时两个都能看到；伪造 `labels.namespace` 成别人的命名空间 → **伪造者自己看不到**（会出现在被伪造的那个租户的列表里，见 §13 残留风险）。
- **一批多个租户**：同一批 payload 里的告警分属不同命名空间，逐条判定。
- **self-test** 端到端：调用后 /alert/list 里能查到。

### 前端

- 纯展示叶子组件走 **Approach B**（直接从 Vite deps import SFC + vue + element-plus 挂 scratch app，免整套后端）。
- 列表页 + 编辑页需要活后端，走「假 token + XHR stub + 单例 store 播种 + `pushState` + `reload`」那套（memory `ui-preview-verification`）。

### 真跑

- **`curl` 模拟 Alertmanager 投递**验落库 —— **不需要真配 Alertmanager** 就能验块 3 的绝大部分。
- **块 2 对真集群验**：本集群 `/api/v1/rules` 有 40 组 / 280 条，是现成的真实数据（含 2 firing、4 pending）。

---

## 12. 未覆盖 / 有意不做

1. **规则表单的「简单档」**（指标下拉 + 阈值 + 时长 → 生成 expr）。`metric-names` 候选端点已经有了，加这一档成本不高，但本批不做。
2. **告警的静默 / 认领 / 备注**。属 Alertmanager 管理面，已定不做。
3. **发送渠道**。用户明确待定。
4. **`record` 规则的运行状态**只显示 `health`（recording 规则没有 `state`/`activeAlerts`）—— 这是 Prometheus API 的形状，不是省略。
5. **跨集群规则**（一条规则的输入跨多个集群）。要它才需要 Thanos Ruler，见决策 3。
6. **告警去重的跨集群语义**。当前一集群一 Alertmanager，`fingerprint` 在不同集群间可能碰撞；唯一键带了 `cluster_id` 所以不冲突，但**跨集群的「同一条告警」不会被识别为同一条** —— 有意如此。
7. **平台不会主动清理 `platform_alert_event`**。长期增长需要保留策略，本批不做（审计日志有 `/audit/purge` 的先例，将来可照做）。

---

## 13. 前提与风险清单

### 集群侧（不在仓库里，必须在部署本批之前完成）

| # | 前提 | 现状 | 依据 |
|---|---|---|---|
| 1 | **RBAC 模板补 `monitoring.coreos.com`** | ⚠️ **未做** —— 已单列任务。同一问题还影响 ServiceMonitor / PodMonitor / Gateway / 5 类 Route / Pipeline 五个既有页面 | §2.5 |
| 2 | Alertmanager 的 receiver 指向平台 | 待办，配置见附录 A | §2.6 |
| 3 | **`sendResolved` 必须为 `true`** | ⚠️ 待确认 —— 若为 false，**平台里的告警永远停在 firing**（恢复事件根本不来）。本批第三个静默坑 | 附录 A |
| 4 | `alertmanagerConfigMatcherStrategy` 不能是默认的 `OnNamespace` | ⚠️ **现在是默认值** —— 不改的话平台只收到一个命名空间的告警 | §2.6 |
| 5 | 四个 `*SelectorNilUsesHelmValues` flag 关掉 | 用户认领 | §2.4 |
| 6 | 平台地址对 Alertmanager Pod 可达 | 用户认领 | — |

> 前提 3 与 4 是**同一个形状**的坑：配错了不会报错，只会「看起来在工作、其实少收一堆」。附录 A 给的是能一次配对的完整方案。

### 残留风险（接受，但显式记下）

**`labels.namespace` 是可伪造的**。平台在**下发规则时**代打，所以经平台建的规则伪造不了；但租户拿到 `monitoring.coreos.com` 的 RBAC 之后可以 `kubectl apply` 一条带伪造 label 的规则，让**别的租户**多看到一条假告警。

影响面：**只能注入，不能读取** —— 伪造者看不到别人的告警。要彻底堵死只能靠「落库时按规则真实来源反查」，而 Alertmanager 的 payload 里没有这个信息（`generatorURL` 只含 alertname，不含命名空间，且 alertname 跨命名空间不唯一）。

**这一条要写进用户文档**：命名空间的归属来自告警标签，由规则作者填写。

---

## 14. 部署清单

**服务**：`platform-common` → `k8s-core` → `k8s-server` → `platform-api` → `platform-web`（`mvn -pl <m> -am` 构建，**`-am` 不能省**）。

**迁移**（**本仓无 Flyway，一律手工重放**）：先重放 `V2026_10_17_1__alerting.sql` **再**起新构建，否则 `PermissionCrossCheckRunner` 拒启。

**登录**：权限行变了要**重新登录**（权限闭包在签发时算）。

**集群侧**：附录 A。

---
---

# 附录 A · 集群侧配置方案（Alertmanager）

> 这部分**不在仓库里**，是运维动作。列在这里是因为上面的代码依赖它才能端到端跑通。
> 对象：Helm release `ps`（kube-prometheus-stack **88.6.2**，`monitor` 命名空间）/ Alertmanager **v0.34.0** / Prometheus Operator **v0.93.1**。

## A.1 现状

```
Alertmanager.spec.alertmanagerConfigSelector          = {}     ← 空选择器 = 匹配全部 AMC ✓ 不用改
Alertmanager.spec.alertmanagerConfigNamespaceSelector = {}     ← 非 nil 空选择器 = 所有命名空间 ✓ 不用改
Alertmanager.spec.alertmanagerConfigMatcherStrategy   = 未设置 ← ⚠️ 默认 OnNamespace，必须改
Alertmanager.spec.alertmanagerConfiguration           = null   ← 用基线 secret，不用改

当前生效的配置（/api/v2/status 读回来的）：
  route.receiver: "null"            ← 所有告警进这里，全丢
  route.group_by: [namespace]
  routes: [{ receiver: "null", matchers: [alertname="Watchdog"] }]
  inhibit_rules: critical 抑制 warning/info、warning 抑制 info、InfoInhibitor
```

**基线那条 `route: {receiver: "null"}` 不用动** —— operator 会把 AMC 的 route 作为**一级子路由**挂进去，父路由是兜底；就算有告警同时落到 `null` 上，那边也只是丢弃，没有副作用。

## A.2 ⚠️ 必须先改 Helm values

```yaml
alertmanager:
  alertmanagerSpec:
    alertmanagerConfigMatcherStrategy:
      type: OnNamespaceExceptForAlertmanagerNamespace
```

**为什么**：默认 `OnNamespace` 会让 operator 往 AMC 的一级路由注入 `namespace="<AMC 所在命名空间>"` 的 matcher（§2.6 的 CRD 原文）。不改的话，把 AMC 建在 `monitor` 就只收到 `monitor` 的告警 —— 而当前唯一在 firing 的 critical 恰好属于 `monitor`，所以**它会看起来能用**。

`OnNamespaceExceptForAlertmanagerNamespace` 的语义是「AMC 建在 Alertmanager 自己所在命名空间时不受 namespace 约束，别处的照旧」—— 只开一个小口子。另一个选择是 `None`（所有 AMC 都不受限），**不推荐**：那是把集群里所有人的 AMC 语义一起放宽了。

> ⚠️ 该枚举值的语义是**按名字推断**的 —— CRD 里没写逐值说明。改完请按下文 A.5 用一个非 `monitor` 命名空间的测试告警验证一遍。

```bash
helm upgrade ps prometheus-community/kube-prometheus-stack -n monitor -f values.yaml --version 88.6.2
```

## A.3 要建的两个对象

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: platform-alert-token
  namespace: monitor
type: Opaque
stringData:
  token: <平台配置项 platform.alert.webhook.token 的同一个值>
---
apiVersion: monitoring.coreos.com/v1alpha1
kind: AlertmanagerConfig
metadata:
  name: platform-webhook
  namespace: monitor          # 必须在 monitor —— A.2 那个策略只对 Alertmanager 自己命名空间开口子
spec:
  route:
    receiver: platform
    groupBy: ['namespace', 'alertname']
    groupWait: 30s
    groupInterval: 5m
    repeatInterval: 12h
  receivers:
    - name: platform
      webhookConfigs:
        - url: http://<平台地址>/alert/webhook
          sendResolved: true        # ⚠️ 前提 3：不给 true，平台里的告警永远停在 firing
          timeout: 10s
          httpConfig:
            authorization:
              type: Bearer          # 默认值就是 Bearer；CRD 明确写了 "Basic" 不支持
              credentials:
                name: platform-alert-token
                key: token
```

字段名已对 CRD 逐一核对：AMC 里是 **`sendResolved`**（不是 Alertmanager 原生配置里的 `send_resolved`）；`httpConfig.authorization.credentials` 是 SecretKeySelector；`url` 与 `urlSecret` 二选一。

## A.4 两个小决定

- **`Watchdog`** 是永远 firing 的心跳告警（`expr: vector(1)`）。**不在集群侧排除** —— 由平台侧过滤并给开关，见 §7.3。所以 A.3 那份配置里**不要**加 `Watchdog` 的排除路由。
- **`repeatInterval: 12h`** 是 kube-prometheus-stack 的默认值。平台按 `(fingerprint, startsAt)` upsert，重复推送幂等，但白跑一次网络。12h 合适，**别调短**。

## A.5 验证

按顺序，每一步都能独立看出问题在哪一层：

1. **配置被 operator 接受了**（AMC 生成到 secret 里）
   ```bash
   kubectl get secret alertmanager-ps-kube-prometheus-stack-alertmanager-generated -n monitor -o jsonpath='{.data.alertmanager\.yaml}' | base64 -d | grep -A3 platform
   ```

2. **namespace 约束确实没了** —— 上面那份生成的配置里，`platform` 那条路由**不应**出现 `namespace="monitor"` 的 matcher。

3. **链路通**：平台端点还没上线时，Alertmanager 会收到 404。**能收到 404 就说明网络与配置都对了**，比等真告警快得多。
   ```bash
   kubectl logs -n monitor alertmanager-ps-kube-prometheus-stack-alertmanager-0 -c alertmanager --tail=50 | grep -i webhook
   ```

4. **端到端**：用平台的 `POST /alert/self-test` 自证（§7.4）—— 它打自己的回环地址，验的是**平台这一侧**的完整链路。集群那一侧由上一步的 404 证明。

5. **`sendResolved` 真的生效**：让一条告警 firing 再恢复，看平台列表里那行状态从 `firing` 变成 `resolved`（**同行迁移**，不是新增一行）。这条必须验 —— 它是前提 3 唯一的证据。

## A.6 改完如果只想回退

删掉 `AlertmanagerConfig/platform-webhook` 即可 —— 基线那条 `null` receiver 一直没动过，告警回到「全丢」的原始状态。
