import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute } from 'vue-router'
import { clusterApi } from '@/api'
import type { K8sCluster } from '@/types'
import { resolveClusterSelection } from '@/utils/clusterSelection'
import { useQueryFilter } from './useQueryFilter'

export function useClusterListSelection() {
  const route = useRoute()
  const listRoute = route.name
  const requestedId = useQueryFilter('clusterId')
  const clusters = ref<K8sCluster[]>([])
  const loaded = ref(false)
  const selection = computed(() => loaded.value ? resolveClusterSelection(clusters.value, requestedId.value) : null)
  const clusterId = computed({
    get: () => selection.value?.cluster?.clusterId ?? '',
    set: (id: string) => { requestedId.value = id },
  })

  watch(selection, (value) => {
    if (!value || route.name !== listRoute) return
    if (value.unavailable) {
      ElMessage.warning(value.cluster
        ? `原集群已停用或不可访问，已切换到「${value.cluster.clusterName}」。`
        : '原集群已停用或不可访问，当前没有可选的集群。')
    }
    if (requestedId.value !== clusterId.value) requestedId.value = clusterId.value
  })

  onMounted(async () => {
    clusters.value = await clusterApi.list()
    loaded.value = true
  })

  return { clusters, clusterId }
}
