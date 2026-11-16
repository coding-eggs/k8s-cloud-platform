import { test } from 'node:test'
import assert from 'node:assert/strict'
import { generate } from '../generate.ts'
import type { InstallForm, InstallNode } from '../types.ts'

function node(hostname: string, ip: string, roles: InstallNode['roles'], os = 'rocky9'): InstallNode {
  return { hostname, ip, sshPort: 22, sshUser: 'root', os, roles }
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

function file(form: InstallForm, path: string): string {
  const hit = generate(form).find((f) => f.path === `inventory/${form.clusterName}/${path}`)
  assert.ok(hit, `missing ${path}`)
  return hit.content
}

test('单 master 兼 worker：两个组都进，且没有 etcd 组 / 没有 VIP / 没有空 all.yml', () => {
  const hosts = file(base(), 'hosts.yaml')
  assert.match(hosts, /kube_control_plane:/)
  assert.match(hosts, /kube_node:/)
  assert.match(hosts, /"cp-1":/)
  assert.doesNotMatch(hosts, /^    etcd:/m)
  const files = generate(base())
  assert.equal(files.some((f) => f.path.endsWith('/group_vars/all/etcd.yml')), false)
  assert.equal(files.some((f) => f.path.endsWith('/group_vars/all/all.yml')), false)
})

test('K8s 版本落到变量（不带 v）；架构自动时不写 image_arch，显式选择才写', () => {
  const auto = file(base(), 'group_vars/k8s_cluster/k8s-cluster.yml')
  assert.match(auto, /kube_version: "1\.36\.4"/)
  // 「自动」交给 kubespray 按主机探测；写死反而会害了架构不同的节点
  assert.doesNotMatch(auto, /image_arch/)
  assert.match(file(base({ arch: 'arm64' }), 'group_vars/k8s_cluster/k8s-cluster.yml'), /image_arch: "arm64"/)
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
  // Review Focus：单 master 不出现 kube_vip_*
  assert.doesNotMatch(file(base(), 'README.md'), /kube_vip_/)
})

test('专用 etcd：独立组、且不进 k8s_cluster', () => {
  const f = base({
    etcdMode: 'dedicated',
    nodes: [node('cp-1', '10.0.0.11', ['control-plane']), node('cp-2', '10.0.0.12', ['control-plane']), node('cp-3', '10.0.0.13', ['control-plane']), node('etcd-1', '10.0.0.31', ['etcd']), node('etcd-2', '10.0.0.32', ['etcd']), node('etcd-3', '10.0.0.33', ['etcd']), node('w-1', '10.0.0.21', ['worker'])],
    apiEntry: 'kubevip-arp', vip: '10.0.0.200',
  })
  const hosts = file(f, 'hosts.yaml')
  assert.match(hosts, /"etcd-1":/)
  const k8sClusterBlock = hosts.slice(hosts.indexOf('k8s_cluster:'))
  assert.doesNotMatch(k8sClusterBlock, /etcd-1/)
  assert.match(file(f, 'group_vars/all/etcd.yml'), /etcd_deployment_type: "host"/)
})

test('外部 etcd：不生成 etcd 组，写 endpoints', () => {
  const f = base({ etcdMode: 'external', externalEtcd: 'https://10.0.0.31:2379,https://10.0.0.32:2379' })
  assert.doesNotMatch(file(f, 'hosts.yaml'), /^    etcd:/m)
  assert.match(file(f, 'group_vars/all/etcd.yml'), /etcd_access_addresses: "https:\/\/10\.0\.0\.31:2379,https:\/\/10\.0\.0\.32:2379"/)
})

test('离线：生成 offline.yml（四个 URL 覆盖 + github 镜像仓）；在线不生成', () => {
  const off = base({ online: false, filesRepo: 'http://10.0.0.5/kubespray', registryHost: 'harbor.local/k8s' })
  const offline = file(off, 'group_vars/all/offline.yml')
  assert.match(offline, /github_url: "http:\/\/10\.0\.0\.5\/kubespray\/github\.com"/)
  assert.match(offline, /dl_k8s_io_url: "http:\/\/10\.0\.0\.5\/kubespray\/dl\.k8s\.io"/)
  assert.match(offline, /storage_googleapis_url: "http:\/\/10\.0\.0\.5\/kubespray\/storage\.googleapis\.com"/)
  assert.match(offline, /get_helm_url: "http:\/\/10\.0\.0\.5\/kubespray\/get\.helm\.sh"/)
  assert.match(offline, /github_image_repo: "harbor\.local\/k8s"/)
  assert.match(offline, /registry_host: "harbor\.local\/k8s"/)
  assert.equal(generate(base({ online: true })).some((x) => x.path.endsWith('/offline.yml')), false)
})

test('在线镜像源写进四个 image_repo + github_image_repo', () => {
  const all = file(base({ imageRegistry: 'harbor.local/mirror' }), 'group_vars/all/all.yml')
  assert.match(all, /kube_image_repo: "harbor\.local\/mirror"/)
  assert.match(all, /gcr_image_repo: "harbor\.local\/mirror"/)
  assert.match(all, /docker_image_repo: "harbor\.local\/mirror"/)
  assert.match(all, /quay_image_repo: "harbor\.local\/mirror"/)
  assert.match(all, /github_image_repo: "harbor\.local\/mirror"/)
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

test('README 的操作系统按节点归类写（混合 OS 也如实写），架构自动写明', () => {
  const mixed = base({
    nodes: [
      node('cp-1', '10.0.0.11', ['control-plane', 'worker'], 'ubuntu2204'),
      node('w-1', '10.0.0.21', ['worker'], 'rocky9'),
    ],
  })
  const readme = file(mixed, 'README.md')
  assert.match(readme, /Ubuntu 22\.04 × 1/)
  assert.match(readme, /Rocky Linux 9 × 1/)
  assert.match(readme, /镜像架构：自动（kubespray 按主机探测）/)
  assert.match(file(base({ arch: 'arm64' }), 'README.md'), /镜像架构：arm64/)
})

test('SSH 密码认证：写 ansible_password / ansible_become_password；密钥认证一个都不写', () => {
  const all = file(base({ sshAuth: 'password', sshPassword: 's3cret' }), 'group_vars/all/all.yml')
  assert.match(all, /ansible_password: "s3cret"/)
  assert.match(all, /ansible_become_password: "s3cret"/)
  assert.equal(generate(base()).some((f) => f.content.includes('ansible_password')), false)
})

test('跳转机：写成 ProxyJump 的 ansible_ssh_common_args', () => {
  const all = file(base({ jumpHost: 'ops@10.0.0.5:2222' }), 'group_vars/all/all.yml')
  assert.match(all, /ansible_ssh_common_args: "-o ProxyJump=ops@10\.0\.0\.5:2222"/)
})

test('README 写明 SSH 认证方式、跳转机与明文密码提醒', () => {
  const readme = file(base({ sshAuth: 'password', sshPassword: 'x', jumpHost: 'ops@10.0.0.5' }), 'README.md')
  assert.match(readme, /SSH：密码认证/)
  assert.match(readme, /经跳转机 ops@10\.0\.0\.5/)
  assert.match(readme, /明文密码/)
  assert.match(readme, /跳转机自身的认证走控制机的 `ssh` 配置/)
  assert.match(file(base(), 'README.md'), /SSH：密钥认证（节点已预置部署机公钥）；直连/)
})
