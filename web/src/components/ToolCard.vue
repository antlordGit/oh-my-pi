<script setup lang="ts">
import { computed, ref } from 'vue'

const props = defineProps<{
  toolName: string; args?: any; result?: any; isError?: boolean; status: 'running' | 'done'
}>()

const expanded = ref(false)

const argsText = computed(() => {
  if (!props.args) return ''
  try { return typeof props.args === 'string' ? props.args : JSON.stringify(props.args, null, 2) } catch { return String(props.args) }
})
const resultText = computed(() => {
  if (props.result == null) return ''
  if (typeof props.result === 'string') return props.result
  try { return JSON.stringify(props.result, null, 2) } catch { return String(props.result) }
})
const glyph = computed(() => {
  const n = props.toolName?.toLowerCase() || ''
  if (n.includes('bash') || n.includes('shell')) return '$'
  if (n.includes('write')) return '✎'
  if (n.includes('edit')) return '✱'
  if (n.includes('read')) return '◎'
  if (n.includes('grep') || n.includes('search')) return '⌕'
  if (n.includes('find') || n.includes('ls')) return '◇'
  return '◆'
})
const argSummary = computed(() => {
  if (!props.args) return ''
  if (typeof props.args === 'string') return props.args.slice(0, 80)
  if (props.args.command) return props.args.command
  if (props.args.path) return props.args.path
  if (props.args.pattern) return props.args.pattern
  if (props.args.query) return props.args.query
  try {
    const keys = Object.keys(props.args).filter(k => !k.startsWith('_'))
    if (keys.length) return `${keys[0]}: ${String(props.args[keys[0]]).slice(0, 60)}`
  } catch {}
  return ''
})
</script>

<template>
  <div :class="['tool', `tool--${status}`, { 'tool--error': isError }]">
    <header class="tool-head" @click="expanded = !expanded">
      <span class="tool-glyph">{{ glyph }}</span>
      <span class="tool-name mono">{{ toolName }}</span>
      <span class="tool-summary mono">{{ argSummary }}</span>
      <span class="tool-status">
        <span v-if="status === 'running'" class="dot-run"></span>
        <span v-else-if="isError" class="badge badge-err mono">失败</span>
        <span v-else class="badge badge-ok mono">完成</span>
      </span>
      <span class="tool-caret mono">{{ expanded ? '▾' : '▸' }}</span>
    </header>

    <transition name="expand">
      <div v-if="expanded" class="tool-body">
        <div v-if="argsText" class="section">
          <div class="section-label mono">参数</div>
          <pre class="section-pre mono">{{ argsText }}</pre>
        </div>
        <div v-if="resultText && status === 'done'" class="section">
          <div class="section-label mono">结果</div>
          <pre class="section-pre mono">{{ resultText }}</pre>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.tool {
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--bg-raised);
  margin: 6px 0;
  overflow: hidden;
  transition: border-color 200ms, box-shadow 200ms;
}
.tool--running { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
.tool--done    { border-color: var(--border); }
.tool--error   { border-color: var(--danger); box-shadow: 0 0 0 3px var(--danger-soft); }

.tool-head {
  display: grid;
  grid-template-columns: 24px auto 1fr auto auto;
  gap: 10px;
  align-items: center;
  padding: 10px 16px;
  cursor: pointer;
  user-select: none;
  transition: background 140ms;
}
.tool-head:hover { background: var(--bg-base); }

.tool-glyph { font-size: 14px; color: var(--text-muted); font-weight: 600; }
.tool--running .tool-glyph { color: var(--accent); }
.tool--done .tool-glyph { color: var(--good); }
.tool--error .tool-glyph { color: var(--danger); }

.tool-name {
  font-size: 11px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--text);
  font-weight: 500;
}
.tool-summary { font-size: 11px; color: var(--text-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.dot-run {
  width: 8px; height: 8px;
  background: var(--accent);
  border-radius: 50%;
  animation: pulse-glow 1.2s ease-in-out infinite;
}

.badge {
  font-size: 9px;
  letter-spacing: 0.12em;
  padding: 2px 8px;
  border-radius: 4px;
  border: 1px solid;
  font-weight: 600;
}
.badge-ok  { color: var(--good); border-color: var(--good); }
.badge-err { color: var(--danger); border-color: var(--danger); }

.tool-caret { font-size: 10px; color: var(--text-faint); }

/* Body */
.tool-body {
  border-top: 1px solid var(--border);
  padding: 12px 16px 14px;
  background: var(--bg-sunken);
}
.section { margin-top: 10px; }
.section:first-child { margin-top: 0; }
.section-label {
  font-size: 9px;
  letter-spacing: 0.14em;
  color: var(--text-muted);
  margin-bottom: 6px;
  text-transform: uppercase;
}
.section-pre {
  margin: 0;
  font-size: 12px;
  line-height: 1.55;
  color: var(--text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 320px;
  overflow-y: auto;
}

/* Expand transition */
.expand-enter-active, .expand-leave-active { transition: all 220ms ease; }
.expand-enter-from, .expand-leave-to { opacity: 0; max-height: 0; }
.expand-enter-to, .expand-leave-from { opacity: 1; max-height: 500px; }
</style>
