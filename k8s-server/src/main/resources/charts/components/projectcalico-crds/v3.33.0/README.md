# projectcalico.org.v3 chart v3.33.0（Calico v3.33.0）

- 来源：`https://docs.tigera.io/calico/charts`（`projectcalico.org.v3` v3.33.0）
- 拉取日期：2026-10-22；原样入库
- 内容：Calico 全部 CRD（IPPools / IPAMBlock / BGP* / Installation …），供 `tigera-operator` 使用
- 本版本注意：chart 体积 3.5MB，**不能做 Helm release**；走 server-side apply + `wait Established`；
  渲染要带目标集群的 kube-version 与 api-versions。

组件级说明见上一级 `../README.md`。
