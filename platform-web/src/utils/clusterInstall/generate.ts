/**
 * 把安装表单变成一组可直接拷进 kubespray v2.32.0 checkout 的文件。
 * 纯函数：不读环境、不发请求。
 */
import type { InstallFile, InstallForm, InstallNode } from './types'
import { KUBESPRAY_VERSION, osFamily, osLabel, type KubesprayVars } from './kubespray.ts'
import { yamlDoc } from './yaml.ts'

function quote(v: string): string {
  return JSON.stringify(v)
}

function inventoryDoc(form: InstallForm): string {
  const cps = form.nodes.filter((n) => n.roles.includes('control-plane'))
  const workers = form.nodes.filter((n) => n.roles.includes('worker'))
  const etcd = form.etcdMode === 'dedicated' ? form.nodes.filter((n) => n.roles.includes('etcd')) : []

  const lines: string[] = ['all:', '  hosts:']
  for (const n of form.nodes) {
    lines.push(`    ${quote(n.hostname)}:`)
    lines.push(`      ansible_host: ${quote(n.ip)}`)
    lines.push(`      ip: ${quote(n.ip)}`)
    lines.push(`      access_ip: ${quote(n.ip)}`)
    lines.push(`      ansible_port: ${n.sshPort}`)
    lines.push(`      ansible_user: ${quote(n.sshUser)}`)
  }

  lines.push('  children:')
  const group = (name: string, nodes: InstallNode[]) => {
    lines.push(`    ${name}:`, '      hosts:')
    for (const n of nodes) lines.push(`        ${quote(n.hostname)}:`)
  }
  group('kube_control_plane', cps)
  group('kube_node', workers)
  if (form.etcdMode === 'external') {
    // 外部 etcd：给一个**空的 etcd 组** —— kubespray 多处直接读 groups['etcd']，
    // 组键不存在会报 undefined；空组表示「不由 kubespray 装 etcd」。
    lines.push('    etcd:', '      hosts:')
  } else if (etcd.length > 0) {
    group('etcd', etcd)
  }
  lines.push('    k8s_cluster:', '      children:', '        kube_control_plane:', '        kube_node:')
  return lines.join('\n') + '\n'
}

function clusterVars(form: InstallForm): KubesprayVars {
  const vars: KubesprayVars = {
    kube_version: form.kubeVersion,
    container_manager: form.runtime,
    kube_network_plugin: form.cni === 'platform' ? 'cni' : form.cni,
    kube_pods_subnet: form.podCidr,
    kube_service_addresses: form.serviceCidr,
    cluster_name: form.clusterDomain,
    kube_proxy_mode: form.kubeProxyMode,
    kube_proxy_strict_arp: form.kubeProxyMode === 'ipvs' && form.apiEntry === 'kubevip-arp',
  }
  // 「自动」就**不写** image_arch：kubespray 默认取每台主机探测到的 `host_architecture`。
  // 写死一个值反而会害了架构不同的节点（按错误架构拉镜像）。
  if (form.arch !== 'auto') vars.image_arch = form.arch
  return vars
}

/** README 用：按 OS 归类计数，混合 OS 集群也能如实写出 `Ubuntu 22.04 × 3、Rocky Linux 9 × 1` */
function osSummary(nodes: InstallNode[]): string {
  const counts = new Map<string, number>()
  for (const n of nodes) counts.set(n.os, (counts.get(n.os) ?? 0) + 1)
  return [...counts.entries()].map(([os, n]) => `${osLabel(os)} × ${n}`).join('、')
}

function noProxy(form: InstallForm): string {
  const parts = [
    form.noProxyExtra,
    '127.0.0.1',
    'localhost',
    form.podCidr,
    form.serviceCidr,
    form.nodeSubnet,
  ]
    .flatMap((s) => s.split(','))
    .map((s) => s.trim())
    .filter(Boolean)
  return [...new Set(parts)].join(',')
}

function entryVars(form: InstallForm): KubesprayVars {
  const vars: KubesprayVars = {}

  if (form.apiEntry === 'kubevip-arp' || form.apiEntry === 'kubevip-bgp') {
    vars.kube_vip_enabled = true
    vars.kube_vip_controlplane_enabled = true
    vars.kube_vip_address = form.vip
    if (form.vipInterface.trim()) vars.kube_vip_interface = form.vipInterface.trim()
    vars.loadbalancer_apiserver = { address: form.vip, port: 6443 }
    if (form.apiEntry === 'kubevip-arp') {
      vars.kube_vip_arp_enabled = true
    } else {
      vars.kube_vip_bgp_enabled = true
      vars.kube_vip_local_as = Number(form.bgpAs)
      if (form.bgpRouterId.trim()) vars.kube_vip_bgp_routerid = form.bgpRouterId.trim()
      vars.kube_vip_bgppeers = form.bgpPeers
        .split('\n')
        .map((s) => s.trim())
        .filter(Boolean)
        .map((peer) => {
          const [ip, as] = peer.split(':')
          return `${ip}:${as}::false`
        })
    }
  } else if (form.apiEntry === 'external-lb') {
    vars.loadbalancer_apiserver = { address: form.lbAddress, port: form.lbPort }
    if (form.lbDomain.trim()) vars.apiserver_loadbalancer_domain_name = form.lbDomain.trim()
  }

  if (form.httpProxy.trim()) vars.http_proxy = form.httpProxy.trim()
  if (form.httpsProxy.trim()) vars.https_proxy = form.httpsProxy.trim()
  if (form.httpProxy.trim() || form.httpsProxy.trim() || form.noProxyExtra.trim()) {
    vars.no_proxy = noProxy(form)
  }

  if (form.sshAuth === 'password' && form.sshPassword) {
    vars.ansible_password = form.sshPassword
    // 密码登录时 sudo 也要口令（kubespray 用 become 提权）
    vars.ansible_become_password = form.sshPassword
  }
  if (form.jumpHost.trim()) {
    vars.ansible_ssh_common_args = `-o ProxyJump=${form.jumpHost.trim()}`
  }

  if (form.online && form.imageRegistry.trim()) {
    const repo = form.imageRegistry.trim()
    vars.kube_image_repo = repo
    vars.gcr_image_repo = repo
    vars.docker_image_repo = repo
    vars.quay_image_repo = repo
    vars.github_image_repo = repo
  }
  return vars
}

