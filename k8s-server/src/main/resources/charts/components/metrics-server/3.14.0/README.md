# metrics-server chart 3.14.0（app 0.9.0）

- 来源：`https://kubernetes-sigs.github.io/metrics-server`
- 拉取日期：2026-10-17（经本地 7890 代理 `helm pull`）
- 入库方式：**原样入库，未改模板**；平台的定制全部通过 recipe 的 values 完成。见 `provenance.yaml`
- 镜像：`registry.k8s.io/metrics-server/metrics-server:v0.9.0`（离线集群需同步）
- 本版本注意：
  - `args` / `defaultArgs` / `fullnameOverride` 三个 key 与 3.x 系列一致，`fullnameOverride` 仍是固定 Deployment 名的唯一手段；
  - 从**别的 chart 来源**（手写版 / 社区另一份打包）换到本版本时不能原地 `helm upgrade` ——
    Deployment selector 不可变，必须先卸载再装。

组件级约定、坑与加版本流程见上一级 `../README.md`。
