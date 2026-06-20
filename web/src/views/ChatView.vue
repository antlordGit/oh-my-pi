<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  getSession, getMessages, getState,
  prompt, abort as abortSession, wsUrl,
  type SessionSummary,
} from '@/api/session'
import { useMessage } from 'naive-ui'
import MessageBubble from '@/components/MessageBubble.vue'
import ToolCard from '@/components/ToolCard.vue'

const route = useRoute()
const router = useRouter()
const msg = useMessage()
const sessionId = computed(() => route.params.id as string)

const session = ref<SessionSummary | null>(null)
const state = ref<any>(null)
const input = ref('')
const sending = ref(false)
const isStreaming = ref(false)
const messagesEl = ref<HTMLElement | null>(null)
const showThinking = ref(true)

// ---- Unified timeline (ordered) ----
type TimelineItem =
  | { kind: 'thinking'; text: string; order: number }
  | { kind: 'text'; text: string; order: number }
  | { kind: 'toolcall'; id: string; name: string; args: any; status: 'running' | 'done'; result?: any; error?: boolean; order: number }
const timeline = ref<TimelineItem[]>([])
const turnLog = ref<{ role: 'user' | 'assistant'; timeline: TimelineItem[]; userText?: string }[]>([])
const history = ref<any[]>([])

let itemOrder = 0

// ---- History load ----
async function refresh() {
  try {
    session.value = await getSession(sessionId.value)
    try {
      const messages = await getMessages(sessionId.value)
      history.value = messages.messages || []
      turnLog.value = []
      // 索引 toolCall 的结果，方便后面合并
      const toolResults: Record<string, { result: any; error: boolean }> = {}
      for (const m of (messages.messages || [])) {
        if (m.role === 'tool' || m.role === 'toolResult') {
          // 收集 tool 结果，附加到对应 toolCall
          collectToolResults(m.content, toolResults)
        }
      }
      // 合并连续的 assistant + tool 消息为同一个 turn
      let currentAssistantTimeline: TimelineItem[] | null = null
      let order = 0
      const flush = () => {
        if (currentAssistantTimeline && currentAssistantTimeline.length > 0) {
          turnLog.value.push({ role: 'assistant', timeline: currentAssistantTimeline })
        }
        currentAssistantTimeline = null
      }
      for (const m of (messages.messages || [])) {
        if (m.role === 'user') {
          flush()
          turnLog.value.push({ role: 'user', timeline: [], userText: extractText(m.content) })
          order = 0
        } else if (m.role === 'assistant') {
          if (!currentAssistantTimeline) currentAssistantTimeline = []
          appendToTimeline(currentAssistantTimeline, m.content, toolResults, () => order++)
        }
        // 'tool' / 'toolResult' 已经合并到上面 toolCall 的 result，跳过
      }
      flush()
    } catch (e) {
      console.error('refresh failed', e)
    }
    try { state.value = await getState(sessionId.value) } catch {}
  } catch (e: any) { msg.error(e?.response?.data?.error || '加载失败') }
}

function collectToolResults(content: any, out: Record<string, { result: any; error: boolean }>) {
  if (!Array.isArray(content)) return
  for (const c of content) {
    if (c.type === 'toolResult' || c.type === 'tool_result') {
      const id = c.toolCallId || c.tool_call_id || c.id
      if (id) out[id] = { result: c.result ?? c.content ?? c.output, error: !!(c.isError || c.is_error) }
    }
  }
}

function appendToTimeline(items: TimelineItem[], content: any, toolResults: Record<string, { result: any; error: boolean }>, nextOrder: () => number) {
  if (!Array.isArray(content)) return
  for (const c of content) {
    if (c.type === 'thinking') {
      const t = c.thinking || ''
      if (t.trim()) items.push({ kind: 'thinking', text: t, order: nextOrder() })
    } else if (c.type === 'text') {
      const t = c.text || ''
      if (t.trim()) items.push({ kind: 'text', text: t, order: nextOrder() })
    } else if (c.type === 'toolCall' || c.type === 'tool_call' || c.type === 'toolcall') {
      const id = c.id || c.toolCallId || c.tool_call_id
      const name = c.name || c.toolName || c.tool_name
      const args = c.arguments ?? c.args ?? c.input
      const r = id ? toolResults[id] : undefined
      items.push({
        kind: 'toolcall',
        id: id || '',
        name: name || '',
        args,
        status: 'done',
        result: r?.result,
        error: r?.error,
        order: nextOrder(),
      })
    }
  }
}

