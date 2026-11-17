import type { PipelineSourceRef } from '@/types'

/**
 * 源码信息的**文案**（触发对话框与运行详情共用）。
 *
 * <p>两处说的是同一件事 —— 「这个 git 节点会构建/构建了哪个提交、相对什么比、改了多少」——
 * 文案分叉过一次就会变成两种口径（一处说「较上次成功」，另一处说「上次成功之后」），
 * 所以统一放这里。
 */

/** `https://gitlab.x/group/repo.git` → `group/repo`（一眼看出是哪个仓库，不用读整条 URL） */
export function repoShort(repo: string): string {
  const parts = (repo ?? '').replace(/\/+$/, '').replace(/\.git$/, '').split('/')
  return parts.length <= 1 ? repo : parts.slice(-2).join('/')
}

/** 分支/标签那一栏：默认分支与「已钉住」要说人话 */
export function refText(s: PipelineSourceRef): string {
  if (s.kind === 'default') return '默认分支'
  if (s.kind === 'pinned') return '已钉住的提交'
  if (s.kind === 'tag') return `标签 ${s.revision ?? ''}`
  return `分支 ${s.revision ?? ''}`
}

/** 与上次成功运行的对比：**相同**比「较上次 +N」更值得强调（那意味着这次没有新代码可构建） */
export function baseText(s: PipelineSourceRef): string {
  if (!s.baseSha) return '首次运行'
  if (s.sha && s.baseSha === s.sha) return '与上次成功构建相同'
  if (s.baseKind === 'parent') return '较上一提交'
  return `较上次成功 ${s.baseSha.slice(0, 7)}`
}

/** 变更规模：`3 个文件 +42 −7`；没有清单（还没点「查看变更」）时回 null */
export function diffSummary(s: PipelineSourceRef): string | null {
  if (!s.files || s.files.length === 0) return null
  const count = s.fileCount ?? s.files.length
  return `${count} 个文件 +${s.additions ?? 0} −${s.deletions ?? 0}`
}
