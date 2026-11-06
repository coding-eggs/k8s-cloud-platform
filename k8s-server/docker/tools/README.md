# 预置的 k8s 命令行工具

镜像里为什么需要它们：

| 工具 | 用途 |
|---|---|
| `helm` | 组件安装（`HelmRunner` 直接调 PATH 上的 `helm`）。⚠️ 版本必须是平台验证过的：Helm 4 用 `--rollback-on-failure`，没有 `--atomic` |

> `kubectl` 已于 2026-11-06 去掉：Calico CRD 这类不建 Helm release 的清单、应用发布与初始化 Job，
> 改由 k8s-server 用 fabric8 直接 server-side apply（`ManifestApplier`）。k8s-server 要适配多个版本的集群，
> 钉死一个 kubectl 版本有版本偏差风险。

## 为什么要提交进仓库

原来 `Dockerfile` 在**构建时**用 `curl` 从 `get.helm.sh` 现下，带来两个问题：

1. **内网构建机没有出网就整条构建链失败**；
2. 每次构建拿到的字节取决于当时的外网内容，**不可复现**。

现在构建只做 `COPY` + 解压，完全离线。

## 目录结构

```
linux-amd64/
  helm-v4.2.0-linux-amd64.tar.gz   # 官方 tar 包（19MB）
```

仓库里**只预置了 linux-amd64**。要出 arm64 镜像时另拉一份：

```bash
bash scripts/fetch-k8s-tools.sh arm64
```

## 换版本

```bash
bash scripts/fetch-k8s-tools.sh
```

脚本会从官方拉取并与官方发布的 `sha256` 比对，**对不上直接失败**，不会把坏文件留在仓库里。
换完版本记得同步改 `k8s-server/Dockerfile` 里 `COPY` 的文件名 —— 只改一处会构建失败。

## 内网镜像站

```bash
HELM_BASE_URL=http://mirror/get.helm.sh bash scripts/fetch-k8s-tools.sh
```
