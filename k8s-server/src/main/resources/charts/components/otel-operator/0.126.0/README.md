# opentelemetry-operator chart 0.126.0（app 0.161.0）

- 来源：`https://github.com/open-telemetry/opentelemetry-helm-charts`，chart 0.126.0 / app 0.161.0
- 拉取日期：2026-10-18；原样入库（未改模板）
- 采集器镜像：`ghcr.io/open-telemetry/opentelemetry-collector-releases/opentelemetry-collector-contrib:0.161.0`
  （**必须是 contrib 发行版**：默认的 `-k8s` 发行版没有 `elasticsearch` exporter）
- 本版本注意：
  - `Instrumentation` 是 `v1alpha1`、`OpenTelemetryCollector` 是 `v1beta1`（两遍安装：第一遍 CRD + operator，
    第二遍才 apply CR —— 否则 `no matches for kind`）；
  - `elasticsearchexporter` 自 0.161.0 起**忽略 `mapping.mode`**，默认 `otel` 模式要求 data stream，
    写普通索引会 404 `resource_not_found_exception`。修法是 `mapping.allowed_modes: [none]`（组件级 README §6.2）；
  - webhook 证书依赖 cert-manager（chart 的 `admissionWebhooks.certManager.enabled=true`）。

组件级约定、坑与加版本流程见上一级 `../README.md`。
