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
        <span v-if="status === 'running'" class="status-pill running">
          <span class="live-dot"></span>
          <span class="mono">执行中</span>
        </span>
        <span v-else-if="isError" class="status-pill err mono">失败</span>
        <span v-else class="status-pill ok mono">完成</span>
      </span>
      <span class="tool-caret mono">{{ expanded ? '−' : '+' }}</span>
    </header>

    <transition name="expand">
      <div v-if="expanded" class="tool-body">
        <div v-if="argsText" class="section">
          <div class="section-label mono">参数 · arguments</div>
          <pre class="section-pre mono">{{ argsText }}</pre>
        </div>
        <div v-if="resultText && status === 'done'" class="section">
          <div class="section-label mono">结果 · result</div>
          <pre class="section-pre mono">{{ resultText }}</pre>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.tool {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  margin: 6px 0;
  overflow: hidden;
  transition: border-color var(--dur-fast) var(--ease-out), background var(--dur-fast) var(--ease-out);
}
.tool--running { border-color: var(--brand); background: var(--brand-soft); }
.tool--done { border-color: var(--border); }
.tool--error { border-color: var(--danger); background: var(--danger-soft); }

.tool-head {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 10px 16px;
  cursor: pointer;
  user-select: none;
  transition: background var(--dur-fast) var(--ease-out);
}
.tool-glyph {
  flex: 0 0 22px;
  font-size: 13px;
  color: var(--ink-mute);
  font-weight: 600;
  text-align: center;
}
.tool-name { flex: 0 0 auto; }
.tool-status { flex: 1; min-width: 0; }
.tool-badge { flex: 0 0 auto; }
.tool-toggle { flex: 0 0 auto; }
.tool--running .tool-glyph { color: var(--brand); }
.tool--done .tool-glyph { color: var(--good); }
.tool--error .tool-glyph { color: var(--danger); }

.tool-name {
  font-size: 12px;
  color: var(--ink);
  font-weight: 600;
}
.tool-summary {
  font-size: 11px;
  color: var(--ink-mute);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  padding: 3px 10px;
  border-radius: var(--radius-pill);
  font-weight: 500;
}
.status-pill.running { background: var(--brand); color: var(--ink-invert); }
.status-pill.running .live-dot { background: var(--ink-invert); }
.status-pill.ok { background: var(--good-soft); color: var(--good); }
.status-pill.err { background: var(--danger-soft); color: var(--danger); }

.tool-caret {
  font-size: 14px;
  color: var(--ink-mute);
  width: 16px;
  text-align: center;
  font-family: var(--font-mono);
}

/* Body */
.tool-body {
  border-top: 1px solid var(--border);
  padding: 12px 16px 14px;
  background: var(--surface-soft);
}
.section { margin-top: 10px; }
.section:first-child { margin-top: 0; }
.section-label {
  font-size: 11px;
  color: var(--ink-mute);
  margin-bottom: 6px;
}
.section-pre {
  margin: 0;
  font-size: 12px;
  line-height: 1.55;
  color: var(--ink-2);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 320px;
  overflow-y: auto;
}

/* Expand transition */
.expand-enter-active, .expand-leave-active {
  transition: all 240ms var(--ease-out);
  overflow: hidden;
}
.expand-enter-from, .expand-leave-to {
  opacity: 0;
  max-height: 0;
}
.expand-enter-to, .expand-leave-from {
  opacity: 1;
  max-height: 600px;
}
</style>