# ztunnel（Istio 组件的 ambient 数据面）

> **这是 chart 级 README（跨版本）**。本 chart 是 ambient 模式的 L4 代理（DaemonSet），不是独立组件。
> 组件总文档见 [istio-base/README.md](../istio-base/README.md)；本次安装的版本见版本目录 `<version>/README.md`。

## 要点

- 没有可编辑 values（由 istiod 的 ambient 开关与 cni 驱动）；
- 日志刷 `ztunnel requires PILOT_ENABLE_AMBIENT=true` = istiod 没开 ambient（见入组件级实锤）；
- 每个节点一个 Pod，滚动升级会短暂中断节点上的新连接建立。
