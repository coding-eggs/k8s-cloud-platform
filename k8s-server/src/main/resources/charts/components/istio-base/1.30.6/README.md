# Istio 1.30.6 · Gateway API 1.6.2（组件当前钉的版本）

本文件只记**这一版**的事实：六个 chart 的版本与来源。跨版本的约定、坑与加版本流程见上一级
`../README.md`。

## 1. 版本表

| release | chart 目录 | chart 版本 | app 版本 | 角色 |
|---|---|---|---|---|
| `platform-gateway-api` | `gateway-api` | 1.6.2 | 1.6.2 | 随附（CRD 依赖，排第一） |
| `istio-base` | `istio-base` | 1.30.6 | 1.30.6 | 随附 |
| `istiod` | `istio-istiod` | **1.30.6** | 1.30.6 | **主（组件版本 = 1.30.6）** |
| `istio-cni` | `istio-cni` | 1.30.6 | 1.30.6 | 随附（ambient 数据面） |
| `ztunnel` | `istio-ztunnel` | 1.30.6 | 1.30.6 | 随附（ambient 数据面） |
| `external-gateway` | `istio-gateway` | 1.30.6 | 1.30.6 | 随附（对外入口，名字固定） |

## 2. 来源

| chart | 来源 | 拉取日期 |
|---|---|---|
| `gateway-api` | `wiremind/helm-charts` 的 `gateway-api-crds`（experimental 通道） | 2026-10-17 |
| `istio-base` / `istio-istiod` / `istio-cni` / `istio-ztunnel` / `istio-gateway` | `https://istio-release.storage.googleapis.com/charts`（官方） | 2026-10-17 |

全部**原样入库**，每个 chart 目录另有 `provenance.yaml` 记录 digest/拉取命令。

## 3. 本版本注意

- Ambient 需要三件套同时在：istiod 的 `PILOT_ENABLE_AMBIENT=true`、cni 的 `ambient.enabled=true`、
  ztunnel —— 缺一个 ztunnel 会刷 `requires PILOT_ENABLE_AMBIENT=true`（2026-10-20 实锤，见组件级 §6）；
- 真集群实测 pilot 镜像为 `registry.istio.io/release/pilot`；老安装的版本标签是 1.30.3，
  与本版本不一致时组件清单会显示差异（版本来源说明见组件级 README）；
- Gateway API 走 experimental，TCP/TLS/UDP Route 才可用；standard 通道没有这三类。
