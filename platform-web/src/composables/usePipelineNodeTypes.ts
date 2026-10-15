import { ref } from 'vue'
import { pipelineApi } from '@/api'
import type { BuildPreset, PipelineNodeField, PipelineNodeType } from '@/types'

/**
 * 内置节点的元数据（表单 schema + 语言预设 + **长相**）。
 *
 * ⚠️ **这是「加一个节点不用改前端」的落点**：属性面板由 `fields` 渲染，
 * 画布上的卡片由 `icon` / `accent` / `summaryFields` 决定，加字段、加语言、加节点都只动后端。
 * 所以这里只做「拉一次、缓存住、按 type 查」。
 *
 * 拉不到时**不抛**：节点面板空着比整页报错好；保存前的那次 render 会把真正的问题暴露出来。
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

  /** 展示名：后端 schema 说了算；schema 还没拉到时退回 type 本身 */
  function labelOf(t: string): string {
    return byType(t)?.label ?? t
  }

  function iconOf(t: string): string {
    return byType(t)?.icon ?? 'Operation'
  }

  /** 配色**语义名**（success / accent / warning / info / danger），到 CSS 里才落到具体色值 */
  function accentOf(t: string): string {
    return byType(t)?.accent ?? 'info'
  }

  /**
   * 卡片副标题：按 schema 说的**字段**取值拼起来。
   *
   * 节点作者只说用哪几个字段，不发明摘要模板 —— 与「不发明模板 DSL」同一条边界（spec §2.2）。
   */
  function summaryOf(t: string, config?: Record<string, string>): string {
    const keys = byType(t)?.summaryFields ?? []
    if (!keys.length || !config) return ''
    return keys
      .map((k) => (config[k] ?? '').trim())
      .filter((v) => v.length > 0)
      .join(' · ')
  }

  /** SELECT 字段的候选值（`preset` 那种），供属性面板用 */
  function optionsOf(field: PipelineNodeField): { value: string; label: string }[] {
    return field.options ?? []
  }

  /** 语言预设：只是默认值，选中后把 image / command 两个框填上 */
  function presetOf(key: string): BuildPreset | undefined {
    return byType('shell')?.presets.find((p) => p.key === key)
  }

  return { types, load, byType, labelOf, iconOf, accentOf, summaryOf, optionsOf, presetOf }
}
