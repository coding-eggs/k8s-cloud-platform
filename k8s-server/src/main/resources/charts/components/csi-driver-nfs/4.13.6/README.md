# csi-driver-nfs chart 4.13.6（app 4.13.6）

- 来源：`https://raw.githubusercontent.com/kubernetes-csi/csi-driver-nfs/master/charts`
- digest：`4b471fb134bfea4fcaa3dc3342a67acd646431b746c673c978327353db5d22da`
- 拉取日期：2026-11-01
- 入库方式：**原样入库，未改模板**；平台的定制全部通过 recipe 的 values 完成。见 `provenance.yaml`
- 镜像（均在 `registry.k8s.io/sig-storage/`）：`nfsplugin:v4.13.6`、`csi-provisioner:v6.3.0`、`csi-resizer:v2.2.1`、
  `csi-snapshotter:v8.6.0`、`livenessprobe:v2.19.0`、`csi-node-driver-registrar:v2.17.0`、`snapshot-controller:v8.6.0`
- 本版本注意：`image.*.repository` 除 `nfs` 外都以 `/` 开头，模板用 `image.baseRepo` 拼前缀。

组件级约定、坑与加版本流程见上一级 `../README.md`。
