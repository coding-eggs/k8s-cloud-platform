/**
 * 受管资源「归属到谁」的纯函数部分（不发请求，便于单测/复用）。
 *
 * k8s-server 只按命名约定判出 managedBy（COMPONENT / MIDDLEWARE），不知道具体是哪个实例 ——
 * 那一层是平台业务，k8s-server 不碰。这里用对象自带的元数据算出「候选实例名」，
 * 再由调用方拿平台库里的实例列表（一次请求）去匹配。
 */
import { managedByOf } from './managedResource'

interface MetaLike {
  name?: string | null
  namespace?: string | null
  labels?: Record<string, string> | null
  annotations?: Record<string, unknown> | null
  ownerReferences?: { kind?: string; name?: string; controller?: boolean | null }[] | null
}

/** 中间件实例名前缀（与 k8s-server ManagedResourceGuard / MiddlewareService 的命名一致） */
const MW_PREFIX = 'mw-'

/** Operator 给子资源打的实例名标签（与 ManagedResourceGuard.INSTANCE_LABEL_KEYS 同一份） */
const INSTANCE_LABEL_KEYS = [
  'app.kubernetes.io/instance',
  'strimzi.io/cluster',
  'elasticsearch.k8s.elastic.co/cluster-name',
  'app',
]

const RELEASE_ANNOTATION = 'meta.helm.sh/release-name'

/** 中间件类型的展示名（与后端 MiddlewareCatalog 一致；不调 /middleware/catalog —— 它要实时查 Operator 状态，太重） */
const MIDDLEWARE_TYPE_NAMES: Record<string, string> = {
  redis: 'Redis',
  mysql: 'MySQL',
  kafka: 'Kafka',
  elasticsearch: 'Elasticsearch',
  postgresql: 'PostgreSQL',
  rabbitmq: 'RabbitMQ',
  nacos: 'Nacos',
  milvus: 'Milvus',
}

export function middlewareTypeName(code: string | null | undefined): string {
  if (!code) return '中间件'
  return MIDDLEWARE_TYPE_NAMES[code] ?? code
}

export function helmReleaseOf(row: unknown): string | null {
  const v = (row as MetaLike | null)?.annotations?.[RELEASE_ANNOTATION]
  return v == null || v === '' ? null : String(v)
}

/** 可能是实例名（或以「实例名-」开头）的那些字符串：属主名 → helm release → 实例名标签 → 自身名 */
export function middlewareCandidates(row: unknown): string[] {
  const r = (row ?? {}) as MetaLike
  const out: string[] = []
  for (const o of r.ownerReferences ?? []) if (o?.name) out.push(o.name)
  const rel = helmReleaseOf(r)
  if (rel) out.push(rel)
  for (const k of INSTANCE_LABEL_KEYS) {
    const v = r.labels?.[k]
    if (v) out.push(v)
  }
  if (r.name) out.push(r.name)
  return out.filter((s) => s.startsWith(MW_PREFIX))
}

/**
 * 在实例列表里找这一行归属的实例：候选串等于实例名，或以「实例名-」开头（ECK 的 `<实例>-es-default`、
 * PVC `data-<实例>-0` 走属主/标签那条）。多个命中取名字最长的（防 `mw-a` 误吞 `mw-a-b`）。
 * 同命名空间优先：实例与其子资源一定同 ns；行没带 namespace 时不过滤。
 */
export function matchInstance<T extends { name: string; namespace: string }>(
  row: unknown,
  instances: readonly T[],
): T | null {
  if (managedByOf(row) !== 'MIDDLEWARE') return null
  const ns = (row as MetaLike | null)?.namespace
  const pool = ns ? instances.filter((i) => i.namespace === ns) : instances
  const cands = middlewareCandidates(row)
  let best: T | null = null
  for (const inst of pool) {
    const hit = cands.some((c) => c === inst.name || c.startsWith(inst.name + '-'))
    if (hit && (!best || inst.name.length > best.name.length)) best = inst
  }
  return best
}
