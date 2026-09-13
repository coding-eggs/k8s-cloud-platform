# Thanos 监控指标功能设计（工作负载详情 CPU / 内存 / 网络 / 磁盘）

日期：2026-09-03　状态：**设计定稿（待用户确认后进入实现）**

## 1. 背景与目标

- **现状**：`PodDetailView.vue` / `ContainerDetailView.vue` 只有静态配置展示，无运行指标。
- **目标**：给**工作负载详情**与 **Pod 详情**各加 4 个实时图表（共 **8 个**）：**CPU、内存、网络 IO、磁盘 IO**。数据经 **Thanos Query** 查询 cAdvisor 容器指标。**容器级查不到**——Prometheus 未采集到容器名称，无法按具体容器查询。
- **数据源架构**（详见 `docs/remote-write-prometheus-thanos.md`）：每个集群的 Prometheus 通过 remote_write 汇入 Thanos，并统一打 **`cluster_name`** 标签（值 = 人类可读集群名，来自 `K8sCluster.clusterName`）。Thanos Query 是**全局单一端点、无鉴权**。
- **范围**：当前只做工作负载级；架构要能横向扩到**命名空间 / 集群 / 租户**汇总——靠往查询目录加具名常量即可，client / controller / 前端结构不动。

## 2. 已确认的设计决策

| # | 决策 | 说明 |
|---|------|------|
| D1 | **数据源 = Thanos Query，无鉴权** | 全局单端点；`cluster_name` 标签 = `K8sCluster.clusterName`（由 clusterId 解析）。传输层只配 baseUrl + 超时 + lenient mapper + GET |
| D2 | **三层彻底分离** | `MetricQuery` 枚举（纯数据目录）→ `ThanosQueryClient`（两个通用方法，收**完整 promql**）→ `Service`（业务拼接）。client **不认识枚举** |
| D3 | **枚举扁平 + 具名常量** | 命名 = `{范围}_{指标}_{语义}`：范围 ∈ CONTAINER/POD/WORKLOAD/NAMESPACE/CLUSTER/TENANT；指标 ∈ CPU/MEMORY/NETWORK/DISK；**语义后缀按查询实际算的取** `_USED`(用量) / `_TOTAL`(总量) / `_AVG`(平均)。横向加常量即可，结构不动 |
| D4 | **client 两个通用方法** | `query(sql, time, type)` / `queryRange(sql, start, end, step, type)`；两者都自动带 `dedup=true&partial_response=true` |
| D5 | **结果类：本功能不需要字段** | 8 条都是纯 `sum(...)`（无 `by`），返回的 metric map 为空，业务只读数值、不读任何 label。故这些查询用**空父类 `MetricLabels`**（当前无字段）即可；typed 字段能力**保留给未来需要 `by(xxx)` 拆分的查询**（届时加子类补字段） |
| D6 | **网络 RX/TX、磁盘读/写 = 两条线** | 方向作为模板**第一个 `%s`**，一个常量出两条线（RX+TX / 读+写），图例由调用方给 |
| D7 | **8 个接口 = 2 维度 × 4 指标，无新建 controller** | 工作负载详情 4 个 + Pod 详情 4 个；cpu/memory/network/disk 各一。**不新建 metrics controller**——工作负载的写进现有 `WorkloadController`、Pod 的写进现有 `PodController`；前端每维度 4 次调用 |
| D8 | **step = 30s（当前默认），不校验上限** | start/end 用 unix 秒；**不强制 ≤30min**——后续要支持更大时间宽度，仅调 step/前端预设即可。当前前端预设 5m/15m/30m |
| D9 | **百分比仅 CPU / 内存** | CPU/内存**有配额时可切 %**、**由前端控制**（用 pod spec limits 本地算）；**网络、磁盘无 %**——磁盘只有 IO（`container_fs_reads/writes_bytes_total`），无配额概念。接口只回原始序列 + unit |
| D10 | **集群管理字段完善** | 新增 `prometheus_url`/`grafana_url`（**仅功能完善，本监控不用**）+ 补齐 `version`/`istioVersion`/`calicoVersion`/`containerRuntime`/`ipStack`；kubeconfig 编辑可改、带提示确认 |

## 3. 总体分层架构

