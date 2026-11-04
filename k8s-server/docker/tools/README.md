# 预置的 k8s 命令行工具

镜像里为什么需要它们：

| 工具 | 用途 |
|---|---|
| `helm` | 组件安装（`HelmRunner` 直接调 PATH 上的 `helm`）。⚠️ 版本必须是平台验证过的：Helm 4 用 `--rollback-on-failure`，没有 `--atomic` |
| `kubectl` | Calico 的 CRD chart 超过 1MiB，装不进 Helm release Secret，那条路径改走 `kubectl apply --server-side` |

## 为什么要提交进仓库

原来 `Dockerfile` 在**构建时**用 `curl` 从 `get.helm.sh` / `dl.k8s.io` 现下，带来两个问题：

1. **内网构建机没有出网就整条构建链失败**；
2. 每次构建拿到的字节取决于当时的外网内容，**不可复现**。

现在构建只做 `COPY` + 解压，完全离线。

## 目录结构

```
linux-amd64/
  helm-v4.2.0-linux-amd64.tar.gz   # 官方 tar 包（19MB）
  kubectl-v1.37.2-linux-amd64.gz   # 裸二进制 gzip 过（约 18MB）；裸的约 60MB
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
换完版本记得同步改 `k8s-server/Dockerfile` 里那两行 `COPY` 的文件名 —— 只改一处会构建失败。

## 内网镜像站

```bash
HELM_BASE_URL=http://mirror/get.helm.sh \
KUBECTL_BASE_URL=http://mirror/dl.k8s.io bash scripts/fetch-k8s-tools.sh
```
