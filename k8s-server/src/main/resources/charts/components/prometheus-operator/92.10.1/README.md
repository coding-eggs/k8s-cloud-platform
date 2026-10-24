# kube-prometheus-stack chart 92.10.1（app v0.94.1）

- 来源：`https://github.com/prometheus-community/helm-charts`（`charts/kube-prometheus-stack`）
- 拉取日期：2026-10-17；原样入库，平台定制只走 recipe values
- 镜像：`quay.io/prometheus-operator/prometheus-operator`、`quay.io/prometheus/prometheus`、
  `quay.io/prometheus/alertmanager`、`quay.io/prometheus/node-exporter`、`registry.k8s.io/kube-state-metrics/kube-state-metrics`
  （Grafana 默认关，装的时候也拉 `docker.io/grafana/grafana`）
- 本版本注意：
  - 四个 `*SelectorNilUsesHelmValues=false` + `*NamespaceSelector={}` 必须覆盖，否则租户 ns 的对象被静默忽略；
  - `crds.upgradeJob.enabled=true`（Helm 不升级 `crds/`，官方 Job 是 CRD 升级的唯一正确姿势）；
  - node_exporter 1.12 的 `--collector.cpu.info` 是 Bool 且无默认值，必须显式加到 `extraArgs`
    （**extraArgs 是 list，整体替换**，默认的两条 filesystem exclude 要原样写回）。

组件级约定、坑与加版本流程见上一级 `../README.md`。
