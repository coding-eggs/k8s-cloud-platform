import { ref } from 'vue'
import { pipelineApi } from '@/api'
import type { BuildPreset, PipelineNodeField, PipelineNodeType } from '@/types'

/**
 * 内置节点的元数据（表单 schema + 语言预设）。
 *
 * ⚠️ **这是「加一个节点不用改前端」的落点**：属性面板完全由 `fields` 渲染，
 * 加字段、加语言都只动后端。所以这里只做「拉一次、缓存住、按 type 查」。
 *
 * 拉不到时**不抛**：节点面板空着比整页报错好；保存前的那次 preview 会把真正的问题暴露出来。
 */
const types = ref<PipelineNodeType[]>([])
let inflight: Promise<void> | null = null

export function usePipelineNodeTypes() {
  function load(): Promise<void> {
    if (!inflight) {
      inflight = pipelineApi
        .nodeTypes()
        .then((r) => {
          types.value = r ?? []
        })
        .catch(() => {
          inflight = null // 失败允许重试
        })
    }
    return inflight
  }

  function byType(t: string): PipelineNodeType | undefined {
    return types.value.find((x) => x.type === t)
  }

  /** 节点的展示名：节点类型没有单独的 label 字段，就用 type 的第一个字段的 label 组不出来的 ——
   *  统一在 UI 上给三个内置 type 一个中文名，未知 type 直接显示 type。 */
  function labelOf(t: string): string {
    const known: Record<string, string> = { git: 'git 克隆', shell: '命令', image: '构建并推送镜像' }
    return known[t] ?? t
  }

  /** SELECT 字段的候选值（`preset` 那种），供属性面板用 */
  function optionsOf(field: PipelineNodeField): { value: string; label: string }[] {
    return field.options ?? []
  }

  /** 语言预设：只是默认值，选中后把 image / command 两个框填上 */
  function presetOf(key: string): BuildPreset | undefined {
    return byType('shell')?.presets.find((p) => p.key === key)
  }

  return { types, load, byType, labelOf, optionsOf, presetOf }
}
