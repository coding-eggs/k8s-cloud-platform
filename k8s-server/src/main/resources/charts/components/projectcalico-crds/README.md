# Calico CRD（`projectcalico.org.v3`，Calico 组件的第一部分）

> **这是 chart 级 README（跨版本）**。本 chart 不是独立组件，而是 **Calico 组件**的 CRD 部分：
> 渲染后走 **server-side apply 直接落盘，不建 Helm release**（chart 3.5MB，超过 Helm release Secret 的 1MiB 上限）。
> 组件总文档见 [tigera-operator/README.md](../tigera-operator/README.md)；
> 本次安装的版本见版本目录 `<version>/README.md`。

## 要点

- `applyMode = SERVER_SIDE_APPLY`：`kubectl apply --server-side` + 等 `Established`；
- 渲染前必须按目标集群探测 `--kube-version` / `--api-versions`（helm template 的默认 capabilities ≈1.35，
  直接拿去 apply 到老集群会失败）；
- CRD 是集群级的，卸载不会删；重装时按"外部 CRD"认领规则处理。
