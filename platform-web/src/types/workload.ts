/** 工作负载编辑器类型 —— 镜像 platform-common WorkloadDTO（字段名逐字一致） */

export type WorkloadKind = 'deployment' | 'statefulset' | 'daemonset'

export interface EnvVar { name: string; value?: string | null; valueFrom?: ValueFrom | null }
export interface ValueFrom {
  configMapKeyRef?: { name: string; key: string; optional?: boolean } | null
  secretKeyRef?: { name: string; key: string; optional?: boolean } | null
  fieldRef?: { apiVersion?: string; fieldPath: string } | null
}
export interface EnvFrom { prefix?: string; configMapRef?: { name: string; optional?: boolean } | null; secretRef?: { name: string; optional?: boolean } | null }
export interface ContainerPort { containerPort?: number | null; protocol?: string | null; name?: string | null }
export interface Resources { limits?: Record<string, string>; requests?: Record<string, string> }
export interface HttpGetAction { port?: string; path?: string; scheme?: string }
export interface TCPSocketAction { port?: string }
export interface ExecAction { command: string[] }
export interface SleepAction { seconds?: number | null }
export interface Probe { httpGet?: HttpGetAction | null; tcpSocket?: TCPSocketAction | null; exec?: ExecAction | null; initialDelaySeconds?: number | null; periodSeconds?: number | null; timeoutSeconds?: number | null; successThreshold?: number | null; failureThreshold?: number | null }
export interface LifecycleHandler { exec?: ExecAction | null; httpGet?: HttpGetAction | null; sleep?: SleepAction | null }
export interface Lifecycle { postStart?: LifecycleHandler | null; preStop?: LifecycleHandler | null }
export interface VolumeMount { name: string; mountPath: string; readOnly?: boolean; subPath?: string | null }

export interface ContainerDef {
  name: string; image?: string | null
  command?: string[] | null; args?: string[] | null; workingDir?: string | null
  imagePullPolicy?: string | null
  envs?: EnvVar[] | null; envFrom?: EnvFrom[] | null; ports?: ContainerPort[] | null
  resources?: Resources | null; lifecycle?: Lifecycle | null
  livenessProbe?: Probe | null; readinessProbe?: Probe | null; startupProbe?: Probe | null
  volumeMounts?: VolumeMount[] | null
}

export interface NodeSelectorRequirement { key: string; operator: string; values: string[] }
export interface NodeSelectorTerm { matchExpressions?: NodeSelectorRequirement[] | null; matchFields?: NodeSelectorRequirement[] | null }
export interface NodeAffinity { required?: { nodeSelectorTerms: NodeSelectorTerm[] } | null; preferred?: { weight: number; preference: NodeSelectorTerm }[] | null }
export interface PodAffinityTerm { namespaces?: string[] | null; topologyKey?: string | null; matchLabels?: Record<string, string> | null }
export interface PodAntiAffinity { required?: PodAffinityTerm[] | null; preferred?: { weight: number; podAffinityTerm: PodAffinityTerm }[] | null }
export interface Affinity { nodeAffinity?: NodeAffinity | null; podAntiAffinity?: PodAntiAffinity | null }

export interface Toleration { key?: string | null; operator?: string | null; value?: string | null; effect?: string | null; tolerationSeconds?: number | null }
export interface KeyToPath { key: string; path: string; mode?: number | null }
export interface VolumeDef {
  name: string; type: 'emptyDir' | 'configMap' | 'secret' | 'persistentVolumeClaim' | 'hostPath'
  emptyDir?: { medium?: string | null; sizeLimit?: string | null } | null
  configMap?: { name: string; items?: KeyToPath[] | null } | null
  secret?: { secretName: string; items?: KeyToPath[] | null } | null
  persistentVolumeClaim?: { claimName: string } | null
  hostPath?: { path: string; type?: string | null } | null
}

export interface RollingUpdate { maxSurge?: string | null; maxUnavailable?: string | null; partition?: number | null }
export interface Strategy { type?: string | null; rollingUpdate?: RollingUpdate | null }
export interface PvcTemplate { name: string; accessModes: string[]; storage: string; storageClassName?: string | null; volumeMode?: string | null }

export interface PodSpec {
  containers: ContainerDef[]
  initContainers?: ContainerDef[] | null
  restartPolicy?: string | null
  serviceAccountName?: string | null
  nodeName?: string | null
  nodeSelector?: Record<string, string> | null
  affinity?: Affinity | null
  tolerations?: Toleration[] | null
  volumes?: VolumeDef[] | null
  imagePullSecrets?: { name: string }[] | null
}

export interface WorkloadDetail {
  kind: WorkloadKind
  name: string
  namespace: string
  labels?: Record<string, string> | null
  description?: string | null
  replicas?: number | null
  readyReplicas?: number | null
  serviceName?: string | null
  strategy?: Strategy | null
  volumeClaimTemplates?: PvcTemplate[] | null
  podTemplate?: { labels?: Record<string, string> | null; annotations?: Record<string, any> | null; spec: PodSpec } | null
  images?: string[] | null
  ports?: ContainerPort[] | null
  creationTime?: string | null
}