function etcdVars(form: InstallForm): KubesprayVars | null {
  if (form.etcdMode === 'stacked') return null
  if (form.etcdMode === 'dedicated') return { etcd_deployment_type: 'host' }
  return {
    // kubespray v2.32 的 etcd_deployment_type 只接受 host / docker / kubeadm（validate_inventory 会挡），
    // 「外部 etcd」靠三件事成立：etcd 组为空（不装 etcd）+ etcd_access_addresses 指向外部 + 自备证书
    etcd_deployment_type: 'host',
    etcd_access_addresses: form.externalEtcd.trim(),
    cert_management: 'none',
  }
}

/** 离线地址不让用户填：deploy-offline.sh 在离线控制机上探测本机 IP，替换掉这个占位符 */
const OFFLINE_HOST_TOKEN = '__CONTROL_HOST__'
const OFFLINE_FILES_PORT = 8080
const OFFLINE_REGISTRY_PORT = 5000

function offlineVars(): KubesprayVars {
  const repo = `http://${OFFLINE_HOST_TOKEN}:${OFFLINE_FILES_PORT}`
  const registry = `${OFFLINE_HOST_TOKEN}:${OFFLINE_REGISTRY_PORT}`
  return {
    // files_repo 自身在 v2.32 没有消费方，仅作为可读的根地址；真正生效的是四个 *_url 覆盖
    files_repo: repo,
    github_url: `${repo}/github.com`,
    dl_k8s_io_url: `${repo}/dl.k8s.io`,
    storage_googleapis_url: `${repo}/storage.googleapis.com`,
    get_helm_url: `${repo}/get.helm.sh`,
    registry_host: registry,
    kube_image_repo: registry,
    gcr_image_repo: registry,
    docker_image_repo: registry,
    quay_image_repo: registry,
    github_image_repo: registry,
    download_run_once: true,
    // 自己起的 registry 是 HTTP：containerd 要走 http 并跳过校验
    containerd_registries_mirrors: [
      {
        prefix: registry,
        mirrors: [{ host: `http://${registry}`, capabilities: ['pull', 'resolve'], skip_verify: true }],
      },
    ],
  }
}

/**
 * 全离线物料准备脚本（参考 D:\coding\k8s-install 的 prepare_offline.sh 做法）：
 * 离线环境完全不能上网，所以一次把 kubespray 源码、inventory、Python 依赖、静态文件、
 * 容器镜像、registry 本体镜像、节点系统依赖包全部收进一个 tar 包。
 */
