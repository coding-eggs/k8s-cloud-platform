# metrics-server（chart 3.14.0）

> 来源：`https://kubernetes-sigs.github.io/metrics-server`，chart 3.14.0 / app 0.9.0，
> 2026-10-17 经本地 7890 代理 `helm pull` 后**原样入库**（未改模板）。见 `provenance.yaml`。

## 1. 概览

| 项 | 值 |
|---|---|
| 组件 code | `metrics-server` |
| 画像 | BUSINESS / MANAGEMENT |
| release / namespace | `platform-metrics-server` / `kube-system` |
| 探测判据 | `kube-system` Deployment `metrics-server/metrics-server`（镜像含 `metrics-server`） |
| 平台功能 | `hpa-resource-metrics`（HPA 资源指标）；未装时能力判定退回 `metrics.k8s.io` API group |

## 2. 契约 values（🔒 不可改）

```yaml
args: [--kubelet-insecure-tls]
defaultArgs:
  - --cert-dir=/tmp
  - --kubelet-preferred-address-types=InternalIP
  - --kubelet-use-node-status-port
  - --metric-resolution=15s
fullnameOverride: metrics-server
```

可编辑值：`replicas`（默认 1）。

| 值 | 为什么是契约 / 来源 |
|---|---|
| `--kubelet-insecure-tls` | 自签 kubelet 证书的集群不加它 metrics-server 永远 NotReady（recipe 设计时写入） |
| `defaultArgs` 全量显式钉住 | 2026-10-18 用户评审第 7 条：`--kubelet-preferred-address-types=InternalIP` 要显式钉住，不依赖 chart 默认值 |
| `fullnameOverride: metrics-server` | 官方 chart 默认拿 release 名当 fullname，会生成 Deployment `platform-metrics-server`；组件探测认的是 `metrics-server` —— 不固定名字，面板永远显示「未安装」。**这条有单测钉住，并做过变异验证**（改成错误值测试确实红） |

## 3. 实锤（来自历史对话）

- **2026-10-17 真集群试点通过**（批①）：用官方 chart 走产品执行链安装后——
  - Deployment `metrics-server` 1/1 Running，镜像 `registry.k8s.io/metrics-server:v0.9.0`；
  - `APIService v1beta1.metrics.k8s.io` Available=True；
  - `kubectl top nodes` 返回三个节点真实数据；
  - release 上三个 `platform.coding/*` 标签完整。
- 试点前先卸载了手写版 chart：`helm upgrade` 换 chart 会被 **Deployment selector 不可变**拒绝
  （这条没有数据，直接重装）。升级到本 chart 的新版本时同理，**换 chart 来源不要原地 upgrade**。
- `fullnameOverride` 的问题是在试点前用单测 + 变异验证确认的，不是猜的。

## 4. 装完怎么验证

```bash
kubectl -n kube-system get deploy metrics-server
kubectl get apiservice v1beta1.metrics.k8s.io -o wide   # Available=True
kubectl top nodes
```

## 5. 已知坑

1. **NotReady / `x509: cannot validate certificate`** —— kubelet 自签证书；契约里的
   `--kubelet-insecure-tls` 不能去掉。
2. **装完页面仍显示未安装** —— `fullnameOverride` 丢失，Deployment 名变成了 `platform-metrics-server`。
3. **`kubectl top` 报 `the server could not find the requested resource`** —— APIService 没注册成功，
   看 `kubectl -n kube-system logs deploy/metrics-server` 与 `kubectl get apiservice`。
4. **升级时 Deployment selector 不可变** —— 从别的 chart/手写版换过来时不能原地 upgrade，先卸载再装。
5. **镜像拉取失败** —— 离线集群需同步 `registry.k8s.io/metrics-server/metrics-server`。

## 6. 新增版本

1. `helm pull metrics-server --repo https://kubernetes-sigs.github.io/metrics-server --version <x.y.z> --untar`；
2. 入库到 `metrics-server/<x.y.z>/chart/` + `provenance.yaml`；
3. `ComponentRecipeCatalog.METRICS_SERVER_RELEASES` **最前面**加 `ReleaseSpec`
   （release 名/namespace/契约 values 不变）；
4. `helm template` 确认新版本的 `args` / `defaultArgs` / `fullnameOverride` key 没改名；
5. 非生产集群真装一次，`kubectl top` 与组件探测都正常；
6. 重建 k8s-server + platform-api。

## 7. 卸载

平台不提供卸载。手工 `helm -n kube-system uninstall platform-metrics-server`；
本组件没有 CRD，组件探测随 Deployment 消失自动变为未安装。