function extractText(content: any): string {
  if (!content) return ''
  if (typeof content === 'string') return content
  if (Array.isArray(content)) {
    return content.filter((c: any) => c.type === 'text').map((c: any) => c.text).join('')
  }
  return ''
}

function buildTimeline(_content: any): TimelineItem[] {
  // legacy; replaced by appendToTimeline + collectToolResults during history load
  return []
}

// ---- WebSocket ----
function connectWs() {
  if (!sessionId.value || sessionId.value === 'undefined') return
  if (ws.value) try { ws.value.close() } catch {}
  const s = new WebSocket(wsUrl(sessionId.value))
  s.onmessage = (ev) => { let frame: any; try { frame = JSON.parse(ev.data) } catch { return }; handleFrame(frame) }
  s.onclose = () => { setTimeout(connectWs, 1500) }
  ws.value = s
}

function handleFrame(frame: any) {
  switch (frame.type) {
    case 'message_update': {
      const evt = frame.assistantMessageEvent
      if (evt?.type === 'text_delta') {
        const last = timeline.value.length > 0 ? timeline.value[timeline.value.length - 1] : null
        if (last?.kind === 'text') { last.text += evt.delta || '' }
        else { timeline.value.push({ kind: 'text', text: evt.delta || '', order: itemOrder++ }) }
      } else if (evt?.type === 'thinking_delta') {
        const last = timeline.value.length > 0 ? timeline.value[timeline.value.length - 1] : null
        if (last?.kind === 'thinking') { last.text += evt.delta || '' }
        else { timeline.value.push({ kind: 'thinking', text: evt.delta || '', order: itemOrder++ }) }
      } else if (evt?.type === 'toolcall') {
        timeline.value.push({ kind: 'toolcall', id: evt.id, name: evt.name, args: evt.arguments, status: 'running', order: itemOrder++ })
        // Also update the toolCall in-place so tool_execution_end can patch it
        toolCallById[evt.id] = timeline.value[timeline.value.length - 1] as any
      }
      break
    }
    case 'tool_execution_start': {
      if (!toolCallById[frame.toolCallId]) {
        timeline.value.push({ kind: 'toolcall', id: frame.toolCallId, name: frame.toolName, args: frame.arguments, status: 'running', order: itemOrder++ })
        toolCallById[frame.toolCallId] = timeline.value[timeline.value.length - 1] as any
      }
      break
    }
    case 'tool_execution_end': {
      const t = toolCallById[frame.toolCallId]
      if (t) { t.status = 'done'; t.result = frame.result; t.error = frame.isError }
      break
    }
    case 'agent_start':
      isStreaming.value = true
      timeline.value = []
      itemOrder = 0
      break
    case 'agent_end':
      isStreaming.value = false
      // snapshot
      if (timeline.value.length) {
        turnLog.value.push({ role: 'assistant', timeline: [...timeline.value] })
        timeline.value = []
      }
      break
    case 'message_end':
      // intermediate (within multi-step tool use)
      break
    case 'session_info_update':
      if (frame.title && session.value) session.value.title = frame.title
      break
  }
  scrollToBottom()
}
const toolCallById: Record<string, TimelineItem & { id: string; name: string; status: 'running'|'done'; result?: any; error?: boolean }> = {}

const ws = ref<WebSocket | null>(null)
function scrollToBottom() { nextTick(() => { if (messagesEl.value) messagesEl.value.scrollTop = messagesEl.value.scrollHeight }) }

async function send() {
  if (!input.value.trim() || sending.value) return
  const text = input.value; input.value = ''; sending.value = true
  turnLog.value.push({ role: 'user', timeline: [], userText: text })
  timeline.value = []; itemOrder = 0
  Object.keys(toolCallById).forEach(k => delete toolCallById[k])
  scrollToBottom()
  try { await prompt(sessionId.value, text) }
  catch (e: any) { msg.error(e?.response?.data?.error || '发送失败') }
  finally { sending.value = false }
}

async function doAbort() { try { await abortSession(sessionId.value) } catch {} }

watch(() => timeline.value.length, scrollToBottom)
onMounted(async () => { await refresh(); connectWs() })
onUnmounted(() => { if (ws.value) try { ws.value.close() } catch {} })

const turnIndex = (i: number) => String(i + 1).padStart(2, '0')
</script>