function offlinePrepareScript(form: InstallForm): string {
  const name = form.clusterName
  const mirror = form.imageMirror.trim().replace(/\/+$/, '')
  const proxy = form.httpProxy.trim()
  const proxyTls = form.httpsProxy.trim()
  const noProxy = ['localhost', '127.0.0.1', ...form.noProxyExtra.split(',').map((s) => s.trim()).filter(Boolean)].join(',')
  const proxyBlock =
    proxy || proxyTls
      ? [
          '# 准备机要经代理才能访问 github.com / dl.k8s.io / 镜像仓库时填的（来自向导「介质」步）',
          proxy ? `export http_proxy="${proxy}"` : '',
          proxyTls ? `export https_proxy="${proxyTls}"` : '',
          `export no_proxy="${noProxy}"`,
        ]
          .filter(Boolean)
          .join('\n')
      : ''
  const families = new Set(form.nodes.map((n) => osFamily(n.os)))
  const rpmPart = families.has('rpm')
    ? `if command -v yum >/dev/null 2>&1; then
  mkdir -p "$WORKDIR/rpm"
  yum install --downloadonly --downloaddir="$WORKDIR/rpm" -y \\
    conntrack-tools socat ebtables ethtool ipset ipvsadm chrony nfs-utils \\
    curl rsync tar unzip xfsprogs device-mapper-libs libseccomp nss openssl
fi
`
    : ''
  const debPart = families.has('deb')
    ? `if command -v apt-get >/dev/null 2>&1; then
  mkdir -p "$WORKDIR/deb"
  apt-get update
  apt-get install --download-only -y \\
    conntrack socat ebtables ethtool ipset ipvsadm chrony nfs-common \\
    curl rsync tar unzip xfsprogs libseccomp2 gnupg
  cp /var/cache/apt/archives/*.deb "$WORKDIR/deb/" 2>/dev/null || true
fi
`
    : ''
  return `#!/usr/bin/env bash
# ${name} —— 全离线物料准备（kubespray ${KUBESPRAY_VERSION}）
# 在**能上网**的机器上执行一次，产出 ${name}-offline.tar.gz；带到离线环境后按 README 第 9 节部署。
# 离线包里含：kubespray 源码 / 本 inventory / Python 依赖(wheel) / 静态文件 / 容器镜像 / registry 镜像 / 系统依赖包
set -euo pipefail

CLUSTER_NAME="${name}"
KUBESPRAY_VERSION="${KUBESPRAY_VERSION}"
# 镜像站前缀：只决定准备机从哪拉镜像，拉完会打回原名打进离线包（留空 = 官方源）
IMAGE_MIRROR="${mirror}"
PKG_DIR="$(cd "$(dirname "$0")" && pwd)"
WORKDIR="$(cd "$PKG_DIR/.." && pwd)/kubespray-offline"
${proxyBlock}

need() { command -v "$1" >/dev/null 2>&1 || { echo "缺少命令：$1"; exit 1; }; }
need git
need python3
need tar
CONTAINER=""
for c in docker podman nerdctl; do
  if command -v "$c" >/dev/null 2>&1; then CONTAINER="$c"; break; fi
done
if [ -z "$CONTAINER" ]; then echo "需要 docker / podman / nerdctl 之一来打包镜像"; exit 1; fi

rm -rf "$WORKDIR"
mkdir -p "$WORKDIR"
cd "$WORKDIR"

echo "==> [1/7] 拉取 kubespray $KUBESPRAY_VERSION"
git clone --depth 1 --branch ${KUBESPRAY_VERSION} https://github.com/kubernetes-sigs/kubespray.git
cd kubespray

echo "==> [2/7] 并入本包的 inventory"
mkdir -p "inventory/$CLUSTER_NAME"
cp -r "$PKG_DIR"/. "inventory/$CLUSTER_NAME/"

echo "==> [3/7] 控制机的 Python 依赖（存 wheel 包，离线机上用 --no-index 安装）"
python3 -m venv venv
. venv/bin/activate
python3 -m pip install -U pip
python3 -m pip download -r requirements.txt -d "$WORKDIR/pip"
python3 -m pip install -r requirements.txt

echo "==> [4/7] 生成下载清单（必须用去掉 offline.yml 的副本：否则清单里全是离线占位地址）"
ONLINE_INV="inventory/\${CLUSTER_NAME}-online"
rm -rf "$ONLINE_INV"
cp -r "inventory/$CLUSTER_NAME" "$ONLINE_INV"
rm -f "$ONLINE_INV/group_vars/all/offline.yml"
./contrib/offline/generate_list.sh -i "$ONLINE_INV/hosts.yaml"
rm -rf "$ONLINE_INV"

echo "==> 下载静态文件（NO_HTTP_SERVER：只下载，不起服务）"
(cd contrib/offline && NO_HTTP_SERVER=true sh ./manage-offline-files.sh)

echo "==> [5/7] 打包容器镜像（镜像站：\${IMAGE_MIRROR:-官方源}）"
# 不用 manage-offline-container-images.sh create：它按清单里的原名拉，没有镜像站这一层。
# 这里按 IMAGE_MIRROR 换成镜像站地址拉，再 tag 回原名，产出的 container-images.txt /
# container-images.tar.gz 与官方脚本格式一致，离线侧 register 照常认。
cd contrib/offline
rm -rf container-images container-images.tar.gz
mkdir -p container-images
: > container-images/container-images.txt
while read -r image; do
  [ -n "$image" ] || continue
  src="$image"
  if [ -n "$IMAGE_MIRROR" ]; then
    case "$image" in
      docker.io/*) src="$IMAGE_MIRROR/\${image#docker.io/}" ;;
      *) src="$IMAGE_MIRROR/$image" ;;
    esac
  fi
  ok=""
  for _ in 1 2 3 4 5; do
    if $CONTAINER pull "$src"; then ok=1; break; fi
    echo "拉取失败，重试：$src"
    sleep 5
  done
  if [ -z "$ok" ]; then echo "拉取失败：$src"; exit 1; fi
  # 打回原名：离线侧 register 会按公共 registry 前缀剥离后推本地仓库，名字必须与 kubespray 模板一致
  if [ "$src" != "$image" ]; then $CONTAINER tag "$src" "$image"; fi
  flat="$(echo "$image" | tr '/:' '--')"
  flat="\${flat%%@*}"
  $CONTAINER save -o "container-images/$flat.tar" "$image"
  raw="$image"
  case "$image" in
    registry.k8s.io/*|gcr.io/*|ghcr.io/*|docker.io/*|quay.io/*) raw="\${image#*/}" ;;
  esac
  echo "$flat.tar  $raw" >> container-images/container-images.txt
done < temp/images.list
tar -czf container-images.tar.gz ./container-images
rm -rf container-images
cd "$WORKDIR/kubespray"

echo "==> [6/7] 带上 registry:2 镜像（离线机上用它起私有仓库）"
REGISTRY_SRC="registry:2"
if [ -n "$IMAGE_MIRROR" ]; then REGISTRY_SRC="$IMAGE_MIRROR/library/registry:2"; fi
$CONTAINER pull "$REGISTRY_SRC"
if [ "$REGISTRY_SRC" != "registry:2" ]; then $CONTAINER tag "$REGISTRY_SRC" registry:2; fi
$CONTAINER save -o "$WORKDIR/registry.tar" registry:2

echo "==> [7/7] 系统依赖包（节点装 conntrack / socat / ipvsadm 等）"
# 这一段要在**与节点同发行版**的机器上执行；准备机不是该发行版就跳过，稍后到同发行版机器补跑，
# 再把生成的 rpm/ 或 deb/ 目录放回 $WORKDIR 一起打包。
${rpmPart}${debPart}
echo "==> 打包"
cd "$WORKDIR"
FILES="kubespray pip registry.tar"
if [ -d rpm ]; then FILES="$FILES rpm"; fi
if [ -d deb ]; then FILES="$FILES deb"; fi
tar czf "$WORKDIR/../$CLUSTER_NAME-offline.tar.gz" $FILES

cat <<EOF2
完成：$WORKDIR/../$CLUSTER_NAME-offline.tar.gz
离线环境按 README 第 9 节：
  1) 解压到控制机
  2) 执行 inventory/$CLUSTER_NAME/deploy-offline.sh（起 registry、灌镜像、起文件服务、装依赖、跑 playbook）
EOF2
`
}

