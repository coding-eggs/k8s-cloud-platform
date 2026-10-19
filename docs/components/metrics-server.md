# metrics-server

## 1. 概览

| 项 | 值 |
|---|---|
| 组件 code | `metrics-server` |
| 显示名 | metrics-server |
| 画像 | BUSINESS / MANAGEMENT 都可装 |
| chart | `metrics-server` **3.14.0**（app 0.9.0） |
| release / namespace | `platform-metrics-server` / `kube-system` |
| chart 目录 | `k8s-server/src/main/resources/charts/components/metrics-server/3.14.0/chart/` |
| 上游 | <https://kubernetes-sigs.github.io/metrics-server> |
| 探测判据 | `kube-system` Deployment `metrics-server/metrics-server`（镜像含 `metrics-server`） |
| 平台功能 | `hpa-resource-metrics`：HPA 的 CPU/内存指标；集群还没装时能力判定会退回 `metrics.k8s.io` API group |

## 2. 安装前前提

- 集群的 **API 聚合层**可用（metrics-server 通过 `APIService v1beta1.metrics.k8s.io` 暴露指标）；
- kubelet 使用自签证书也没问题：平台已加 `--kubelet-insecure-tls`；
- 集群能拉取 `registry.k8s.io/metrics-server/*` 镜像（离线集群需先同步）。

## 3. 平台的契约 values

```yaml
args:
  - --kubelet-insecure-tls
defaultArgs:
  - --cert-dir=/tmp
  - --kubelet-preferred-address-types=InternalIP
  - --kubelet-use-node-status-port
  - --metric-resolution=15s
fullnameOverride: metrics-server
```

| 值 | 为什么是契约 |
|---|---|
| `args: --kubelet-insecure-tls` | 自签 kubelet 证书的集群不加这个参数，metrics-server 永远 NotReady；这是「装了但不能用」最常见的原因 |
| `defaultArgs` 显式钉住 | 不依赖 chart 默认值漂移；`InternalIP` 适配绝大多数内网集群 |
| `fullnameOverride: metrics-server` | 官方 chart 默认用 release 名做 fullname（会变成 `platform-metrics-server`），而组件探测认的是 Deployment `metrics-server` —— 不改名会出现「装成功但页面显示未安装」 |

可编辑值：`replicas`（默认 1）。

## 4. 安装 / 升级

- 表单只有「副本数」；
- 升级时 values 合并顺序见 [README §1.3](README.md#13-values-合并顺序)；
- 只有平台装过的 release 才允许升级（`UPGRADE` + 无任何平台操作记录 → 拒绝）。

## 5. 装完怎么验证

```bash
kubectl get apiservice v1beta1.metrics.k8s.io -o wide   # Available=True
kubectl top nodes
kubectl -n kube-system get deploy metrics-server
```

平台侧：集群详情 → 组件 → metrics-server 显示「已安装且健康」，HPA 页能看到资源指标。

## 6. 已知坑

1. **NotReady / `x509: cannot validate certificate`** —— 集群 kubelet 自签证书。
   契约已加 `--kubelet-insecure-tls`，不要为了「更安全」去掉它。
2. **装完页面仍显示未安装** —— 多半是 Deployment 名字被 release 名带偏。`fullnameOverride` 不能删。
3. **`kubectl top` 报 `the server could not find the requested resource`** —— APIService 没注册成功，
   看 `kubectl -n kube-system logs deploy/metrics-server` 与 `kubectl get apiservice`。
4. **升级后 HPA 指标短暂中断** —— 单副本滚动更新期间的正常现象；副本数 > 1 可缓解。
5. **镜像拉取失败** —— 离线集群需要把 `registry.k8s.io/metrics-server/metrics-server` 同步到私服。

## 7. 新增版本

1. `helm pull metrics-server --repo https://kubernetes-sigs.github.io/metrics-server --version <x.y.z> --untar`；
2. 入库到 `charts/components/metrics-server/<x.y.z>/chart/`，写 `provenance.yaml`；
3. 在 `ComponentRecipeCatalog.METRICS_SERVER_RELEASES` **最前面**加 `ReleaseSpec`（release 名/namespace/契约 values 不变）；
4. 检查新 chart 的 `args` / `defaultArgs` / `fullnameOverride` 是否还叫这些 key（上游改过 key 的话，
   契约 values 会静默不生效 —— 用 `helm template` 看渲染结果确认）；
5. 非生产集群真装一次，确认 `kubectl top` 与组件探测都正常；
6. 重建 k8s-server（charts 入镜像）+ platform-api（recipe）。

## 8. 卸载

平台不提供卸载。手工：

```bash
helm -n kube-system uninstall platform-metrics-server
kubectl delete apiservice v1beta1.metrics.k8s.io   # 视 chart 是否管理它而定，确认后再删
```

卸载不会删 CRD（本组件没有 CRD），组件探测会随 Deployment 消失自动变为未安装。
