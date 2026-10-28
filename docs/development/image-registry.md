# 镜像来源纳管 · 运维手册

> 2026-10-28。设计：[specs/2026-10-28-image-registry-mirror-design.md](../superpowers/specs/2026-10-28-image-registry-mirror-design.md)
> 实施计划：[plans/2026-10-28-image-registry-mirror.md](../superpowers/plans/2026-10-28-image-registry-mirror.md)

## 1. 这套东西是什么

组件、中间件（含 Operator）用到的镜像**改从平台 Harbor 的 `platform` 项目拉**，而不是公网。
表单里可选「平台 Harbor（默认）/ 官方」；切回官方 = 行为完全回到纳管之前。

## 2. 涉及的地址与凭据

| 项 | 值 |
|---|---|
| Harbor 入口 | `https://harbor.ackcp.cloud`（DigiCert 证书，2027-01-25 到期） |
| 镜像前缀 | `harbor.ackcp.cloud/platform/<上游主机>/<仓库路径>:<tag>` |
| 拉取 | **项目已设公开读**（匿名可 pull），节点不需要 imagePullSecret |
| 推送 | Harbor 的复制规则（用管理凭据）；租户项目仍是私有 + 各自 robot |

本集群 `platform` 项目 id=2，`public=true`（2026-10-28 已切）。

## 3. Harbor 侧：把镜像搬进来（复制规则）

每个上游配一条 **replication rule**，目标都是本地 `platform` 项目：

| 远端 | Endpoint | Destination namespace |
|---|---|---|
| docker.io | `https://registry-1.docker.io` | `docker.io` |
| quay.io | `https://quay.io` | `quay.io` |
| ghcr.io | `https://ghcr.io` | `ghcr.io` |
| registry.k8s.io | `https://registry.k8s.io` | `registry.k8s.io` |
| docker.elastic.co | `https://docker.elastic.co` | `docker.elastic.co` |
| gcr.io | `https://gcr.io` | `gcr.io` |

> Destination namespace 决定仓库路径，**必须与平台改写规则一致**（保留上游主机名）。
> 若 Harbor 版本不接受带点号的 namespace，改成 `docker-io` 形式，并同步改
> `ImageReference.mirror` 的主机名转换（一处常量 + 单测）。

**首次全量同步**：装完组件/中间件后调 `POST /cluster/components/images`（或看平台页面）拿到清单，
在 Harbor 里按清单逐个触发复制；核对方法：`GET /v2/<repo>/manifests/<tag>` 返回 200。

## 4. 平台配置

| 配置 | 位置 | 说明 |
|---|---|---|
| `platform.image-registry.host` | platform-api `application.yaml` / 环境变量 | 默认镜像仓库（本集群可留空，平台会取 Harbor 安装记录） |
| `platform.image-registry.project` | 同上 | 默认 `platform` |
| `platform.image-registry.enabled` | 同上 | `false` = 全局关闭改写（回退开关） |
| `k8s_cluster.image_registry_host/project` | 集群设置 | 按集群覆盖（业务集群连不到平台 Harbor 时用） |

地址解析顺序：**集群列 → Harbor 组件安装记录 → 平台默认**；三者都没有 → 视为未配置，不改写。

## 5. 切换与回退

- 组件安装 / 中间件创建表单里的「镜像来源」：`PLATFORM`（默认）/ `OFFICIAL`。
- 切回官方：表单选 OFFICIAL 重放一次即可（**不需要**回滚代码）。
- 全局回退：`platform.image-registry.enabled=false`。
- Harbor 本身的镜像**永远走官方**（先有鸡还是先有蛋）：装 Harbor 组件时即使选了平台 Harbor 也不改写。

## 6. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| Pod `ImagePullBackOff` + `manifest unknown` | 镜像还没被复制到 Harbor | 按 §3 触发复制 |
| Pod `x509: certificate signed by unknown authority` | Harbor 证书链不完整（缺中间证书） | 重新下发完整 PEM（`tls.crt` 含中间证书） |
| Pod `x509: certificate has expired` | Harbor 证书到期 | 见 [续期方案](../superpowers/specs/2026-10-28-harbor-tls-renewal-design.md) |
| `no such host: harbor.ackcp.cloud` | 节点解析不到该域名 | 内网 DNS / 节点 hosts 加记录（**containerd 用节点解析器，不是 CoreDNS**） |
| 装了组件但镜像仍走公网 | 该 chart 的 image 字段不在识别口径里 | 跑 `ImageCatalogGuardTest` 看是哪份 values 漏了，补 `ImageRewriter` 规则 |

## 7. 平台自身镜像

platform-api / k8s-server / platform-web / platform-auth 的 Deployment 不在本仓库里，平台只提供清单。
**顺序很重要**：先把这 4 个镜像推到 `platform/<上游主机>/...`，再改部署清单的 image 与
`imagePullSecrets`（若项目仍是私有）——否则 k8s-server 起不来，连"下发 Secret"的机会都没有。
