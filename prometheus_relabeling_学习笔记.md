# Prometheus Relabeling 学习笔记

> 本文档整理自对 Prometheus `relabelings` 与 `metricRelabelings` 的深入学习，涵盖核心概念、两个阶段对比、正则机制、实战模板等。
>
> 适用场景：Prometheus + Kubernetes ServiceMonitor，多集群/多环境监控治理。

---

## 目录

- [一、核心概念：指标名就是 `__name__`，其余都是 label](#一核心概念指标名就是__name__其余都是-label)
- [二、两个阶段的整体分工](#二两个阶段的整体分工)
- [三、Relabelings（抓前 / 目标重标签）](#三relabelings抓前--目标重标签)
  - [3.1 可用标签](#31-可用标签)
  - [3.2 典型配置示例](#32-典型配置示例)
- [四、MetricRelabelings（抓后 / 指标重标签）](#四metricrelabelings抓后--指标重标签)
  - [4.1 可用标签](#41-可用标签)
  - [4.2 典型配置示例](#42-典型配置示例)
- [五、sourceLabels 是什么](#五sourcelabels是什么)
- [六、regex 的作用与 4 种身份](#六regex-的作用与-4-种身份)
- [七、MetricRelabelings 能否添加 label](#七metricrelabelings-能否添加-label)
- [八、Discovered labels vs /metrics 文本](#八discovered-labels-vs--metrics-文本)
- [九、添加自定义 label 的位置选择](#九添加自定义-label-的位置选择)
- [十、__name__ 在两个阶段的存在性](#十__name__-在两个阶段的存在性)
- [十一、完整时间线](#十一完整时间线)
- [十二、ServiceMonitor 速查模板](#十二servicemonitor-速查模板)
- [十三、常见踩坑汇总](#十三常见踩坑汇总)

---

## 一、核心概念：指标名就是 `__name__`，其余都是 label

在 Prometheus 内部，**没有"指标"和"标签"的区别**，只有：

```text
{ __name__="指标名", label1="v1", label2="v2", ... }
```

你看到的是：
```text
container_cpu_cfs_periods_total{pod="x", container="y", cpu="total"}
```

Prometheus 内部看到的是：
```text
__name__="container_cpu_cfs_periods_total"
pod="x"
container="y"
cpu="total"
```

> **指标名 = 一个特殊的、必填的 label（`__name__`）；其余都是普通 label。**

---

## 二、两个阶段的整体分工

| 维度 | relabelings | metricRelabelings |
|---|---|---|
| 阶段 | 抓取**之前**（目标发现/重标签） | 抓取**之后、写入 TSDB 之前**（指标重标签） |
| 时机 | K8s 服务发现 → 决定抓不抓 | HTTP 抓回 `/metrics` → 加工样本 |
| 操作对象 | 目标维度（发现元数据、target 标签） | 具体指标样本（`__name__` + 标签） |
| 影响 `up` | ✅ 会 | ❌ 不会 |
| 典型用途 | 环境维度、提取 K8s 信息、抓取控制 | 指标级降基数、改写/删除指标、条件加工 |
| 常用 sourceLabels | `__meta_kubernetes_*`、`__address__` | `__name__`、业务标签（pod/container 等） |

---

## 三、Relabelings（抓前 / 目标重标签）

### 3.1 可用标签

- 服务发现元数据：`__meta_kubernetes_endpoint_*`，`__meta_kubernetes_endpoints_label_*`，`__meta_kubernetes_endpoint_address_target_name` 等
- 抓取控制标签：`__address__`、`__scheme__`、`__metrics_path__`、`__param_*`
- ServiceMonitor 配置生成的 `job` 等

### 3.2 典型配置示例

```yaml
relabelings:
# 从 K8s Endpoint 元数据提取为指标标签
- sourceLabels: [__meta_kubernetes_endpoints_label_release]
  targetLabel: release
  action: replace

- sourceLabels: [__meta_kubernetes_endpoint_address_target_name]
  targetLabel: pod
  action: replace

- sourceLabels: [__meta_kubernetes_endpoint_node_name]
  targetLabel: node
  action: replace

# 从 Service 名生成 service 标签
- sourceLabels: [__meta_kubernetes_endpoints_name]
  targetLabel: service
  action: replace

# 静态加环境维度（不写 sourceLabels = 直接写死值）
- action: replace
  targetLabel: cluster
  replacement: prod
```

> 结束后所有以 `__` 开头的标签会被 Prometheus 自动清除。

---

## 四、MetricRelabelings（抓后 / 指标重标签）

### 4.1 可用标签

- 指标名：`__name__`
- 抓取后附加到目标的普通标签：`instance`、`job`、`namespace`、`pod`、`cluster` 等
- Exporter 在 `/metrics` 里吐出的业务标签：`state`、`version`、`status`、`container` 等

### 4.2 典型配置示例

```yaml
metricRelabelings:
# 1. 删除特定指标
- sourceLabels: [__name__]
  action: drop
  regex: "go_.*|process_.*"

# 2. 删除高基数 label
- sourceLabels: [__name__]
  regex: "container_cpu_cfs_periods_total"
  action: labeldrop
  regex: "id|container_id"

# 3. 只给特定指标改 label（匹配 + 捕获）
- sourceLabels: [__name__, pod]
  regex: "container_cpu_cfs_periods_total;(.+)"
  action: replace
  targetLabel: workload
  replacement: "$1"

# 4. 给指定指标加静态 label
- sourceLabels: [__name__]
  regex: "alertmanager_alerts"
  action: replace
  targetLabel: component
  replacement: alertmanager
```

---

## 五、sourceLabels 是什么

`sourceLabels` 是 relabel 规则的**"原料"**：

1. 取出指定 label 的值
2. 按 `separator`（默认 `;`）拼接成一个字符串
3. 交给 `regex` 去匹配
4. 匹配成功 → 执行 `action`；失败 → 忽略

**规则：**
- 普通维度 → 用 label 名（如 `pod`、`container`）
- 指标名 → 用特殊内部 label `__name__`
- `separator` 只在 `sourceLabels` 有多个时才生效

---

## 六、regex 的作用与 4 种身份

`regex` 在 relabeling 里只有一个职责：**判断 `sourceLabels` 拼出来的字符串是否匹配，并可选地捕获内容。**

### 根据 action 不同，regex 扮演 4 种角色：

| action | regex 角色 | 示例 |
|---|---|---|
| `drop` / `keep` | 过滤器（指标/目标的开关） | `regex: container_cpu_cfs_periods_total` |
| `replace` / `labelmap` | 匹配 + 捕获 | `regex: container_cpu_cfs_periods_total;(.+)` |
| `labeldrop` / `labelkeep` | 匹配 label **名**（非值） | `regex: pod_uid\|container_id` |
| 静态 replace（无 sourceLabels） | 无条件写死 | `replacement: test` |

### 关键特性

- 默认是**完全匹配**（等价于加 `^` 和 `$`）
- `sourceLabels: [__name__]` 取的是 `__name__` 这个 label 的值
- `regex: (.*)` 等价于无条件匹配（语义上冗余）
- **regex 只能匹配，改值靠 `replacement`**

---

## 七、MetricRelabelings 能否添加 label

**能，且是 metricRelabelings 推荐用途之一。**

三种添加方式：

### 方式 1：静态添加（最简单）

```yaml
metricRelabelings:
- action: replace
  targetLabel: team
  replacement: sre
```
→ 所有该 target 的业务指标带上 `team="sre"`

### 方式 2：从已有 label 计算

```yaml
- sourceLabels: [pod]
  regex: "(.+)"
  targetLabel: workload
  replacement: "$1"
```

### 方式 3：只给特定指标加

```yaml
- sourceLabels: [__name__]
  regex: "container_cpu_cfs_periods_total"
  action: replace
  targetLabel: sampled
  replacement: "true"
```

> ⚠️ 注意：不写 `sourceLabels` 的静态 replace 会**不影响 `up`** 等自动指标，若需 `up` 也带标签应放在 `relabelings`。

---

## 八、Discovered labels vs /metrics 文本

| 视角 | 看到的内容 |
|---|---|
| `/metrics`（curl） | 只有业务数据：`alertmanager_alerts{state="active"} 2` |
| Discovered labels（Service Discovery 页面） | K8s 服务发现生成的 `__meta_kubernetes_*` + `__address__` |
| Prometheus 查询时 | 拼接后的完整时间序列 |

**Discovered labels 分类：**
- ✅ 有用：`__meta_kubernetes_endpoints_label_release`、`__meta_kubernetes_endpoints_name`、`__meta_kubernetes_endpoint_node_name` 等
- ❌ 噪音：`labelpresent_*`（只表示"是否存在"）、Helm 自动生成的 `chart`、`heritage`

**关键链路：**
```
K8s API (Endpoints/Pod/Service)
  ↓ Watch
【Discovered labels】(__meta_*、__address__)
  ↓ relabelings（提取/加标签）
【Target labels】(instance/job/namespace/pod/cluster)
  ↓ HTTP GET /metrics（业务数据）
【原始指标文本】(state/version)
  ↓ metricRelabelings（增删改）
【TSDB 最终指标】
```

---

## 九、添加自定义 label 的位置选择

| 需求 | 推荐位置 |
|---|---|
| 给某 job / target 所有指标加 `cluster`、`env`、`data_center` | `relabelings` |
| 让 `up` 也带上自定义维度 | `relabelings` |
| 只给特定指标加标签 | `metricRelabelings` |
| 根据 pod/namespace 自动派生标签 | `relabelings` 提取 `__meta_*` |
| 降基数、删标签 | `metricRelabelings` 的 `labeldrop` |
| 指标本身就需要维度 | 在 Exporter 代码里定义 |

---

## 十、__name__ 在两个阶段的存在性

> `__name__` 在 `relabelings` 和 `metricRelabelings` 里**都存在**，但值不同。

| 阶段 | `__name__` 的值 | 能否操作业务指标 | 实战价值 |
|---|---|---|---|
| relabelings | `up` / `scrape_*` | ❌ 不能 | 极低，官方不推荐用 |
| metricRelabelings | 业务指标名（如 `container_cpu_cfs_periods_total`） | ✅ 能 | 极高 |

- relabelings 里的 `__name__` 只是 `up` 之类的系统指标名（脚手架）
- metricRelabelings 里的 `__name__` 才是业务指标的名字（主角）

---

## 十一、完整时间线

```
T0  Prometheus 问 K8s API，Watch Endpoints / Pod / Service
T1  K8s 返回资源信息
T2  Prometheus 生成 __meta_*（草稿纸）
T3  通过 relabelings 把 __meta_* → Target labels
T4  发 HTTP 请求到 /metrics
T5  Exporter 返回纯文本（业务 label，如 state/version）
T6  metricRelabelings 对每条指标做增/删/改
T7  写入 TSDB（最终指标，含 instance/job/cluster/业务标签）
```

---

## 十二、ServiceMonitor 速查模板

### 完整生产级示例

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: ps-kube-state-metrics
  namespace: monitor
spec:
  selector:
    matchLabels:
      app.kubernetes.io/instance: "ps"
      app.kubernetes.io/name: "kube-state-metrics"
  jobLabel: "app.kubernetes.io/name"
  endpoints:
  - interval: 30s
    path: /metrics
    port: http
    relabelings:
    # 1. 环境维度（影响 up / 所有指标）
    - action: replace
      targetLabel: cluster
      replacement: prod
    - sourceLabels: [__meta_kubernetes_endpoints_label_release]
      targetLabel: release
    - sourceLabels: [__meta_kubernetes_namespace]
      targetLabel: namespace
    - sourceLabels: [__meta_kubernetes_endpoint_address_target_name]
      targetLabel: pod
    - sourceLabels: [__meta_kubernetes_endpoint_node_name]
      targetLabel: node
    metricRelabelings:
    # 2. 指标级加工（不影响 up）
    - sourceLabels: [__name__]
      regex: "go_.*"
      action: drop
    - regex: "pod_uid|container_id"
      action: labeldrop
    - sourceLabels: [__name__, pod]
      regex: "container_cpu_cfs_periods_total;(.+)"
      action: replace
      targetLabel: workload
      replacement: "$1"
```

### 错误写法 vs 正确写法对照

| 场景 | ❌ 不推荐 | ✅ 推荐 |
|---|---|---|
| 加环境维度 `data_center` | metricRelabelings + `regex: (.*)` + 多余 `separator` | relabelings 里静态 replace |
| 删高基数 | 写错 sourceLabels | sourceLabels: [__name__] 配合 labeldrop |
| 改单个指标 label | 直接对指标名用 sourceLabels | sourceLabels: [__name__, pod] |

---

## 十三、常见踩坑汇总

| 坑 | 说明 |
|---|---|
| 写 `sourceLabels: [container_cpu_cfs_periods_total]` | 指标名不是 label 名，应为 `[__name__]` |
| 忘记 separator | 多 sourceLabels 拼接需与 regex 中的分隔符一致 |
| `regex: (.*)` 无意义 | 等价于无条件 replace，语义不清 |
| 写多余 `separator` | 无 sourceLabels 时 separator 不生效 |
| 在 metricRelabelings 里加 `up` 需要的标签 | up 不受 metricRelabelings 影响，应放 relabelings |
| 以为 `/metrics` 里有 `__meta_*` | 从不存在于 HTTP 响应中，只存在于 Prometheus 内存 |
| 以为 regex 是包含匹配 | 默认完全匹配，需加 `.*` 实现包含 |

---

## 核心口诀

> **sourceLabels 拼字符串，regex 决定"要不要、抓哪段"，action 决定"干什么"。**
>
> - relabelings 用 `__meta_*` 当 sourceLabels 造维度；
> - metricRelabelings 用 `__name__` / 业务 label 当 sourceLabels 管指标。
>
> 指标名 = `__name__` label，其他维度 = 普通 label。

---

*整理日期：2026-09-11 · 基于 Prometheus + Kubernetes ServiceMonitor 实践*