/**
 * 离线环境的一键部署脚本：README 第 9 节的手工步骤全在这里。
 * 地址不写死——控制机 IP 运行时探测，用来替换 offline.yml 里的 __CONTROL_HOST__。
 */
function deployOfflineScript(form: InstallForm): string {
  const name = form.clusterName
  return `#!/usr/bin/env bash
# ${name} —— 离线环境一键部署（在离线环境的控制机上执行）
# 做的事：探测控制机 IP → 写进 inventory → 起本地 registry 并灌镜像 → 起静态文件服务
#        → 用离线 wheel 装 ansible → 给所有节点装系统依赖 → 跑 kubespray
set -euo pipefail

CLUSTER_NAME="${name}"
FILES_PORT=${OFFLINE_FILES_PORT}
REGISTRY_PORT=${OFFLINE_REGISTRY_PORT}
INV="$(cd "$(dirname "$0")" && pwd)"
KUBESPRAY_DIR="$(cd "$INV/../.." && pwd)"
BUNDLE_DIR="$(cd "$KUBESPRAY_DIR/.." && pwd)"
cd "$KUBESPRAY_DIR"

# 1) 控制机 IP：节点必须能访问到它
HOST_IP="$(ip route get 1.1.1.1 2>/dev/null | awk '{print $7; exit}' || true)"
if [ -z "$HOST_IP" ]; then HOST_IP="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"; fi
if [ -z "$HOST_IP" ]; then echo "取不到本机 IP，请用 HOST_IP=<控制机IP> 重新执行"; exit 1; fi
echo "==> 控制机 IP：$HOST_IP"

# 2) 把地址写进 inventory（offline.yml 里是 ${OFFLINE_HOST_TOKEN} 占位符）
sed -i "s/${OFFLINE_HOST_TOKEN}/$HOST_IP/g" "$INV/group_vars/all/offline.yml"

# 3) 本地 registry：加载 registry:2、启动、灌入容器镜像
RUNTIME=""
for c in docker podman; do
  if command -v "$c" >/dev/null 2>&1; then RUNTIME="$c"; break; fi
done
if [ -z "$RUNTIME" ]; then echo "需要 docker 或 podman"; exit 1; fi
if ! $RUNTIME inspect registry >/dev/null 2>&1; then
  $RUNTIME load -i "$BUNDLE_DIR/registry.tar"
  $RUNTIME run -d --restart=always -p "$REGISTRY_PORT:$REGISTRY_PORT" --name registry registry:2
else
  $RUNTIME start registry >/dev/null 2>&1 || true
fi
(cd contrib/offline && DESTINATION_REGISTRY="$HOST_IP:$REGISTRY_PORT" sh ./manage-offline-container-images.sh register)

# 4) 静态文件服务（后台；日志 $BUNDLE_DIR/files-server.log）
if ! (command -v pgrep >/dev/null 2>&1 && pgrep -f "http.server $FILES_PORT" >/dev/null 2>&1); then
  cd contrib/offline/offline-files
  nohup python3 -m http.server "$FILES_PORT" > "$BUNDLE_DIR/files-server.log" 2>&1 &
  cd "$KUBESPRAY_DIR"
  sleep 1
fi

# 5) 控制机的 ansible：从离线 wheel 装，不联网
if [ ! -x "$KUBESPRAY_DIR/venv/bin/python" ]; then python3 -m venv "$KUBESPRAY_DIR/venv"; fi
. "$KUBESPRAY_DIR/venv/bin/activate"
python3 -m pip install --no-index --find-links "$BUNDLE_DIR/pip" -r requirements.txt

# 6) 节点系统依赖（用 inventory 里的主机与凭据分发安装）
for dir in "$BUNDLE_DIR/rpm" "$BUNDLE_DIR/deb"; do
  [ -d "$dir" ] || continue
  ansible all -i "$INV/hosts.yaml" -b -m copy -a "src=$dir/ dest=/tmp/kubespray-pkgs/"
  if [ "$(basename "$dir")" = "rpm" ]; then
    ansible all -i "$INV/hosts.yaml" -b -m shell -a "yum install -y /tmp/kubespray-pkgs/*.rpm || rpm -Uvh --replacepkgs /tmp/kubespray-pkgs/*.rpm"
  else
    ansible all -i "$INV/hosts.yaml" -b -m shell -a "dpkg -i /tmp/kubespray-pkgs/*.deb || apt-get install -f -y"
  fi
done

# 7) 安装集群
ansible-playbook -i "$INV/hosts.yaml" -b cluster.yml
`
}

