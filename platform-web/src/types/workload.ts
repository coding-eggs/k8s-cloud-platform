/** 工作负载编辑器类型 —— 镜像 platform-common WorkloadDTO（字段名逐字一致） */

export type WorkloadKind = 'deployment' | 'statefulset' | 'daemonset'

/** 属主引用（metadata.ownerReferences 单项）；非空即「op 管理」→ 禁用编辑并展示管理方 */
export interface OwnerRef {
  apiVersion?: string | null
  kind?: string | null
  name?: string | null
  controller?: boolean | null
}

export interface EnvVar { name: string; value?: string | null; valueFrom?: ValueFrom | null }
export interface ValueFrom {
  configMapKeyRef?: { name: string; key: string; optional?: boolean } | null
  secretKeyRef?: { name: string; key: string; optional?: boolean } | null
  fieldRef?: { apiVersion?: string; fieldPath: string } | null
  fileKeyRef?: {key: string; optional?: boolean ;path : string; volumeName: string} | null
  resourceKeyRef?: {containerName: string; divisor: number | string ; resource: string}
}
export interface EnvFrom { prefix?: string; configMapRef?: { name: string; optional?: boolean } | null; secretRef?: { name: string; optional?: boolean } | null }
export interface ContainerPort { containerPort?: number | null; protocol?: string | null; name?: string | null }
export interface Resources { limits?: Record<string, number>; requests?: Record<string, number> }
export interface HttpGetAction { port?: number; path?: string; scheme?: string }
export interface TCPSocketAction { port?: number }
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
export interface PodAffinity { required?: PodAffinityTerm[] | null; preferred?: { weight: number; podAffinityTerm: PodAffinityTerm }[] | null }
export interface Affinity { nodeAffinity?: NodeAffinity | null; podAffinity?: PodAffinity | null; podAntiAffinity?: PodAntiAffinity | null }

export interface Toleration { key?: string | null; operator?: string | null; value?: string | null; effect?: string | null; tolerationSeconds?: number | null }
export interface KeyToPath { key: string; path: string; mode?: number | null }

// ---- 卷：downwardAPI / projected 共享的字段选择器与文件结构（镜像后端同名 DTO）----
export interface ObjectFieldSelector { apiVersion?: string | null; fieldPath?: string | null }
export interface ResourceFieldSelector { containerName?: string | null; divisor?: number | null; resource?: string | null }
export interface DownwardAPIVolumeFile { path?: string | null; mode?: number | null; fieldRef?: ObjectFieldSelector | null; resourceFieldRef?: ResourceFieldSelector | null }

// ---- projected 卷的投影来源子结构（官方六种，六选一）----
export interface ServiceAccountTokenProjection { audience?: string | null; expirationSeconds?: number | null; path?: string | null }
export interface ConfigMapProjection { name?: string | null; items?: KeyToPath[] | null; optional?: boolean | null }
export interface SecretProjection { name?: string | null; items?: KeyToPath[] | null; optional?: boolean | null }
export interface DownwardAPIProjection { items?: DownwardAPIVolumeFile[] | null }
export interface LabelSelector { matchLabels?: Record<string, string> | null; matchExpressions?: NodeSelectorRequirement[] | null }
export interface ClusterTrustBundleProjection { name?: string | null; signerName?: string | null; labelSelector?: LabelSelector | null; optional?: boolean | null; path?: string | null }
export interface PodCertificateProjection { signerName?: string | null; keyType?: string | null; credentialBundlePath?: string | null; keyPath?: string | null; certificateChainPath?: string | null; maxExpirationSeconds?: number | null; userAnnotations?: Record<string, string> | null }
export interface ProjectedSource { serviceAccountToken?: ServiceAccountTokenProjection | null; configMap?: ConfigMapProjection | null; secret?: SecretProjection | null; downwardAPI?: DownwardAPIProjection | null; clusterTrustBundle?: ClusterTrustBundleProjection | null; podCertificate?: PodCertificateProjection | null }

// ---- 各卷类型子结构 ----
export interface ProjectedVolume { defaultMode?: number | null; sources?: ProjectedSource[] | null }
export interface DownwardAPIVolume { defaultMode?: number | null; items?: DownwardAPIVolumeFile[] | null }
export interface LocalObjectReference { name?: string | null }
export interface CsiVolume { driver?: string | null; readOnly?: boolean | null; fsType?: string | null; volumeAttributes?: Record<string, string> | null; nodePublishSecretRef?: LocalObjectReference | null }
export interface NfsVolume { server?: string | null; path?: string | null; readOnly?: boolean | null }

export interface VolumeDef {
  name: string; type: 'emptyDir' | 'configMap' | 'secret' | 'persistentVolumeClaim' | 'hostPath' | 'projected' | 'downwardAPI' | 'csi' | 'nfs'
  emptyDir?: { medium?: string | null; sizeLimit?: number | null } | null
  configMap?: { name: string; items?: KeyToPath[] | null } | null
  secret?: { secretName: string; items?: KeyToPath[] | null } | null
  persistentVolumeClaim?: { claimName: string } | null
  hostPath?: { path: string; type?: string | null } | null
  projected?: ProjectedVolume | null
  downwardAPI?: DownwardAPIVolume | null
  csi?: CsiVolume | null
  nfs?: NfsVolume | null
}

export interface RollingUpdate { maxSurge?: string | null; maxUnavailable?: string | null; partition?: number | null }
export interface Strategy { type?: string | null; rollingUpdate?: RollingUpdate | null }
export interface PvcTemplate { name: string; accessModes: string[]; storage: number | null; storageClassName?: string | null; volumeMode?: string | null }

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
  /** 对象自身注解（metadata.annotations；与标签同层） */
  annotations?: Record<string, string> | null
  description?: string | null
  replicas?: number | null
  readyReplicas?: number | null
  /** 状态原因（未完全就绪时的说明；正常时为空） */
  statusReason?: string | null
  serviceName?: string | null
  /** 新建 Pod 就绪且无容器崩溃后被视为可用的最短秒数（deployment + statefulset；默认 0） */
  minReadySeconds?: number | null
  /** Deployment 专属：是否暂停更新 */
  paused?: boolean | null
  /** StatefulSet 专属 Pod 管理策略 */
  podManagementPolicy?: 'OrderedReady' | 'Parallel' | null
  /** StatefulSet 专属 PVC 保留策略 */
  persistentVolumeClaimRetentionPolicy?: { whenDeleted?: string | null; whenScaled?: string | null } | null
  /** StatefulSet 专属编号起始值（默认 0） */
  ordinals?: { start?: number | null } | null
  strategy?: Strategy | null
  /** Pod 选择器 = spec.selector.matchLabels（反查该工作负载的 Pod 用） */
  selector?: Record<string, string> | null
  volumeClaimTemplates?: PvcTemplate[] | null
  podTemplate?: { labels?: Record<string, string> | null; annotations?: Record<string, any> | null; spec: PodSpec } | null
  images?: string[] | null
  ports?: ContainerPort[] | null
  creationTime?: string | null
  /** 属主引用（由 Operator/控制器创建的才有；非空即「op 管理」） */
  ownerReferences?: OwnerRef[] | null
  /** 对外暴露的 Service（仅 list/get 返回，api 侧 join 计算） */
  exposedServices?: K8sExposedService[] | null
}

/** 工作负载对外暴露的 Service（名称 + 类型 + port:nodePort），仅 list/get 返回，api 侧 join 计算 */
export interface K8sExposedService {
  name: string
  type: string
  ports: { port: number; nodePort: number }[]
}
