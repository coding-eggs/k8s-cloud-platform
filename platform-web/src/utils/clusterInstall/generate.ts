/**
 * 把安装表单变成一组可直接拷进 kubespray v2.32.0 checkout 的文件。
 * 纯函数：不读环境、不发请求。
 */
import type { InstallFile, InstallForm, InstallNode } from './types'
import { KUBESPRAY_VERSION, osLabel, type KubesprayVars } from './kubespray.ts'
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
  if (etcd.length > 0) group('etcd', etcd)
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
    etcd_deployment_type: 'host',
    etcd_access_addresses: form.externalEtcd.trim(),
  }
}

function offlineVars(form: InstallForm): KubesprayVars {
  const repo = form.filesRepo.trim().replace(/\/+$/, '')
  return {
    // files_repo 自身在 v2.32 没有消费方，仅作为可读的根地址；真正生效的是四个 *_url 覆盖
    files_repo: repo,
    github_url: `${repo}/github.com`,
    dl_k8s_io_url: `${repo}/dl.k8s.io`,
    storage_googleapis_url: `${repo}/storage.googleapis.com`,
    get_helm_url: `${repo}/get.helm.sh`,
    registry_host: form.registryHost,
    kube_image_repo: form.registryHost,
    gcr_image_repo: form.registryHost,
    docker_image_repo: form.registryHost,
    quay_image_repo: form.registryHost,
    github_image_repo: form.registryHost,
    download_run_once: true,
  }
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

function readme(form: InstallForm): string {
  const dir = `inventory/${form.clusterName}`
  const lines = [
    `# ${form.clusterName} 集群安装包`,
    '',
    `- kubespray：${KUBESPRAY_VERSION}`,
    `- Kubernetes：${form.kubeVersion}`,
    `- 镜像架构：${form.arch === 'auto' ? '自动（kubespray 按主机探测）' : form.arch}`,
    `- 操作系统：${osSummary(form.nodes)}`,
    `- 容器运行时：${form.runtime}`,
    `- SSH：${sshSummary(form)}`,
    `- 控制面：${form.nodes.filter((n) => n.roles.includes('control-plane')).length} 台`,
    '',
    '## 1. 准备',
    '',
    '把本目录拷进 kubespray 检出（从本目录所在位置执行）：',
    '',
    '```bash',
    `cp -r ${form.clusterName} <kubespray>/inventory/`,
    '```',
    '',
    `逐个下载时按面板里的完整路径建目录（\`${dir}/...\`）再放入文件。`,
    '',
    '## 2. 执行',
    '',
    '```bash',
    'cd <kubespray>',
    `ansible-playbook -i ${dir}/hosts.yaml cluster.yml`,
    '```',
    '',
    form.online
      ? '在线模式：节点按 `group_vars` 里的镜像源拉取镜像与二进制。'
      : '离线模式：先把 `group_vars/all/offline.yml` 里的 `files_repo`（按 `<host>/...` 布局镜像 github.com / dl.k8s.io / storage.googleapis.com / get.helm.sh）与 `registry_host` 准备好。OS 包仓库（kubespray 的 `yum_repo` / `debian_repo` / `ubuntu_repo`）需要另行配置，本向导不生成。',
    '',
    '## 3. 纳管后',
    '',
    `- 在平台的「集群管理 → 接入集群」导入 kubeconfig。`,
    `- 若 CNI 选了「平台管理」：到组件页安装 Calico 时，**Pod CIDR 必须填 \`${form.podCidr}\`**，与该集群的 Pod 网段一字不差；写错会立即断网。`,
    '- 其它集群侧前提（Tekton 开关、配额、存储、网格指标、告警）见 `docs/development/cluster-prerequisites.md`。',
  ]
  if (form.sshAuth === 'password') {
    lines.push(
      '',
      '> ⚠️ 本包含**明文密码**（`group_vars/all/all.yml` 里的 `ansible_password` / `ansible_become_password`）：',
      '> 不要提交到 git；拷到控制机后建议 `chmod -R go-rwx inventory/` 收紧权限，并确认已安装 `sshpass`。',
    )
  }
  if (form.jumpHost.trim()) {
    lines.push(
      '',
      '> 跳转机只写进 `ansible_ssh_common_args`（ProxyJump）：跳转机自身的认证走控制机的 `ssh` 配置',
      '> （密钥 / ssh-agent / `~/.ssh/config`），向导不生成跳转机的口令。',
    )
  }
  if (form.etcdMode === 'external') {
    lines.push(
      '',
      '> ⚠️ 外部 etcd 为实验特性：kubespray 目前没有干净的「跳过 etcd 安装」开关，装之前请确认它不会在控制面上再装一套 etcd。',
    )
  }
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
    files.push({ path: `${root}/group_vars/all/offline.yml`, content: yamlDoc(offlineVars(form)) })
  }
  files.push({ path: `${root}/README.md`, content: readme(form) })
  return files
}
