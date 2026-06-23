<script setup lang="ts">
import {
  ref,
  computed,
  watch,
  onMounted,
  onUnmounted,
  nextTick,
} from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { useThemeStore } from '@/stores/theme'
import { useAuthStore } from '@/stores/auth'
import {
  getSession,
  getMessages,
  prompt,
  abort as abortSession,
  unarchive as restoreSession,
  newSession as newSessionRpc,
  branch as branchRpc,
  type SessionSummary,
  type ImageContent,
} from '@/api/session'
import { api } from '@/api/http'
import { OmpSocket } from '@/utils/websocket'
import { chooseImages, removeImage, type ImageItem } from '@/utils/image'
import { success, error, warn } from '@/utils/toast'
import MessageBubble from '@/components/MessageBubble.vue'
import ToolCard from '@/components/ToolCard.vue'
import UiRequestDialog from '@/components/UiRequestDialog.vue'
import OmpIcon from '@/components/OmpIcon.vue'

const theme = useThemeStore()
const auth = useAuthStore()

// ------ H5 滚动容器 ------
const msgContainer = ref<HTMLElement | null>(null)
const msgScrollTop = ref(0)

// ------ 路由参数 ------
const sessionId = ref('')

onLoad((options?: any) => {
  sessionId.value = options?.sessionId || ''
  if (!sessionId.value) {
    error('缺少会话 ID')
    uni.navigateBack()
    return
  }
  refresh().then(() => {
    if (session.value?.status !== 'archived') connectWs()
    // 历史消息加载完后强制滚到底
    setTimeout(() => scrollToBottom(true), 200)
    setTimeout(() => scrollToBottom(true), 600)
  })
})

// ------ 会话元信息 ------
const session = ref<SessionSummary | null>(null)
const isArchived = computed(() => session.value?.status === 'archived')

// ------ 消息状态模型 ------
type TimelineItem =
  | { kind: 'thinking'; text: string; order: number }
  | { kind: 'text'; text: string; order: number }
  | { kind: 'toolcall'; id: string; name: string; args: any; status: 'running' | 'done'; result?: string; error?: boolean; order: number }
  | { kind: 'tree'; entries: TreeEntry[]; selectedIdx: number; order: number }

interface TreeEntry {
  label: string
  sessionId: string
}

interface Turn {
  role: 'user' | 'assistant'
  timeline: TimelineItem[]
  userText?: string
}

const timeline = ref<TimelineItem[]>([])
const turnLog = ref<Turn[]>([])
const toolCallById = ref<Record<string, TimelineItem & { kind: 'toolcall' }>>({})
let itemOrder = 0

// ------ WebSocket ------
let socket: OmpSocket | null = null
const isStreaming = ref(false)
const isConnected = ref(false)

function connectWs() {
  const token = uni.getStorageSync('omp.token') || ''
  let url: string

  // #ifdef H5
  const wsProto = location.protocol === 'https:' ? 'wss:' : 'ws:'
  url = `${wsProto}//${location.host}/ws/sessions/${sessionId.value}?token=${encodeURIComponent(token)}`
  // #endif

  // #ifndef H5
  // #ifdef MP-WEIXIN || MP-ALIPAY || MP-TOUTIAO || MP-BAIDU || APP-PLUS
  url = `ws://192.168.1.100:8080/ws/sessions/${sessionId.value}?token=${encodeURIComponent(token)}`
  // #endif
  // #endif

  socket = new OmpSocket(url!)
  socket.onFrame(handleFrame)
  socket.onClose((code) => {
    isConnected.value = false
    isStreaming.value = false
    if (code === 1000) return
    warn('连接已断开')
  })
  socket.connect().then(() => {
    isConnected.value = true
  }).catch((e) => {
    error(e?.message || 'WebSocket 连接失败')
  })
}

