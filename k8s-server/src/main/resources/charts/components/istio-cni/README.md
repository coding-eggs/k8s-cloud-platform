# istio-cni（Istio 组件的 ambient 数据面）

> **这是 chart 级 README（跨版本）**。本 chart 是 Istio 组件在 ambient 模式下的 CNI 插件，
> 不是独立组件。组件总文档见 [istio-base/README.md](../istio-base/README.md)；
> 本次安装的版本见版本目录 `<version>/README.md`。

## 要点

- 契约：`ambient.enabled=true`（平台默认 ambient，不装 sidecar 注入）；
- 与 istiod 的 `PILOT_ENABLE_AMBIENT`、ztunnel 是**三件套**，缺一个 ztunnel 就不 Ready；
- DaemonSet 会滚动节点上的 CNI 配置，升级风险高，先在非生产集群走一遍。
