/** 集群安装向导的表单模型。纯数据，无 Vue 依赖。 */

export type Arch = 'amd64' | 'arm64'
/** 镜像架构：`auto` = 不写 `image_arch`，由 kubespray 按主机探测（`host_architecture`）。
 *  它是**集群级**变量而不是节点选择 —— kubespray 的镜像清单与 download 都按它走，混架构集群跑不通。 */
export type ArchMode = 'auto' | Arch
export type Runtime = 'containerd' | 'crio'
export type EtcdMode = 'stacked' | 'dedicated' | 'external'
export type ApiEntryMode = 'kubevip-arp' | 'kubevip-bgp' | 'external-lb' | 'direct'
export type CniMode = 'platform' | 'calico' | 'cilium' | 'flannel'
export type KubeProxyMode = 'ipvs' | 'iptables' | 'nftables'
export type NodeRole = 'control-plane' | 'worker' | 'etcd'
/** SSH 认证：密钥 = 部署机公钥已被节点信任（默认）；密码 = 写 `ansible_password` / `ansible_become_password` */
export type SshAuth = 'key' | 'password'

export interface InstallNode {
  hostname: string
  ip: string
  sshPort: number
  sshUser: string
  /** 操作系统是**每台机器**自己的属性：kubespray 按主机探测 `ansible_os_family`，混合 OS 合法。
   *  本向导只用它生成说明与离线提示，不写进 group_vars。 */
  os: string
  roles: NodeRole[]
}

export interface InstallForm {
  clusterName: string
  arch: ArchMode
  /** 集群级 SSH 认证方式；节点表只填用户名/端口，密码与跳转机在这里统一给 */
  sshAuth: SshAuth
  /** 密码认证时的 SSH 密码（同时作为 sudo 密码）。明文写进 group_vars，README 会提示注意权限 */
  sshPassword: string
  /** 跳转机 ProxyJump，格式 `[user@]host[:port]`；留空 = 直连 */
  jumpHost: string
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
