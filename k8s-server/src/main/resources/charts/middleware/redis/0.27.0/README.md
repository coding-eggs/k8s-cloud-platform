# redis-operator 0.27.0（内置 chart）

- 来源：`https://github.com/OT-CONTAINER-KIT/helm-charts/releases/download/redis-operator-0.27.0/redis-operator-0.27.0.tgz`
- 拉取日期：2026-10-22
- 上游仓库：<https://github.com/OT-CONTAINER-KIT/redis-operator>（Apache-2.0）
- 用法：k8s-server 的 `MiddlewareOperatorInstaller` 以 `platform-middleware-redis` 为 release 名安装到 `platform-middleware-system`。
- 包含的 CRD（`crds/crds.yaml`）：`redis`、`redisreplications`、`redissentinels`、`redisclusters`，组 `redis.redis.opstreelabs.in`。
- 镜像：`quay.io/opstree/redis-operator`（集群需能拉取 quay.io 或配置镜像代理）。
- 升级注意：CRD 只随 chart 的 `crds/` 首装写入，`helm upgrade` 不会更新 `crds/` 目录的内容；升级本 chart 时要单独评估 CRD 变更。

组件级约定、坑与加版本流程见上一级 `../README.md`。