// ------ 帧解析 ------
function handleFrame(frame: Record<string, unknown>) {
  switch (frame.type) {
    case 'message_update': {
      const evt = frame.assistantMessageEvent as any
      if (!evt) break

      if (evt.type === 'text_delta' && evt.delta) {
        if (isStreamingFiller(evt.delta)) {
          // 占位段：追加到末尾文本（保留），但不创建新段
          const last = timeline.value[timeline.value.length - 1]
          if (last && last.kind === 'text') last.text += evt.delta
          return
        }
        const last = timeline.value[timeline.value.length - 1]
        if (last && last.kind === 'text') {
          last.text += evt.delta
        } else {
          timeline.value.push({ kind: 'text', text: evt.delta, order: itemOrder++ })
        }
      }

      else if (evt.type === 'thinking_delta' && evt.delta) {
        if (isStreamingFiller(evt.delta)) {
          const last = timeline.value[timeline.value.length - 1]
          if (last && last.kind === 'thinking') last.text += evt.delta
          return
        }
        const last = timeline.value[timeline.value.length - 1]
        if (last && last.kind === 'thinking') {
          last.text += evt.delta
        } else {
          timeline.value.push({ kind: 'thinking', text: evt.delta, order: itemOrder++ })
        }
      }

      else if (evt.type === 'toolcall' || evt.type === 'tool_call') {
        const id = evt.id || evt.toolCallId || evt.tool_call_id || ''
        const existing = toolCallById.value[id]
        if (existing) {
          existing.name = evt.name || evt.toolName || evt.tool_name || existing.name
          existing.args = evt.args || evt.arguments || evt.input || existing.args
        } else {
          const item: any = {
            kind: 'toolcall',
            id,
            name: evt.name || evt.toolName || evt.tool_name || '',
            args: evt.args || evt.arguments || evt.input || {},
            status: 'running',
            order: itemOrder++,
          }
          timeline.value.push(item)
          toolCallById.value[id] = item
        }
      }
      break
    }

    case 'tool_execution_start': {
      const id = (frame.toolCallId as string) || ''
      if (!toolCallById.value[id]) {
        const item: any = {
          kind: 'toolcall',
          id,
          name: (frame.toolName as string) || '',
          args: frame.args || frame.arguments || {},
          status: 'running',
          order: itemOrder++,
        }
        timeline.value.push(item)
        toolCallById.value[id] = item
      }
      break
    }

    case 'tool_execution_end': {
      const id = (frame.toolCallId as string) || ''
      const item = toolCallById.value[id]
      if (item) {
        item.status = 'done'
        item.result = typeof frame.result === 'string' ? frame.result : JSON.stringify(frame.result)
        item.error = !!frame.isError
      }
      break
    }

    case 'agent_start': {
      isStreaming.value = true
      timeline.value = []
      toolCallById.value = {}
      itemOrder = 0
      break
    }

    case 'agent_end': {
      isStreaming.value = false
      const errMsg = frame.errorMessage as string | undefined
      if (errMsg) {
        try {
          const inner = JSON.parse(errMsg)
          const msg = inner?.error?.message || errMsg
          turnLog.value.push({ role: 'assistant', timeline: [{ kind: 'text', text: msg, order: itemOrder++ }] })
        } catch {
          turnLog.value.push({ role: 'assistant', timeline: [{ kind: 'text', text: errMsg, order: itemOrder++ }] })
        }
      }

      const msgs = frame.messages as any[] | undefined
      if (msgs?.length) {
        for (const m of msgs) {
          const innerErr = m.errorMessage
          if (innerErr) {
            try {
              const parsed = JSON.parse(innerErr)
              const msg = parsed?.error?.message || innerErr
              turnLog.value.push({
                role: 'assistant',
                timeline: [{ kind: 'text', text: msg, order: itemOrder++ }],
              })
            } catch {
              turnLog.value.push({
                role: 'assistant',
                timeline: [{ kind: 'text', text: innerErr, order: itemOrder++ }],
              })
            }
          }
        }
      }

      if (timeline.value.length > 0) {
        turnLog.value.push({ role: 'assistant', timeline: [...timeline.value] })
      }
      timeline.value = []
      toolCallById.value = {}
      break
    }

    case 'session_info_update': {
      const title = frame.title as string | undefined
      if (title) session.value!.title = title
      if (frame.sessionFile && frame.sessionFile !== session.value?.ompSessionFile) {
        refresh()
      }
      break
    }

    case 'extension_ui_request': {
      const method = frame.method as string
      if (method === 'notify' || method === 'setStatus' || method === 'setWidget' ||
          method === 'setTitle' || method === 'set_editor_text' || method === 'open_url') {
        // no-op
      } else if (method === 'cancel') {
        uiRequest.value = null
      } else if (['select', 'confirm', 'input', 'editor'].includes(method)) {
        uiRequest.value = {
          id: (frame.id as string) || '',
          method: method as any,
          title: (frame.title as string) || '请输入',
          options: frame.options as string[] | undefined,
          message: frame.message as string | undefined,
          placeholder: frame.placeholder as string | undefined,
          prefill: frame.prefill as string | undefined,
          timeout: frame.timeout as number | undefined,
        }
      }
      break
    }
  }

  scrollToBottom()
}

// ------ UI 询问弹窗 ------
interface UiReq {
  id: string
  method: 'select' | 'confirm' | 'input' | 'editor'
  title: string
  options?: string[]
  message?: string
  placeholder?: string
  prefill?: string
  timeout?: number
}
const uiRequest = ref<UiReq | null>(null)

function sendUiResponse(id: string, value: string | boolean) {
  if (!socket) return
  const frame: Record<string, unknown> = { type: 'extension_ui_response', id }
  if (typeof value === 'boolean') {
    if (value) frame.confirmed = true
    else frame.cancelled = true
  } else {
    frame.value = value
  }
  socket.send(frame).catch(() => {})
  uiRequest.value = null
}

function cancelUiRequest(id: string) {
  if (!socket) return
  socket.send({ type: 'extension_ui_response', id, cancelled: true }).catch(() => {})
  uiRequest.value = null
}

