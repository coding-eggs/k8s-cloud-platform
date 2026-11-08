# external-gateway（Istio 组件的对外入口 release）

> **这是 chart 级 README（跨版本）**。本 chart 部署集群对外入口（Deployment + Service），
> release 名固定 `external-gateway` —— 集群对外拓扑、平台路由与 Telemetry 都按这个名字引用。
> 组件总文档见 [istio-base/README.md](../istio-base/README.md)；本次安装的版本见版本目录 `<version>/README.md`。

## 要点

- chart **只建 Deployment + Service，不建 Gateway CR**；Gateway CR 由平台 SSA 下发并维护
  `spec.addresses` 绑定（否则 `Programmed=False / AddressNotUsable`）；
- 可编辑：`service.type` + 端口表（一行同时描述 Service 端口与 Gateway listener）；
- 入口名不能改（改了 = 换入口，路由与 Telemetry 全部指错）。

## 应用端口池（2026-11-08）

- 编辑表单里的「应用端口池」是一个范围（默认 `20000-20999`，最多 5000 个，都留空 = 停用），
  值存在 values 的 `platform.ingressPortPool`，下发 helm 前由平台展开成 `service.ports` 里的
  `pool-<端口>` 行（`nodePort = port`），`platform` 键不进 helm；
- **为什么要预声明**：ListenerSet 用了 Service 上没声明的端口，整个 Gateway 会变成
  `Programmed=False / AddressNotUsable`（实测）。所以应用只能用池里的端口；
- 启用时 Gateway 设 `allowedListeners`：只放行带 `ingress.platform.coding/ports=true` 标签的命名空间里的
  ListenerSet（每个命名空间一个 `platform-ingress`，由平台维护，租户不能自己建）；
- 校验：不能与端口表的 port / nodePort 重叠；缩小或停用时，新范围之外还有应用在用的端口会被拒并逐条列出；
- 端口数超过 1000 时表单提示：上线前核对 kube-proxy（本集群 ipvs）规则同步耗时。