```
platform-web   MetricsPanel（4 图 + 时间范围）→ MetricChart（单图 ECharts）
  │            每维度 4 次调用：cpu / memory / network / disk
  ▼
platform-api / WorkloadController（+4 POST）· PodController（+4 POST）   （薄，委托共享组合逻辑；不新建 controller）
  ▼
platform-api / MetricsService                ★业务层：clusterId→clusterName、选具名常量、String.format 拼完整 sql、组响应
  ▼
platform-api / ThanosQueryClient.queryRange   （通用收发：完整 sql + start/end/step + labelType；自动带 dedup/partial_response）
  │            RestClient（baseUrl=Thanos Query，无鉴权）+ lenient JsonMapper
  ▼
Thanos Query  /api/v1/query_range             （全局单端点，cluster_name 标签区分集群）
```

- **枚举 `MetricQuery`**：纯数据。每个常量 = 一条具名查询 =（返回类型 + 带 `%s` 的 promsql）。零逻辑。
- **client**：只收完整 sql + 时间参数 + 反序列化目标类，返回 `List<PromResult<T>>`；不感知枚举、不感知业务。
- **service（共享 `MetricsService`）**：决定「用哪条具名常量、填什么值」+ clusterId→clusterName + 组 series——唯一知道业务语义的地方；由两个维度的 controller 调用。

---

# Part A. 集群管理字段完善

## A.1 数据库

新增两列（用户自行执行）：

```sql
ALTER TABLE k8s_cluster ADD COLUMN prometheus_url VARCHAR(255) NULL COMMENT 'Prometheus 地址（仅记录，本监控功能不用）';
ALTER TABLE k8s_cluster ADD COLUMN grafana_url    VARCHAR(255) NULL COMMENT 'Grafana 地址（仅记录）';
```

> 说明：这两个地址**不用于**本监控功能——我们只打 Thanos Query。此处仅为集群信息完整。

## A.2 实体 / DTO

- `K8sCluster`（platform-data）：新增 `String prometheusUrl`、`String grafanaUrl`。
- `ClusterCreateRequest` / `ClusterUpdateRequest`（platform-api）：补齐字段
  - `version`、`istioVersion`、`calicoVersion`、`containerRuntime`、`ipStack`（枚举 IPV4/IPV6/IPV4_AND_IPV6）
  - `prometheusUrl`、`grafanaUrl`
  - （原有 `clusterName` / `kubeconfig` / `description` 保留）

## A.3 Service（create / update）

- **create**：补全所有字段赋值（含 `ipStack`——修掉现状 `ip_stack NOT NULL` 无默认值的隐患）；`kubeconfig` 走 `AESUtils.encrypt` + `adminClient.probe()`。
- **update**：
  - `kubeconfig`：**留空 = 保留原值**；填了 = 重新 probe + 重新加密。
  - 其余新字段正常 set。
- `sanitize()`：继续把 `kubeconfig` 置 null，绝不下发前端。

## A.4 Mapper XML（`K8sClusterMapper.xml`）

`prometheus_url` / `grafana_url` 加入：resultMap、Base_Column_List、insert、insertSelective、updateByPrimaryKeySelective、updateByPrimaryKey。

## A.5 前端 `ClusterView.vue`

- 表单分组：**基本信息**（名称/描述）· **组件版本**（version/istio/calico/runtime）· **IP 栈**（`ipStack` el-select）· **监控接入**（prometheus_url/grafana_url）。
- `kubeconfig`：编辑模式下可改，带**提示 + 确认**（留空保留 / 填写重新探测）。

---

# Part B. Thanos Query 后端

## B.1 包结构

```
com.coding.platformapi.metrics
├─ MetricQuery.java                // 枚举：具名查询目录（返回类型 + promsql）
├─ ThanosQueryClient.java          // 两个通用方法 query / queryRange
├─ MetricsService.java             // ★共享业务层：clusterId→clusterName、选常量、String.format 拼 sql、组响应
│                                  //   （被 WorkloadController / PodController 调用，不新建 controller）
├─ model/
│  ├─ PromApiResponse.java         // envelope：status/data（默认命名，resultType/errorType 为 camelCase）
│  ├─ PromQueryData.java           // resultType / result
│  └─ PromResult.java              // metric(Map<T>) / value / values
├─ labels/
│  └─ MetricLabels.java            // 空父类（当前无字段）；未来 by(xxx) 查询按需加子类补字段
└─ dto/
   ├─ WorkloadMetricsRequest.java  // clusterId / namespace / name(工作负载或pod名) / start / end
   ├─ MetricSeriesResponse.java    // unit + series[]
   ├─ MetricSeries.java            // legend + points[]
   └─ MetricPoint.java             // ts + value

端点（不新建 controller，加进现有维度 controller）：
  WorkloadController  POST /resource/workloads/{name}/metrics/{cpu,memory,network,disk}   ×4
  PodController       POST /resource/pods/{name}/metrics/{cpu,memory,network,disk}        ×4
```