/** README 用：SSH 认证 + 跳转机一句话 */
function sshSummary(form: InstallForm): string {
  const auth =
    form.sshAuth === 'password'
      ? '密码认证（控制机需装 sshpass）'
      : '密钥认证（节点已预置部署机公钥）'
  const jump = form.jumpHost.trim()
  return jump ? `${auth}；经跳转机 ${jump}` : `${auth}；直连`
}

/** README 用：入口方式一句话 */
function entrySummary(form: InstallForm): string {
  switch (form.apiEntry) {
    case 'kubevip-arp':
      return `kube-vip 以 ARP 宣告 VIP \`${form.vip || '（未填）'}\`，所有控制面共用它访问 apiserver`
    case 'kubevip-bgp':
      return `kube-vip 以 BGP 宣告 VIP \`${form.vip || '（未填）'}\`，本地 AS \`${form.bgpAs || '（未填）'}\``
    case 'external-lb':
      return `外部 LB \`${form.lbAddress || '（未填）'}:${form.lbPort}\` 转发到控制面 6443`
    default:
      return '单 master 直连（没有 VIP，kubeconfig 里直接是第一台控制面的地址）'
  }
}

/** README 用：CNI 一句话 */
function cniSummary(form: InstallForm): string {
  return form.cni === 'platform'
    ? '不装具体 CNI（`kube_network_plugin: cni`），装完到平台「组件」页安装 Calico'
    : `由 kubespray 安装 \`${form.cni}\``
}

/** README 用：按 CNI 列出额外端口（对照 kubespray v2.32.0 的 docs/operations/port-requirements.md） */
function cniPorts(cni: InstallForm['cni']): string {
  switch (cni) {
    case 'calico':
      return '- Calico：`179/tcp` BGP、`4789/udp` VXLAN、`5473/tcp` Typha、`51820-51821/udp` WireGuard、IPIP（启用时）'
    case 'cilium':
      return '- Cilium：`8472/udp` VXLAN、`4240/tcp` health、`51871/udp` WireGuard、`9962-9964/tcp` metrics'
    case 'flannel':
      return '- flannel：`8472/udp` VXLAN'
    default:
      return '- 平台管理的 CNI：先按 Calico 准备（`179/tcp`、`4789/udp`、`5473/tcp`），之后装 Calico 时用得上'
  }
}

