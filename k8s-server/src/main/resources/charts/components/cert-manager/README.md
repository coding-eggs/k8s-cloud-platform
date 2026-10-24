# cert-manager（集群组件）

> **这是组件级 README（跨版本）**：平台怎么装、约定是什么、踩过哪些坑、加新版本要改哪里。
> 本次安装的 chart 版本与来源见版本目录：`<version>/README.md`（当前 `v1.21.2/`）。

## 1. 概览

| 项 | 值 |
|---|---|
| 组件 code | `cert-manager` |
| 画像 | BUSINESS / MANAGEMENT |
| release / namespace | `platform-cert-manager` / `cert-manager` |
| 探测判据 | `cert-manager` Deployment 镜像 `jetstack/cert-manager-controller` |
| 平台功能 | `gateway-tls`（网关 HTTPS 证书）；也是 **OTel Operator 的硬前置**（webhook 证书） |

## 2. 契约 values（🔒 不可改）

```yaml
crds:
  enabled: true
  keep: true
```

| 值 | 为什么 |
|---|---|
| `crds.enabled: true` | CRD 跟着 chart 走，否则 `Certificate`/`Issuer` 对象无处可放 |
| `crds.keep: true` | **卸载/升级时不删 CRD** —— 删了会级联清掉集群里全部证书对象，依赖方（网关 TLS、OTel webhook）一起坏 |

## 3. 平台约定

- **唯一有硬依赖关系的组件**：`otel-operator` 的 recipe 里 `requires = ["cert-manager"]`，
  安装前校验必须 `INSTALLED_HEALTHY`，否则 webhook 证书签不出来、operator 起不来；
- 组件页的依赖校验在 platform-api（`requireDependencies`），k8s-server 只执行；
- 本组件没有 CRD 之外的平台对象，也没有可编辑字段（安装表单只有版本选择）。

## 4. 坑

1. **卸载时别删 CRD**：`crds.keep` 丢了之后 `helm uninstall` 会连 CRD 一起删，
   集群里所有 Certificate 对象随之消失（网关证书、OTel webhook 证书）；
2. **webhook 就绪慢**：装完立刻用 cert-manager 签证书会失败（webhook 尚未 Available），
   等 Deployment ready 再继续 —— 平台的依赖校验看的是 `INSTALLED_HEALTHY`，正好覆盖这一点；
3. 与集群里已有的 cert-manager 冲突：同一集群只能有一个（CRD 是集群级），
   平台安装前会做 CRD 归属预检，别人装的就拒绝。

## 5. 装完怎么验证

```bash
kubectl -n cert-manager get deploy
kubectl get crd certificates.cert-manager.io issuers.cert-manager.io
kubectl get pods -A | grep -c cert-manager   # 通常是 3 个（controller / webhook / cainjector）
```

## 6. 加一个新版本怎么做

1. `helm pull cert-manager --repo https://charts.jetstack.io --version <vX.Y.Z> --untar`
   → 入库 `cert-manager/<vX.Y.Z>/chart/` + `provenance.yaml` + `<vX.Y.Z>/README.md`；
2. 改 `ComponentRecipeCatalog.CERT_MANAGER_RELEASES` 的 `chartVersion` / `appVersion`
   （`crds.enabled + keep` 契约不变）；
3. 核对 CRD chart 的升级方式（cert-manager 官方的标准做法是 `kubectl apply` CRD 或 helm `--set crds.enabled=true`，
   两种都支持；平台走 chart 自带 CRD）；
4. 跑测试 → 非生产集群装一次 → 看 3 个 Deployment 全 Ready、CRD 存在；
5. 重建 k8s-server + platform-api。
