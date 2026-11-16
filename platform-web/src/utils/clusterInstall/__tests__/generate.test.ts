import { test } from 'node:test'
import assert from 'node:assert/strict'
import { generate } from '../generate.ts'
import type { InstallForm, InstallNode } from '../types.ts'

function node(hostname: string, ip: string, roles: InstallNode['roles']): InstallNode {
  return { hostname, ip, sshPort: 22, sshUser: 'root', roles }
}

function base(overrides: Partial<InstallForm> = {}): InstallForm {
  return {
    clusterName: 'demo', os: 'rocky9', arch: 'amd64', kubeVersion: 'v1.36.4',
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

function file(form: InstallForm, path: string): string {
  const hit = generate(form).find((f) => f.path === `inventory/${form.clusterName}/${path}`)
  assert.ok(hit, `missing ${path}`)
  return hit.content
}

test('单 master 兼 worker：两个组都进，且没有 etcd 组', () => {
  const hosts = file(base(), 'hosts.yaml')
  assert.match(hosts, /kube_control_plane:/)
  assert.match(hosts, /kube_node:/)
  assert.match(hosts, /cp-1:/)
  assert.doesNotMatch(hosts, /^    etcd:/m)
  assert.equal(generate(base()).some((f) => f.path.endsWith('/group_vars/all/etcd.yml')), false)
})

test('3 master + ARP：VIP、LB、strictARP 都在', () => {
  const f = base({
    nodes: [node('cp-1', '10.0.0.11', ['control-plane']), node('cp-2', '10.0.0.12', ['control-plane']), node('cp-3', '10.0.0.13', ['control-plane']), node('w-1', '10.0.0.21', ['worker'])],
    apiEntry: 'kubevip-arp', vip: '10.0.0.200',
  })
  const all = file(f, 'group_vars/all/all.yml')
  assert.match(all, /kube_vip_enabled: true/)
  assert.match(all, /kube_vip_address: "10\.0\.0\.200"/)
  assert.match(all, /loadbalancer_apiserver:/)
  const net = file(f, 'group_vars/k8s_cluster/k8s-cluster.yml')
  assert.match(net, /kube_proxy_strict_arp: true/)
})

test('专用 etcd：独立组、且不进 k8s_cluster', () => {
  const f = base({
    etcdMode: 'dedicated',
    nodes: [node('cp-1', '10.0.0.11', ['control-plane']), node('cp-2', '10.0.0.12', ['control-plane']), node('cp-3', '10.0.0.13', ['control-plane']), node('etcd-1', '10.0.0.31', ['etcd']), node('etcd-2', '10.0.0.32', ['etcd']), node('etcd-3', '10.0.0.33', ['etcd']), node('w-1', '10.0.0.21', ['worker'])],
    apiEntry: 'kubevip-arp', vip: '10.0.0.200',
  })
  const hosts = file(f, 'hosts.yaml')
  assert.match(hosts, /etcd-1:/)
  const k8sClusterBlock = hosts.slice(hosts.indexOf('k8s_cluster:'))
  assert.doesNotMatch(k8sClusterBlock, /etcd-1:/)
  assert.match(file(f, 'group_vars/all/etcd.yml'), /etcd_deployment_type: "host"/)
})

test('外部 etcd：不生成 etcd 组，写 endpoints', () => {
  const f = base({ etcdMode: 'external', externalEtcd: 'https://10.0.0.31:2379,https://10.0.0.32:2379' })
  assert.doesNotMatch(file(f, 'hosts.yaml'), /^    etcd:/m)
  assert.match(file(f, 'group_vars/all/etcd.yml'), /etcd_access_addresses: "https:\/\/10\.0\.0\.31:2379,https:\/\/10\.0\.0\.32:2379"/)
})

test('离线：生成 offline.yml；在线不生成', () => {
  const off = base({ online: false, filesRepo: 'http://10.0.0.5/kubespray', registryHost: 'harbor.local/k8s' })
  assert.match(file(off, 'group_vars/all/offline.yml'), /files_repo: "http:\/\/10\.0\.0\.5\/kubespray"/)
  assert.match(file(off, 'group_vars/all/offline.yml'), /registry_host: "harbor\.local\/k8s"/)
  assert.equal(generate(base({ online: true })).some((x) => x.path.endsWith('/offline.yml')), false)
})

test('平台管 CNI 生成 cni；Calico 生成 calico', () => {
  assert.match(file(base(), 'group_vars/k8s_cluster/k8s-cluster.yml'), /kube_network_plugin: "cni"/)
  assert.match(file(base({ cni: 'calico' }), 'group_vars/k8s_cluster/k8s-cluster.yml'), /kube_network_plugin: "calico"/)
})

test('README 写明 Pod CIDR 与 Calico 一致', () => {
  const readme = file(base(), 'README.md')
  assert.match(readme, /10\.233\.64\.0\/18/)
  assert.match(readme, /Calico/)
})
