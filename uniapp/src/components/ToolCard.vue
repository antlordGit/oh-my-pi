<script setup lang="ts">
import { computed, ref } from 'vue'

const props = defineProps<{
  toolName: string
  args?: any
  result?: any
  isError?: boolean
  status: 'running' | 'done'
}>()

const expanded = ref(false)

const argsText = computed(() => {
  if (!props.args) return ''
  try {
    return typeof props.args === 'string'
      ? props.args
      : JSON.stringify(props.args, null, 2)
  } catch {
    return String(props.args)
  }
})

const resultText = computed(() => {
  if (props.result == null) return ''
  if (typeof props.result === 'string') return props.result
  try {
    return JSON.stringify(props.result, null, 2)
  } catch {
    return String(props.result)
  }
})

const glyph = computed(() => {
  const n = props.toolName?.toLowerCase() || ''
  if (n.includes('bash') || n.includes('shell')) return '$'
  if (n.includes('write')) return '✎'
  if (n.includes('edit')) return '✱'
  if (n.includes('read')) return '◎'
  if (n.includes('grep') || n.includes('search')) return '⌕'
  return '◆'
})

const argSummary = computed(() => {
  if (!props.args) return ''
  if (typeof props.args === 'string') return props.args.slice(0, 80)
  if (props.args.command) return props.args.command.slice(0, 80)
  if (props.args.path) return props.args.path
  if (props.args.pattern) return props.args.pattern
  if (props.args.query) return props.args.query
  try {
    const keys = Object.keys(props.args).filter((k) => !k.startsWith('_'))
    if (keys.length)
      return `${keys[0]}: ${String(props.args[keys[0]]).slice(0, 60)}`
  } catch {}
  return ''
})
</script>

<template>
  <view :class="['tool', `tool--${status}`, { 'tool--error': isError }]">
    <view class="tool-head" @click="expanded = !expanded">
      <text class="tool-glyph">{{ glyph }}</text>
      <text class="tool-name">{{ toolName }}</text>
      <text class="tool-summary">{{ argSummary }}</text>
      <view class="tool-status">
        <text v-if="status === 'running'" class="st running">执行中</text>
        <text v-else-if="isError" class="st err">失败</text>
        <text v-else class="st done">完成</text>
      </view>
      <text class="tool-caret">{{ expanded ? '−' : '+' }}</text>
    </view>

    <view v-if="expanded" class="tool-body">
      <view v-if="argsText" class="section">
        <text class="section-label">参数</text>
        <view class="section-pre">{{ argsText }}</view>
      </view>
      <view v-if="resultText && status === 'done'" class="section">
        <text class="section-label">结果</text>
        <view class="section-pre">{{ resultText }}</view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.tool {
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--surface-soft);
  margin: 6px 0;
  overflow: hidden;
}

.tool--running {
  border-color: var(--brand-soft);
  background: var(--brand-soft);
}

.tool--error {
  border-color: rgba(255, 80, 80, 0.2);
  background: rgba(255, 80, 80, 0.06);
}

.tool-head {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 6px 10px;
}

.tool-glyph {
  font-size: 11px;
  color: var(--ink-faint);
  font-weight: 600;
  text-align: center;
  width: 16px;
}

.tool--running .tool-glyph {
  color: var(--brand);
}

.tool--done .tool-glyph {
  color: var(--good);
}

.tool--error .tool-glyph {
  color: var(--danger);
}

.tool-name {
  font-size: 12px;
  color: var(--ink-2);
  font-weight: 500;
}

.tool-summary {
  flex: 1;
  font-size: 11px;
  color: var(--ink-faint);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.tool-status {
  flex-shrink: 0;
}

.st {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 3px;
  font-weight: 500;
}

.st.running {
  background: var(--brand);
  color: #fff;
}

.st.done {
  background: var(--good-soft);
  color: var(--good);
}

.st.err {
  background: rgba(255, 80, 80, 0.1);
  color: var(--danger);
}

.tool-caret {
  font-size: 12px;
  color: var(--ink-faint);
  margin-left: 4px;
}

.tool-body {
  border-top: 1px solid var(--border);
  padding: 8px 10px;
}

.section {
  margin-top: 8px;
}

.section:first-child {
  margin-top: 0;
}

.section-label {
  font-size: 10px;
  color: var(--ink-faint);
  margin-bottom: 4px;
  letter-spacing: 0.3px;
}

.section-pre {
  margin: 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--ink-mute);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 200px;
  overflow-y: auto;
}
</style>