// ------ 数据加载 ------
async function refresh() {
  try {
    session.value = await getSession(sessionId.value)
  } catch (e: any) {
    error(e?.message || '加载失败')
    return
  }

  try {
    const data = await getMessages(sessionId.value)
    hydrateHistory(data.messages || [])
  } catch {
    // ignore
  }
}

function hydrateHistory(messages: any[]) {
  const resultMap: Record<string, { result?: string; error?: boolean }> = {}

  for (const c of messages) {
    const kind = c.role || c.type || c.kind
    if (kind === 'tool' || kind === 'toolResult' || kind === 'tool_result') {
      const id = c.toolCallId || c.tool_call_id || c.id || ''
      if (id) {
        let resultText: string
        if (typeof c.result === 'string') resultText = c.result
        else if (Array.isArray(c.content)) {
          resultText = c.content.map((b: any) => b.text || JSON.stringify(b)).join('')
        } else resultText = JSON.stringify(c.result || c.content || '')
        resultMap[id] = { result: resultText, error: c.isError || c.error }
      }
    }
  }

  const turns: Turn[] = []
  let pendingTimeline: TimelineItem[] = []

  for (const c of messages) {
    const kind = c.role || c.type || c.kind

    if (kind === 'user') {
      if (pendingTimeline.length > 0) {
        turns.push({ role: 'assistant', timeline: pendingTimeline })
        pendingTimeline = []
      }
      let userText = ''
      if (typeof c.content === 'string') userText = c.content
      else if (Array.isArray(c.content)) {
        userText = c.content
          .filter((b: any) => b?.type === 'text' || typeof b === 'string')
          .map((b: any) => (typeof b === 'string' ? b : b.text || ''))
          .join('')
      } else {
        userText = c.text || c.message || ''
      }
      turns.push({ role: 'user', timeline: [], userText })
    } else if (kind === 'assistant' || kind === 'agent') {
      const blocks = Array.isArray(c.content) ? c.content : (c.blocks || [])
      for (const block of blocks) {
        appendToTimeline(block, pendingTimeline, resultMap)
      }
    }
  }

  if (pendingTimeline.length > 0) {
    turns.push({ role: 'assistant', timeline: pendingTimeline })
  }

  turnLog.value = turns
}

/**
 * 判断 delta 是否为"流式占位段"（应忽略，但保留它向后合并的可能）
 * - 长度极短（<= 3 chars）
 * - 只含空白、句末标点（.。?!！ ·\n\r 等）
 */
function isStreamingFiller(s: string): boolean {
  if (!s) return true
  const trimmed = s.replace(/[\s ​-‍﻿]/g, '')
  if (trimmed.length === 0) return true
  if (trimmed.length > 3) return false
  return /^[.。,，·:：;；?!！…\-—]+$/.test(trimmed)
}

function appendToTimeline(
  block: any,
  tl: TimelineItem[],
  resultMap: Record<string, { result?: string; error?: boolean }>,
) {
  const type = block.type || block.kind || ''

  if (type === 'text' || type === 'text_delta') {
    const text = block.text || block.delta || block.content || ''
    if (isStreamingFiller(text)) {
      // 占位段：若已有 text 段，附加（保留句末标点）；否则忽略
      const last = tl[tl.length - 1]
      if (last && last.kind === 'text') last.text += text
      return
    }
    const last = tl[tl.length - 1]
    if (last && last.kind === 'text') {
      last.text += text
    } else {
      tl.push({ kind: 'text', text, order: itemOrder++ })
    }
  } else if (type === 'thinking' || type === 'thinking_delta') {
    const text = block.thinking || block.text || block.delta || block.content || ''
    if (isStreamingFiller(text)) {
      const last = tl[tl.length - 1]
      if (last && last.kind === 'thinking') last.text += text
      return
    }
    const last = tl[tl.length - 1]
    if (last && last.kind === 'thinking') {
      last.text += text
    } else {
      tl.push({ kind: 'thinking', text, order: itemOrder++ })
    }
  } else if (
    type === 'toolCall' || type === 'tool_call' || type === 'toolcall'
  ) {
    const id = block.id || block.toolCallId || block.tool_call_id || ''
    const r = resultMap[id]
    tl.push({
      kind: 'toolcall',
      id,
      name: block.name || block.toolName || block.tool_name || '',
      args: block.arguments || block.args || block.input || {},
      status: 'done',
      result: r?.result,
      error: r?.error,
      order: itemOrder++,
    })
  }
}

// ------ 滚动控制 — 智能自动跟随 ------
const scrollTop = ref(0)
const userScrolledUp = ref(false)
const userScrollTimer = ref<ReturnType<typeof setTimeout> | null>(null)
const SCROLL_RESUME_DELAY = 5000
const SCROLL_BOTTOM_THRESHOLD = 40

