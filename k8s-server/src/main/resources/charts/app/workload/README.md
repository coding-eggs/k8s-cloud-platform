# 应用编排节点模板（`app/workload`）

平台「应用编排（画布）」的一个节点类型 = 一个 chart 目录。本目录是**服务 / 后台进程**两种节点共用的模板。
它不对外发布，也不走 Helm release（平台只借 `helm template` 出清单，落盘走 SSA）。

## 目录约定

```
charts/app/workload/
├── README.md              ← 本文（组件级约定，加新版本时看这里）
└── 1.0.0/
    └── chart/             ← helm 能吃目录，不需要打 tgz
```

新增节点类型 = 新增一个 `charts/app/<kind>/<version>/chart`，并在 `AppRenderService` 里按 kind 分派。
**不要**在一个 chart 里塞多种不相关的节点类型 —— values 会互相污染。

## values 结构（由 `AppWorkloadValues` 生成，不要手写）

| 键 | 说明 |
|---|---|
| `app.id` / `app.slug` / `app.revision` | 应用标识；标签与对象名都用它 |
| `workload.key` | 节点 key（应用内唯一），对象名 = `<slug>-<key>` |
| `workload.mode` | `service`（有端口、建 Service）/ `worker`（无端口、不建 Service） |
| `workload.image` / `imagePullPolicy` / `port` / `replicas` | 基本参数 |
| `workload.env[]` | `{name, value}` → **ConfigMap** `…-env` |
| `workload.secretEnv[]` | `{name, secretName, secretKey}` → `valueFrom.secretKeyRef`，**只引用不存值** |
| `workload.resources` / `persistence` / `autoscaling` / `metrics` / `expose` | 见 `values.yaml` 注释 |

## 产物对照

| 条件 | 生成的资源 |
|---|---|
| 默认（`mode=service`，无持久化） | Deployment + Service(ClusterIP)（有 env 时加 ConfigMap） |
| `mode=worker` | Deployment（无端口、无 Service、无探针） |
| `mode=service` + `persistence.enabled=true` | **StatefulSet** + Service + Headless Service（不再有 Deployment） |
| `autoscaling.enabled` | 加 HPA（`scaleTargetRef` 会自动指向 Deployment 或 StatefulSet） |
| `metrics.enabled` 且 `mode=service` | 加 ServiceMonitor（`/metrics`，端口名 `http`） |
| `expose.type=nodeport` | Service 类型改 NodePort |

## 标签与只读保护（别改）

每个对象都带：

- `platform.coding/app=<appId>`、`platform.coding/app-node=<key>` → 反查与画布归属；
- `platform.coding/managed=true` → **资源页只读**。`ManagedResourceGuard` 就是按这个标签判 COMPONENT 的，
  写操作会被拒。删掉这个标签等于给普通页面开了双写口子。
- `platform.coding/revision=<n>` 注解 → 便于回溯是哪一版写下去的。

## 敏感值

**平台不接收明文敏感值**：`env` 只放普通变量，敏感值必须先在「Secret」页建好，再在节点里以
`secretEnv` 引用（Secret 名 + 键）。这样 spec、revision 快照、渲染清单里都不会出现密码。

## 校验与已知提示

- `helm lint` 会对模板默认值报一条 `object name does not conform … "-"` 的 **warning**：
  `values.yaml` 里 `slug`/`key` 故意留空（真正值由平台生成），所以名字渲染成 `-`。这是预期行为，
  不是错误；`helm lint` 的结论应为 `1 chart(s) linted, 0 chart(s) failed`。
- 改模板后至少要跑三种渲染并肉眼比对产物：`service`、`worker`、`service + persistence`。
