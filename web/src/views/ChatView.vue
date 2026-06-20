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
import { api } from '@/api/http'

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

type TimelineItem =
  | { kind: 'thinking'; text: string; order: number }
  | { kind: 'text'; text: string; order: number }
  | { kind: 'toolcall'; id: string; name: string; args: any; status: 'running' | 'done'; result?: any; error?: boolean; order: number }
  | { kind: 'tree'; entries: TreeEntry[]; selectedIdx: number; order: number }

interface TreeEntry {
  id: string
  role: 'user' | 'assistant' | 'tool'
  label: string
  detail?: string
}
const timeline = ref<TimelineItem[]>([])
const turnLog = ref<{ role: 'user' | 'assistant'; timeline: TimelineItem[]; userText?: string }[]>([])
const history = ref<any[]>([])

let itemOrder = 0

async function refresh() {
  try {
    session.value = await getSession(sessionId.value)
    try {
      const messages = await getMessages(sessionId.value)
      history.value = messages.messages || []
      turnLog.value = []
      const toolResults: Record<string, { result: any; error: boolean }> = {}
      for (const m of (messages.messages || [])) {
        if (m.role === 'tool' || m.role === 'toolResult') {
          collectToolResults(m.content, toolResults)
        }
      }
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

function buildTimeline(_content: any): TimelineItem[] { return [] }

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
      if (timeline.value.length) {
        turnLog.value.push({ role: 'assistant', timeline: [...timeline.value] })
        timeline.value = []
      }
      break
    case 'message_end':
      break
    case 'session_info_update':
      if (frame.title && session.value) session.value.title = frame.title
      if (frame.sessionFile) refresh()
      break
  }
  scrollToBottom()
}
const toolCallById: Record<string, TimelineItem & { id: string; name: string; status: 'running'|'done'; result?: any; error?: boolean }> = {}

const ws = ref<WebSocket | null>(null)
function scrollToBottom() { nextTick(() => { if (messagesEl.value) messagesEl.value.scrollTop = messagesEl.value.scrollHeight }) }

async function send() {
  if (!input.value.trim() || sending.value || isStreaming.value) return
  const text = input.value; input.value = ''; sending.value = true
  if (text.trim() === '/tree') {
    try {
      const data = await getMessages(sessionId.value)
      const entries = buildTreeEntries(data.messages || [])
      turnLog.value.push({ role: 'user', timeline: [], userText: text })
      const order = (turnLog.value.filter(t => t.role === 'assistant').length)
      turnLog.value.push({
        role: 'assistant',
        timeline: [{ kind: 'tree', entries, selectedIdx: Math.max(0, entries.length - 1), order }],
      })
      scrollToBottom()
    } catch (e: any) { msg.error(e?.response?.data?.error || '/tree 失败') }
    finally { sending.value = false }
    return
  }
  turnLog.value.push({ role: 'user', timeline: [], userText: text })
  timeline.value = []; itemOrder = 0
  Object.keys(toolCallById).forEach(k => delete toolCallById[k])
  scrollToBottom()
  try { await prompt(sessionId.value, text) }
  catch (e: any) { msg.error(e?.response?.data?.error || '发送失败') }
  finally { sending.value = false }
}

function buildTreeEntries(entries: any[]): TreeEntry[] {
  const out: TreeEntry[] = []
  for (const e of entries) {
    const id = e.id || e.entryId || String(out.length)
    const role = e.role
    if (role === 'user') {
      const t = typeof e.content === 'string' ? e.content : extractText(e.content)
      out.push({ id, role: 'user', label: 'user', detail: (t || '').slice(0, 80) })
    } else if (role === 'assistant') {
      if (Array.isArray(e.content)) {
        for (const c of e.content) {
          if (c.type === 'text' && c.text?.trim()) {
            out.push({ id, role: 'assistant', label: 'assistant', detail: c.text.trim().slice(0, 80) })
          } else if (c.type === 'thinking' && c.thinking?.trim()) {
            out.push({ id, role: 'assistant', label: '[think]', detail: c.thinking.trim().slice(0, 80) })
          } else if (c.type === 'toolCall' || c.type === 'tool_call') {
            const name = c.name || c.toolName || 'tool'
            const args = JSON.stringify(c.arguments ?? c.args ?? {})
            out.push({ id, role: 'tool', label: name, detail: args.slice(0, 80) })
          }
        }
      } else if (typeof e.content === 'string' && e.content.trim()) {
        out.push({ id, role: 'assistant', label: 'assistant', detail: e.content.trim().slice(0, 80) })
      }
    } else if (role === 'tool' || role === 'toolResult') {
      if (Array.isArray(e.content)) {
        for (const c of e.content) {
          if (c.type === 'toolResult' || c.type === 'tool_result') {
            const r = typeof c.result === 'string' ? c.result : JSON.stringify(c.result ?? '')
            out.push({ id, role: 'tool', label: '[result]', detail: r.slice(0, 80) })
          }
        }
      }
    }
  }
  return out
}

function moveTreeSel(item: any, delta: number) {
  item.selectedIdx = Math.max(0, Math.min(item.entries.length - 1, item.selectedIdx + delta))
  nextTick(() => scrollTreeSelIntoView(item))
}

function selectTree(item: any, idx: number) {
  item.selectedIdx = idx
  nextTick(() => scrollTreeSelIntoView(item))
}

function scrollTreeSelIntoView(item: any) {
  const el = document.querySelector('.tree-list li.sel')
  if (el) el.scrollIntoView({ block: 'nearest' })
}

async function treeEnter(item: any) {
  const entry = item.entries[item.selectedIdx]
  if (!entry) return
  try {
    await api.post(`/api/sessions/${sessionId.value}/branch`, { entryId: entry.id })
    msg.success(`已跳转到 ${entry.label}`)
  } catch (e: any) {
    msg.error(e?.response?.data?.error || '跳转失败')
  }
}

async function doAbort() { try { await abortSession(sessionId.value) } catch {} }

watch(() => timeline.value.length, scrollToBottom)
onMounted(async () => { await refresh(); connectWs() })
onUnmounted(() => { if (ws.value) try { ws.value.close() } catch {} })

const turnIndex = (i: number) => String(i + 1).padStart(2, '0')

const composedAt = computed(() => {
  const d = new Date()
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
})
</script>

<template>
  <div class="chat">
    <!-- Flat topbar (Volcengine style) -->
    <header class="topbar fade-up">
      <button class="back-btn btn-ghost" @click="router.push('/sessions')">
        <span class="caret">←</span>
        <span>返回</span>
      </button>
      <div class="topbar-meta">
        <span class="serial">{{ sessionId.slice(0, 8) }}</span>
        <h1 class="session-title">{{ session?.title || '未命名会话' }}</h1>
      </div>
      <div class="topbar-status">
        <span class="status-pill" :class="{ live: isStreaming }">
          <span v-if="isStreaming" class="live-dot"></span>
          <span v-else class="status-dot idle"></span>
          <span>{{ isStreaming ? '生成中' : '空闲' }}</span>
        </span>
        <button class="btn-mini-danger abort-btn" :disabled="!isStreaming" @click="doAbort">
          中断
        </button>
      </div>
    </header>

    <main class="messages" ref="messagesEl">
      <!-- Welcome -->
      <div v-if="!turnLog.length && !timeline.length" class="welcome fade-up">
        <span class="tag">工作室 · Workshop</span>
        <h2 class="welcome-title">
          一张<br />
          <strong>空白纸.</strong>
        </h2>
        <p class="welcome-dek">
          描述你的需求，AI 代理在工作目录
          <code>{{ session?.repoId || 'workspace' }}</code>
          中执行编辑、读取与命令。
        </p>
        <div class="welcome-meta">
          <span class="kbd">↵</span><span class="serial">发送</span>
          <span class="kbd">⇧↵</span><span class="serial">换行</span>
          <span class="kbd">/tree</span><span class="serial">分支</span>
        </div>
      </div>

      <!-- History turns -->
      <div
        v-for="(t, i) in turnLog"
        :key="'turn-' + i"
        class="turn"
        :style="{ animationDelay: i * 60 + 'ms' }"
      >
        <div class="turn-gutter">
          <span class="turn-num mono" :class="{ accent: t.role === 'assistant' }">
            {{ turnIndex(i) }}
          </span>
          <span class="turn-role" :class="{ accent: t.role === 'assistant' }">
            {{ t.role === 'user' ? '操作员' : '代理' }}
          </span>
        </div>
        <div class="turn-body">
          <MessageBubble v-if="t.role === 'user'" role="user" :text="t.userText || ''" />
          <template v-if="t.role === 'assistant' && Array.isArray(t.timeline) && t.timeline.length">
            <template v-for="item in t.timeline" :key="item.order">
              <details v-if="item.kind === 'thinking'" class="thinking-block" open>
                <summary class="mono">
                  <span class="caret">▸</span>
                  <span>思考 · thinking</span>
                </summary>
                <pre class="thinking-text mono">{{ item.text }}</pre>
              </details>
              <MessageBubble v-else-if="item.kind === 'text'" role="assistant" :text="item.text" />
              <ToolCard
                v-else-if="item.kind === 'toolcall'"
                :tool-name="item.name"
                :args="item.args"
                :result="item.result"
                :is-error="item.error"
                :status="item.status"
              />
              <div v-else-if="item.kind === 'tree'" class="tree-panel" tabindex="0"
                @keydown.up.prevent="moveTreeSel(item, -1)" @keydown.down.prevent="moveTreeSel(item, 1)"
                @keydown.enter.prevent="treeEnter(item)">
                <header class="tree-head mono">
                  <span>Session Tree</span>
                  <span class="dim">↑↓ 选 · ↵ 跳转 · {{ item.entries.length }} 条</span>
                </header>
                <ul class="tree-list mono">
                  <li v-for="(e, i) in item.entries" :key="e.id + '-' + i"
                    :class="{ sel: i === item.selectedIdx, [e.role]: true }"
                    @click.stop="selectTree(item, i)" @dblclick.stop="treeEnter(item)">
                    <span class="caret">{{ i === item.selectedIdx ? '›' : ' ' }}</span>
                    <span class="lbl">{{ e.label }}</span>
                    <span class="det dim" v-if="e.detail">: {{ e.detail }}</span>
                  </li>
                </ul>
              </div>
            </template>
          </template>
        </div>
      </div>

      <!-- Live streaming turn -->
      <div v-if="isStreaming || timeline.length" class="turn turn-live">
        <div class="turn-gutter">
          <span class="turn-num mono accent live-num">{{ turnIndex(turnLog.filter(t => t.role === 'assistant').length) }}</span>
          <span class="turn-role accent">实时</span>
        </div>
        <div class="turn-body">
          <div class="turn-label" v-if="timeline.some(i => i.kind === 'thinking')">
            <button class="toggle-think mono" @click="showThinking = !showThinking">
              {{ showThinking ? '收起思考' : '展开思考' }}
            </button>
          </div>
          <template v-for="item in timeline" :key="'live-' + item.order">
            <details v-if="item.kind === 'thinking'" class="thinking-block" :open="showThinking">
              <summary class="mono">
                <span class="caret">▸</span>
                <span>思考 · thinking</span>
              </summary>
              <pre class="thinking-text mono">{{ item.text }}</pre>
            </details>
            <div v-else-if="item.kind === 'text'" class="streaming-block">
              <MessageBubble role="assistant" :text="item.text" />
              <span v-if="item.order === timeline.length - 1" class="cursor-blink"></span>
            </div>
            <ToolCard
              v-else-if="item.kind === 'toolcall'"
              :tool-name="item.name"
              :args="item.args"
              :result="item.result"
              :is-error="item.error"
              :status="item.status"
            />
            <div v-else-if="item.kind === 'tree'" class="tree-panel" tabindex="0"
              @keydown.up.prevent="moveTreeSel(item, -1)" @keydown.down.prevent="moveTreeSel(item, 1)"
              @keydown.enter.prevent="treeEnter(item)">
              <header class="tree-head mono">
                <span>Session Tree</span>
                <span class="dim">↑↓ 选 · ↵ 跳转 · {{ item.entries.length }} 条</span>
              </header>
              <ul class="tree-list mono">
                <li v-for="(e, i) in item.entries" :key="e.id + '-' + i"
                  :class="{ sel: i === item.selectedIdx, [e.role]: true }"
                  @click.stop="selectTree(item, i)" @dblclick.stop="treeEnter(item)">
                  <span class="caret">{{ i === item.selectedIdx ? '›' : ' ' }}</span>
                  <span class="lbl">{{ e.label }}</span>
                  <span class="det dim" v-if="e.detail">: {{ e.detail }}</span>
                </li>
              </ul>
            </div>
          </template>
        </div>
      </div>
    </main>

    <!-- Composer — flat with pill primary CTA -->
    <footer class="composer">
      <textarea
        v-model="input"
        class="composer-input field-raw"
        rows="3"
        :placeholder="isStreaming ? '正在生成中，可继续输入，生成完成后将自动发送…' : '在此描述你的需求，代理将在沙箱中执行…'"
        @keydown.enter.exact.prevent="send"
        :disabled="sending"
      />
      <div class="composer-foot">
        <div class="composer-hints">
          <span class="hint">
            <span class="kbd">↵</span>
            <span class="serial">发送</span>
          </span>
          <span class="hint">
            <span class="kbd">⇧↵</span>
            <span class="serial">换行</span>
          </span>
          <span class="hint">
            <span class="kbd">/tree</span>
            <span class="serial">分支</span>
          </span>
          <span class="serial dim">{{ composedAt }} · {{ input.length }} 字符</span>
        </div>
        <button class="btn-primary send-btn" :disabled="sending || isStreaming || !input.trim()" @click="send">
          {{ isStreaming ? '生成中…' : (sending ? '提交中…' : '发送给代理') }}
        </button>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.chat {
  display: grid;
  grid-template-rows: auto 1fr auto;
  height: 100dvh;
  max-width: 860px;
  margin: 0 auto;
  padding: 20px 32px;
  gap: 16px;
}

/* ====================================================================
   Topbar
   ==================================================================== */
.topbar {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 16px;
  padding: 10px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  background: var(--surface);
  box-shadow: var(--shadow-card);
}
.back-btn { padding: 6px 14px; font-size: 12px; }
.back-btn .caret { font-size: 12px; }
.topbar-meta {
  display: flex;
  align-items: baseline;
  gap: 12px;
  padding: 0 14px;
  border-left: 1px solid var(--border);
  border-right: 1px solid var(--border);
  min-width: 0;
}
.session-title {
  font-family: var(--font-display);
  font-size: 17px;
  font-weight: 700;
  color: var(--ink);
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.topbar-status { display: flex; align-items: center; gap: 10px; }
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  background: var(--surface);
  font-size: 12px;
  font-weight: 500;
  color: var(--ink-2);
}
.status-pill .status-dot.idle {
  width: 6px; height: 6px; border-radius: 50%;
  background: var(--ink-faint);
}
.status-pill.live { background: var(--brand); color: var(--ink-invert); border-color: var(--brand); }
.abort-btn { padding: 4px 12px; font-size: 11px; }

/* ====================================================================
   Messages
   ==================================================================== */
.messages {
  overflow-y: auto;
  padding: 16px 0;
  scroll-behavior: smooth;
}

.welcome {
  margin-top: 32px;
  margin-bottom: 16px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.welcome-title {
  font-family: var(--font-display);
  font-size: clamp(40px, 5vw, 56px);
  font-weight: 800;
  line-height: 1.06;
  letter-spacing: -0.025em;
  color: var(--ink);
  margin: 0;
}
.welcome-title strong { color: var(--brand); font-weight: 800; }
.welcome-dek {
  font-size: 15px;
  color: var(--ink-2);
  line-height: 1.65;
  max-width: 480px;
}
.welcome-dek code {
  font-family: var(--font-mono);
  background: var(--brand-soft);
  color: var(--brand);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 12px;
  border: 1px solid var(--border-soft);
}
.welcome-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  padding-top: 16px;
  border-top: 1px dashed var(--border);
}

/* Turns — note: turn-role is now HORIZONTAL (no vertical writing-mode) */
.turn {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: 16px;
  margin-bottom: 28px;
  animation: fade-up var(--dur-slow) var(--ease-out) both;
}
.turn-gutter {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
  padding-top: 4px;
  text-align: right;
}
.turn-num {
  font-size: 13px;
  color: var(--ink-mute);
  background: var(--surface-soft);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 2px 8px;
}
.turn-num.accent { color: var(--brand); border-color: var(--brand); background: var(--brand-soft); }
.live-num { animation: gentle-pulse 1.8s var(--ease-out) infinite; }
.turn-role {
  font-size: 12px;
  color: var(--ink-mute);
  font-weight: 500;
}
.turn-role.accent { color: var(--brand); }

.turn-body { min-width: 0; }
.turn-label { margin-bottom: 8px; display: flex; justify-content: flex-end; }

/* Thinking */
.thinking-block {
  border: 1px dashed var(--border);
  border-radius: var(--radius);
  background: var(--surface-soft);
  padding: 12px 16px;
  margin: 6px 0 10px;
}
.thinking-block summary {
  font-size: 11px;
  color: var(--ink-mute);
  cursor: pointer;
  user-select: none;
  list-style: none;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.thinking-block summary .caret { color: var(--brand); font-size: 10px; }
.thinking-block summary::marker, .thinking-block summary::-webkit-details-marker { display: none; }
.thinking-block[open] summary { margin-bottom: 8px; }
.thinking-text {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--ink-2);
  white-space: pre-wrap;
}
.toggle-think {
  font-size: 11px;
  color: var(--ink-mute);
  background: transparent;
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  padding: 4px 12px;
  cursor: pointer;
  transition: all var(--dur-fast) var(--ease-out);
}
.toggle-think:hover { color: var(--brand); border-color: var(--brand); }

.streaming-block { display: flex; align-items: flex-end; gap: 4px; }

/* ---- Session Tree ---- */
.tree-panel {
  background: var(--surface-soft);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 12px 16px;
  margin: 8px 0;
  font-size: 12px;
  line-height: 1.6;
  outline: none;
  transition: border-color var(--dur-fast) var(--ease-out), box-shadow var(--dur-fast) var(--ease-out);
}
.tree-panel:focus-within { border-color: var(--brand); box-shadow: var(--shadow-focus); }
.tree-head {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--ink-mute);
  padding-bottom: 8px;
  margin-bottom: 6px;
  border-bottom: 1px dashed var(--border);
}
.tree-list { list-style: none; padding: 0; margin: 0; max-height: 320px; overflow-y: auto; }
.tree-list li {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 3px 6px;
  border-radius: 4px;
  cursor: pointer;
  color: var(--ink-2);
  transition: background var(--dur-fast) var(--ease-out);
}
.tree-list li:hover { background: var(--surface); }
.tree-list li.sel { background: var(--brand); color: var(--ink-invert); }
.tree-list li.user .lbl { color: var(--ink); }
.tree-list li.sel .lbl { color: var(--ink-invert); }
.tree-list li.assistant .lbl { color: var(--brand); }
.tree-list li.sel.assistant .lbl { color: var(--ink-invert); }
.tree-list li.tool .lbl { color: var(--ink-mute); }
.tree-list li.sel.tool .lbl { color: var(--ink-invert); }
.tree-list .caret { width: 10px; color: currentColor; font-weight: 700; }
.tree-list .det { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; flex: 1; min-width: 0; }

