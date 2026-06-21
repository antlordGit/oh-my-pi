<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  getSession, getMessages, getState,
  prompt, abort as abortSession, unarchive as unarchiveSession, newSession as newSessionRpc, wsUrl,
  openSessionIde,
  type SessionSummary,
} from '@/api/session'
import { useMessage } from 'naive-ui'
import MessageBubble from '@/components/MessageBubble.vue'
import ToolCard from '@/components/ToolCard.vue'
import WorkspaceTree from '@/components/WorkspaceTree.vue'
import UiRequestDialog from '@/components/UiRequestDialog.vue'
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

let itemOrder = 0

const isArchived = ref(false)
const restoringArchive = ref(false)
const showThinking = ref(true)
const showWorkspaceTree = ref(false)

// omp ask 工具发起的 UI 请求 (extension_ui_request 帧)
interface UiRequest {
  id: string
  method: 'select' | 'confirm' | 'input' | 'editor'
  title: string
  options?: string[]
  message?: string
  placeholder?: string
  prefill?: string
  timeout?: number
}
const uiRequest = ref<UiRequest | null>(null)

const gridComputed = computed(() =>
  !isArchived.value && showWorkspaceTree.value
    ? '200px minmax(0, 1fr) 260px'
    : '200px minmax(0, 1fr)',
)

// Shared optimistic status override (also written by the admin control room and
// the session list). Keep it in step on restore so other views don't snap the
// row back to a stale "archived" value. See SessionListView.effectiveStatus().
const OVERRIDES_KEY = 'omp.admin.sessionOverrides'
function setStatusOverride(id: string, status: 'active' | 'archived') {
  try {
    const m = JSON.parse(sessionStorage.getItem(OVERRIDES_KEY) || '{}') as Record<string, string>
    m[id] = status
    sessionStorage.setItem(OVERRIDES_KEY, JSON.stringify(m))
  } catch {}
}

async function restoreFromArchive() {
  if (restoringArchive.value) return
  restoringArchive.value = true
  try {
    // Call the resume API — unarchive + spawn fresh process with --resume <sessionFile>
    await api.post(`/api/sessions/${sessionId.value}/resume`)
    isArchived.value = false
    if (session.value) session.value.status = 'active'
    setStatusOverride(sessionId.value, 'active')
    // Load messages from the resumed session and reconnect WS
    await refresh()
    connectWs()
    msg.success('会话已恢复，可以继续对话')
  } catch (e: any) {
    isArchived.value = true
    if (session.value) session.value.status = 'archived'
    msg.error(e?.response?.data?.error || '恢复失败')
  } finally {
    restoringArchive.value = false
  }
}

