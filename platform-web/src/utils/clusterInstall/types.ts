/** 集群安装向导的表单模型。纯数据，无 Vue 依赖。 */

export type Arch = 'amd64' | 'arm64'
export type Runtime = 'containerd' | 'crio'
export type EtcdMode = 'stacked' | 'dedicated' | 'external'
export type ApiEntryMode = 'kubevip-arp' | 'kubevip-bgp' | 'external-lb' | 'direct'
export type CniMode = 'platform' | 'calico' | 'cilium' | 'flannel'
export type KubeProxyMode = 'ipvs' | 'iptables' | 'nftables'
export type NodeRole = 'control-plane' | 'worker' | 'etcd'

export interface InstallNode {
  hostname: string
  ip: string
  sshPort: number
  sshUser: string
  roles: NodeRole[]
}

export interface InstallForm {
  clusterName: string
  os: string
  arch: Arch
  kubeVersion: string
  runtime: Runtime
  nodes: InstallNode[]
  etcdMode: EtcdMode
  /** 外部托管 etcd 的 endpoints，逗号分隔 */
  externalEtcd: string
  apiEntry: ApiEntryMode
  vip: string
  vipInterface: string
  bgpAs: string
  /** 每行一个 peer，格式 `<ip>:<as>` */
  bgpPeers: string
  bgpRouterId: string
  lbAddress: string
  lbPort: number
  lbDomain: string
  cni: CniMode
  podCidr: string
  serviceCidr: string
  clusterDomain: string
  kubeProxyMode: KubeProxyMode
  /** 可选；填了才做网段重叠校验与 no_proxy 补全 */
  nodeSubnet: string
  online: boolean
  imageRegistry: string
  filesRepo: string
  registryHost: string
  httpProxy: string
  httpsProxy: string
  noProxyExtra: string
}

export interface Issue {
  field: string
  level: 'error' | 'warning'
  message: string
}

export interface InstallFile {
  /** 相对下载根目录，例如 `inventory/demo/hosts.yaml` */
  path: string
  content: string
}