## B.2 `MetricQuery` 枚举（命名规范 + 全部常量）

**命名 = `{范围}_{指标}_{语义}`**：范围（WORKLOAD/POD/NAMESPACE/CLUSTER/TENANT，本功能用 WORKLOAD + POD）+ 指标（CPU/MEMORY/NETWORK/DISK）+ **语义后缀**——按该查询实际算的是什么取：`_USED`（用量/当前值）、`_TOTAL`（总量）、`_AVG`（平均）。本功能 8 条都是「随时间的当前用量」→ 均 `_USED`。后续加 `NAMESPACE_*` / `CLUSTER_*` / `TENANT_*` 即可。

约定：**一律纯 `sum(...)`，不用 `by(...)`**——查的是「整个 Pod / 整个工作负载」的总量，按容器/标签拆分组没有意义（用户钦定）。网络/磁盘的**方向是第一个 `%s`**。返回的 metric map 为空，业务只读数值。

```java
public enum MetricQuery {

    // ===== Pod 详情（%s = clusterName, namespace, pod[, 方向]）=====
    POD_CPU_USED(MetricLabels.class,
        "sum(rate(container_cpu_usage_seconds_total{cluster_name=\"%s\",namespace=\"%s\",pod=\"%s\",container!=\"POD\"}[5m]))"),
    POD_MEMORY_USED(MetricLabels.class,
        "sum(container_memory_working_set_bytes{cluster_name=\"%s\",namespace=\"%s\",pod=\"%s\",container!=\"POD\"})"),
    // 方向 %s = receive|transmit → RX / TX 两条线（netns 在 pod 级，用 container="POD"）
    POD_NETWORK_USED(MetricLabels.class,
        "sum(rate(container_network_%s_bytes_total{cluster_name=\"%s\",namespace=\"%s\",pod=\"%s\",container=\"POD\"}[5m]))"),
    // 方向 %s = reads|writes → 读 / 写 两条线
    POD_DISK_USED(MetricLabels.class,
        "sum(rate(container_fs_%s_bytes_total{cluster_name=\"%s\",namespace=\"%s\",pod=\"%s\",container!=\"POD\"}[5m]))"),

    // ===== 工作负载详情：后端先用 K8s API 列出该 workload 全部 pod → 拼选择器 → 查 Thanos =====
    // ⚠️ promql 由用户填写（当前留空占位）。约定 %s 顺序：[方向] clusterName, namespace, <pod选择器>；
    //    <pod选择器> = 用列出的 pod 名拼出（如 pod=~"p1|p2|..."）。
    WORKLOAD_CPU_USED(MetricLabels.class, ""),   // TODO 用户填写 promql
    WORKLOAD_MEMORY_USED(MetricLabels.class, ""), // TODO 用户填写 promql
    // 方向 %s = receive|transmit → RX / TX（netns 在 pod 级，container="POD"）
    WORKLOAD_NETWORK_USED(MetricLabels.class, ""), // TODO 用户填写 promql
    // 方向 %s = reads|writes → 读 / 写
    WORKLOAD_DISK_USED(MetricLabels.class, "");   // TODO 用户填写 promql

    private final Class<?> labelType;   // 该查询的返回类型（本功能均为空父类 MetricLabels）
    private final String sql;           // promsql（%s 占位；纯 sum，无 by）
    // ctor + labelType() / sql()
}
```

> CPU/内存 `container!="POD"`（排除 pod 级伪容器，聚合所有真实容器 = 该维度总量）；网络 `container="POD"`（netns 在 pod 级，避免按容器重复计数）；磁盘 `container!="POD"`（每容器独立 fs，聚成总量）。

## B.3 结果类（labels）

本功能 8 条都是纯 `sum(...)`（无 `by`），返回的 metric map **为空**——业务只读数值、不读任何 label，所以这些查询用**空父类**即可，不需要字段。typed 字段能力**保留给未来需要按标签拆分的查询**。

```java
// labels/MetricLabels.java —— 通用父类（当前无字段）
@Getter @Setter @NoArgsConstructor
public class MetricLabels {
    // 本功能的 sum() 查询不产生 label，故无字段；业务只读 PromResult.values。
}
// 未来某条查询用 by(xxx) 需要读标签时，加子类补对应字段：
//   class PodBreakdownLabels extends MetricLabels { private String pod; }        // by(pod) 的命名空间汇总
//   class ClusterPodLabels extends MetricLabels { @JsonProperty("cluster_name") ... }  // 多词标签用 @JsonProperty 显式映射
```

