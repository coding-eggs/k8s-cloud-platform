/**
 * kubespray v2.32.0 的静态数据：支持的 K8s 版本、OS 清单、变量名单。
 *
 * <p>变量名单是"生成器只能写这些键"的类型约束——`--syntax-check` 不校验 group_vars 键名，
 * 拼错会被 Ansible 静默忽略并落到 kubespray 默认值，所以键名必须在这里收口。
 */
import type { CniMode, KubeProxyMode, Runtime } from './types'

export const KUBESPRAY_VERSION = 'v2.32.0'

/** 来自 kubespray v2.32.0 `roles/kubespray_defaults/vars/main/checksums.yml` 的 kubelet_checksums。
 *  ⚠️ 必须**不带 `v` 前缀**：v2.32.0 的 `kube_version` 默认值直接取 checksums 的键（`1.36.4`），
 *  kubeadm 模板会自己拼 `v`。展示层需要时另行加 `v`。 */
export const KUBE_VERSIONS: readonly string[] = [
  '1.36.4', '1.36.3', '1.36.2', '1.36.1', '1.36.0',
  '1.35.8', '1.35.7', '1.35.6', '1.35.5', '1.35.4', '1.35.3', '1.35.2', '1.35.1', '1.35.0',
  '1.34.11', '1.34.10', '1.34.9', '1.34.8', '1.34.7', '1.34.6', '1.34.5',
  '1.34.4', '1.34.3', '1.34.2', '1.34.1', '1.34.0',
] as const

export const DEFAULT_KUBE_VERSION = '1.36.4'

export type OsFamily = 'rpm' | 'deb'

export interface OsOption {
  value: string
  label: string
  family: OsFamily
}

/** 实施时对照 kubespray v2.32.0 的 `roles/bootstrap_os` 再删一遍不支持的项 */
export const OS_OPTIONS: readonly OsOption[] = [
  { value: 'ubuntu2204', label: 'Ubuntu 22.04', family: 'deb' },
  { value: 'ubuntu2404', label: 'Ubuntu 24.04', family: 'deb' },
  { value: 'debian12', label: 'Debian 12', family: 'deb' },
  { value: 'debian13', label: 'Debian 13', family: 'deb' },
  { value: 'rocky9', label: 'Rocky Linux 9', family: 'rpm' },
  { value: 'rocky10', label: 'Rocky Linux 10', family: 'rpm' },
  { value: 'alma9', label: 'AlmaLinux 9', family: 'rpm' },
  { value: 'alma10', label: 'AlmaLinux 10', family: 'rpm' },
  { value: 'centos9', label: 'CentOS Stream 9', family: 'rpm' },
  { value: 'centos10', label: 'CentOS Stream 10', family: 'rpm' },
] as const

/** 新加节点时的默认 OS（引导页里 Kubespray 支持面最广的一档） */
export const DEFAULT_OS = 'rocky9'

/** 展示用：OS 值 → 人话名；未知值原样返回，别在 UI 里露出裸 key */
export function osLabel(value: string): string {
  return OS_OPTIONS.find((o) => o.value === value)?.label ?? value
}

/** `kube_network_plugin` 的取值：平台的 `platform` 映射为 `cni`（不装 CNI） */
export type KubesprayCni = 'cni' | Exclude<CniMode, 'platform'>

/**
 * 允许写进 group_vars 的键。生成器返回这个类型，拼接一个不存在的键会被 vue-tsc 拒绝。
 */
export interface KubesprayVars {
  kube_version?: string
  image_arch?: 'amd64' | 'arm64'
  container_manager?: Runtime
  kube_network_plugin?: KubesprayCni
  kube_pods_subnet?: string
  kube_service_addresses?: string
  cluster_name?: string
  kube_proxy_mode?: KubeProxyMode
  kube_proxy_strict_arp?: boolean

  kube_vip_enabled?: boolean
  kube_vip_controlplane_enabled?: boolean
  kube_vip_address?: string
  kube_vip_interface?: string
  kube_vip_arp_enabled?: boolean
  kube_vip_bgp_enabled?: boolean
  kube_vip_local_as?: number
  kube_vip_bgp_routerid?: string
  kube_vip_bgppeers?: string[]
  loadbalancer_apiserver?: { address: string; port: number }
  apiserver_loadbalancer_domain_name?: string

  etcd_deployment_type?: 'host'
  etcd_access_addresses?: string
  /** 外部 etcd：不让 kubespray 生成 etcd 证书（它没有「跳过 etcd」开关，只能自备证书） */
  cert_management?: 'none'

  /** SSH 密码认证（控制机需装 sshpass；明文，README 有权限提示） */
  ansible_password?: string
  ansible_become_password?: string
  /** 跳转机：`-o ProxyJump=[user@]host[:port]` */
  ansible_ssh_common_args?: string

  http_proxy?: string
  https_proxy?: string
  no_proxy?: string

  /** 仅作为用户可读的镜像根地址保留；真正生效的是下面四个 *_url 覆盖 */
  files_repo?: string
  registry_host?: string
  github_url?: string
  dl_k8s_io_url?: string
  storage_googleapis_url?: string
  get_helm_url?: string
  kube_image_repo?: string
  gcr_image_repo?: string
  docker_image_repo?: string
  quay_image_repo?: string
  github_image_repo?: string
  download_run_once?: boolean
}
