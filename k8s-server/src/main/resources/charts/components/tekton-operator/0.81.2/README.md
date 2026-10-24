# tekton-operator chart 0.81.2（app v0.81.2，Tekton Pipelines 1.15.x）

- 来源：Tekton Operator 0.81.2 release 资产里的 Helm chart（`tekton-operator-0.81.2.tgz`）
- 拉取日期：2026-10-17；原样入库
- 镜像：`ghcr.io/tektoncd/operator/*`；按 profile 拉 Tekton Pipelines / Trigger / Result / Chain / Dashboard
- 本版本注意：
  - profile 值只有 `lite` / `basic` / `all`，通过占位符 `${tekton.profile}` 落进平台下发的 `TektonConfig`；
  - **两遍安装**：第一遍只装 CRD（`installCRDs: true`），第二遍带 `extraObjects: [TektonConfig]`
    —— 同一份 manifest 里既装 CRD 又下发 CR 会报 `no matches for kind`；
  - `operator.autoInstallComponents: false`：TektonConfig 由平台下发，不让 operator 自己装（否则两边抢）；
  - **`fullnameOverride: tekton-operator` 必须写**：operator 注册的 webhook 配置硬编码 service
    `tekton-operator-webhook`，release 名带 `platform-` 前缀时不覆盖会 `service not found`，
    TektonConfig 被 webhook 拒（2026-10-18 实装踩到）；
  - 1.15.x 的 values 名是 `pipelineruns`（不是 `pipeline`），改调度/缓存时要对。

组件级约定、坑与加版本流程见上一级 `../README.md`。
