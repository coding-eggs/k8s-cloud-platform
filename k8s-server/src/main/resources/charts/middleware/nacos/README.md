# Nacos（中间件 code: `nacos`）

> **这是组件级 README（跨版本）**：平台怎么建实例、约定是什么、踩过哪些坑、加新版本要改哪里。
> 具体 chart 版本的来源/关键 values 在 `1.2.0/README.md`。
> 使用与设计文档：`docs/development/middleware-store.md`。

## 1. 概览

| 项 | 值 |
|---|---|
| 部署方式 | **Chart（不是 Operator）**：一个实例 = 一次 `helm upgrade --install`，**release 名 = 实例名** |
| Chart | `1.2.0`（appVersion 3.2.4），见 `1.2.0/`；来自 `nacos-group/nacos-k8s` 的 `helm/` 目录 |
| 实例形态 | `STANDALONE`（1）/ `CLUSTER`（内嵌 JRaft，≥3） |
| 版本 | 镜像固定 `nacos/nacos-server:v3.2.4`（表单不展示版本字段） |
| 凭据 | **无**：chart 默认不鉴权，对外暴露前请自行配置 |
| 业务指标 | actuator，8848 `/nacos/actuator/prometheus`（见 §6） |
| 存储 | Nacos **3.x 集群用内嵌 JRaft，不需要外置 MySQL**（2.x 时代的 `mysql.db` 依赖已不存在） |

## 2. 平台约定（所有版本都适用）

- **实例名**：`mw-nacos-<8位随机>`，同时是 **Helm release 名**（所以 `helm list -A` 能直接对上）；
- **标签**：chart 的 Pod/Service 带 `app.kubernetes.io/instance=<release>`；平台额外在建 release 时
  注入 `platform.coding/managed-by=middleware` + `platform.coding/middleware-instance=<id>`（资源页只读）；
- **存在性判定**：Nacos 没有 CR，**以 helm release 是否存在**判断实例是否存在，状态再看 Pod 就绪；
- **编辑只允许改显示名**：不支持在线扩容（见 §5）；
- **租户**：chart 直接建原生资源（Deployment/StatefulSet/Service/ConfigMap/PVC），租户 SA 的权限来自
  模板里的 core/apps 规则，不需要额外 CRD apiGroup。

## 3. 关键 values（平台固定下发，别改）

```yaml
global:
  mode: standalone|cluster          # ⚠️ chart 读 global.mode，不是顶层 mode；写错静默变成 standalone
nacos:
  replicaCount: <节点数>
  image: { repository: nacos/nacos-server, tag: v3.2.4 }
  storage: { type: embedded }       # 3.x 内嵌存储
persistence:
  enabled: <有存储大小时 true>
  data:
    storageClassName: "nfs-client"  # 空 = null（集群默认 SC）
    resources: { requests: { storage: 10Gi } }
service:
  type: ClusterIP                   # chart 默认 NodePort/30000，多实例会撞端口，必须覆盖
  port: 8848
```

## 4. 表单字段

命名空间、显示名、StorageClass、CPU/内存、存储大小（留空 = 不持久化，用 emptyDir）、`replicas`
（单节点固定 1、集群 ≥3）、「采集业务指标」开关（默认关）。

## 5. 坑（真集群实锤，别回退）

1. **同一命名空间只能有一个 Nacos 实例**：chart 的 Service / ConfigMap 名字固定为 `nacos-cs`，
   第二个实例会直接撞名。平台在创建前就拒（报错写明原因）。
2. **`storageClassName` 留空必须写 `null`，不能写空串 `""`**：空串在 K8s 里是「不申请存储类」，
   PVC 永远 Pending；chart 的默认值还是 `manual`（更坑），平台必须显式覆盖。
3. **`global.mode` 不是 `mode`**：写错不会报错，只是静默渲染成 standalone（集群形态白配）。
4. **不支持在线扩容**：volume 里有上一次安装的存储等设置，而扩容请求只带副本/资源，
   重新 upgrade 会把没带的字段冲回默认。要扩容请删实例重建（数据在 PVC 上，可保留）。
5. **业务 gauge 不稳定**：`configCount` 这类业务指标要等对应模块初始化后才注册，Pod 重启后一段时间内不出现
   —— 平台指标只选 JVM/HTTP/进程级（见 §6），别再加回去。
6. 集群模式 `replicaCount ≥ 3`（JRaft 要多数派）；1 节点只能用 standalone。

## 6. 业务指标

- 打开方式：`nacos.extraEnv` 下发 `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=prometheus`
  （chart 原生支持，**不改模板**）；
- 采集：Service（8848）+ ServiceMonitor，路径 **`/nacos/actuator/prometheus`**（Nacos 3 的 context-path 是 `/nacos`），
  Pod 选择器 `app.kubernetes.io/instance=<release 名>`；
- 指标定义在 `MiddlewareCatalog.NACOS_METRICS`（堆/非堆/线程/类/GC/HTTP 请求与平均响应时间/CPU/负载/运行时长）；
- 指标开关是**单向**的：已建实例可在「编辑」里开启（会重新 `helm upgrade`，Pod 重启），不支持关闭。

## 7. 加一个新版本怎么做

1. 从 `nacos-group/nacos-k8s` 的 `helm/` 拉新 chart 到 `charts/middleware/nacos/<新版本>/chart/`，
   新建 `<新版本>/README.md`（记来源/日期/关键 values 变化）；
2. 改 `NacosChartInstaller` 的 `IMAGE_TAG`（必要时改 `IMAGE_REPOSITORY`）与 chart 版本；
3. **对比 values schema**：`global.mode` / `nacos.replicaCount` / `persistence.data.storageClassName` /
   `service.type` 这几个键如果改名，valuesFor 要同步（单测直接断言字符串，会红）；
4. 注意大版本差异：**3.x 才用内嵌 JRaft**；如果回退到 2.x，集群模式会重新需要外置 MySQL —— 那是另一个形态，
   要同时改表单、适配器与文档；
5. 真集群验证：standalone + cluster 各建一个、开指标看端点 200、删实例确认 PVC 按要求保留/清理。
