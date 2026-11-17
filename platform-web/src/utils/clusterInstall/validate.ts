/** 新建集群表单的硬校验。只产出问题列表，不做 UI 决策。 */
import type { InstallForm, InstallNode, Issue, NodeRole } from './types'

function isIpv4(ip: string): boolean {
  const parts = ip.split('.')
  if (parts.length !== 4) return false
  return parts.every((p) => /^\d{1,3}$/.test(p) && Number(p) <= 255 && String(Number(p)) === p)
}

function ipv4ToInt(ip: string): number | null {
  if (!isIpv4(ip)) return null
  return ip.split('.').reduce((acc, p) => ((acc << 8) | Number(p)) >>> 0, 0) >>> 0
}

function cidrRange(cidr: string): { start: number; end: number } | null {
  const [ip, bitsRaw] = cidr.trim().split('/')
  if (!ip || bitsRaw === undefined) return null
  const bits = Number(bitsRaw)
  if (!Number.isInteger(bits) || bits < 0 || bits > 32) return null
  const base = ipv4ToInt(ip)
  if (base === null) return null
  const size = 2 ** (32 - bits)
  const start = base - (base % size)
  return { start, end: start + size - 1 }
}

function cidrValid(cidr: string): boolean {
  return cidrRange(cidr) !== null
}

function cidrOverlap(a: string, b: string): boolean {
  const ra = cidrRange(a)
  const rb = cidrRange(b)
  if (!ra || !rb) return false
  return ra.start <= rb.end && rb.start <= ra.end
}

function hasRole(n: InstallNode, role: NodeRole): boolean {
  return n.roles.includes(role)
}

