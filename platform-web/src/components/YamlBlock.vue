<script setup lang="ts">
/**
 * 只读 YAML 展示：语法高亮 + 行号槽。
 *
 * <p><b>为什么带行号</b>：K8s 排障是按行号沟通的（"第 43 行的 image"），而全站原来 27 处 YAML 都没有行号。
 * 也因此这里长行<b>不换行</b>（横向滚动）：折行会让续行缩不到行号槽右侧、行号与内容对不上，
 * 而且 kubectl 的输出本身也不折行 —— 不换行时两边的行号才对得上。
 *
 * <p><b>配色在哪</b>：本组件只负责产出 hljs 的语义 class（hljs-attr / hljs-string / …），
 * 颜色映射写在 `assets/theme.css`，全部派生自本仓 token，没有第二套色板，亮/暗主题自动成立。
 *
 * <p><b>为什么 v-html 是安全的</b>：hljs 的 `highlight()` 先对源码做 HTML 转义、再插 span，
 * 输出里不含来自 YAML 的裸标签。而这里的 YAML 内容包含用户可控的键值（对象名、注解值等），
 * 所以这条前提必须成立 —— 换成手拼 HTML 的实现就要重新论证一遍。
 */
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { writeClipboard } from '@/utils/clipboard'
import { CopyDocument, Select } from '@element-plus/icons-vue'
import hljs from 'highlight.js/lib/core'
import yaml from 'highlight.js/lib/languages/yaml'

// 只注册 yaml：core 不带语言包，整包 import 会把 190+ 种语言全打进 bundle
hljs.registerLanguage('yaml', yaml)

const props = withDefaults(
  defineProps<{
    /** YAML 文本（后端原样返回的字符串） */
    yaml: string
    /** 可视区最大高度，超出纵向滚动（详情页 60vh，BGP 那几个原本是 70vh） */
    maxHeight?: string
  }>(),
  { maxHeight: '60vh' },
)

const html = computed(() =>
  // ignoreIllegals：畸形 YAML（例如 Secret 里塞了非 YAML 文本）不该让整个抽屉抛错，退化成不高亮也比崩了好
  hljs.highlight(props.yaml ?? '', { language: 'yaml', ignoreIllegals: true }).value,
)

/** 行号槽文本：末行以 \n 结尾时不额外算一行（否则每份 YAML 末尾都多一个空行号） */
const lineNumbers = computed(() => {
  const lines = (props.yaml ?? '').split('\n')
  if (lines.length > 1 && lines[lines.length - 1] === '') lines.pop()
  return lines.map((_, i) => i + 1).join('\n')
})

// ---------------- 复制 ----------------

const copied = ref(false)
let resetTimer: ReturnType<typeof setTimeout> | undefined

/** 复制的是 props.yaml（原始文本），不是 DOM —— 行号槽不会被带进去 */
async function copyYaml(): Promise<void> {
  const ok = await writeClipboard(props.yaml ?? '')
  if (!ok) {
    ElMessage.warning('当前浏览器不允许自动复制，请手动选中文本复制')
    return
  }
  copied.value = true
  ElMessage.success('已复制 YAML')
  clearTimeout(resetTimer)
  resetTimer = setTimeout(() => (copied.value = false), 1500)
}
</script>

<template>
  <div class="yaml-view" :style="{ maxHeight }">
    <div class="yaml-scroll">
      <pre class="yaml-gutter" aria-hidden="true">{{ lineNumbers }}</pre>
      <pre class="yaml-code"><code class="hljs" v-html="html" /></pre>
    </div>
    <!-- 放在滚动容器之外：代码横竖滚动时它都钉在块右上角 -->
    <button type="button" class="yaml-copy" :title="copied ? '已复制' : '复制 YAML'" @click="copyYaml">
      <el-icon><component :is="copied ? Select : CopyDocument" /></el-icon>
      <span>{{ copied ? '已复制' : '复制' }}</span>
    </button>
  </div>
</template>

<style scoped>
/* 外层只负责外形与圆角裁剪；滚动交给里面的 .yaml-scroll ——
   复制按钮要能脱离滚动（否则代码一横滚它就跟着跑），故不能把按钮放进滚动容器里 */
.yaml-view {
  position: relative;
  width: 100%;
  border-radius: 8px;
  background: var(--panel-hover);
  border: 1px solid var(--border);
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-2);
  overflow: hidden;
}
.yaml-scroll {
  display: flex;
  align-items: stretch;
  /* 继承外层的 max-height（内联 style 给的 60vh/70vh），让滚动发生在这里而不是外层 */
  max-height: inherit;
  overflow: auto;
}
/* 行号槽：横向滚动时钉在左侧（sticky + 与块同底色，遮住滑过的代码），纵向随内容一起滚 */
.yaml-gutter {
  position: sticky;
  left: 0;
  z-index: 1;
  flex: none;
  margin: 0;
  padding: 14px 10px 14px 14px;
  border-right: 1px solid var(--border);
  background: var(--panel-hover);
  color: var(--text-3);
  text-align: right;
  user-select: none;
}
.yaml-code {
  flex: none;
  margin: 0;
  /* 右侧留出复制按钮的通道（横滚到底时最长行也不会钻到按钮底下） */
  padding: 14px 76px 14px 14px;
  /* 不换行：见组件头注释 */
  white-space: pre;
}
.yaml-code code {
  font-family: inherit;
}
/* 复制按钮：常态安静（是内容上的工具，不是主角），hover/聚焦时才亮起来 */
.yaml-copy {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  border-radius: 6px;
  border: 1px solid var(--border-strong);
  background: var(--panel);
  box-shadow: var(--shadow-panel);
  color: var(--text-3);
  font-family: inherit;
  font-size: 12px;
  line-height: 1.5;
  cursor: pointer;
  opacity: 0.75;
  transition: opacity 0.15s, color 0.15s, border-color 0.15s;
}
.yaml-copy:hover {
  opacity: 1;
  color: var(--accent);
  border-color: var(--accent);
}
.yaml-copy:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 1px;
  opacity: 1;
}
</style>