- 用**普通 class + Lombok**（record 不能继承）。
- 多词标签（如 `cluster_name`）在子类里用 `@JsonProperty("cluster_name")` 显式映射，不用 class 级 naming（避免继承歧义）。

## B.4 `ThanosQueryClient`（两个通用方法）

```java
@Component
public class ThanosQueryClient {
    private final RestClient rest;    // baseUrl = thanos query，无鉴权；连接/读超时
    private final JsonMapper mapper;  // lenient：FAIL_ON_UNKNOWN_PROPERTIES=false

    /** 即时查询 → vector。time 可空（空 = now） */
    public <T> List<PromResult<T>> query(String sql, String time, Class<T> labels) {
        Map<String,String> p = common();
        if (time != null && !time.isBlank()) p.put("time", time);
        return unwrap(get("/api/v1/query", p, sql, labels));
    }

    /** 区间查询 → matrix */
    public <T> List<PromResult<T>> queryRange(String sql, long start, long end, int step, Class<T> labels) {
        Map<String,String> p = common();
        p.put("start", String.valueOf(start));
        p.put("end",   String.valueOf(end));
        p.put("step",  String.valueOf(step));
        return unwrap(get("/api/v1/query_range", p, sql, labels));
    }

    /** 两个方法共用的通用参数 */
    private Map<String,String> common() {
        var m = new LinkedHashMap<String,String>();
        m.put("dedup", "true");
        m.put("partial_response", "true");
        return m;
    }

    // get(): query=sql 拼进 params，GET；反序列化成 PromApiResponse<PromQueryData<PromResult<T>>>
    //        （用 labels 类经 TypeFactory 构造嵌套泛型）；unwrap() 取 data.result
}
```

## B.5 传输与反序列化

- `RestClient`：baseUrl = Thanos Query 端点，无鉴权头；连接超时 ~10s、读超时放宽（跨集群查询）。
- **专用 lenient mapper**：`FAIL_ON_UNKNOWN_PROPERTIES=false`。envelope（`PromApiResponse/PromQueryData/PromResult`）用**默认命名**（`resultType`/`errorType` 是 camelCase）；标签映射靠 labels 类上的 `@JsonProperty("cluster_name")`。**不套全局 snake_case**（会破坏 envelope）。
- `PromResult<T>`：`metric`（`Map<String,String>` → 反序列化为 `T`）、`value`（即时）、`values`（区间，`List<PromSample>`）。

## B.6 接口 / DTO / Service / Controller（4 接口 · 4 调用）

**8 个 POST 接口 = 2 维度 × 4 指标**，加进现有维度 controller（**不新建 controller**）：

| 维度 | 接口 | 说明 |
|------|------|------|
| 工作负载 | `POST /resource/workloads/{name}/metrics/{cpu,memory,network,disk}` | CPU(核) / 内存(字节) / 网络(RX·TX) / 磁盘(读·写) |
| Pod | `POST /resource/pods/{name}/metrics/{cpu,memory,network,disk}` | 同上，单 pod 维度 |

**入参**（POST body，`WorkloadMetricsRequest`）：`clusterId`、`namespace`、`start`、`end`（`name` 走 path）。step = 30s（服务端当前默认），**不校验时间上限**。

**出参** `MetricSeriesResponse`：
```json
{
  "code": 200,
  "data": {
    "unit": "字节/秒",
    "series": [
      { "legend": "RX", "points": [ {"ts": 1756800000, "value": 1024.0}, ... ] },
      { "legend": "TX", "points": [ {"ts": 1756800000, "value": 512.0},  ... ] }
    ]
  }
}
```

**Service 逻辑**（共享 `MetricsService`，以「工作负载 network」为例）：
```java
// clusterId → clusterName（现有集群查询解析）
String c = clusterRepo.loadName(req.getClusterId());
MetricQuery q = WORKLOAD_NETWORK_USED;                    // 维度 + 指标决定常量
long step = 30;
List<MetricSeries> series = new ArrayList<>();
for (String dir : new String[]{ "receive", "transmit" }) {          // RX / TX 两条线
    List<String> pods = listWorkloadPods(req);             // K8s API 列出该 workload 全部 pod
    String sel = buildPodSelector(pods);                   // 拼 promql 选择器（如 pod=~"p1|p2"）
    String sql = String.format(q.sql(), dir, c, req.getNamespace(), sel);
    List<PromResult<MetricLabels>> rows = client.queryRange(sql, start, end, step, MetricLabels.class);
    series.add(toSeries(dirLabel(dir), rows));             // legend=RX/TX，只读 values→points（不读 metric）
}
return new MetricSeriesResponse("字节/秒", series);
```