export function validate(form: InstallForm): Issue[] {
  const issues: Issue[] = []
  const err = (field: string, message: string) =>
    issues.push({ field, level: 'error', message })
  const warn = (field: string, message: string) =>
    issues.push({ field, level: 'warning', message })

  // ---- 集群名 ----
  if (!form.clusterName.trim()) {
    err('clusterName', '集群名称必填')
  } else if (!/^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/.test(form.clusterName)) {
    err('clusterName', '集群名称只能用小写字母、数字和连字符')
  }

  // ---- 节点 ----
  const controlPlanes = form.nodes.filter((n) => hasRole(n, 'control-plane'))
  const workers = form.nodes.filter((n) => hasRole(n, 'worker'))
  const etcdNodes = form.nodes.filter((n) => hasRole(n, 'etcd'))

  if (controlPlanes.length === 0) {
    err('nodes', '至少需要一台控制面节点')
  } else if (![1, 3, 5].includes(controlPlanes.length)) {
    err('nodes', '控制面节点数量必须是 1、3 或 5 台')
  }
  if (workers.length === 0) {
    err('nodes', '至少需要一个 worker 角色（控制面可以兼 worker）')
  }

  const seenHost = new Set<string>()
  const seenIp = new Set<string>()
  for (const n of form.nodes) {
    const hostname = n.hostname.trim()
    if (!hostname) {
      err('nodes', '节点 hostname 必填')
    } else if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(hostname)) {
      err('nodes', `节点 hostname 格式不正确：${hostname}`)
    } else if (seenHost.has(hostname)) {
      err('nodes', `节点 hostname 重复：${hostname}`)
    } else {
      seenHost.add(hostname)
    }

    if (!n.ip.trim()) {
      err('nodes', `节点 ${n.hostname || '(未命名)'} 的 IP 必填`)
    } else if (!isIpv4(n.ip)) {
      err('nodes', `节点 IP 格式不正确：${n.ip}`)
    } else if (seenIp.has(n.ip)) {
      err('nodes', `节点 IP 重复：${n.ip}`)
    } else {
      seenIp.add(n.ip)
    }

    if (!n.sshUser.trim()) err('nodes', `节点 ${n.hostname || '(未命名)'} 的 SSH 用户必填`)
    if (!Number.isInteger(n.sshPort) || n.sshPort < 1 || n.sshPort > 65535) {
      err('nodes', `节点 ${n.hostname || '(未命名)'} 的 SSH 端口不合法`)
    }
    if (!n.os) err('nodes', `节点 ${n.hostname || '(未命名)'} 的操作系统必填`)
    // 没有角色的节点会落进 all.hosts 却不进任何组 —— kubespray 里就是一台孤儿主机
    if (n.roles.length === 0) err('nodes', `节点 ${n.hostname || '(未命名)'} 至少需要一个角色`)
  }

  // ---- etcd ----
  if (form.etcdMode === 'dedicated') {
    if (![3, 5].includes(etcdNodes.length)) {
      err('etcdMode', '专用 etcd 必须是 3 或 5 台')
    }
    if (etcdNodes.some((n) => hasRole(n, 'control-plane') || hasRole(n, 'worker'))) {
      err('etcdMode', '专用 etcd 节点不能同时承担控制面或 worker')
    }
  }
  if (form.etcdMode === 'external') {
    if (!form.externalEtcd.trim()) {
      err('etcdMode', '外部 etcd 至少需要一个 endpoint')
    } else {
      const endpoints = form.externalEtcd.split(',').map((s) => s.trim()).filter(Boolean)
      const bad = endpoints.find((e) => !/^https?:\/\/[^/\s]+:\d+$/.test(e))
      if (bad) err('etcdMode', `外部 etcd endpoint 格式不正确：${bad}`)
    }
    warn(
      'etcdMode',
      '外部 etcd：节点上不会装 etcd，但每台控制面都要自备 /etc/ssl/etcd/ssl 下的 ca.pem 与客户端证书（本包已设 cert_management: none）',
    )
  }
  if (form.etcdMode !== 'dedicated' && etcdNodes.length > 0) {
    warn('etcdMode', '当前 etcd 模式不会使用勾选的 etcd 角色')
  }

  // ---- API 入口 ----
  const multiMaster = controlPlanes.length > 1
  if (multiMaster && form.apiEntry === 'direct') {
    err('apiEntry', '控制面多于 1 台时必须配置 API 入口（kube-vip 或外部 LB）')
  }
  if (!multiMaster && form.apiEntry !== 'direct') {
    err('apiEntry', '单 master 只能使用直连入口')
  }

  if (form.apiEntry === 'kubevip-arp' || form.apiEntry === 'kubevip-bgp') {
    if (!isIpv4(form.vip)) err('apiEntry', 'kube-vip 需要合法的 VIP')
    if (form.apiEntry === 'kubevip-bgp') {
      const as = Number(form.bgpAs)
      if (!Number.isInteger(as) || as < 1 || as > 65535) err('apiEntry', 'BGP 本地 AS 必须是 1-65535')
      const peers = form.bgpPeers.split('\n').map((s) => s.trim()).filter(Boolean)
      if (peers.length === 0) err('apiEntry', 'BGP 模式至少需要一个 peer')
      const badPeer = peers.find((p) => !/^\d{1,3}(\.\d{1,3}){3}:\d+$/.test(p))
      if (badPeer) err('apiEntry', `BGP peer 格式应为 <ip>:<as>：${badPeer}`)
    }
  }
  if (form.apiEntry === 'external-lb') {
    if (!form.lbAddress.trim()) err('apiEntry', '外部 LB 需要填写地址')
    if (!Number.isInteger(form.lbPort) || form.lbPort < 1 || form.lbPort > 65535) {
      err('apiEntry', '外部 LB 端口不合法')
    }
  }

  if (form.vip.trim() && form.nodes.some((n) => n.ip === form.vip)) {
    err('vip', 'VIP 不能与节点 IP 相同')
  }

  // ---- 网络 ----
  if (!form.podCidr.trim()) {
    err('podCidr', 'Pod CIDR 必填')
  } else if (!cidrValid(form.podCidr)) {
    err('podCidr', 'Pod CIDR 格式不正确')
  }
  if (!form.serviceCidr.trim()) {
    err('serviceCidr', 'Service CIDR 必填')
  } else if (!cidrValid(form.serviceCidr)) {
    err('serviceCidr', 'Service CIDR 格式不正确')
  }
  if (cidrValid(form.podCidr) && cidrValid(form.serviceCidr) && cidrOverlap(form.podCidr, form.serviceCidr)) {
    err('podCidr', 'Pod CIDR 与 Service CIDR 不能重叠')
  }
  if (form.nodeSubnet.trim()) {
    if (!cidrValid(form.nodeSubnet)) {
      err('nodeSubnet', '节点网段格式不正确')
    } else {
      if (cidrValid(form.podCidr) && cidrOverlap(form.nodeSubnet, form.podCidr)) {
        err('nodeSubnet', '节点网段不能与 Pod CIDR 重叠')
      }
      if (cidrValid(form.serviceCidr) && cidrOverlap(form.nodeSubnet, form.serviceCidr)) {
        err('nodeSubnet', '节点网段不能与 Service CIDR 重叠')
      }
    }
  }
  if (!form.clusterDomain.trim()) {
    err('clusterDomain', 'clusterDomain 必填')
  }

  // ---- SSH 连接 ----
  if (form.sshAuth === 'password' && !form.sshPassword.trim()) {
    err('sshPassword', '密码认证需要填写 SSH 密码（同时作为 sudo 密码）')
  }
  const jump = form.jumpHost.trim()
  if (jump) {
    const m = /^([\w.-]+@)?[\w.-]+(?::(\d{1,5}))?$/.exec(jump)
    if (!m) {
      err('jumpHost', '跳转机格式应为 [user@]host[:port]')
    } else if (m[2] && (Number(m[2]) < 1 || Number(m[2]) > 65535)) {
      err('jumpHost', '跳转机端口不合法')
    }
  }

  // ---- 介质 ----
  if (!form.online && form.arch === 'auto') {
    warn(
      'arch',
      '离线镜像按下载主机的架构准备（download_run_once）：与节点架构不同时请显式选择 amd64 / arm64',
    )
  }
  // 镜像下载源是「registry 前缀」而不是 URL：写成 http://... 会在准备机上拼出非法镜像名
  if (form.imageMirror.trim() && form.imageMirror.includes('://')) {
    err('imageMirror', '镜像下载源只写 <主机>[:端口][/路径]，不要带 http:// 前缀')
  }

  return issues
}