function getMsgEl(): HTMLElement | null {
  // #ifdef H5
  if (msgContainer.value) {
    const el = msgContainer.value as any
    return el?.$el || el || null
  }
  // #endif
  return document.querySelector('.messages') as HTMLElement | null
}

function isAtBottom(): boolean {
  const el = getMsgEl()
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight <= SCROLL_BOTTOM_THRESHOLD
}

// 思考块展开/收起：避免布局变化导致滚动跳到顶部
function toggleThinking() {
  showThinking.value = !showThinking.value
  setTimeout(() => scrollToBottom(true), 280)
}

function scrollToBottom(force = false) {
  nextTick(() => {
    // #ifdef H5
    const el = getMsgEl()
    if (el) {
      if (force || !userScrolledUp.value || isAtBottom()) {
        smoothScrollToBottom(el)
        userScrolledUp.value = false
        if (userScrollTimer.value) {
          clearTimeout(userScrollTimer.value)
          userScrollTimer.value = null
        }
      }
    }
    // #endif
    // #ifndef H5
    scrollTop.value = 999999
    // #endif
  })
}

// 滚动用 rAF 防抖，避免高频更新导致抖动
let pendingScrollFrame: number | null = null
function debouncedScrollToBottom() {
  if (pendingScrollFrame !== null) return
  pendingScrollFrame = requestAnimationFrame(() => {
    pendingScrollFrame = null
    scrollToBottom()
  })
}

let scrollAnimFrame: number | null = null
function smoothScrollToBottom(el: HTMLElement) {
  if (scrollAnimFrame !== null) cancelAnimationFrame(scrollAnimFrame)
  const target = el.scrollHeight
  const start = el.scrollTop
  const distance = target - start
  if (distance <= 0) return
  const duration = Math.min(280, 120 + distance * 0.3)
  const t0 = performance.now()
  const step = (now: number) => {
    const t = Math.min(1, (now - t0) / duration)
    // ease-out-cubic
    const eased = 1 - Math.pow(1 - t, 3)
    el.scrollTop = start + distance * eased
    if (t < 1) scrollAnimFrame = requestAnimationFrame(step)
    else scrollAnimFrame = null
  }
  scrollAnimFrame = requestAnimationFrame(step)
}

function handleScroll() {
  if (isAtBottom()) {
    userScrolledUp.value = false
    if (userScrollTimer.value) {
      clearTimeout(userScrollTimer.value)
      userScrollTimer.value = null
    }
  } else {
    if (!userScrolledUp.value) {
      userScrolledUp.value = true
      if (userScrollTimer.value) clearTimeout(userScrollTimer.value)
      userScrollTimer.value = setTimeout(() => {
        userScrolledUp.value = false
        userScrollTimer.value = null
        scrollToBottom(true)
      }, SCROLL_RESUME_DELAY)
    }
  }
}

watch(() => timeline.value.length, debouncedScrollToBottom)
watch(() => turnLog.value.length, debouncedScrollToBottom)

// 进入页面与刷新历史后滚到底
onMounted(() => {
  // 同步滚动到顶，避免浏览器 paint 一帧顶部状态
  const el = getMsgEl()
  if (el) el.scrollTop = el.scrollHeight
  setTimeout(() => scrollToBottom(true), 100)
})

onUnmounted(() => {
  if (userScrollTimer.value) clearTimeout(userScrollTimer.value)
  if (scrollAnimFrame !== null) cancelAnimationFrame(scrollAnimFrame)
})

// ------ 发送消息 ------
const inputText = ref('')
const pendingImages = ref<ImageItem[]>([])
const sending = ref(false)

async function sendMessage() {
  const text = inputText.value.trim()
  if (!text && pendingImages.value.length === 0) return
  if (sending.value) return

  sending.value = true

  // 立即清空输入，不等待网络返回
  const sendText = text || '[图片]'
  const sendImages = [...pendingImages.value]
  inputText.value = ''
  pendingImages.value = []

  if (text.startsWith('/tree')) {
    await handleTreeCommand()
    sending.value = false
    return
  }
  if (text.startsWith('/rewind')) {
    // passthrough
  }

  try {
    const images: ImageContent[] = sendImages.map((p) => ({
      data: p.base64,
      mimeType: p.mimeType,
    }))

    turnLog.value.push({
      role: 'user',
      timeline: [],
      userText: sendText,
    })

    await prompt(
      sessionId.value,
      text || '请查看图片',
      images.length > 0 ? images : undefined,
    )
  } catch (e: any) {
    error(e?.message || '发送失败')
    if (sendText !== '[图片]') inputText.value = sendText
    pendingImages.value = sendImages
  } finally {
    sending.value = false
  }
}

// ------ /tree 命令 ------
const treeMode = ref(false)
const treeEntries = ref<TreeEntry[]>([])

