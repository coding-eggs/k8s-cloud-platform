# seaweedfs chart 4.49.0（app 4.49）

- 来源：`https://seaweedfs.github.io/seaweedfs/helm`（`helm pull seaweedfs --version 4.49.0 --untar`）
- 拉取日期：2026-10-24（经本地 7890 代理）；上游 <https://github.com/seaweedfs/seaweedfs>，Apache-2.0
- 覆盖范围：**chart 目录只含这一份**；入库时仅删掉 `ci/`，模板未改
- digest：`96ae9dc86ac2ba016aa8977b06ea87c8f5659005aca95d031fb965c98de71226`（见 `provenance.yaml`）
- 镜像：`docker.io/chrislusf/seaweedfs:4.49`（chart 默认，随 chart 的 `global.seaweedfs.image.name`）
- 本版本注意：
  - `s3.createBuckets` 已经是**声明式建桶**的稳定入口（后续做桶分配时可以走 values，见组件级 README §6）；
  - `global.seaweedfs.enableSecurity` 默认 false；`s3.enableAuth` 才是 S3 的 AK/SK 开关，两者独立；
  - 渲染出的对象（平台 values）：SA / ConfigMap / 5 个 Service / `seaweedfs-s3` Deployment /
    master、volume、filer 三个 StatefulSet / `seaweedfs-s3-secret`；**关掉 `createClusterRole` 后
    没有 ClusterRole/ClusterRoleBinding**；
  - **真集群验证（2026-10-24，coding-cluster k8s 1.35.7）**：`helm lint` 通过；
    `kubectl apply --dry-run=server` 12 个对象全部通过（含 STS 的 `volumeClaimTemplates`、resources、
    Service 端口）。真正 `helm install` 未执行（执行时被会话权限策略拦下），部署后从组件 tab 装一次即可补验。

组件级约定、坑与加版本流程见上一级 `../README.md`。
