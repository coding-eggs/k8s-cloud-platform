/** 集群安装向导的表单状态：纯本地，不发请求、不落库。 */
import { computed, reactive } from 'vue'
import type { ComputedRef } from 'vue'
import type { InstallFile, InstallForm, Issue } from '@/utils/clusterInstall/types'
import { DEFAULT_KUBE_VERSION, DEFAULT_OS } from '@/utils/clusterInstall/kubespray'
import { validate } from '@/utils/clusterInstall/validate'
import { generate } from '@/utils/clusterInstall/generate'

export function createDefaultForm(): InstallForm {
  return {
    clusterName: '',
    arch: 'auto',
    sshAuth: 'key',
    sshPassword: '',
    jumpHost: '',
    kubeVersion: DEFAULT_KUBE_VERSION,
    runtime: 'containerd',
    nodes: [
      { hostname: 'cp-1', ip: '', sshPort: 22, sshUser: 'root', os: DEFAULT_OS, roles: ['control-plane'] },
      { hostname: 'cp-2', ip: '', sshPort: 22, sshUser: 'root', os: DEFAULT_OS, roles: ['control-plane'] },
      { hostname: 'cp-3', ip: '', sshPort: 22, sshUser: 'root', os: DEFAULT_OS, roles: ['control-plane'] },
      { hostname: 'worker-1', ip: '', sshPort: 22, sshUser: 'root', os: DEFAULT_OS, roles: ['worker'] },
    ],
    etcdMode: 'stacked',
    externalEtcd: '',
    apiEntry: 'kubevip-arp',
    vip: '',
    vipInterface: '',
    bgpAs: '65000',
    bgpPeers: '',
    bgpRouterId: '',
    lbAddress: '',
    lbPort: 6443,
    lbDomain: '',
    cni: 'platform',
    podCidr: '10.233.64.0/18',
    serviceCidr: '10.233.0.0/18',
    clusterDomain: 'cluster.local',
    kubeProxyMode: 'ipvs',
    nodeSubnet: '',
    online: true,
    imageRegistry: '',
    imageMirror: '',
    httpProxy: '',
    httpsProxy: '',
    noProxyExtra: '',
  }
}

export interface ClusterInstallPlan {
  form: InstallForm
  issues: ComputedRef<Issue[]>
  errors: ComputedRef<Issue[]>
  warnings: ComputedRef<Issue[]>
  artifacts: ComputedRef<InstallFile[]>
}

export function useClusterInstallPlan(): ClusterInstallPlan {
  const form = reactive<InstallForm>(createDefaultForm())
  const issues = computed(() => validate(form))
  const errors = computed(() => issues.value.filter((i) => i.level === 'error'))
  const warnings = computed(() => issues.value.filter((i) => i.level === 'warning'))
  const artifacts = computed(() => generate(form))
  return { form, issues, errors, warnings, artifacts }
}