async function handleTreeCommand() {
  try {
    const data = await getMessages(sessionId.value)
    const msgs = data.messages || []
    const entries = buildTreeEntries(msgs)
    if (entries.length === 0) {
      warn('没有可用的树形分支')
      return
    }
    treeEntries.value = entries
    treeMode.value = true
    inputText.value = ''
  } catch {
    error('获取树形结构失败')
  }
}

function buildTreeEntries(msgs: any[]): TreeEntry[] {
  const seen = new Set<string>()
  const entries: TreeEntry[] = []
  for (const m of msgs) {
    const sid = m.sessionId || m.session_id
    if (sid && !seen.has(sid)) {
      seen.add(sid)
      entries.push({
        label: `#${sid.slice(0, 12)}`,
        sessionId: sid,
      })
    }
  }
  return entries
}

async function treeEnter(entry: TreeEntry) {
  try {
    await branchRpc(sessionId.value, entry.sessionId)
    success('已跳转')
    treeMode.value = false
  } catch (e: any) {
    error(e?.message || '跳转失败')
  }
}

function exitTreeMode() {
  treeMode.value = false
}

// ------ 图片上传 ------
async function pickImages() {
  try {
    const items = await chooseImages(5 - pendingImages.value.length)
    pendingImages.value.push(...items)
  } catch {
    // cancel
  }
}

function removePendingImage(id: string) {
  pendingImages.value = removeImage(pendingImages.value, id)
}

function previewImage(item: ImageItem) {
  uni.previewImage({
    urls: [item.localPath],
    current: item.localPath,
  })
}

// ------ 中断 / 新对话 / 回退 ------
async function doAbort() {
  if (!sessionId.value) return
  try {
    await abortSession(sessionId.value)
    success('已中断')
  } catch (e: any) {
    error(e?.message || '中断失败')
  }
}

async function doRewind() {
  if (sending.value || isStreaming.value || isArchived.value) return
  const text = '/rewind'
  sending.value = true
  // 同步推入用户回退消息
  turnLog.value.push({ role: 'user', timeline: [], userText: text })
  timeline.value = []
  toolCallById.value = {}
  try {
    await prompt(sessionId.value, text)
  } catch (e: any) {
    error(e?.message || '回退失败')
  } finally {
    sending.value = false
  }
}

async function doNew() {
  if (!sessionId.value) return
  try {
    const r = await newSessionRpc(sessionId.value)
    success('新对话已创建')
    sessionId.value = r.sessionId
    turnLog.value = []
    timeline.value = []
    toolCallById.value = {}
    uiRequest.value = null
    socket?.close()
    connectWs()
  } catch (e: any) {
    error(e?.message || '新对话创建失败')
  }
}

// ------ 归档恢复 ------
async function doRestore() {
  try {
    await restoreSession(sessionId.value)
    success('会话已恢复')
    session.value!.status = 'active'
    connectWs()
  } catch (e: any) {
    error(e?.message || '恢复失败')
  }
}

// ------ 思考块折叠 ------
const showThinking = ref(false)

// 是否有可回退消息
const canRewind = computed(() => turnLog.value.length > 0)

// ------ 返回 ------
function goBack() {
  socket?.close()
  const pages = getCurrentPages()
  if (pages.length > 1) {
    uni.navigateBack()
  } else {
    uni.reLaunch({ url: '/pages/sessions/sessions' })
  }
}

// ------ 生命周期 ------
onUnmounted(() => {
  socket?.close()
})
</script>


