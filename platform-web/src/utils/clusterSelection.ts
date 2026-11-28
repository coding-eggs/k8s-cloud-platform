interface ClusterOption {
  clusterId: string
  clusterName: string
  enabled: number
}

export function resolveClusterSelection<T extends ClusterOption>(clusters: readonly T[], requestedId: string) {
  const selected = clusters.find((cluster) => cluster.clusterId === requestedId && cluster.enabled === 1)
  return {
    cluster: selected ?? clusters.find((cluster) => cluster.enabled === 1),
    unavailable: Boolean(requestedId) && !selected,
  }
}
