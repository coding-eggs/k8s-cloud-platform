# tigera-operator chart v3.33.0 + projectcalico.org.v3 chart v3.33.0（Calico v3.33.0）

- 来源：`https://docs.tigera.io/calico/charts`（`projectcalico/calico` 仓库）
- 拉取日期：operator 2026-10-17、CRD chart 2026-10-22；原样入库
- 镜像：`docker.io/calico/operator`、`docker.io/calico/node`、`docker.io/calico/cni`、`docker.io/calico/kube-controllers`
- 本版本注意：
  - **tigera-operator chart 不含 CRD**，CRD 是独立 chart `projectcalico-crds`（3.5MB），
    必须走 `applyMode=SERVER_SIDE_APPLY`（Helm release Secret 上限 1MiB，装不下）；
  - CRD chart 渲染前按集群能力探测：`--kube-version <major.minor>` + `--api-versions`，
    否则 helm 用默认 capabilities（≈1.35）渲染，direct-apply 到老集群会失败；
  - CRD 就绪判定要等 `Established`（server-side apply 之后）；
  - `Installation` CR 由 operator 接管（CIDR / 封装方式来自安装表单，写错直接断网）。

组件级约定、坑与加版本流程见上一级 `../README.md`。
