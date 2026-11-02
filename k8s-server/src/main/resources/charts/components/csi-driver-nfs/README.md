# csi-driver-nfs（集群组件）

> **这是组件级 README（跨版本）**：平台怎么装、约定是什么、踩过哪些坑、加新版本要改哪里。
> 具体 chart 版本的来源/版本号在版本目录：`<version>/README.md`（当前 `4.13.6/`）。

## 1. 概览

| 项 | 值 |
|---|---|
| 组件 code | `csi-driver-nfs` |
| 画像 | BUSINESS / MANAGEMENT |
| release / namespace | `platform-csi-driver-nfs` / `kube-system` |
| 探测判据 | `kube-system` Deployment `csi-nfs-controller`（镜像含 `sig-storage/nfsplugin`） |
| 平台功能 | `volume-snapshot`（卷快照） |
| 安装单元 | 驱动 + `snapshot-controller` + 三个快照 CRD + 存储类 `nfs-csi` + 快照类 `nfs-csi-snapclass` |

## 2. 表单与契约

可编辑值：

| 字段 | 默认 | 说明 |
|---|---|---|
| NFS 服务地址 / 导出目录 | 空，必填 | 填集群现有 NFS 服务即可；`platform-api` 校验必填 |
| PVC 删除后的数据 | `Retain` | 要承接迁移数据，误删 PVC 不应连带删数据。上游默认是 Delete |
| 快照删除后的数据 | `Delete` | `Retain` 时删快照不删 NFS 上的 tar 包 |

前三项写入 StorageClass，**安装后不可改**（K8s 不可变字段，平台提前拒绝）。要换地址只能另建存储类。

契约值（🔒）：

| 值 | 为什么 |
|---|---|
| `externalSnapshotter.enabled: true` + CRD `enabled: true` | 集群没有这些就做不了快照；不单列 snapshot-controller 组件 |
| `controller.enableSnapshotter: true` | 显式钉住，不依赖 chart 默认值 |
| `storageClass.name: nfs-csi`，不带默认存储类注解 | **与现有 nfs-subdir 存储类并存**，现有 PVC 和没写存储类的新 PVC 都不受影响 |
| 第二遍 `volumeSnapshotClass.create: true` | 见 §3 |

## 3. 约定

- **两遍同名 release**：CRD 在 `templates/`，与快照类同一份 manifest 会报 `no matches for kind`（Tekton / OTel 同类问题，本组件未实测复现）。
  第一遍不写 `create`：新装取 chart 默认 false，重装从快照继承 true，所以编辑不会先删快照类。
- **平台不删任何存储类**，也不改默认存储类。切默认、迁移数据、下线 nfs-subdir 都由管理员决定。
- **不设 `mountOptions`**：NFS 协议版本由客户端和服务端协商。

## 4. 已知限制（来自 chart 参数与上游说明，**未在本平台真集群验证**）

- 旧 nfs-subdir PV 不归这个驱动管，不能打快照；要迁到 `nfs-csi` 的 PVC。
- 快照是 tar 包，存在同一台 NFS 上：不是备份，数据量大时慢，写入中打快照不保证一致。
- NFS 不按 PVC 容量限制写入。
- 集群已有 `snapshot-controller` 或快照 CRD 时，安装会用 `--force-conflicts` 接管 CRD 并多跑一个控制器，先手工确认。
- 集群已有同名存储类 `nfs-csi` 时安装会冲突。
- 节点需要有 NFS 客户端（`nfs-common` / `nfs-utils`）；导出目录需要 `no_root_squash`。

## 5. 装完怎么验证

```bash
kubectl -n kube-system get deploy csi-nfs-controller snapshot-controller
kubectl get csidriver nfs.csi.k8s.io
kubectl get storageclass nfs-csi
kubectl get volumesnapshotclass nfs-csi-snapclass
```

## 6. 加新版本

按 [通用流程](../README.md) §2.4；两个 `ReleaseSpec` 的版本要一起改。