<template>
  <view :class="['chat', theme.themeClass()]">
    <!-- ============ Topbar ============ -->
    <view class="topbar">
      <view class="back-btn" @click="goBack">
        <text class="back-icon">‹</text>
        <text class="back-text">返回</text>
      </view>
      <view class="topbar-meta">
        <view class="repo-chip" v-if="session?.repoId">
          <text class="repo-chip-label">{{ session.repoId }}</text>
        </view>
        <text class="session-title">{{ session?.title || '未命名会话' }}</text>
      </view>
      <view class="topbar-status">
        <view :class="['conn-dot', isConnected ? 'on' : 'off']" />
      </view>
    </view>

    <!-- ============ Messages ============ -->
    <!-- #ifdef H5 -->
    <view class="messages" ref="msgContainer" @scroll="handleScroll">
    <!-- #endif -->
    <!-- #ifndef H5 -->
    <scroll-view
      class="messages"
      scroll-y
      :scroll-top="scrollTop"
      :scroll-with-animation="true"
      :show-scrollbar="false"
      :enhanced="false"
    >
    <!-- #endif -->

      <!-- Archived banner -->
      <view v-if="isArchived" class="archived-block">
        <view class="archived-card">
          <text class="archived-title">此会话已归档</text>
          <text class="archived-sub">归档状态不可发送消息，恢复后可继续对话。</text>
          <view class="archived-btn" @click="doRestore">
            <text>{{ session?.status === 'archived' ? '恢复会话' : '正在恢复…' }}</text>
          </view>
        </view>
      </view>

      <!-- 历史 turns -->
      <view
        v-for="(t, i) in turnLog"
        :key="'turn-' + i"
        class="turn"
      >
        <view class="turn-gutter">
          <text :class="['turn-num', t.role === 'assistant' ? 'accent' : '']">
            {{ String(i + 1).padStart(2, '0') }}
          </text>
          <text :class="['turn-role', t.role === 'assistant' ? 'accent' : '']">
            {{ t.role === 'user' ? '操作员' : '代理' }}
          </text>
        </view>
        <view class="turn-body">
          <!-- 用户消息 -->
          <MessageBubble
            v-if="t.role === 'user'"
            role="user"
            :text="t.userText || ''"
          />

          <!-- 助手消息：thinking / text / toolcall 序列 -->
          <template v-if="t.role === 'assistant' && Array.isArray(t.timeline) && t.timeline.length">
            <template v-for="item in t.timeline" :key="item.order">
              <!-- Thinking 折叠块 -->
              <view v-if="item.kind === 'thinking'" class="thinking-block">
                <view class="thinking-summary" @click="toggleThinking">
                  <text class="caret">{{ showThinking ? '▾' : '▸' }}</text>
                  <text class="thinking-summary-text">思考 · thinking</text>
                </view>
                <view v-if="showThinking" class="thinking-text">{{ (item as any).text }}</view>
              </view>

              <!-- 文本 -->
              <MessageBubble
                v-else-if="item.kind === 'text'"
                role="assistant"
                :text="(item as any).text"
              />

              <!-- 工具卡 -->
              <ToolCard
                v-else-if="item.kind === 'toolcall'"
                :tool-name="(item as any).name"
                :args="(item as any).args"
                :result="(item as any).result"
                :is-error="(item as any).error"
                :status="(item as any).status"
              />
            </template>
          </template>
        </view>
      </view>

      <!-- 实时流式 turn -->
      <view v-if="isStreaming || timeline.length > 0" class="turn turn-live">
        <view class="turn-gutter">
          <text class="turn-num accent live-num">●</text>
          <text class="turn-role accent">实时</text>
        </view>
        <view class="turn-body">
          <view v-if="timeline.some(it => it.kind === 'thinking')" class="turn-label">
            <view class="toggle-think" @click="toggleThinking">
              <text>{{ showThinking ? '收起思考' : '展开思考' }}</text>
            </view>
          </view>
          <template v-for="item in timeline" :key="'live-' + item.order">
            <view v-if="item.kind === 'thinking'" class="thinking-block">
              <view class="thinking-summary" @click="toggleThinking">
                <text class="caret">{{ showThinking ? '▾' : '▸' }}</text>
                <text class="thinking-summary-text">思考 · thinking</text>
              </view>
              <view v-if="showThinking" class="thinking-text">{{ (item as any).text }}</view>
            </view>

            <view v-else-if="item.kind === 'text'" class="streaming-block">
              <MessageBubble role="assistant" :text="(item as any).text" />
            </view>

            <ToolCard
              v-else-if="item.kind === 'toolcall'"
              :tool-name="(item as any).name"
              :args="(item as any).args"
              :result="(item as any).result"
              :is-error="(item as any).error"
              :status="(item as any).status"
            />
          </template>

          <view v-if="isStreaming && !timeline.length" class="typing">
            <view class="typing-dot" />
            <view class="typing-dot" style="animation-delay:.15s" />
            <view class="typing-dot" style="animation-delay:.3s" />
          </view>
        </view>
      </view>

      <!-- UI 询问 -->
      <view v-if="uiRequest" class="turn ui-request-turn">
        <view class="turn-gutter">
          <text class="turn-num accent">◆</text>
          <text class="turn-role accent">等待输入</text>
        </view>
        <view class="turn-body">
          <UiRequestDialog
            :request="uiRequest"
            @submit="sendUiResponse"
            @cancel="cancelUiRequest"
          />
        </view>
      </view>

      <view style="height: 16px" />
    <!-- #ifdef H5 -->
    </view>
    <!-- #endif -->
    <!-- #ifndef H5 -->
    </scroll-view>
    <!-- #endif -->

    <!-- ============ Composer ============ -->
    <view v-if="!isArchived" class="composer">
      <!-- 工具栏 -->
      <view class="composer-toolbar">
        <view
          v-if="canRewind"
          class="btn-mini"
          :class="{ disabled: sending || isStreaming }"
          @click="doRewind"
        >
          <text class="caret">↺</text>
          <text>回退</text>
        </view>
        <view class="btn-mini" :class="{ disabled: sending || isStreaming }" @click="doNew">
          <text class="caret">+</text>
          <text>新对话</text>
        </view>
        <view
          v-if="isStreaming"
          class="btn-mini-danger"
          @click="doAbort"
        >
          <text>中断</text>
        </view>
        <text class="composer-hint">{{ isStreaming ? '生成中…' : '' }}</text>
      </view>

      <!-- 图片缩略 -->
      <view v-if="pendingImages.length > 0" class="composer-thumbs">
        <view
          v-for="img in pendingImages"
          :key="img.id"
          class="composer-thumb"
          @click="previewImage(img)"
        >
          <image :src="img.localPath" mode="aspectFill" class="thumb-img" />
          <view class="thumb-x" @click.stop="removePendingImage(img.id)">
            <text>✕</text>
          </view>
        </view>
      </view>

      <!-- 输入行 -->
      <view class="composer-input-row">
        <view class="attach-btn" @click="pickImages">
          <text class="attach-icon">📎</text>
          <text v-if="pendingImages.length > 0" class="attach-badge">{{ pendingImages.length }}</text>
        </view>
        <textarea
          v-model="inputText"
          class="composer-input"
          :placeholder="isStreaming ? '生成中，可继续输入…' : '在此描述你的需求…'"
          placeholder-class="composer-ph"
          :disabled="sending"
          :auto-height="true"
          :show-confirm-bar="false"
          confirm-type="send"
          :maxlength="-1"
          @confirm="sendMessage"
        />
        <view
          class="send-btn"
          :class="{ disabled: sending || (!inputText.trim() && pendingImages.length === 0) }"
          @click="sendMessage"
        >
          <text>{{ isStreaming ? '生成中…' : (sending ? '提交中…' : '发送') }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
// ====================================================================
// Chat View — 移动端编辑风格（参照 Web ChatView 移植）
// ====================================================================

.chat {
  display: flex;
  flex-direction: column;
  height: 100vh;
  height: 100dvh;
  padding: 8px;
  gap: 10px;
  background: #fff;
  overflow: hidden;
  box-sizing: border-box;
}

// ============ Topbar — 胶囊卡 ============
.topbar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 14px;
  padding-top: calc(8px + env(safe-area-inset-top, 0));
  background: #fff;
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: 999px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03), 0 2px 8px rgba(0, 0, 0, 0.03);
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 12px;
  border-radius: 999px;
  background: rgba(7, 193, 96, 0.08);
  flex-shrink: 0;
  &:active { background: rgba(7, 193, 96, 0.15); }
}
.back-icon { font-size: 18px; color: #07c160; line-height: 1; }
.back-text { font-size: 13px; color: #07c160; }

.topbar-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 8px;
  border-left: 1px solid rgba(0, 0, 0, 0.06);
}

.repo-chip {
  display: inline-flex;
  align-items: center;
  padding: 3px 9px;
  background: rgba(7, 193, 96, 0.1);
  border: 1px solid rgba(7, 193, 96, 0.2);
  color: #07c160;
  border-radius: 999px;
  flex-shrink: 0;
  max-width: 120px;
}
.repo-chip-label {
  font-size: 11px;
  font-weight: 600;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.session-title {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  font-weight: 600;
  color: #1a1a1a;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.topbar-status { flex-shrink: 0; }
.conn-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.conn-dot.on { background: #07c160; box-shadow: 0 0 4px rgba(7,193,96,.6); }
.conn-dot.off { background: #c0c0c0; }

// ============ Messages ============
.messages {
  flex: 1;
  height: 0;
  min-height: 0;
  padding: 12px 6px 0;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  background: #fff;
}

// 归档卡
.archived-block {
  padding: 30px 16px;
  display: flex;
  justify-content: center;
}
.archived-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 24px 20px;
  background: #fff;
  border: 1px solid rgba(0,0,0,.06);
  border-radius: 12px;
  max-width: 320px;
  box-shadow: 0 1px 3px rgba(0,0,0,.03);
}
.archived-title {
  font-size: 16px; font-weight: 600;
  color: #1a1a1a; margin-bottom: 8px;
}
.archived-sub {
  font-size: 13px; color: #888;
  line-height: 1.6; margin-bottom: 18px;
}
.archived-btn {
  padding: 8px 22px;
  background: #07c160;
  color: #fff;
  border-radius: 6px;
  font-size: 14px;
  &:active { background: #06ad55; }
}

// ============ Turn — 编号 + role + body ============
.turn {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 0;
  margin-bottom: 12px;
  animation: turn-in .35s cubic-bezier(.22,1,.36,1) both;
}
@keyframes turn-in {
  from { opacity: 0; transform: translateY(8px); }
  to   { opacity: 1; transform: translateY(0); }
}

// Gutter — 横向小标签（移动端）
.turn-gutter {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 2px;
}

.turn-num {
  font-size: 11px;
  color: #888;
  background: #f0efec;
  border: 1px solid rgba(0,0,0,.06);
  border-radius: 4px;
  padding: 1px 7px;
  font-weight: 600;
  letter-spacing: 0.04em;
}
.turn-num.accent {
  color: #07c160;
  background: rgba(7,193,96,.08);
  border-color: rgba(7,193,96,.2);
}

.turn-role {
  font-size: 11px;
  color: #888;
  font-weight: 500;
}
.turn-role.accent { color: #07c160; }

.live-num {
  animation: gentle-pulse 1.8s ease-in-out infinite;
}
@keyframes gentle-pulse {
  0%, 100% { opacity: .5; }
  50% { opacity: 1; }
}

.turn-body {
  min-width: 0;
}
.turn-label {
  margin-bottom: 8px;
  display: flex;
  justify-content: flex-start;
}

.toggle-think {
  display: inline-flex;
  padding: 4px 12px;
  font-size: 11px;
  color: #888;
  background: #fff;
  border: 1px solid rgba(0,0,0,.08);
  border-radius: 999px;
  &:active { background: #f0efec; }
}

// ============ Thinking 折叠块 ============
.thinking-block {
  border: 1px dashed rgba(0,0,0,.1);
  border-radius: 8px;
  background: rgba(0,0,0,.02);
  padding: 10px 14px;
  margin: 6px 0 10px;
}
.thinking-summary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.caret { font-size: 11px; color: #07c160; }
.thinking-summary-text { font-size: 11px; color: #888; }
.thinking-text {
  margin-top: 8px;
  font-size: 12.5px;
  line-height: 1.6;
  color: #555;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: var(--font-mono, monospace);
}

// 流式块
.streaming-block { display: block; }

// 打字指示器
.typing {
  display: flex;
  gap: 5px;
  padding: 8px 0;
}
.typing-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #b5b5b5;
  animation: typing-bounce 1.2s ease-in-out infinite;
}
@keyframes typing-bounce {
  0%, 60%, 100% { opacity: .3; transform: scale(.85); }
  30% { opacity: 1; transform: scale(1); }
}

// ============ Composer — 卡片+按钮 ============
.composer {
  flex-shrink: 0;
  background: #fff;
  border: 1px solid rgba(0,0,0,.06);
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 2px rgba(0,0,0,.03), 0 4px 12px rgba(0,0,0,.03);
  margin-bottom: env(safe-area-inset-bottom, 0);
}

// Toolbar
.composer-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-bottom: 1px solid rgba(0,0,0,.06);
  background: #fafaf8;
}
.btn-mini {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
  font-size: 11px;
  color: #555;
  background: #fff;
  border: 1px solid rgba(0,0,0,.08);
  border-radius: 999px;
  &:active:not(.disabled) {
    border-color: #07c160;
    color: #07c160;
  }
  &.disabled { opacity: .4; }
}
.btn-mini .caret { color: #07c160; font-size: 12px; }
.btn-mini-danger {
  display: inline-flex;
  align-items: center;
  padding: 4px 12px;
  font-size: 11px;
  background: rgba(250,81,81,.08);
  border: 1px solid rgba(250,81,81,.2);
  color: #fa5151;
  border-radius: 999px;
}
.composer-hint {
  margin-left: auto;
  font-size: 11px;
  color: #888;
}

// 缩略图行
.composer-thumbs {
  display: flex;
  gap: 8px;
  padding: 8px 10px 0;
  flex-wrap: wrap;
}
.composer-thumb {
  position: relative;
  width: 56px;
  height: 56px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid rgba(0,0,0,.08);
}
.thumb-img { width: 100%; height: 100%; }
.thumb-x {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: rgba(0,0,0,.55);
  color: #fff;
  font-size: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}

// 输入行
.composer-input-row {
  display: flex;
  align-items: flex-end;
  gap: 0;
  padding: 4px 4px 4px 0;
}

.attach-btn {
  position: relative;
  width: 38px;
  height: 38px;
  margin: 4px 2px 4px 8px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #888;
  flex-shrink: 0;
  &:active { background: rgba(7,193,96,.1); color: #07c160; }
}
.attach-icon { font-size: 16px; }
.attach-badge {
  position: absolute;
  top: 2px;
  right: 2px;
  min-width: 14px;
  height: 14px;
  padding: 0 3px;
  border-radius: 999px;
  background: #07c160;
  color: #fff;
  font-size: 9px;
  font-weight: 600;
  line-height: 14px;
  text-align: center;
}

.composer-input {
  flex: 1;
  min-height: 38px;
  max-height: 120px;
  padding: 10px 12px;
  background: #fff;
  border: 0;
  font-size: 14px;
  color: #1a1a1a;
  line-height: 1.55;
  resize: none;
}
.composer-ph { color: #b5b5b5; }

.send-btn {
  margin: 4px;
  padding: 8px 18px;
  background: #07c160;
  color: #fff;
  font-size: 13px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  &:active:not(.disabled) { background: #06ad55; }
  &.disabled {
    background: #d8d6d2;
    color: #fff;
    opacity: .6;
  }
}

// ============ 移动端窄屏适配 ============
@media (max-width: 380px) {
  .chat { padding: 6px; gap: 8px; }
  .repo-chip { max-width: 80px; }
  .session-title { font-size: 13px; }
  .turn { padding: 10px 0; margin-bottom: 10px; }
  .composer-input { font-size: 13px; }
}
</style>
