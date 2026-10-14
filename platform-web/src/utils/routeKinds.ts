import { grpcRouteApi, httpRouteApi, tcpRouteApi, tlsRouteApi, udpRouteApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import type {
  K8sGrpcRoute,
  K8sHttpRoute,
  K8sL4RouteRule,
  K8sRouteBackendRef,
  K8sTcpRoute,
  K8sTlsRoute,
  K8sUdpRoute,
} from '@/types'

/**
 * 「路由策略」的 5 种 kind 静态目录（列表页与编辑器共用）。
 *
 * <p>放这里而不是各自的页面里：kind 的**身份信息**（叫什么、有没有 hostnames、用哪几个码、怎么拉/怎么删、
 * 列表摘要怎么写）是同一份事实，页面只负责渲染。**注意**：这里只装"种类"的事实，不装表单 ——
 * 5 种 kind 的规则表单仍在各自的 components/route/*RouteForm.vue 里各写各的。
 */
export type RouteKind = 'httproute' | 'grpcroute' | 'tcproute' | 'tlsroute' | 'udproute'

/** 列表行：5 种 wire 类型 + 前端打上的判别字段（不依赖后端 DTO 是否回带 kind） */
export type RouteRow = (K8sHttpRoute | K8sGrpcRoute | K8sTcpRoute | K8sTlsRoute | K8sUdpRoute) & { _kind: RouteKind }

/** 三个既有上下文字段（与各处 ctx3 同形） */
export type RouteCtx = { tenantId: string; clusterId: string; namespace: string }

export interface RouteKindMeta {
  key: RouteKind
  /** 展示名，如 HTTPRoute */
  label: string
  /** 该 kind 有没有 hostnames 字段（编辑器外壳据此决定渲染与否；TCP/UDP 没有） */
  usesHostnames: boolean
  /** 逐 kind 拉取的短路码（apiCodes，不是 pageCodes） */
  listCode: string
  createCode: string
  updateCode: string
  list: (ctx: RouteCtx) => Promise<unknown[]>
  yaml: (name: string, ctx: RouteCtx) => Promise<string>
  remove: (name: string, ctx: RouteCtx) => Promise<void>
  /** 列表「摘要」列的 kind 相关文本 */
  summary: (row: RouteRow) => string
  /** 删除确认框里的后果说明（各 kind 不同） */
  deleteHint: string
}

/** backendRefs → `ns/name:port, …`；空则 `—` */
function backendsText(refs: K8sRouteBackendRef[] | null | undefined): string {
  const list = (refs ?? []).map((b) => `${b.namespace ? `${b.namespace}/` : ''}${b.name}:${b.port ?? '?'}`)
  return list.join(', ') || '—'
}

/** hostnames → `a.com, b.com`；空则 `全部主机名` */
function hostsText(hostnames: string[] | null | undefined): string {
  return (hostnames ?? []).length ? (hostnames ?? []).join(', ') : '全部主机名'
}

/** TCP/UDP 只有一条 rule，摘要取它的 backendRefs */
function l4Backends(rules: K8sL4RouteRule[] | null | undefined): string {
  return backendsText((rules ?? [])[0]?.backendRefs)
}

export const ROUTE_KINDS: RouteKindMeta[] = [
  {
    key: 'httproute',
    label: 'HTTPRoute',
    usesHostnames: true,
    listCode: apiCodes.httpRouteList,
    createCode: apiCodes.httpRouteCreate,
    updateCode: apiCodes.httpRouteUpdate,
    list: (ctx) => httpRouteApi.list(ctx),
    yaml: (name, ctx) => httpRouteApi.getYaml(name, ctx),
    remove: (name, ctx) => httpRouteApi.delete(name, ctx),
    summary: (row) => {
      const r = row as K8sHttpRoute
      return `${(r.rules ?? []).length} 条规则 · ${hostsText(r.hostnames)}`
    },
    deleteHint: '其匹配的流量将回落到该 Gateway 的其他路由或 404。',
  },
  {
    key: 'grpcroute',
    label: 'GRPCRoute',
    usesHostnames: true,
    listCode: apiCodes.grpcRouteList,
    createCode: apiCodes.grpcRouteCreate,
    updateCode: apiCodes.grpcRouteUpdate,
    list: (ctx) => grpcRouteApi.list(ctx),
    yaml: (name, ctx) => grpcRouteApi.getYaml(name, ctx),
    remove: (name, ctx) => grpcRouteApi.delete(name, ctx),
    summary: (row) => {
      const r = row as K8sGrpcRoute
      return `${(r.rules ?? []).length} 条规则 · ${hostsText(r.hostnames)}`
    },
    deleteHint: '其匹配的 gRPC 流量将回落到该 Gateway 的其他路由或失败。',
  },
  {
    key: 'tcproute',
    label: 'TCPRoute',
    usesHostnames: false,
    listCode: apiCodes.tcpRouteList,
    createCode: apiCodes.tcpRouteCreate,
    updateCode: apiCodes.tcpRouteUpdate,
    list: (ctx) => tcpRouteApi.list(ctx),
    yaml: (name, ctx) => tcpRouteApi.getYaml(name, ctx),
    remove: (name, ctx) => tcpRouteApi.delete(name, ctx),
    summary: (row) => l4Backends((row as K8sTcpRoute).rules),
    deleteHint: '该 TCP 端口将不再转发到任何后端。',
  },
  {
    key: 'tlsroute',
    label: 'TLSRoute',
    usesHostnames: true,
    listCode: apiCodes.tlsRouteList,
    createCode: apiCodes.tlsRouteCreate,
    updateCode: apiCodes.tlsRouteUpdate,
    list: (ctx) => tlsRouteApi.list(ctx),
    yaml: (name, ctx) => tlsRouteApi.getYaml(name, ctx),
    remove: (name, ctx) => tlsRouteApi.delete(name, ctx),
    summary: (row) => {
      const r = row as K8sTlsRoute
      return `SNI ${(r.hostnames ?? []).length ? (r.hostnames ?? []).join(', ') : '全部'}`
    },
    deleteHint: '按 SNI 命中的 TLS 连接将不再转发到任何后端。',
  },
  {
    key: 'udproute',
    label: 'UDPRoute',
    usesHostnames: false,
    listCode: apiCodes.udpRouteList,
    createCode: apiCodes.udpRouteCreate,
    updateCode: apiCodes.udpRouteUpdate,
    list: (ctx) => udpRouteApi.list(ctx),
    yaml: (name, ctx) => udpRouteApi.getYaml(name, ctx),
    remove: (name, ctx) => udpRouteApi.delete(name, ctx),
    summary: (row) => l4Backends((row as K8sUdpRoute).rules),
    deleteHint: '该 UDP 端口将不再转发到任何后端。',
  },
]

/** 取 kind 的身份信息；未知 key 抛错（调用方保证 key 来自 parseRouteKind / 目录本身） */
export function kindMeta(key: RouteKind): RouteKindMeta {
  const m = ROUTE_KINDS.find((k) => k.key === key)
  if (!m) throw new Error(`未知的 route 类型：${key}`)
  return m
}

/** 解析 `?kind=`：合法值返回它，非法/缺失返回 null（调用方回落默认） */
export function parseRouteKind(raw: unknown): RouteKind | null {
  return typeof raw === 'string' && ROUTE_KINDS.some((k) => k.key === raw) ? (raw as RouteKind) : null
}