async function refresh() {
  try {
    session.value = await getSession(sessionId.value)
    isArchived.value = session.value?.status === 'archived'
    // Only load messages and connect ws if session is active.
    if (isArchived.value) return
    try {
      const messages = await getMessages(sessionId.value)
      const all = messages.messages || []
      turnLog.value = []

      // First pass: index every tool result by its tool-call id so the matching
      // tool-call entry can render its output inline.
      const toolResults: Record<string, { result: any; error: boolean }> = {}
      for (const m of all) {
        if (m.role === 'tool' || m.role === 'toolResult') {
          collectToolResults(m.content, toolResults)
        }
      }

      // Second pass: fold the flat message list into user/assistant turns.
      // Consecutive assistant messages share one timeline; a user message flushes it.
      let currentAssistantTimeline: TimelineItem[] | null = null
      let order = 0
      const flush = () => {
        if (currentAssistantTimeline && currentAssistantTimeline.length > 0) {
          turnLog.value.push({ role: 'assistant', timeline: currentAssistantTimeline })
        }
        currentAssistantTimeline = null
      }
      for (const m of all) {
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

const ws = ref<WebSocket | null>(null)
let wsDisposed = false        // 组件已卸载,禁止复活
let reconnectAttempts = 0     // 重连次数(指数退避)
const MAX_RECONNECT = 5       // 最大重连次数
const BASE_DELAY = 1500       // 基础回退 ms

function connectWs() {
  if (wsDisposed) return
  if (!sessionId.value || sessionId.value === 'undefined') return
  // 无 token 直接跳登录,不发起空 token 连接
  const token = localStorage.getItem('omp.token')
  if (!token) {
    msg.warning('登录已失效,请重新登录')
    router.push('/login')
    return
  }
  if (ws.value) try { ws.value.close() } catch {}
  const s = new WebSocket(wsUrl(sessionId.value))
  s.onmessage = (ev) => { let frame: any; try { frame = JSON.parse(ev.data) } catch { return }; handleFrame(frame) }
  s.onclose = (ev) => {
    if (wsDisposed) return  // 组件已卸载,不再重连
    // 鉴权失败(1003 NOT_ACCEPTABLE 或自定义 4001)不重连,提示并跳登录
    if (ev.code === 1003 || ev.code === 4001) {
      msg.warning('登录已失效,请重新登录')
      router.push('/login')
      return
    }
    // 其余原因:指数退避重连,有上限
    if (reconnectAttempts >= MAX_RECONNECT) {
      msg.error('连接中断,请刷新页面重试')
      return
    }
    const delay = BASE_DELAY * Math.pow(2, reconnectAttempts)
    reconnectAttempts++
    setTimeout(connectWs, delay)
  }
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
      // 从 agent_end 携带的 messages 中提取错误，只展示友好提示
      {
        const raw: string = frame.errorMessage
          || (Array.isArray(frame.messages) && frame.messages.find((m: any) => m?.errorMessage)?.errorMessage)
          || ''
        let friendly = raw
        // 尝试解析 JSON 格式的额度/认证错误，提取其中的人类可读消息
        try {
          // raw 可能形如 "429 {...json...}"，JSON 部分从第一个 { 开始
          const jsonStart = raw.indexOf('{')
          if (jsonStart >= 0) {
            const parsed = JSON.parse(raw.slice(jsonStart))
            if (parsed?.error?.message) friendly = parsed.error.message
          }
        } catch {}
        if (friendly) {
          turnLog.value.push({
            role: 'assistant',
            timeline: [{ kind: 'text', text: friendly, order: 0 }],
          })
        }
      }
      break
    case 'message_end':
      break
    case 'session_info_update':
      if (frame.title && session.value) session.value.title = frame.title
      if (frame.sessionFile) refresh()
      break
    case 'extension_ui_request': {
      // omp ask 工具发起的 UI 请求
      const method = frame.method as string
      // 被动方法 (notify/setStatus/setWidget/setTitle/set_editor_text/open_url) 不弹框
      if (['notify', 'setStatus', 'setWidget', 'setTitle', 'set_editor_text', 'open_url'].includes(method)) {
        // notify 可用 msg.info(frame.message) 做轻提示,但暂不实现
        break
      }
      // cancel 方法: 关掉当前弹框
      if (method === 'cancel') {
        if (uiRequest.value && uiRequest.value.id === frame.targetId) {
          uiRequest.value = null
        }
        break
      }
      // 需要用户交互的方法: select / confirm / input / editor
      uiRequest.value = {
        id: frame.id,
        method: method as UiRequest['method'],
        title: frame.title || '',
        options: frame.options,
        message: frame.message,
        placeholder: frame.placeholder,
        prefill: frame.prefill,
        timeout: frame.timeout,
      }
      break
    }
  }
  scrollToBottom()
}
const toolCallById: Record<string, TimelineItem & { id: string; name: string; status: 'running'|'done'; result?: any; error?: boolean }> = {}

/** 发送 extension_ui_response 回复 omp 的 UI 请求 */
function sendUiResponse(id: string, value: string | boolean | null, cancelled: boolean) {
  if (!ws.value) return
  const frame: any = { type: 'extension_ui_response', id }
  if (cancelled) {
    frame.cancelled = true
  } else if (typeof value === 'boolean') {
    frame.confirmed = value
  } else {
    frame.value = value
  }
  ws.value.send(JSON.stringify(frame))
  uiRequest.value = null
}

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

async function doRewind() {
  if (sending.value || isStreaming.value || isArchived.value) return
  const text = '/rewind'
  sending.value = true
  turnLog.value.push({ role: 'user', timeline: [], userText: text })
  timeline.value = []; itemOrder = 0
  Object.keys(toolCallById).forEach(k => delete toolCallById[k])
  scrollToBottom()
  try { await prompt(sessionId.value, text) }
  catch (e: any) { msg.error(e?.response?.data?.error || '回退失败') }
  finally { sending.value = false }
}

async function doNew() {
  if (sending.value || isStreaming.value || isArchived.value) return
  sending.value = true
  try {
    // /new is a CLI slash command that triggers session.newSession() in
    // coding-agent. The web equivalent is the new_session RPC (backend
    // POST /api/sessions/{id}/new-session) — sending "/new" through the
    // PROMPT RPC is NOT intercepted (builtin-registry only registers a
    // handleTui, not a handle), so the LLM would see it as plain text.
    const r = await newSessionRpc(sessionId.value)
    if (r?.cancelled) {
      msg.warning('新对话已被取消（扩展钩子中断）')
      return
    }
    // newSession() swaps the session file under the hood; refresh state and
    // reload messages to mirror the cleared transcript.
    await refresh()
  } catch (e: any) {
    msg.error(e?.response?.data?.error || '新对话失败')
  } finally {
    sending.value = false
  }
}

// ---- Operator message index (left sidebar) -------------------------------
const turnRefs = ref<HTMLElement[]>([])
function setTurnRef(el: any, i: number) {
  if (el) turnRefs.value[i] = el as HTMLElement
}

interface UserIndexItem { turnIdx: number; text: string }
const userIndex = computed<UserIndexItem[]>(() =>
  turnLog.value
    .map((t, i) => (t.role === 'user' ? { turnIdx: i, text: t.userText || '' } : null))
    .filter((x): x is UserIndexItem => x !== null),
)

function scrollToTurn(turnIdx: number) {
  const el = turnRefs.value[turnIdx]
  if (!el) return
  // Account for the sticky-ish scroll behavior of .messages — scroll the
  // nearest scrollable ancestor rather than the page.
  const messages = messagesEl.value
  if (messages) {
    const elTop = el.offsetTop
    messages.scrollTo({ top: elTop - 12, behavior: 'smooth' })
  } else {
    el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

function previewText(text: string): string {
  const oneLine = text.replace(/\s+/g, ' ').trim()
  return oneLine.length > 36 ? oneLine.slice(0, 36) + '…' : oneLine
}

const canRewind = computed(() => turnLog.value.length > 0)

// ---- 澄清式提问表单 ------------------------------------------------------
// 把助手 turn 里的文本解析成可勾选表单，并「内联」渲染在该 turn 内部——
// 这样它是历史记录的一部分，发送新消息后依然保留，不会回退成纯文本。
// 流式中、归档会话、或用户已点「以纯文本查看」的 turn 不渲染表单。

type Turn = { role: 'user' | 'assistant'; timeline: TimelineItem[]; userText?: string }

/** 打开 IDE（code-server/openvscode-server），在新标签页中加载工作区 */
async function openIde() {
  if (!sessionId.value) {
    msg.error('会话不存在')
    return
  }
  try {
    const { url } = await openSessionIde(sessionId.value)
    window.open(url, '_blank')
  } catch (e: any) {
    msg.error(e?.response?.data?.error || e?.message || '打开 IDE 失败')
  }
}

watch(() => timeline.value.length, scrollToBottom)
onMounted(async () => { await refresh(); if (!isArchived.value) connectWs() })
onUnmounted(() => {
  wsDisposed = true
  if (ws.value) {
    ws.value.onclose = null  // 先清掉 onclose,避免 close 触发复活
    try { ws.value.close() } catch {}
  }
})

const turnIndex = (i: number) => String(i + 1).padStart(2, '0')
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
        <span class="serial">{{ session?.repoId || '—' }}</span>
        <h1 class="session-title">{{ session?.title || '未命名会话' }}</h1>
      </div>
    </header>

    <aside class="chat-index" v-if="!isArchived && userIndex.length">
      <button class="btn-mini ide-btn chat-index-ide" @click="openIde">
        <span class="caret">◈</span>
        <span>打开 IDE</span>
      </button>
      <header class="chat-index-head mono">
        <span class="caret">§</span>
        <span>索引</span>
        <span class="dim">{{ userIndex.length }}</span>
      </header>
      <ul class="chat-index-list" v-if="userIndex.length">
        <li
          v-for="(item, idx) in userIndex"
          :key="`idx-${item.turnIdx}-${idx}`"
          class="chat-index-item"
          :title="item.text"
          @click="scrollToTurn(item.turnIdx)"
        >
          <span class="chat-index-num mono">{{ String(idx + 1).padStart(2, '0') }}</span>
          <span class="chat-index-text">{{ previewText(item.text) || '(空)' }}</span>
        </li>
      </ul>
      <p class="chat-index-empty mono dim" v-else>暂无操作员消息</p>
    </aside>

    <WorkspaceTree v-if="!isArchived && showWorkspaceTree" :repo-id="session?.repoId" />

    <main class="messages" ref="messagesEl">
      <!-- Archived session: locked -->
      <div v-if="isArchived" class="archived-block blur-in">
        <div class="archived-message card">
          <span class="archived-icon">—</span>
          <h2 class="archived-title">此会话<strong>已归档</strong></h2>
          <p class="archived-sub">
            处于归档状态的会话不可发送新消息。<br />
            点击下方按钮恢复后可继续对话，历史消息将重新加载。
          </p>
          <button class="btn-primary" :disabled="restoringArchive" @click="restoreFromArchive">
            {{ restoringArchive ? '正在恢复…' : '恢复会话' }}
          </button>
        </div>
      </div>

      <!-- Welcome -->
      <!--v-if-->

      <!-- History turns -->
      <div
        v-for="(t, i) in turnLog"
        :key="'turn-' + i"
        :ref="el => setTurnRef(el, i)"
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

      <!-- UI 请求对话框 (omp ask 工具发起) -->
      <div v-if="uiRequest" class="turn ui-request-turn">
        <div class="turn-gutter">
          <span class="turn-num mono accent">◆</span>
          <span class="turn-role accent">等待输入</span>
        </div>
        <div class="turn-body">
          <UiRequestDialog
            :request="uiRequest"
            @submit="(id, value) => sendUiResponse(id, value, false)"
            @cancel="(id) => sendUiResponse(id, null, true)"
          />
        </div>
      </div>
    </main>

    <!-- Composer — hidden for archived sessions -->
    <footer v-if="!isArchived" class="composer">
      <div class="composer-toolbar" v-if="canRewind">
        <button class="btn-mini rewind-btn" :disabled="sending || isStreaming" @click="doRewind">
          <span class="caret">↺</span>
          <span>回退</span>
        </button>
        <button class="btn-mini new-btn" :disabled="sending || isStreaming" @click="doNew">
          <span class="caret">+</span>
          <span>新对话</span>
        </button>
        <button v-if="!isArchived" class="btn-mini-danger abort-btn" :disabled="!isStreaming" @click="doAbort">
          中断
        </button>
        <span class="rewind-hint"></span>
      </div>
      <div class="composer-toolbar" v-else>
        <button class="btn-mini new-btn" :disabled="sending || isStreaming" @click="doNew">
          <span class="caret">+</span>
          <span>新对话</span>
        </button>
        <button v-if="!isArchived" class="btn-mini-danger abort-btn" :disabled="!isStreaming" @click="doAbort">
          中断
        </button>
        <span class="rewind-hint"></span>
      </div>
      <div class="composer-input-row">
        <textarea
          v-model="input"
          class="composer-input field-raw"
          rows="1"
          :placeholder="isStreaming ? '正在生成中，可继续输入，生成完成后将自动发送…' : '在此描述你的需求，代理将在沙箱中执行…'"
          @keydown.enter.ctrl.prevent="send"
          @keydown.enter.meta.prevent="send"
          :disabled="sending"
        />
        <button class="btn-primary send-btn" :disabled="sending || isStreaming || !input.trim()" @click="send">
          {{ isStreaming ? '生成中…' : (sending ? '提交中…' : '发送') }}
        </button>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.chat {
  display: grid;
  grid-template-rows: auto 1fr auto;
  grid-template-columns: 200px minmax(0, 1fr) 260px;
  column-gap: 14px;
  height: 100dvh;
  max-width: 1240px;
  margin: 0 auto;
  padding: 20px 32px;
  gap: 16px;
}
.topbar { grid-column: 1 / -1; }
.composer { grid-column: 1 / -1; }

/* ====================================================================
   Left-side operator message index
   ==================================================================== */
.chat-index {
  grid-column: 1;
  grid-row: 2;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow-card);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  align-self: start;
  position: sticky;
  top: 84px;
  max-height: calc(100dvh - 110px);
}
.chat-index-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 14px;
  font-size: 11px;
  color: var(--ink-2);
  border-bottom: 1px solid var(--border);
  background: var(--surface-soft);
}
.chat-index-head .caret { color: var(--brand); }
.chat-index-head .dim { color: var(--ink-faint); margin-left: auto; }
.chat-index-ide {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin: 10px 10px 0;
  padding: 8px 12px;
  font-size: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  background: var(--surface);
  color: var(--ink-2);
  cursor: pointer;
  transition: all var(--dur-fast) var(--ease-out);
}
.chat-index-ide:hover { color: var(--brand); border-color: var(--brand); }
.chat-index-list {
  list-style: none;
  margin: 0;
  padding: 6px;
  overflow-y: auto;
  flex: 1;
  min-height: 0;
}
.chat-index-item {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  cursor: pointer;
  color: var(--ink-2);
  font-size: 12px;
  line-height: 1.45;
  transition: background var(--dur-fast) var(--ease-out), color var(--dur-fast) var(--ease-out);
}
.chat-index-item:hover { background: var(--brand-soft); color: var(--ink); }
.chat-index-num {
  flex-shrink: 0;
  font-size: 10px;
  color: var(--brand);
  background: var(--brand-soft);
  border: 1px solid var(--border-soft);
  border-radius: 4px;
  padding: 1px 5px;
  min-width: 22px;
  text-align: center;
}
.chat-index-text {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.chat-index-empty {
  padding: 18px 14px;
  font-size: 11px;
  color: var(--ink-faint);
  margin: 0;
  text-align: center;
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
.status-pill .status-dot.archived {
  width: 6px; height: 6px; border-radius: 50%;
  background: var(--warn);
}
.status-pill.archived-pill {
  border-color: var(--warn);
  color: var(--warn);
  background: var(--warn-soft);
}
.status-pill.live { background: var(--brand); color: var(--ink-invert); border-color: var(--brand); }
.abort-btn { padding: 4px 12px; font-size: 11px; }
.ws-btn {
  padding: 4px 12px;
  font-size: 11px;
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  background: var(--surface);
  color: var(--ink-2);
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  transition: all var(--dur-fast) var(--ease-out);
}
.ws-btn:hover { color: var(--brand); border-color: var(--brand); }
.ws-btn.on { background: var(--brand); color: var(--ink-invert); border-color: var(--brand); }
.ws-btn.on:hover { background: var(--brand-hover); }
.ide-btn {
  padding: 4px 12px;
  font-size: 11px;
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  background: var(--surface);
  color: var(--ink-2);
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  transition: all var(--dur-fast) var(--ease-out);
}
.ide-btn:hover { color: var(--brand); border-color: var(--brand); }

/* ====================================================================
   Archived block
   ==================================================================== */
.archived-block {
  padding: 64px 0;
  display: flex;
  justify-content: center;
}
.archived-message {
  padding: 48px 40px;
  text-align: center;
  max-width: 480px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}
.archived-icon {
  font-size: 56px;
  color: var(--ink-faint);
  line-height: 1;
}
.archived-title {
  font-family: var(--font-display);
  font-size: 28px;
  font-weight: 700;
  color: var(--ink);
  letter-spacing: -0.02em;
}
.archived-title strong { color: var(--warn); font-weight: 700; }
.archived-sub {
  font-size: 14px;
  color: var(--ink-2);
  line-height: 1.7;
  margin: 0;
}
.archived-message .btn-primary {
  margin-top: 12px;
  padding: 12px 28px;
}

/* ====================================================================
   Messages
   ==================================================================== */
.messages {
  overflow-y: auto;
  padding: 16px 0;
  scroll-behavior: smooth;
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

/* Input row: textarea + send button merged */
.composer-input-row {
  display: flex;
  align-items: center;
  gap: 0;
  border-top: 1px solid var(--border);
}
.composer-input-row .composer-input {
  border: 0 !important;
  border-radius: 0 !important;
  background: var(--surface) !important;
  padding: 12px 16px !important;
  font-size: 14px !important;
  font-family: var(--font-ui) !important;
  line-height: 1.65;
  flex: 1;
  min-width: 0;
  resize: none;
}
.composer-input-row .composer-input:focus { box-shadow: none !important; border: 0 !important; }
.composer-input-row .composer-input::placeholder { color: var(--ink-faint); }
.composer-input-row .composer-input:disabled { opacity: 0.55; cursor: not-allowed; }
.composer-input-row .send-btn {
  border-radius: 0;
  margin: 6px;
  padding: 10px 20px;
  font-size: 13px;
  flex-shrink: 0;
}

.composer-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  border-bottom: 1px solid var(--border);
  background: var(--surface-soft);
}
.rewind-btn {
  padding: 4px 12px;
  font-size: 11px;
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  background: var(--surface);
  color: var(--ink-2);
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  transition: all var(--dur-fast) var(--ease-out);
}
.rewind-btn:hover:not(:disabled) { color: var(--brand); border-color: var(--brand); }
.rewind-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.new-btn {
  padding: 4px 12px;
  font-size: 11px;
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  background: var(--surface);
  color: var(--ink-2);
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  transition: all var(--dur-fast) var(--ease-out);
}
.new-btn:hover:not(:disabled) { color: var(--brand); border-color: var(--brand); }
.new-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.rewind-hint { font-size: 11px; }

.composer-input {
  border: 0 !important;
  border-radius: 0 !important;
  background: var(--surface) !important;
  padding: 12px 16px !important;
  font-size: 14px !important;
  font-family: var(--font-ui) !important;
  line-height: 1.65;
  resize: none;
}
.composer-input:focus { box-shadow: none !important; border: 0 !important; }
.composer-input::placeholder { color: var(--ink-faint); }
.composer-input:disabled { opacity: 0.55; cursor: not-allowed; }

.composer-foot {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  padding: 10px 14px;
  border-top: 1px solid var(--border);
  background: var(--surface-soft);
  gap: 12px;
}
.send-btn { padding: 9px 22px; font-size: 13px; }

/* ====================================================================
   Mobile collapse
   ==================================================================== */
@media (max-width: 900px) {
  .chat { padding: 8px; grid-template-columns: 1fr; max-width: none; }
  .chat-index { display: none; }
  .workspace-tree { display: none; }
  .topbar { grid-template-columns: auto 1fr; gap: 10px; padding: 8px 12px; }
  .topbar-meta { border: 0; padding: 0; gap: 6px; }
  .session-title { font-size: 14px; }
  .turn { grid-template-columns: 1fr; gap: 4px; }
  .turn-gutter { flex-direction: row; align-items: center; gap: 8px; padding-top: 0; text-align: left; }
  .turn-num { font-size: 11px; }
  .messages { padding: 4px 0; }
  .composer-input-row { flex-wrap: wrap; }
  .composer-input-row .composer-input { padding: 10px 12px !important; }
  .composer-input-row .send-btn { width: 100%; margin: 0; border-radius: 0; }
}
</style>