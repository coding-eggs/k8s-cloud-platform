# external-gateway（Istio 组件的对外入口 release）

> **这是 chart 级 README（跨版本）**。本 chart 部署集群对外入口（Deployment + Service），
> release 名固定 `external-gateway` —— 集群对外拓扑、平台路由与 Telemetry 都按这个名字引用。
> 组件总文档见 [istio-base/README.md](../istio-base/README.md)；本次安装的版本见版本目录 `<version>/README.md`。

## 要点

- chart **只建 Deployment + Service，不建 Gateway CR**；Gateway CR 由平台 SSA 下发并维护
  `spec.addresses` 绑定（否则 `Programmed=False / AddressNotUsable`）；
- 可编辑：`service.type` + 端口表（一行同时描述 Service 端口与 Gateway listener）；
- 入口名不能改（改了 = 换入口，路由与 Telemetry 全部指错）。
