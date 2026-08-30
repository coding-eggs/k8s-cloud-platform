<script setup lang="ts">
import type { Lifecycle, LifecycleHandler } from '@/types/workload'

type LifeAction = 'none' | 'exec' | 'httpGet' | 'sleep'
type Slot = 'postStart' | 'preStop'

const model = defineModel<Lifecycle>()

/** A2：以「哪个子对象存在」判定当前动作（空 handler 无动作 = 未配置） */
function actionOf(h?: LifecycleHandler | null): LifeAction {
  if (h?.exec) return 'exec'
  if (h?.httpGet) return 'httpGet'
  if (h?.sleep) return 'sleep'
  return 'none'
}

/** 切换动作：清空其它子对象，只保留选中项（A2 互斥） */
function setAction(slot: Slot, a: LifeAction): void {
  const next: Lifecycle = { ...(model.value ?? {}) }
  if (a === 'none') next[slot] = null
  else if (a === 'exec') next[slot] = { exec: { command: [] }, httpGet: null, sleep: null }
  else if (a === 'httpGet') next[slot] = { exec: null, httpGet: { port: '', path: '', scheme: 'HTTP' }, sleep: null }
  else next[slot] = { exec: null, httpGet: null, sleep: { seconds: null } }
  model.value = next
}

/** exec command：textarea 按行/逗号切分 → string[] */
function cmdText(slot: Slot): string {
  return model.value?.[slot]?.exec?.command.join('\n') ?? ''
}

function setCmd(slot: Slot, text: string): void {
  const command = text.split(/[\n,]/).map((s) => s.trim()).filter(Boolean)
  const next: Lifecycle = { ...(model.value ?? {}) }
  next[slot] = { exec: { command }, httpGet: null, sleep: null }
  model.value = next
}
</script>

<template>
  <div class="life-editor">
    <div class="life-block">
      <div class="life-head">
        <span class="life-title">postStart</span>
        <el-radio-group :model-value="actionOf(model?.postStart)" @update:model-value="(a: LifeAction) => setAction('postStart', a)">
          <el-radio value="none">无</el-radio>
          <el-radio value="exec">exec</el-radio>
          <el-radio value="httpGet">httpGet</el-radio>
          <el-radio value="sleep">sleep</el-radio>
        </el-radio-group>
      </div>
      <div v-if="model?.postStart?.exec" class="life-fields">
        <el-input :model-value="cmdText('postStart')" type="textarea" :rows="2" placeholder="命令，每行一个（或逗号分隔）" @update:model-value="(t: string) => setCmd('postStart', t)" />
      </div>
      <div v-else-if="model?.postStart?.httpGet" class="life-fields">
        <el-input v-model="model.postStart.httpGet.port" placeholder="端口（数字或命名端口）" style="width: 150px" />
        <el-input v-model="model.postStart.httpGet.path" placeholder="路径（如 /healthz）" style="width: 180px" />
        <el-select v-model="model.postStart.httpGet.scheme" style="width: 110px">
          <el-option label="HTTP" value="HTTP" />
          <el-option label="HTTPS" value="HTTPS" />
        </el-select>
      </div>
      <div v-else-if="model?.postStart?.sleep" class="life-fields">
        <span class="life-label">seconds</span>
        <el-input-number v-model="model.postStart.sleep.seconds" :min="0" controls-position="right" />
      </div>
    </div>

    <div class="life-block">
      <div class="life-head">
        <span class="life-title">preStop</span>
        <el-radio-group :model-value="actionOf(model?.preStop)" @update:model-value="(a: LifeAction) => setAction('preStop', a)">
          <el-radio value="none">无</el-radio>
          <el-radio value="exec">exec</el-radio>
          <el-radio value="httpGet">httpGet</el-radio>
          <el-radio value="sleep">sleep</el-radio>
        </el-radio-group>
      </div>
      <div v-if="model?.preStop?.exec" class="life-fields">
        <el-input :model-value="cmdText('preStop')" type="textarea" :rows="2" placeholder="命令，每行一个（或逗号分隔）" @update:model-value="(t: string) => setCmd('preStop', t)" />
      </div>
      <div v-else-if="model?.preStop?.httpGet" class="life-fields">
        <el-input v-model="model.preStop.httpGet.port" placeholder="端口（数字或命名端口）" style="width: 150px" />
        <el-input v-model="model.preStop.httpGet.path" placeholder="路径（如 /healthz）" style="width: 180px" />
        <el-select v-model="model.preStop.httpGet.scheme" style="width: 110px">
          <el-option label="HTTP" value="HTTP" />
          <el-option label="HTTPS" value="HTTPS" />
        </el-select>
      </div>
      <div v-else-if="model?.preStop?.sleep" class="life-fields">
        <span class="life-label">seconds</span>
        <el-input-number v-model="model.preStop.sleep.seconds" :min="0" controls-position="right" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.life-editor { width: 100% }
.life-block { margin-bottom: 8px }
.life-head { display: flex; gap: 8px; align-items: center; margin-bottom: 8px }
.life-title { width: 90px; flex-shrink: 0; font-size: 12px; color: var(--el-text-color-secondary) }
.life-fields { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; padding-left: 98px }
.life-label { font-size: 12px; color: var(--el-text-color-secondary) }
</style>