<template>
  <div class="chat">
    <!-- Top bar -->
    <header class="topbar">
      <div class="topbar-l">
        <button class="back-btn btn-ghost" style="padding:5px 12px;font-size:10px" @click="router.push('/sessions')">← 返回</button>
        <div>
          <div class="serial dim">{{ sessionId.slice(0, 8) }}</div>
          <h1 class="session-title serif">{{ session?.title || '未命名会话' }}</h1>
        </div>
      </div>
      <div class="topbar-r">
        <span class="pill">
          <span class="pill-k serial">仓库</span>
          <span class="pill-v mono">{{ session?.repoId || '—' }}</span>
        </span>
        <span class="pill" :class="{ live: isStreaming }">
          <span class="dot" :class="{ on: isStreaming }"></span>
          <span class="pill-v mono">{{ isStreaming ? '生成中' : '空闲' }}</span>
        </span>
        <button class="btn-mini-danger" :disabled="!isStreaming" @click="doAbort">中断</button>
      </div>
    </header>

    <main class="messages" ref="messagesEl">
      <!-- Welcome -->
      <div v-if="!turnLog.length && !timeline.length" class="welcome fade-up">
        <span class="welcome-icon">§</span>
        <h2 class="welcome-title serif">一张<br /><em>空白纸.</em></h2>
        <p class="welcome-dek">描述你的需求，AI 代理在工作目录 <code>{{ session?.repoId || 'workspace' }}</code> 中执行编辑、读取与命令。</p>
      </div>

      <!-- History turns -->
      <div v-for="(t, i) in turnLog" :key="'turn-' + i" class="turn fade-up" :style="{ animationDelay: i * 25 + 'ms' }">
        <div class="turn-gutter">
          <span class="turn-num mono" :class="{ accent: t.role === 'assistant' }">{{ turnIndex(i) }}</span>
        </div>
        <div class="turn-body">
          <div class="turn-label serial" :class="{ accent: t.role === 'assistant' }">
            {{ t.role === 'user' ? '操作员 · 第 ' + turnIndex(i) + ' 轮' : '代理 · 回复 ' + turnIndex(i) }}
          </div>
          <!-- User: just show bubble -->
          <MessageBubble v-if="t.role === 'user'" role="user" :text="t.userText || ''" />
          <!-- Assistant: interleave timeline items in order -->
          <template v-if="t.role === 'assistant' && Array.isArray(t.timeline) && t.timeline.length">
            <template v-for="item in t.timeline" :key="item.order">
              <details v-if="item.kind === 'thinking'" class="thinking-block" open>
                <summary class="mono">▸ 思考过程</summary>
                <pre class="thinking-text mono">{{ item.text }}</pre>
              </details>
              <MessageBubble v-else-if="item.kind === 'text'" role="assistant" :text="item.text" />
              <ToolCard v-else-if="item.kind === 'toolcall'" :tool-name="item.name" :args="item.args" :result="item.result" :is-error="item.error" :status="item.status" />
            </template>
          </template>
        </div>
      </div>

      <!-- Live streaming turn -->
      <div v-if="isStreaming || timeline.length" class="turn turn-live fade-up">
        <div class="turn-gutter">
          <span class="turn-num mono accent live-dot">{{ turnIndex(turnLog.filter(t => t.role === 'assistant').length) }}</span>
        </div>
        <div class="turn-body">
          <div class="turn-label">
            <span class="serial accent">代理 · 实时</span>
            <button v-if="timeline.some(i => i.kind === 'thinking')" class="toggle-think mono" @click="showThinking = !showThinking">
              {{ showThinking ? '收起思考' : '展开思考' }}
            </button>
          </div>
          <template v-for="item in timeline" :key="'live-' + item.order">
            <details v-if="item.kind === 'thinking'" class="thinking-block" :open="showThinking">
              <summary class="mono">▸ 思考过程</summary>
              <pre class="thinking-text mono">{{ item.text }}</pre>
            </details>
            <div v-else-if="item.kind === 'text'" class="streaming-block">
              <MessageBubble role="assistant" :text="item.text" />
              <span v-if="item.order === timeline.length - 1" class="cursor-blink"></span>
            </div>
            <ToolCard v-else-if="item.kind === 'toolcall'" :tool-name="item.name" :args="item.args" :result="item.result" :is-error="item.error" :status="item.status" />
          </template>
        </div>
      </div>
    </main>

    <!-- Composer -->
    <footer class="composer">
      <div class="composer-card">
        <textarea v-model="input" class="composer-input mono" rows="3" placeholder="描述你的需求…"
                  @keydown.enter.exact.prevent="send" :disabled="sending" />
        <div class="composer-foot">
          <span class="serial dim">↵ 发送 · ⇧↵ 换行 · {{ input.length }} 字符</span>
          <button class="btn-primary" :disabled="sending || !input.trim()" @click="send">
            {{ sending ? '提交中…' : '发送' }}
          </button>
        </div>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.chat {
  display: grid;
  grid-template-rows: auto 1fr auto;
  height: 100vh;
  max-width: 880px;
  margin: 0 auto;
  padding: 16px 32px 20px;
}

