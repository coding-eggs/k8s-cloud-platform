import { test } from 'node:test'
import assert from 'node:assert/strict'
import { validate } from '../validate.ts'
import type { InstallForm, InstallNode } from '../types.ts'

function node(hostname: string, ip: string, roles: InstallNode['roles']): InstallNode {
  return { hostname, ip, sshPort: 22, sshUser: 'root', os: 'rocky9', roles }
}

function base(overrides: Partial<InstallForm> = {}): InstallForm {
  return {
    clusterName: 'demo', arch: 'auto', kubeVersion: '1.36.4',
    sshAuth: 'key', sshPassword: '', jumpHost: '',
    runtime: 'containerd',
    nodes: [node('cp-1', '10.0.0.11', ['control-plane', 'worker'])],
    etcdMode: 'stacked', externalEtcd: '',
    apiEntry: 'direct', vip: '', vipInterface: '', bgpAs: '', bgpPeers: '', bgpRouterId: '',
    lbAddress: '', lbPort: 6443, lbDomain: '',
    cni: 'platform', podCidr: '10.233.64.0/18', serviceCidr: '10.233.0.0/18',
    clusterDomain: 'cluster.local', kubeProxyMode: 'ipvs', nodeSubnet: '',
    online: true, imageRegistry: '', filesRepo: '', registryHost: '',
    httpProxy: '', httpsProxy: '', noProxyExtra: '',
    ...overrides,
  }
}

const errors = (f: InstallForm) => validate(f).filter((i) => i.level === 'error')

test('单 master 兼 worker 合法', () => {
  assert.equal(errors(base()).length, 0)
})

test('两台控制面非法', () => {
  const f = base({ nodes: [node('cp-1', '10.0.0.11', ['control-plane']), node('cp-2', '10.0.0.12', ['control-plane', 'worker'])] })
  assert.ok(errors(f).some((i) => i.field === 'nodes'))
})

test('多控制面没有 API 入口非法', () => {
  const f = base({ nodes: [node('cp-1', '10.0.0.11', ['control-plane', 'worker']), node('cp-2', '10.0.0.12', ['control-plane']), node('cp-3', '10.0.0.13', ['control-plane'])], apiEntry: 'direct' })
  assert.ok(errors(f).some((i) => i.field === 'apiEntry'))
})

test('专用 etcd 与控制面重叠非法', () => {
  const f = base({ etcdMode: 'dedicated', nodes: [node('cp-1', '10.0.0.11', ['control-plane', 'worker', 'etcd']), node('etcd-1', '10.0.0.21', ['etcd'])] })
  assert.ok(errors(f).some((i) => i.field === 'etcdMode' || i.field === 'nodes'))
})

test('节点 IP 重复非法', () => {
  const f = base({ nodes: [node('cp-1', '10.0.0.11', ['control-plane', 'worker']), node('w-1', '10.0.0.11', ['worker'])] })
  assert.ok(errors(f).some((i) => i.field === 'nodes'))
})

test('平台管 CNI 但 Pod CIDR 为空非法', () => {
  assert.ok(errors(base({ podCidr: '' })).some((i) => i.field === 'podCidr'))
})

test('没有任何 worker 角色非法', () => {
  assert.ok(errors(base({ nodes: [node('cp-1', '10.0.0.11', ['control-plane'])] })).some((i) => i.field === 'nodes'))
})

test('没有控制面非法', () => {
  assert.ok(errors(base({ nodes: [node('w-1', '10.0.0.21', ['worker'])] })).some((i) => i.field === 'nodes'))
})

test('单 master 选 kube-vip 非法', () => {
  assert.ok(errors(base({ apiEntry: 'kubevip-arp', vip: '10.0.0.200' })).some((i) => i.field === 'apiEntry'))
})

test('外部 etcd 给出 warning', () => {
  assert.ok(validate(base({ etcdMode: 'external', externalEtcd: 'https://10.0.0.31:2379' })).some((i) => i.level === 'warning'))
})

test('hostname 含空格非法', () => {
  assert.ok(errors(base({ nodes: [node('cp 1', '10.0.0.11', ['control-plane', 'worker'])] })).some((i) => i.field === 'nodes'))
})

test('非专用 etcd 模式勾了 etcd 角色给出 warning', () => {
  const f = base({ nodes: [node('cp-1', '10.0.0.11', ['control-plane', 'worker', 'etcd'])] })
  assert.ok(validate(f).some((i) => i.level === 'warning' && i.field === 'etcdMode'))
})

test('节点没选操作系统非法', () => {
  const f = base()
  f.nodes[0].os = ''
  assert.ok(errors(f).some((i) => i.field === 'nodes'))
})

test('离线 + 架构「自动」给 warning，显式选架构后不再提示', () => {
  const offline = base({ online: false, filesRepo: 'http://10.0.0.5/kubespray', registryHost: 'harbor.local/k8s' })
  assert.ok(validate(offline).some((i) => i.level === 'warning' && i.field === 'arch'))
  assert.equal(
    validate({ ...offline, arch: 'amd64' }).some((i) => i.field === 'arch'),
    false,
  )
})

test('密码认证但没填密码：报错', () => {
  assert.ok(errors(base({ sshAuth: 'password', sshPassword: '   ' })).some((i) => i.field === 'sshPassword'))
})

test('跳转机格式：[user@]host[:port]，端口要合法', () => {
  assert.equal(errors(base({ jumpHost: 'ops@10.0.0.5:2222' })).length, 0)
  assert.equal(errors(base({ jumpHost: '10.0.0.5' })).length, 0)
  assert.ok(errors(base({ jumpHost: 'bad host' })).some((i) => i.field === 'jumpHost'))
  assert.ok(errors(base({ jumpHost: '10.0.0.5:70000' })).some((i) => i.field === 'jumpHost'))
})
