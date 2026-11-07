# 中间件「节点调度」高级选项 设计

日期：2026-11-07　状态：**搁置（用户决定先不做）**

> 搁置原因：租户可见节点范围没定（全量下发 = 租户可加容忍调度到 master；
> 白名单 = 要新表/新配置页）。用户决定先不给用户这个权限，调度维持 k8s 默认。
> 重启时先回答这个问题。

## 1. 意图

用户用本地盘存储类（如 `local-client`，local-path）跑中间件时，往往只有个别节点磁盘大，
需要**自己指定实例的 Pod 只能落在哪些节点上**。带污点的节点（如 master 的 `NoSchedule`）
要能调度上去，用户**自己添加容忍**。

起因：ES 实例数据盘在 NFS（服务端就在 master 上）导致三节点同时崩溃、分片损坏，
用户想换本地盘，但本地盘必须控制落点。

## 2. 目标 / 非目标 / 反目标

**目标**
- 全部 8 种中间件（elasticsearch / kafka / milvus / mysql / postgresql / rabbitmq / redis / nacos）
  的创建与编辑都支持一个**可选、默认折叠**的「高级 · 节点调度」：
  - 从当前集群节点列表**多选节点**；
  - 自己添加**容忍**（key / operator / value / effect）。
- 选了节点 = 该实例**所有 Pod** 只能调度到这些节点（硬约束）。

**非目标（用户明确不要）**
- **不探测**存储类是不是本地盘，**不加任何提醒**（本地盘提示、单副本提示都不做）——用户自己选择。
- 不按组件分别设置（实例统一一份）。
- 不做 label 表达式 / nodeSelector / 软亲和（preferred）/ 反亲和 / topologySpread。
- 选了带污点的节点**不自动**补容忍。

**反目标（做成这样就算失败）**
- 不填时行为与现在有任何差异（CR / values 里多出空的 affinity 字段也算差异——不填就**不写**）。
- 编辑时把 Operator 原有的亲和/反亲和设置冲掉（例如 Percona 的反亲和）。
- 编辑实例时未改调度，却把已设好的调度清掉。

## 3. 已定的做法

### 3.1 数据形状
请求里新增一个可选对象（创建 DTO、编辑 DTO 都加；`platform-common` 共享）：

```
scheduling: {
  nodeNames:   string[]            // 空/缺 = 不限节点
  tolerations: [{ key, operator, value, effect }]   // operator: Equal|Exists；effect: NoSchedule|PreferNoSchedule|NoExecute|空(全部)
}
```

- **创建**：`scheduling` 缺省或两项都空 = 不写任何调度字段。
- **编辑：三态**（照搬 Kibana 开关的约定）：`scheduling == null` = 不处理；
  非 null = 以它为准整体替换（两项都空 = 清除平台加的调度约束）。前端只在与现状不一致时发。
- 台账 `spec_json` 新增 `scheduling` 键（自由 JSON，无 SQL 迁移；老实例无此键 = 未设置）。

### 3.2 落到 Pod 上
- 节点 → `affinity.nodeAffinity.requiredDuringSchedulingIgnoredDuringExecution`，
  **每个节点一个 term**：`matchFields: metadata.name In [<节点名>]`（terms 之间是 OR；
  k8s 要求 metadata.name 的 matchFields 只能一个值）。【我的决定】用 node 名而不是
  `kubernetes.io/hostname` 标签：云上节点二者可能不一致，按标签会永远 Pending。
- 容忍 → `tolerations` 原样写入。
- 每个适配器写进自己 CR / values 的 Pod 模板位置，**覆盖该实例产生的全部 Pod**，含附属与依赖：
  ES 的 nodeSets + Kibana；Kafka 的 NodePool + entityOperator 等；Milvus 的组件 + 集群内 etcd 依赖
  （Milvus 对象存储只用外部的，没有集群内 MinIO）；
  MySQL 的 mysql + 代理（haproxy/router）+ orchestrator；Redis 各模式（含哨兵 CR、Cluster 的 leader/follower）；
  CNPG、RabbitMQ 各自字段；Nacos 走 chart values。
- **只动 nodeAffinity 与 tolerations 两处**，不碰 podAntiAffinity / podAffinity 及 Operator 自己的反亲和开关。
- 【我的决定】编辑时这两处**整体替换**（`nodeAffinity.required…` 与 `tolerations` 以平台给出的为准），
  不与台账做差量：这两处目前没有别的写入方，用 kubectl 手工加的会被覆盖。
- 【我的决定】Nacos 编辑调度沿用现有「台账原始规格全量重放 helm upgrade」的路径（开指标就是这么做的），
  台账里的 `scheduling` 一并带上；不另开编辑路径。

### 3.3 编辑后果由用户承担
改节点会触发滚动；若把已有本地盘数据的节点去掉，Pod 会因 PV 节点亲和冲突一直 Pending。
平台**不拦、不提示**（见非目标）。删 Pod 不会漂移丢数据（PV 自带节点亲和）。

### 3.4 节点列表从哪来
沿用创建页「后端按租户/权限算好再下发」的 form-options 模式，在其中加节点列表：
名字、InternalIP、角色、Ready、**污点**（展示污点帮助用户知道要加什么容忍，不自动加）。

## 4. 约束
- 架构原则：k8s-server 零业务、同类资源不抽公共基类 —— 每个适配器各自把 `scheduling` 写进自己的 CR；
  可以有一个只做「scheduling → affinity/tolerations map」的纯工具函数（不是基类）。
- SSA 更新：atomic list（tolerations、nodeSelectorTerms）整段替换，编辑路径必须给出完整列表，
  见 memory「fabric8 SSA 更新」。
- 写接口审计照旧（创建/编辑接口已有审计，载荷多一个字段）。
- 无新权限码（复用创建 / 编辑端点）。

## 5. 留给实现者
- 每个 Operator 的确切字段路径（对着各 CRD 的 schema 核实，别凭印象）。
- 依赖组件（Milvus etcd/MinIO、Kafka entityOperator 等）的 values/字段写法。
- 节点名提交时是否校验存在（建议：创建时不存在即拒；编辑时不校验，节点可能已下线）。
- 前端折叠区样式、容忍表格增删行的交互；详情页展示当前调度设置。
- 单测覆盖：每个适配器「不填 = CR 无调度字段」「填了 = 字段齐全」「编辑不碰反亲和」。