/* ====================================================================
   Composer — flat card with pill CTA
   ==================================================================== */
.composer {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  overflow: hidden;
  box-shadow: var(--shadow-card);
  transition: border-color var(--dur-fast) var(--ease-out), box-shadow var(--dur-fast) var(--ease-out);
}
.composer:focus-within {
  border-color: var(--brand);
  box-shadow: var(--shadow-focus);
}

.composer-input {
  border: 0 !important;
  border-radius: 0 !important;
  background: var(--surface) !important;
  padding: 16px 18px 12px !important;
  font-size: 14px !important;
  font-family: var(--font-ui) !important;
  line-height: 1.65;
}
.composer-input:focus { box-shadow: none !important; border: 0 !important; }
.composer-input::placeholder { color: var(--ink-faint); }
.composer-input:disabled { opacity: 0.55; cursor: not-allowed; }

.composer-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  border-top: 1px solid var(--border);
  background: var(--surface-soft);
  gap: 12px;
  flex-wrap: wrap;
}
.composer-hints { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.hint { display: inline-flex; align-items: center; gap: 4px; }
.send-btn { padding: 9px 22px; font-size: 13px; }

/* ====================================================================
   Mobile collapse
   ==================================================================== */
@media (max-width: 640px) {
  .chat { padding: 12px 16px; }
  .topbar { grid-template-columns: auto 1fr; }
  .topbar-status { grid-column: 1 / -1; justify-content: flex-end; padding-top: 8px; border-top: 1px solid var(--border); }
  .session-title { font-size: 15px; }
  .turn { grid-template-columns: 64px minmax(0, 1fr); gap: 12px; }
  .turn-gutter { align-items: flex-start; text-align: left; }
  .composer-foot { flex-direction: column; align-items: stretch; }
  .send-btn { width: 100%; }
}
</style>