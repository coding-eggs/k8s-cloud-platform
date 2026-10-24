# seaweedfs chart 4.48.0（app 4.48）

- 来源：`https://seaweedfs.github.io/seaweedfs/helm`（`helm pull seaweedfs --version 4.48.0 --untar`）
- 拉取日期：2026-10-24（经本地 7890 代理）；上游 <https://github.com/seaweedfs/seaweedfs>，Apache-2.0
- 覆盖范围：**chart 目录只含这一份**；入库时仅删掉 `ci/`，模板未改
- digest：`fa9beb4b68ee1e56129779643194968210381a33caf47fc177fd17d43efb686e`（见 `provenance.yaml`）
- 镜像：`docker.io/chrislusf/seaweedfs:4.48`
  —— **tag 来自 Chart.yaml 的 appVersion**（chart 的 `seaweedfs.image` helper：`default .Chart.AppVersion .Values.image.tag`）
- 本版本注意：
  - ⚠️ **为什么不是 4.49.0**：chart 4.49.0 的 appVersion 是 `4.49`，但 Docker Hub 上
    `chrislusf/seaweedfs:4.49` 至今**没有发布**（只有 `4.49_large_disk_foundationdb` 这种变体），
    部署会 `manifest unknown`。`4.48` 是当前最新的普通镜像 tag。
    升级 chart 前必须先在 registry 上确认 tag 存在（`curl -o /dev/null -w '%{http_code}' https://hub.docker.com/v2/repositories/chrislusf/seaweedfs/tags/<tag>`）；
  - `s3.createBuckets` 已支持声明式建桶（后续做桶分配可以走 values）；
  - `global.seaweedfs.enableSecurity` 默认 false；`s3.enableAuth` 才是 S3 的 AK/SK 开关，两者独立；
  - **admin 管理台**（可选，`admin.enabled`）：端口 **23646**、gRPC 33646；绑定 `0.0.0.0`（探针需要）时
    **必须有口令**，否则容器直接退出 —— 平台用 `admin.secret.existingSecret` 引用生成的
    `platform-seaweedfs-admin`（见组件级 README §3.1）；管理台数据盘默认 emptyDir；
  - 渲染出的对象（平台 values）：SA / ConfigMap / 5 个 Service / `seaweedfs-s3` Deployment /
    master、volume、filer 三个 StatefulSet / `seaweedfs-s3-secret`；**关掉 `createClusterRole` 后
    没有 ClusterRole/ClusterRoleBinding**；
  - **真集群验证（2026-10-24，coding-cluster k8s 1.35.7）**：`helm lint` 通过；
    `kubectl apply --dry-run=server` 12 个对象全部通过（含 STS 的 `volumeClaimTemplates`、resources、
    Service 端口）。本版本尚未在真集群完整装过（4.49.0 的实装暴露了镜像 tag 问题，已改用 4.48.0）。

组件级约定、坑与加版本流程见上一级 `../README.md`。