> 说明：`String.format` 的实参个数随常量而变（含方向的常量多一个前置方向；WORKLOAD 用选择器、POD 用 pod 名），由 service 按所选常量精确填写。返回的 metric map 为空，`toSeries` **只读 `values`**，不读任何 label 字段。

## B.7 时间范围与 step

- 前端给 `start`/`end`（unix 秒），预设 **5m / 15m / 30m**；**不设上限**——后续要更大时间宽度时调 step + 加前端预设即可，接口不改。
- `step = 30s`（当前默认）→ 每线 ≤ ~60 点/30min。
- rate 窗口固定 `[5m]`（≥ 2×step，安全）；若将来 step 增大可再放宽。

---

# Part C. 前端图表

## C.1 依赖

`platform-web/package.json` 新增 **ECharts v5**（当前无任何图表库）。按需引入 `echarts/core` + LineChart + Grid/Tooltip/Legend/DataZoom。

## C.2 组件结构

```
views/resource/WorkloadDetailView.vue     ─┐
views/resource/PodDetailView.vue          ─┴─► <MetricsPanel .../>   （ContainerDetailView 不加图表）
components/workload/MetricsPanel.vue      // 4 图容器 + 时间范围选择（5m/15m/30m）；变化时发 4 次 API（按维度选 workload/pod 接口）
components/workload/MetricChart.vue       // 单图 ECharts：props = title/unit/series/percentable/limit/showPercent
api/metrics.ts                            // workload + pod 各 cpu/memory/network/disk 请求（复用 http.ts，自动解 ResponseData.data）
```

## C.3 四个图表规格

| 图表 | unit | 线 | % 开关 | 工作负载详情 | Pod 详情 |
|------|------|----|--------|-------------|----------|
| CPU | 核 | 单条（该维度总量） | 有配额时 | `WORKLOAD_CPU_USED` | `POD_CPU_USED` |
| 内存 | 字节（humanize KiB/MiB/GiB） | 单条 | 有配额时 | `WORKLOAD_MEMORY_USED` | `POD_MEMORY_USED` |
| 网络 IO | 字节/秒 | **RX / TX 两条** | 无 | `WORKLOAD_NETWORK_USED` | `POD_NETWORK_USED` |
| 磁盘 IO | 字节/秒 | **读 / 写 两条** | 无 | `WORKLOAD_DISK_USED` | `POD_DISK_USED` |

- X 轴 = 时间，Y 轴 = 值；tooltip 显示各线数值。
- CPU/内存因纯 `sum()` 是**单条总量线**（不按容器拆）；网络 RX/TX、磁盘读/写**恒为两条独立线**（后端已按方向拆好）。

## C.4 百分比开关

- **仅 CPU / 内存**：当该容器存在对应 `limit`（pod spec `resources.limits`，前端详情页已有）时，显示「绝对值 / %」切换；% = value / limit，**由前端控制**。
- **网络、磁盘无 %**：只展示具体数据（RX/TX、读/写）。
- 接口只回原始序列 + unit，百分比由前端本地算（limit 来自 pod spec，不重复下发）。

## C.5 放置位置

- **工作负载详情**（`WorkloadDetailView.vue`）：主栏顶部。
- **Pod 详情**（`PodDetailView.vue`）：主栏顶部。
- **容器详情**（`ContainerDetailView.vue`）：**不加图表**（Prometheus 未采集容器名，无法查询）。

---

## 附：状态

已按用户反馈修订：**8 图 = 工作负载详情 + Pod 详情各 4**（容器级查不到，Prometheus 未采集容器名）；promql **一律纯 `sum()`、不用 `by`**（返回 metric map 为空，业务只读数值）；**不新建 controller**（写进现有 `WorkloadController` / `PodController`）；**不校验时间上限**。**磁盘 / 网络无 %**（仅 CPU/内存有，前端控制）；接口用 **POST**；`clusterName` 由前端传 `clusterId` 查库得到。

已确认：**工作负载级 = 后端先用 K8s API 列出该 workload 全部 pod，再拼选择器查 Thanos**；`WORKLOAD_*` 的 promql **由用户填写**（枚举里留空占位）。**网络用 `container="POD"`**。可进入实现（Part A → B → C）。
