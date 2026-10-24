# cert-manager chart v1.21.2（app v1.21.2）

- 来源：`https://github.com/cert-manager/cert-manager`（官方 helm chart）
- 拉取日期：2026-10-17；原样入库
- 镜像：`quay.io/jetstack/cert-manager-controller` / `-webhook` / `-cainjector`（同 tag）
- 本版本注意：
  - `crds.enabled=true` + `crds.keep=true` 是平台的契约值（见组件级 README）；
  - 三个 Deployment（controller / webhook / cainjector）都 Ready 才算装好；
  - OTel Operator 依赖本版本签发的 webhook 证书。

组件级约定、坑与加版本流程见上一级 `../README.md`。