function readme(form: InstallForm): string {
  const dir = `inventory/${form.clusterName}`
  const cps = form.nodes.filter((n) => n.roles.includes('control-plane'))
  const someNode = form.nodes[0]
  const jump = form.jumpHost.trim()
  const sshProbe = someNode
    ? `ssh${jump ? ` -J ${jump}` : ''} -p ${someNode.sshPort} ${someNode.sshUser || 'root'}@${someNode.ip || '<节点IP>'} hostname`
    : 'ssh <user>@<节点IP> hostname'
  const sshUsers = [...new Set(form.nodes.map((n) => (n.sshUser || 'root').trim()))].join('、')
  const cni = cniSummary(form)

  const lines: string[] = [
    `# ${form.clusterName} 集群安装包`,
    '',
    `一份 kubespray ${KUBESPRAY_VERSION} 的 inventory：${form.nodes.length} 台机器、Kubernetes ${form.kubeVersion}、`,
    `${form.runtime}。按下面的顺序做就能装起来，不需要事先了解 kubespray。`,
    '',
    `- kubespray：${KUBESPRAY_VERSION}`,
    `- Kubernetes：${form.kubeVersion}`,
    `- 镜像架构：${form.arch === 'auto' ? '自动（kubespray 按主机探测）' : form.arch}`,
    `- 操作系统：${osSummary(form.nodes)}`,
    `- 容器运行时：${form.runtime}`,
    `- SSH：${sshSummary(form)}`,
    `- 控制面：${cps.length} 台`,
    `- 部署模式：${form.online ? '在线（节点直接拉官方源 / 镜像源）' : '离线（全离线包，见第 9 节）'}`,
    '',
    ...(form.online
      ? []
      : [
          '> **这是全离线部署包**：节点完全不能上网，kubespray 源码、Python 依赖、镜像与 registry、静态文件、',
          '> 系统依赖都随离线包提供。完整流程见第 9 节：先在能上网的机器生成离线包，',
          '> 再到离线环境起本地 registry 与文件服务器安装。',
          '',
        ]),
    '## 1. 包里有什么',
    '',
    '- `hosts.yaml`：机器清单——hostname、IP、SSH 端口/用户，以及 control-plane / worker / etcd 分组',
    '- `group_vars/all/all.yml`：API 入口/VIP、镜像源、代理、SSH 认证方式（这些都没有时文件不存在）',
    '- `group_vars/all/etcd.yml`：etcd 模式（仅「专用 etcd / 外部 etcd」时存在）',
    '- `group_vars/k8s_cluster/k8s-cluster.yml`：K8s 版本、容器运行时、CNI、Pod/Service 网段、kube-proxy 模式',
  ]
  if (!form.online) {
    lines.push(
      '- `group_vars/all/offline.yml`：离线物料地址（四个 `*_url` 与内网 registry）',
      '- `offline-prepare.sh`：离线物料准备脚本（在能上网的机器上跑，见第 9 节）',
      '- `deploy-offline.sh`：离线环境一键部署脚本（在离线控制机上跑，见第 9 节）',
    )
  }
  lines.push(
    '- `README.md`：本文件',
    '',
    '## 2. 控制机准备',
    '',
    '一台 Linux（推荐）或 macOS 机器，能 SSH 到所有节点即可。',
    '',
    '1. 装 Python 3.11~3.13、检出 kubespray、装它的 Python 依赖（建议用 venv 隔离）：',
    '',
    '```bash',
    `git clone --depth 1 --branch ${KUBESPRAY_VERSION} https://github.com/kubernetes-sigs/kubespray.git <kubespray>`,
    'cd <kubespray>',
    'python3 -m venv .venv && . .venv/bin/activate',
    'python3 -m pip install -U pip',
    'python3 -m pip install -r requirements.txt',
    '```',
    '',
    '`requirements.txt` 里钉的是 ansible 12.3.0 等依赖，**别用系统里更老的 ansible**——版本不匹配时报的错很难懂。',
    '装完用 `ansible --version` 确认。',
    '',
    '2. 节点若是**密码登录**，控制机还要装 `sshpass`（`apt install sshpass` / `yum install sshpass`）。',
    '',
    '3. 先确认能免交互连上节点（任选一台试）：',
    '',
    '```bash',
    sshProbe,
    '```',
    '',
    '## 3. 节点准备',
    '',
    `本包登记了 ${form.nodes.length} 台。其中控制面 ${cps.length} 台、带 worker 角色的 ${form.nodes.filter((n) => n.roles.includes('worker')).length} 台（一台机器可兼任，两者相加可能大于总数）。`,
    '',
    `- **操作系统**：${osSummary(form.nodes)}（kubespray ${KUBESPRAY_VERSION} 支持 Ubuntu 22.04/24.04、Debian 12/13、Rocky/Alma/CentOS Stream 9/10）`,
    `- **SSH 用户**：${sshUsers}；需要 sudo 权限（root，或 NOPASSWD 的 sudo）`,
    '- **规格**：kubeadm 硬性要求控制面至少 2 核；控制面建议 4C/8G 起，worker 按负载来',
    '- **时间同步**：节点时间必须准（证书对时间敏感），确认 chrony / systemd-timesyncd 在跑',
    '- **swap**：不用手动关，kubespray 会处理',
    '- **防火墙**：若节点开了 firewalld / ufw，请放行下列端口，或临时关掉',
    '',
    '需要打通的端口（对照 kubespray v2.32 的端口清单）：',
    '',
    '- `22/tcp` SSH（控制机 → 所有节点）',
    '- `6443/tcp` kube-apiserver（VIP / LB 也是这个端口）',
    '- `2379/tcp`、`2380/tcp` etcd 客户端端口 / 集群内部端口',
    '- `10250/tcp` kubelet；`10257/tcp`、`10259/tcp` scheduler / controller-manager',
    '- `30000-32767/tcp` NodePort（对外暴露服务用）',
    cniPorts(form.cni),
    '',
    '## 4. 放入 inventory',
    '',
    '`<kubespray>` 指 kubespray 仓库的检出目录（例如 `/opt/kubespray`），`inventory/`、`cluster.yml` 都在它下面。还没有的话先拉一份：',
    '',
    '```bash',
    `git clone --depth 1 --branch ${KUBESPRAY_VERSION} https://github.com/kubernetes-sigs/kubespray.git <kubespray>`,
    '```',
    '',
    `**整包下载**：「打包下载 .zip」得到的 \`${form.clusterName}.zip\` 解压后就是 \`${dir}/\` 这一层，`,
    `所以解到 kubespray 检出的**根目录**即可（结果是 \`<kubespray>/inventory/${form.clusterName}/\`）：`,
    '',
    '```bash',
    `unzip ${form.clusterName}.zip -d <kubespray>`,
    '```',
    '',
    '没有 `unzip` 就用系统的解压工具解到 `<kubespray>` 下，效果一样。',
    '',
    `**逐个下载文件**：按面板里的完整路径建目录（\`${dir}/...\`）再放入文件。`,
    '',
    '## 5. 安装前确认几处',
    '',
    '- `hosts.yaml`：机器名、IP、SSH 端口/用户是否与现网一致',
    `- API 入口：${entrySummary(form)}`,
    `- CNI：${cni}；Pod 网段 \`${form.podCidr}\`，Service 网段 \`${form.serviceCidr}\``,
    `- kube-proxy：\`${form.kubeProxyMode}\`；集群域名：\`${form.clusterDomain}\`；容器运行时：\`${form.runtime}\``,
    form.online
      ? `- 镜像源：${form.imageRegistry.trim() ? `\`${form.imageRegistry.trim()}\`` : '官方源（没有配镜像加速）'}`
      : '- 离线：地址不用填，`deploy-offline.sh` 会按控制机 IP 写进 `offline.yml`（文件服务 :8080、registry :5000）',
    '- 想装完自动把 kubeconfig / kubectl 拉到控制机：在 `group_vars/k8s_cluster/k8s-cluster.yml` 末尾加',
    `  \`kubeconfig_localhost: true\` 与 \`kubectl_localhost: true\`，产物在 \`${dir}/artifacts/\`（\`admin.conf\`、\`kubectl.sh\`）`,
    '',
    '## 6. 执行安装',
    '',
    '```bash',
    'cd <kubespray>',
    `ansible-playbook -i ${dir}/hosts.yaml -b cluster.yml`,
    '```',
    '',
    '- 首次一般 20~40 分钟，取决于网络和机器性能',
    '- 中途失败：修好再跑同一条命令即可，kubespray 是幂等的，不会重复安装已完成的部分',
    '- 只重跑某几台：加 `--limit cp-1,worker-2`',
    '- 需要详细日志：加 `-vvv`，或整条命令末尾接 `| tee install.log`',
    '- 如果卡在 `UNREACHABLE`，先回到第 2 节第 3 步确认 SSH 通',
    '',
    '## 7. 验证',
    '',
    '从控制机连到第一台控制面执行：',
    '',
    '```bash',
    sshProbe.replace(/\bhostname$/, "'kubectl get nodes -o wide'"),
    '```',
    '',
    '- 所有节点都是 `Ready`，`kubectl get pods -A` 里系统组件全部 Running',
    `- kubeconfig：第一台控制面的 \`/etc/kubernetes/admin.conf\`；若上面开了 \`kubeconfig_localhost\`，控制机上也有 \`${dir}/artifacts/admin.conf\``,
    '',
    '## 8. 接入平台',
    '',
    '- 在平台的「集群管理 → 接入集群」导入上面的 kubeconfig。',
    `- 若 CNI 选了「平台管理」：到组件页安装 Calico 时，**Pod CIDR 必须填 \`${form.podCidr}\`**，与该集群的 Pod 网段一字不差；写错会立即断网。`,
    '- 其它集群侧前提（Tekton 开关、配额、存储、网格指标、告警）见 `docs/development/cluster-prerequisites.md`。',
  )

  if (!form.online) {
    lines.push(
      '',
      '## 9. 离线模式（全离线）',
      '',
      '节点完全不能上网，所有物料随一个离线包带进去。`offline-prepare.sh` 产出的包里有：',
      '',
      `- kubespray 源码（${KUBESPRAY_VERSION}）与本包的 inventory`,
      '- 控制机用的 Python 依赖（wheel 包，离线机上用 `pip install --no-index` 安装）',
      '- 静态文件：kubectl / kubelet / kubeadm / etcd / CNI 插件 / helm 等（`offline-files/`）',
      '- 容器镜像（`container-images.tar.gz`）与 registry 本体镜像（`registry.tar`）',
      '- 节点系统依赖包（rpm / deb）',
      '',
      '### 9.1 在能上网的机器上生成离线包',
      '',
      '需要 Python 3.11~3.13、git、tar，以及 docker / podman / nerdctl 任一个（打包镜像用）：',
      '',
      '```bash',
      'chmod +x offline-prepare.sh',
      './offline-prepare.sh',
      '```',
      '',
      '准备机连不上 `registry.k8s.io` / `docker.io` / `quay.io` / `gcr.io` 时，回到向导「介质」步填下载源，脚本会带上：',
      '',
      '- **镜像**：填「镜像下载源」镜像站前缀（例如 `docker.m.daocloud.io`、内网 Harbor 的代理项目地址）。',
      '  脚本按 `<镜像源>/<原镜像名>` 拉取（`docker.io/` 前缀会省掉），拉回来再打回原名打进包，',
      '  **离线侧仍然只认本地 registry**，不受镜像源影响。留空则按官方地址拉。',
      '- **静态文件 / pip**：填 `http_proxy` / `https_proxy`，脚本会 export 给 wget 与 pip；',
      '  `no_proxy 追加` 里的主机也会一并带上。',
      '',
      '产出 `<集群名>-offline.tar.gz`。其中系统依赖包那一步要在**与节点同发行版**的机器上跑（脚本按 yum / apt 自动判断）；',
      '准备机发行版不一致时，把脚本第 7 步那两条命令拷到同发行版机器执行，再把 `rpm/`、`deb/` 放回工作目录重新打包。',
      '',
      '### 9.2 带到离线环境',
      '',
      '```bash',
      'tar xzf <集群名>-offline.tar.gz',
      'cd kubespray-offline',
      '```',
      '',
      '### 9.3 一键部署',
      '',
      '在离线环境的控制机上执行（脚本与本 inventory 同目录）：',
      '',
      '```bash',
      'bash inventory/<集群名>/deploy-offline.sh',
      '```',
      '',
      '脚本会依次：探测控制机 IP → 写进 `group_vars/all/offline.yml`（文件服务 :8080、registry :5000）→',
      '加载 `registry.tar` 起本地 registry 并灌入镜像 → 后台起静态文件服务 → 用离线 wheel 装 ansible →',
      '给所有节点分发并安装 rpm/deb → 跑 `ansible-playbook cluster.yml`。',
      '',
      '### 9.4 等价的手工步骤（排错时用）',
      '',
      '```bash',
      'HOST_IP=<控制机IP>',
      'sed -i "s/__CONTROL_HOST__/$HOST_IP/g" inventory/<集群名>/group_vars/all/offline.yml',
      'docker load -i registry.tar',
      'docker run -d --restart=always -p 5000:5000 --name registry registry:2',
      '(cd contrib/offline && DESTINATION_REGISTRY="$HOST_IP:5000" sh ./manage-offline-container-images.sh register)',
      '(cd contrib/offline/offline-files && python3 -m http.server 8080)',
      '```',
      '',
      '用 kubespray 自带的 `manage-offline-files.sh` 起 nginx 时，文件挂在 `/download` 下，',
      '`files_repo` 要相应改成 `http://<控制机IP>:8080/download`。',
      '',
      '节点系统依赖与安装命令见第 6 节。',
    )
  }

  // 有没有第 9 节（离线）决定后面的编号，避免 8 → 10 跳号
  const section = form.online ? 9 : 10
  lines.push(
    '',
    `## ${section}. 日常操作`,
    '',
    '- **加节点**：把节点写进 `hosts.yaml` 对应分组，跑 `scale.yml`（或完整重跑 `cluster.yml`）',
    '- **删节点**：`remove-node.yml --extra-vars "node=<节点名>"`；它不支持删除第一台控制面 / etcd 节点',
    `- **升级 K8s**：改 \`group_vars/k8s_cluster/k8s-cluster.yml\` 里的 \`kube_version\`，把命令里的 \`cluster.yml\` 换成 \`upgrade-cluster.yml\``,
    `- **清空重装**：\`ansible-playbook -i ${dir}/hosts.yaml -b reset.yml\`（会卸掉节点上的 K8s 组件与数据目录，慎用）`,
  )
  // 注意事项只在确实有话说时出现，避免留下一个空标题
  const hasNotes =
    form.sshAuth === 'password' ||
    Boolean(jump) ||
    Boolean(form.httpProxy.trim() || form.httpsProxy.trim()) ||
    form.arch !== 'auto' ||
    form.etcdMode === 'external'
  if (hasNotes) lines.push('', `## ${section + 1}. 注意事项`)

  if (form.sshAuth === 'password') {
    lines.push(
      '',
      '> ⚠️ 本包含**明文密码**（`group_vars/all/all.yml` 里的 `ansible_password` / `ansible_become_password`）：',
      '> 不要提交到 git；拷到控制机后建议 `chmod -R go-rwx inventory/` 收紧权限，并确认已安装 `sshpass`。',
    )
  }
  if (jump) {
    lines.push(
      '',
      '> 跳转机只写进 `ansible_ssh_common_args`（ProxyJump）：跳转机自身的认证走控制机的 `ssh` 配置',
      '> （密钥 / ssh-agent / `~/.ssh/config`），本包不含跳转机的口令。',
    )
  }
  if (form.httpProxy.trim() || form.httpsProxy.trim()) {
    lines.push(
      '',
      '> 代理写在 `group_vars/all/all.yml`（`http_proxy` / `https_proxy` / `no_proxy`）：节点拉镜像、下载二进制都会走它，',
      '> 已自动把 Pod/Service/节点网段加进 `no_proxy`。',
    )
  }
  if (form.arch !== 'auto') {
    lines.push(
      '',
      `> 镜像架构写死为 \`${form.arch}\`（\`image_arch\`）：集群里若有不同架构的节点，请改成「自动」或分集群管理。`,
    )
  }
  if (form.etcdMode === 'external') {
    lines.push(
      '',
      '### 外部 etcd：自备证书',
      '',
      '本包的 `etcd` 组是空的（kubespray 不会在节点上装 etcd），kubeadm 直接连 `etcd_access_addresses`。',
      '但 kubeadm 用的是 `etcd_cert_dir`（默认 `/etc/ssl/etcd/ssl/`）里的证书，且本包已设 `cert_management: none`，',
      'kubespray **不会**替你生成。跑 playbook 前请在**每台控制面**放好：',
      '',
      '```',
      '/etc/ssl/etcd/ssl/ca.pem                    # 外部 etcd 的 CA',
      '/etc/ssl/etcd/ssl/node-<hostname>.pem       # 客户端证书（hostname 用 inventory 里的名字）',
      '/etc/ssl/etcd/ssl/node-<hostname>-key.pem',
      '```',
      '',
      '否则「把 etcd CA 加进系统信任库」那一步会因为找不到 `ca.pem` 直接失败。',
    )
  }
  lines.push(
    '',
    '---',
    '',
    '更多细节见 kubespray 仓库的 `docs/`：`getting_started/`（首次安装）、`ansible/`（inventory 与变量优先级）、',
    '`operations/`（端口、HA、离线环境、升级）。遇到报错先在仓库 issues 里搜关键字。',
  )
  return lines.join('\n') + '\n'
}

export function generate(form: InstallForm): InstallFile[] {
  const root = `inventory/${form.clusterName}`
  const entry = entryVars(form)
  const files: InstallFile[] = [
    { path: `${root}/hosts.yaml`, content: inventoryDoc(form) },
    { path: `${root}/group_vars/k8s_cluster/k8s-cluster.yml`, content: yamlDoc(clusterVars(form)) },
  ]
  // 空对象不落文件：单 master 直连、无代理时确实没有任何 all.yml 变量
  if (Object.keys(entry).length > 0) {
    files.push({ path: `${root}/group_vars/all/all.yml`, content: yamlDoc(entry) })
  }

  const etcd = etcdVars(form)
  if (etcd) files.push({ path: `${root}/group_vars/all/etcd.yml`, content: yamlDoc(etcd) })
  if (!form.online) {
    files.push({ path: `${root}/group_vars/all/offline.yml`, content: yamlDoc(offlineVars()) })
    files.push({ path: `${root}/offline-prepare.sh`, content: offlinePrepareScript(form) })
    files.push({ path: `${root}/deploy-offline.sh`, content: deployOfflineScript(form) })
  }
  files.push({ path: `${root}/README.md`, content: readme(form) })
  return files
}
