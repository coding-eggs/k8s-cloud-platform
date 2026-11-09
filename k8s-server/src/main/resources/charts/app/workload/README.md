# 应用工作负载模板（`app/workload`）

平台「应用」里的每个服务渲染一次本 chart。本目录是**服务 / 后台进程**两种形态共用的模板（有端口 = 服务）。
模型与上线步骤见 `docs/development/application-release.md`。
它不对外发布，也不走 Helm release（平台只借 `helm template` 出清单，落盘走 SSA）。

## 目录约定

```
charts/app/workload/
├── README.md              ← 本文（组件级约定，加新版本时看这里）
└── 1.0.0/
    └── chart/             ← helm 能吃目录，不需要打 tgz
```

新增工作负载形态 = 新增一个 `charts/app/<kind>/<version>/chart`，并在 `AppRenderService` 里按 kind 分派。
**不要**在一个 chart 里塞多种不相关的形态 —— values 会互相污染。

## values 结构（由 `AppWorkloadValues` 生成，不要手写）

| 键 | 说明 |
|---|---|
| `app.id` / `app.slug` / `app.revision` | 应用标识；标签与对象名都用它 |
| `workload.key` | 节点 key（应用内唯一），对象名 = `<slug>-<key>` |
| `workload.mode` | `service`（有端口、建 Service）/ `worker`（无端口、不建 Service） |
| `workload.image` / `imagePullPolicy` / `port` / `replicas` | 基本参数 |
| `workload.env[]` | `{name, value}` → **ConfigMap** `…-env` |
| `workload.secretEnv[]` | `{name, secretName, secretKey}` → `valueFrom.secretKeyRef`，**只引用不存值** |
| `workload.inlineEnv[]` | `{name, value}` 直接写进容器 `env[].value`（含 `$(_REF_n)` 的拼接值），排在 secretEnv 之后 |
| `workload.configFiles[]` | `{key, path, content}` → **ConfigMap** `…-files`，每个文件以只读 `subPath` 挂到 `path`；内容由 platform-api 替换好引用，**不含凭据** |
| `workload.configHash` | Pod 模板注解 `platform.coding/config-hash`：env / secretEnv / inlineEnv / configFiles 的摘要，不含发布号 |
| `workload.imagePullSecrets[]` | 只取自发布快照；平台 Harbor 的拉取凭据靠命名空间 default SA |
| `workload.resources` / `persistence` / `autoscaling` / `metrics` / `expose` | 见 `values.yaml` 注释；`autoscaling.targetMemory` > 0 才加内存指标（CPU 指标始终有） |
| `workload.podAnnotations` / `podLabels` | 合并进 Pod 模板注解 / 标签（OTel 注入、`istio.io/dataplane-mode`）；不进 `configHash` |
| `workload.serviceLabels` | 合并进 Service 标签（`istio.io/use-waypoint`） |
| `workload.extraEnv[]` | `{name, value}` 直接写进容器 env，排在 `inlineEnv` 之后（OTel 服务名 / 采样） |
| `workload.nodeSelector` / `tolerations` | 调度（2026-11-09）：desired 的 `nodeSelector` / `tolerations` 原样透传进 Pod spec；空 Map / 空列表不渲染 |
| `workload.affinity` | K8s 原生 affinity（platform-api 的 `AppScheduling` 已把工作负载页的紧凑结构翻好）；空对象不渲染 |
| `workload.extraVolumes[]` / `extraVolumeMounts[]` | 额外挂载（2026-11-09）：**已经是 K8s 原生形状**（`{name, emptyDir\|configMap\|secret\|persistentVolumeClaim\|hostPath\|projected\|downwardAPI\|csi\|nfs}` 与 `{name, mountPath, readOnly?, subPath?}`），模板直接 toYaml；与 `configFiles` / `platform-otel-logs` / StatefulSet 的 `data` 卷并列。`hostPath` 只来自平台管理员的保存 |
| `workload.logVolume` | `{enabled, mountDir}`：emptyDir 卷 `platform-otel-logs` 挂到 `mountDir`（OTel 读文件日志） |
| `workload.route` | `{enabled, kind, parentName, sectionName, port}`：网关端口池暴露（2026-11-08）。`kind=http` → HTTPRoute，`tcp` → TCPRoute（都是 `gateway.networking.k8s.io/v1`）；`parentRefs` 指向命名空间 ListenerSet（`kind: ListenerSet`）的 listener `sectionName`；`port` = 服务端口。Service 保持 ClusterIP（`expose.type=none`）；worker 不生成 |
| `workload.meshRoute` | `{enabled, kind, port, canary}`：网格路由（内部灰度，2026-11-09）。HTTPRoute / TCPRoute `<name>-mesh`，`parentRefs` 指向本服务 Service（`group: ""`、`kind: Service`，GAMMA），规则与 `route` 的灰度规则同形；platform-api 只在灰度中且应用为 L7 网格时给 |
| `workload.expose.portName` | Service 端口名 `http` / `tcp`（params `protocol=tcp` 时为 `tcp`）；Istio 按端口名判协议，`targetPort` 仍是容器端口 `http` |