/* Top bar */
.topbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0 14px;
  gap: 20px;
}
.topbar-l { display: flex; align-items: center; gap: 16px; }
.serial.dim { color: var(--text-muted); }
.session-title {
  font-size: 22px;
  font-weight: 380;
  font-variation-settings: "opsz" 144, "SOFT" 30;
  letter-spacing: -0.015em;
}

.topbar-r { display: flex; align-items: center; gap: 12px; }

.pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 4px 14px;
  border: 1px solid var(--border);
  border-radius: 20px;
  background: var(--bg-base);
}
.pill.live {
  border-color: var(--accent);
  background: var(--accent-soft);
}
.pill-k { color: var(--text-muted); }
.pill-v { font-size: 11px; color: var(--text-secondary); }
.pill.live .pill-v { color: var(--accent); font-weight: 600; }

.dot {
  width: 6px; height: 6px;
  border-radius: 50%;
  background: var(--border);
  transition: all 200ms;
}
.dot.on {
  background: var(--accent);
  animation: pulse-glow 2s ease-in-out infinite;
}

/* Messages */
.messages {
  overflow-y: auto;
  padding: 24px 0 16px;
  scroll-behavior: smooth;
}

/* Welcome */
.welcome {
  padding: 48px 0;
  max-width: 460px;
}
.welcome-icon {
  font-family: var(--font-display);
  font-size: 72px;
  color: var(--border);
  font-weight: 300;
  line-height: 1;
}
.welcome-title {
  font-size: 52px;
  line-height: 0.96;
  font-weight: 300;
  font-variation-settings: "opsz" 144, "SOFT" 30;
  margin: 12px 0 16px;
}
.welcome-title em {
  font-style: italic;
  color: var(--accent);
  font-variation-settings: "opsz" 144, "SOFT" 100, "WONK" 1;
}
.welcome-dek { font-size: 16px; color: var(--text-secondary); line-height: 1.6; }
.welcome-dek code {
  color: var(--accent);
  background: var(--accent-soft);
  padding: 1px 7px;
  border-radius: 4px;
  font-size: 13px;
}

/* Turns */
.turn {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  gap: 16px;
  margin-bottom: 32px;
}
.turn-gutter { display: flex; flex-direction: column; align-items: center; padding-top: 2px; }
.turn-num {
  font-size: 11px;
  color: var(--text-faint);
  background: var(--bg-sunken);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 2px 8px;
}
.turn-num.accent {
  color: var(--accent);
  border-color: var(--accent);
  background: var(--accent-soft);
  font-weight: 600;
}
.live-dot { animation: pulse-glow 2s ease-in-out infinite; }

.turn-body { min-width: 0; }
.turn-label { margin-bottom: 8px; }
.turn-label.accent { color: var(--accent); font-weight: 600; }

/* Thinking */
.thinking-block {
  border: 1px dashed var(--border);
  border-radius: 8px;
  background: var(--bg-sunken);
  padding: 10px 14px;
  margin-bottom: 12px;
}
.thinking-block summary {
  font-size: 11px;
  color: var(--text-muted);
  cursor: pointer;
  user-select: none;
  list-style: none;
}
.thinking-block summary::marker { display: none; }
.thinking-block summary::-webkit-details-marker { display: none; }
.thinking-block[open] summary { margin-bottom: 8px; }
.thinking-text {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
  white-space: pre-wrap;
  font-style: italic;
}
.toggle-think {
  font-size: 10px;
  color: var(--text-muted);
  background: transparent;
  border: 0;
  cursor: pointer;
  text-transform: uppercase;
  font-family: var(--font-mono);
}
.toggle-think:hover { color: var(--accent); }

.streaming-block { display: flex; align-items: flex-end; gap: 4px; }

/* Composer */
.composer { padding-top: 12px; }
.composer-card {
  background: var(--bg-raised);
  border: 1px solid var(--border);
  border-radius: 16px;
  box-shadow: var(--shadow-float);
  overflow: hidden;
}
.composer-input {
  width: 100%;
  resize: none;
  background: transparent;
  border: 0;
  padding: 18px 20px 12px;
  font-size: 14px;
  color: var(--text);
  caret-color: var(--accent);
  outline: 0;
  line-height: 1.6;
}
.composer-input::placeholder { color: var(--text-faint); }
.composer-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 20px;
  border-top: 1px solid var(--border);
}
</style>