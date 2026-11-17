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
    online: true, imageRegistry: '', imageMirror: '',
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

test('外部 etcd：空的 etcd 组 + endpoints + 自备证书（cert_management: none）', () => {
  const f = base({ etcdMode: 'external', externalEtcd: 'https://10.0.0.31:2379,https://10.0.0.32:2379' })
  // 组键必须存在但为空：kubespray 多处直接读 groups['etcd']，且为空才表示不在节点上装 etcd
  assert.match(file(f, 'hosts.yaml'), /^ {4}etcd:\n {6}hosts:$/m)
  const etcdYml = file(f, 'group_vars/all/etcd.yml')
  assert.match(etcdYml, /etcd_access_addresses: "https:\/\/10\.0\.0\.31:2379,https:\/\/10\.0\.0\.32:2379"/)
  assert.match(etcdYml, /cert_management: "none"/)
  const readme = file(f, 'README.md')
  assert.match(readme, /自备证书/)
  assert.match(readme, /\/etc\/ssl\/etcd\/ssl\/ca\.pem/)
})

test('离线：offline.yml 地址用占位符（部署脚本按控制机 IP 替换）；在线不生成', () => {
  const offline = file(base({ online: false }), 'group_vars/all/offline.yml')
  assert.match(offline, /files_repo: "http:\/\/__CONTROL_HOST__:8080"/)
  assert.match(offline, /github_url: "http:\/\/__CONTROL_HOST__:8080\/github\.com"/)
  assert.match(offline, /dl_k8s_io_url: "http:\/\/__CONTROL_HOST__:8080\/dl\.k8s\.io"/)
  assert.match(offline, /storage_googleapis_url: "http:\/\/__CONTROL_HOST__:8080\/storage\.googleapis\.com"/)
  assert.match(offline, /get_helm_url: "http:\/\/__CONTROL_HOST__:8080\/get\.helm\.sh"/)
  assert.match(offline, /registry_host: "__CONTROL_HOST__:5000"/)
  assert.match(offline, /github_image_repo: "__CONTROL_HOST__:5000"/)
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

test('离线：额外生成 offline-prepare.sh，串起官方的清单生成与下载脚本', () => {
  const sh = file(base({ online: false }), 'offline-prepare.sh')
  assert.match(sh, /clone --depth 1 --branch v2\.32\.0/)
  assert.match(sh, /generate_list\.sh -i "\$ONLINE_INV\/hosts\.yaml"/)
  assert.match(sh, /manage-offline-files\.sh/)
  // 在线模式不该有这份脚本
  assert.equal(generate(base()).some((x) => x.path.endsWith('offline-prepare.sh')), false)
})

test('离线：containerd 走 http registry（mirrors + skip_verify）', () => {
  const off = file(base({ online: false }), 'group_vars/all/offline.yml')
  assert.match(off, /containerd_registries_mirrors:/)
  assert.match(off, /- prefix: "__CONTROL_HOST__:5000"/)
  assert.match(off, /host: "http:\/\/__CONTROL_HOST__:5000"/)
  assert.match(off, /skip_verify: true/)
})

test('离线：准备脚本产出全离线包（pip / 镜像 / registry / 系统依赖 / 统一打包）', () => {
  const f = base({
    online: false,
    nodes: [
      node('cp-1', '10.0.0.11', ['control-plane'], 'rocky9'),
      node('w-1', '10.0.0.21', ['worker'], 'ubuntu2204'),
    ],
  })
  const sh = file(f, 'offline-prepare.sh')
  assert.match(sh, /pip download -r requirements\.txt/)
  // 自己拉镜像：写 register 认的 container-images.txt / container-images.tar.gz
  assert.match(sh, /while read -r image/)
  assert.match(sh, /container-images\.txt/)
  assert.match(sh, /container-images\.tar\.gz/)
  assert.match(sh, /registry:2/)
  assert.match(sh, /--downloadonly/) // rpm 侧
  assert.match(sh, /apt-get install --download-only/) // deb 侧
  assert.match(sh, /tar czf/)
})

test('离线：清单从去掉 offline.yml 的副本生成，否则下载地址会是离线占位符', () => {
  const sh = file(base({ online: false }), 'offline-prepare.sh')
  assert.match(sh, /ONLINE_INV="inventory\/\$\{CLUSTER_NAME\}-online"/)
  assert.match(sh, /rm -f "\$ONLINE_INV\/group_vars\/all\/offline\.yml"/)
  assert.match(sh, /rm -rf "\$ONLINE_INV"/)
})

test('离线：镜像下载源可配（默认官方；配了就从镜像站拉，再打回原名打包）', () => {
  const mirror = file(base({ online: false, imageMirror: 'docker.m.daocloud.io' }), 'offline-prepare.sh')
  assert.match(mirror, /IMAGE_MIRROR="docker\.m\.daocloud\.io"/)
  // docker.io 是默认 registry，镜像站下不带它；其他公共 registry 保留在路径里
  assert.match(mirror, /docker\.io\/\*\) src="\$IMAGE_MIRROR\/\$\{image#docker\.io\/\}"/)
  assert.match(mirror, /\$CONTAINER tag "\$src" "\$image"/)
  // 没配就按官方地址拉
  assert.match(file(base({ online: false }), 'offline-prepare.sh'), /IMAGE_MIRROR=""/)
})

test('离线：表单里的代理会导出给准备脚本（拉镜像与下载静态文件都走它）', () => {
  const sh = file(
    base({ online: false, httpProxy: 'http://10.0.0.1:8080', httpsProxy: 'http://10.0.0.1:8080', noProxyExtra: 'harbor.local' }),
    'offline-prepare.sh',
  )
  assert.match(sh, /export http_proxy="http:\/\/10\.0\.0\.1:8080"/)
  assert.match(sh, /export https_proxy="http:\/\/10\.0\.0\.1:8080"/)
  assert.match(sh, /export no_proxy="localhost,127\.0\.0\.1,harbor\.local"/)
})

test('离线：生成 deploy-offline.sh（探测 IP、改写 offline.yml、起 registry 与文件服务、跑 playbook）', () => {
  const sh = file(base({ online: false }), 'deploy-offline.sh')
  assert.match(sh, /__CONTROL_HOST__/)
  assert.match(sh, /sed -i "s\/__CONTROL_HOST__\/\$HOST_IP\/g"/)
  assert.match(sh, /registry:2/)
  assert.match(sh, /manage-offline-container-images\.sh register/)
  assert.match(sh, /http\.server/)
  assert.match(sh, /ansible-playbook -i "\$INV\/hosts\.yaml" -b cluster\.yml/)
  // 在线模式不该有这份脚本
  assert.equal(generate(base()).some((x) => x.path.endsWith('deploy-offline.sh')), false)
})

test('离线：README 第 9 节按全离线写（离线包内容 + 一键部署脚本）', () => {
  const readme = file(base({ online: false }), 'README.md')
  assert.match(readme, /## 9\. 离线模式/)
  assert.match(readme, /registry:2/)
  assert.match(readme, /http\.server/)
  assert.match(readme, /deploy-offline\.sh/)
  assert.match(readme, /系统依赖/)
  assert.doesNotMatch(readme, /仍需另行配置/)
})

test('离线声明放在 README 最上面，且不再有名词介绍', () => {
  const offline = file(base({ online: false }), 'README.md')
  const head = offline.slice(0, offline.indexOf('## 1.'))
  assert.match(head, /全离线/)
  assert.match(head, /不能上网/)
  assert.match(head, /第 9 节/)
  // 名词介绍整节删掉
  assert.doesNotMatch(offline, /三个名词/)
  // 在线包开头不该出现离线声明
  const online = file(base(), 'README.md')
  assert.doesNotMatch(online.slice(0, online.indexOf('## 1.')), /全离线/)
})