## 产物对照

| 条件 | 生成的资源 |
|---|---|
| 默认（`mode=service`，无持久化） | Deployment + Service(ClusterIP)（有 env 时加 ConfigMap `-env`，有配置文件时加 ConfigMap `-files`） |
| `mode=worker` | Deployment（无端口、无 Service、无探针） |
| `mode=service` + `persistence.enabled=true` | **StatefulSet** + Service + Headless Service（不再有 Deployment） |
| `autoscaling.enabled` | 加 HPA（`scaleTargetRef` 会自动指向 Deployment 或 StatefulSet）；**Deployment / StatefulSet 不写 `spec.replicas`**（否则 SSA forceConflicts 每次发布把 HPA 的副本打回） |
| `metrics.enabled` 且 `mode=service` | 加 ServiceMonitor（`/metrics`，端口名 `http`） |
| `expose.type=nodeport` | Service 类型改 NodePort |
| `route.enabled` 且 `mode=service` | 加 HTTPRoute 或 TCPRoute（与 Service 同名），挂到 ListenerSet 的 listener |
| `meshRoute.enabled` 且 `mode=service` | 加 HTTPRoute 或 TCPRoute `<name>-mesh`，挂到本服务 Service |

## 标签与只读保护（别改）

每个对象都带：

- `platform.coding/app=<appId>`、`platform.coding/app-node=<key>` → 反查所属应用与服务；
- `platform.coding/managed=true` → **资源页只读**。`ManagedResourceGuard` 就是按这个标签判 COMPONENT 的，
  写操作会被拒。删掉这个标签等于给普通页面开了双写口子。
- `platform.coding/revision=<n>` 注解 → 便于回溯是哪一版写下去的。

## 敏感值

**平台不接收明文敏感值**：`env` 只放普通变量，敏感值走 `secretEnv`（Secret 名 + 键，或依赖的
`${x.password}` 引用解析出的 Secret 位置）。配置文件内容会明文进 ConfigMap，所以只能引用
`host / port / address / url`，platform-api 保存与渲染时都会拒绝凭据引用。发布快照、渲染清单里都不会出现密码。

## 配置文件为什么用 subPath

按文件挂（`subPath`）不会把所在目录整个盖掉（`/etc/nginx/conf.d/x.conf` 不影响同目录其他文件）。代价是
subPath 挂载**不会**跟着 ConfigMap 更新，所以内容一改就必须滚动 Pod —— 这正是 `configHash` 把 configFiles
算进去的原因。对象名 `…-files` 是保留后缀，服务 / 依赖 key 不能以 `-files` 结尾（台账按后缀把对象归回服务）。

## 校验与已知提示

- `helm lint` 会对模板默认值报一条 `object name does not conform … "-"` 的 **warning**：
  `values.yaml` 里 `slug`/`key` 故意留空（真正值由平台生成），所以名字渲染成 `-`。这是预期行为，
  不是错误；`helm lint` 的结论应为 `1 chart(s) linted, 0 chart(s) failed`。
- 改模板后至少要跑三种渲染并肉眼比对产物：`service`、`worker`、`service + persistence`。
  `AppWorkloadChartRenderTest` 会用本机 helm 真渲染 Deployment / StatefulSet 两种形态的配置文件挂载（没有 helm 时跳过）。
