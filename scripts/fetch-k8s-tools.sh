#!/usr/bin/env bash
#
# 把 helm 的 linux 二进制预置进 k8s-server/docker/tools/linux-<arch>/。
#
# 为什么要预置：k8s-server 的镜像里必须有 helm（组件安装，HelmRunner 直接调 `helm`）。
# kubectl 已于 2026-11-06 去掉：清单直接落盘改由 fabric8 做（ManifestApplier）。
# 原来 Dockerfile 在**构建时**从 get.helm.sh 现下 —— 内网构建机没有出网就整个
# 构建失败。改成「仓库里存好、构建时 COPY」，构建就完全离线、且每次构建拿到的是同一份字节。
#
# 用法（在仓库任意位置执行都行）：
#   bash scripts/fetch-k8s-tools.sh            # 默认 linux-amd64（仓库里只提交了这一份）
#   bash scripts/fetch-k8s-tools.sh arm64      # 需要多架构时再拉一份，并改 Dockerfile
#
# 内网镜像站（构建机/开发机都上不了外网时）：
#   HELM_BASE_URL=http://mirror/get.helm.sh bash scripts/fetch-k8s-tools.sh
#
# ⚠️ 下载后**必须**与官方发布的校验值比对，对不上直接失败 —— 不会把坏文件或半截文件
#    留在仓库里（先下到临时目录，校验通过才 mv 过去）。
# ⚠️ 换版本时：改下面的默认值 → 跑本脚本 → 改 k8s-server/Dockerfile 里的文件名。
set -euo pipefail

ARCH="${1:-amd64}"
HELM_VERSION="${HELM_VERSION:-v4.2.0}"
HELM_BASE_URL="${HELM_BASE_URL:-https://get.helm.sh}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/k8s-server/docker/tools/linux-$ARCH"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

say() { printf '\n==> %s\n' "$*"; }

mkdir -p "$DEST"

# ---------------- helm ----------------
# 官方同时提供 .tar.gz 和同名 .sha256sum（内容是 "<hash>  <文件名>"）
HELM_FILE="helm-${HELM_VERSION}-linux-${ARCH}.tar.gz"
say "helm ${HELM_VERSION} (linux-${ARCH})"
curl -fsSL --retry 3 --connect-timeout 20 \
  "${HELM_BASE_URL}/${HELM_FILE}" -o "$TMP/$HELM_FILE"
EXPECT="$(curl -fsSL --retry 3 --connect-timeout 20 "${HELM_BASE_URL}/${HELM_FILE}.sha256sum" | awk '{print $1}')"
ACTUAL="$(sha256_of "$TMP/$HELM_FILE")"
[ "$EXPECT" = "$ACTUAL" ] || {
  echo "✗ helm 校验失败：期望 $EXPECT，实际 $ACTUAL" >&2
  exit 1
}
tar -tzf "$TMP/$HELM_FILE" | grep -qx "linux-${ARCH}/helm" || {
  echo "✗ helm 包里没有 linux-${ARCH}/helm" >&2
  exit 1
}
mv "$TMP/$HELM_FILE" "$DEST/$HELM_FILE"
echo "✓ $DEST/$HELM_FILE  ($(wc -c <"$DEST/$HELM_FILE") 字节, sha256 $ACTUAL)"

say "完成。记得让 k8s-server/Dockerfile 里的文件名与上面的版本号一致。"
