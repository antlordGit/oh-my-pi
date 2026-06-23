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

// ------ 中断 / 新对话 ------
async function doAbort() {
  if (!sessionId.value) return
  try {
    await abortSession(sessionId.value)
    success('已中断')
  } catch (e: any) {
    error(e?.message || '中断失败')
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
  <view :class="['page', theme.themeClass()]">
    <!-- ========== 导航栏 ========== -->
    <view class="navbar">
      <view class="navbar-left" @click="goBack">
        <OmpIcon name="back" size="22" style="color: var(--brand)" />
        <text class="back-label">返回</text>
      </view>
      <view class="navbar-title-wrap">
        <text class="navbar-title">{{ session?.title || '会话' }}</text>
        <view class="navbar-sub">
          <view :class="['dot', isConnected ? 'on' : 'off']" />
          <text class="navbar-sub-text">{{ isConnected ? (isStreaming ? '正在回复…' : '已连接') : '未连接' }}</text>
        </view>
      </view>
      <view class="navbar-right" />
    </view>

    <!-- ========== 消息区域 ========== -->
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
      <!-- 归档恢复条 -->
      <view v-if="isArchived" class="archived-banner">
        <text class="banner-text">此会话已归档，不可发送消息</text>
        <view class="banner-btn" @click="doRestore">恢复会话</view>
      </view>

      <!-- 历史 turns -->
      <template v-for="(turn, i) in turnLog" :key="'turn-' + i">
        <view v-if="turn.role === 'user'" class="row row-user">
          <view class="bubble bubble-user">
            <view class="bubble-tail" />
            <view class="bubble-text user-text">{{ turn.userText }}</view>
          </view>
          <view class="avatar avatar-user">
            <OmpIcon name="user" size="20" style="color: #fff" />
          </view>
        </view>

        <view v-else class="row row-assistant">
          <view class="avatar avatar-ai">
            <OmpIcon name="robot" size="20" style="color: #fff" />
          </view>
          <view class="bubble bubble-ai">
            <view class="bubble-content">
              <template v-for="item in turn.timeline" :key="item.order">
                <view v-if="item.kind === 'thinking'" class="thinking">
                  <view class="thinking-tag" @click="toggleThinking">
                    <text class="thinking-tag-text">{{ showThinking ? '收起' : '展开' }}思考</text>
                  </view>
                  <view v-if="showThinking" class="thinking-content">
                    <MessageBubble role="assistant" :text="(item as any).text" />
                  </view>
                </view>

                <MessageBubble
                  v-else-if="item.kind === 'text'"
                  role="assistant"
                  :text="(item as any).text"
                />

                <ToolCard
                  v-else-if="item.kind === 'toolcall'"
                  :tool-name="(item as any).name"
                  :args="(item as any).args"
                  :result="(item as any).result"
                  :is-error="(item as any).error"
                  :status="(item as any).status"
                />
              </template>
            </view>
          </view>
        </view>
      </template>

      <!-- 实时流式 turn -->
      <view v-if="timeline.length > 0" class="row row-assistant">
        <view class="avatar avatar-ai">
          <OmpIcon name="robot" size="20" style="color: #fff" />
        </view>
        <view class="bubble bubble-ai">
          <view class="bubble-tail bubble-tail-l" />
          <view class="bubble-content">
            <template v-for="item in timeline" :key="'live-' + item.order">
              <view v-if="item.kind === 'thinking'" class="thinking">
                <view class="thinking-tag" @click="toggleThinking">
                  <text class="thinking-tag-text">{{ showThinking ? '收起' : '展开' }}思考</text>
                </view>
                <view v-if="showThinking" class="thinking-content">
                  <MessageBubble role="assistant" :text="(item as any).text" />
                </view>
              </view>

              <MessageBubble
                v-else-if="item.kind === 'text'"
                role="assistant"
                :text="(item as any).text"
              />

              <ToolCard
                v-else-if="item.kind === 'toolcall'"
                :tool-name="(item as any).name"
                :args="(item as any).args"
                :result="(item as any).result"
                :is-error="(item as any).error"
                :status="(item as any).status"
              />
            </template>

            <view v-if="isStreaming" class="typing">
              <view class="typing-dot" />
              <view class="typing-dot" style="animation-delay: .2s" />
              <view class="typing-dot" style="animation-delay: .4s" />
            </view>
          </view>
        </view>
      </view>

      <!-- /tree 模式 -->
      <view v-if="treeMode" class="tree-panel">
        <view class="tree-head">
          <text class="tree-title">会话分支</text>
          <view class="tree-close" @click="exitTreeMode">
            <OmpIcon name="close" size="18" style="color: var(--ink-mute)" />
          </view>
        </view>
        <view class="tree-list">
          <view
            v-for="(entry, idx) in treeEntries"
            :key="entry.sessionId"
            class="tree-row"
            @click="treeEnter(entry)"
          >
            <text>{{ entry.label }}</text>
            <OmpIcon name="back" size="14" style="color: var(--ink-faint); transform: rotate(180deg)" />
          </view>
        </view>
      </view>

      <!-- UI 询问弹窗 -->
      <view v-if="uiRequest" class="row row-assistant">
        <view class="avatar avatar-ai">
          <OmpIcon name="robot" size="20" style="color: #fff" />
        </view>
        <view class="bubble bubble-ai" style="flex: 1">
          <view class="bubble-tail bubble-tail-l" />
          <view class="bubble-content" style="padding: 0">
            <UiRequestDialog
              :request="uiRequest"
              @submit="sendUiResponse"
              @cancel="cancelUiRequest"
            />
          </view>
        </view>
      </view>

      <view style="height: 16px" />
    <!-- #ifdef H5 -->
    </view>
    <!-- #endif -->
    <!-- #ifndef H5 -->
    </scroll-view>
    <!-- #endif -->

    <!-- ========== Composer ========== -->
    <view class="composer">
      <view v-if="pendingImages.length > 0" class="composer-images">
        <view
          v-for="img in pendingImages"
          :key="img.id"
          class="thumb"
          @click="previewImage(img)"
        >
          <image :src="img.localPath" mode="aspectFill" class="thumb-img" />
          <view class="thumb-close" @click.stop="removePendingImage(img.id)">×</view>
        </view>
      </view>

      <view class="toolbar-row">
        <view class="toolbar-btn" @click="pickImages">
          <OmpIcon name="image" size="16" />
          <text class="toolbar-text">图片</text>
        </view>
        <view v-if="!isArchived" class="toolbar-btn" @click="doNew">
          <OmpIcon name="newChat" size="16" />
          <text class="toolbar-text">新对话</text>
        </view>
        <view v-if="isStreaming" class="toolbar-btn toolbar-btn-danger" @click="doAbort">
          <OmpIcon name="stop" size="14" />
          <text class="toolbar-text">中断</text>
        </view>
      </view>

      <view class="composer-main">
        <textarea
          v-model="inputText"
          class="composer-input"
          :placeholder="isArchived ? '已归档' : '请输入消息…'"
          placeholder-class="composer-ph"
          :disabled="isArchived"
          :auto-height="true"
          :show-confirm-bar="false"
          confirm-type="send"
          :maxlength="-1"
          @confirm="sendMessage"
        />

        <view
          v-if="inputText.trim() || pendingImages.length > 0"
          class="send-btn"
          :class="{ loading: sending }"
          @click="sendMessage"
        >
          <OmpIcon name="send" size="16" style="color: #fff" />
        </view>
        <view v-else class="send-btn send-btn-plus">
          <OmpIcon name="plus" size="18" style="color: var(--ink-2)" />
        </view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
// ====================================================================
// Chat View — 温润质感 · 微信骨架 · 细腻深度
// ====================================================================

.page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  height: 100dvh;
  background: #eeedea;
  overflow: hidden;
}

// ============ 导航栏 ============
.navbar {
  position: relative;
  display: flex;
  align-items: center;
  height: 48px;
  padding: 0 16px;
  padding-top: env(safe-area-inset-top, 0);
  background: rgba(255,255,255,.94);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-bottom: .5px solid rgba(0,0,0,.06);
  flex-shrink: 0;
}

.navbar-left {
  position: absolute; left: 16px;
  display: flex; align-items: center; gap: 4px;
  z-index: 2;
}

.back-label { font-size: 17px; color: #07c160; }

.navbar-title-wrap {
  position: absolute; left: 50%; transform: translateX(-50%);
  display: flex; flex-direction: column; align-items: center;
  max-width: 55%;
}

.navbar-title {
  font-size: 17px; font-weight: 600; color: #191919;
  overflow: hidden; white-space: nowrap; text-overflow: ellipsis;
  max-width: 100%;
}

.navbar-sub { display: flex; align-items: center; gap: 4px; }
.dot { width: 5px; height: 5px; border-radius: 50%; }
.dot.on { background: #07c160; }
.dot.off { background: #c0c0c0; }
.navbar-sub-text { font-size: 11px; color: #999; }
.navbar-right { position: absolute; right: 16px; }

// ============ 消息区域 ============
.messages {
  flex: 1; height: 0; min-height: 0;
  padding: 12px 12px 0;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}

.archived-banner {
  display: flex; align-items: center; justify-content: center;
  gap: 12px; margin: 0 auto 16px; padding: 10px 16px;
  background: rgba(0,0,0,.04); border-radius: 8px; max-width: 80%;
}
.banner-text { font-size: 13px; color: #999; }
.banner-btn { font-size: 13px; color: #07c160; font-weight: 500; }

// ============ 气泡 — 微影深度 ============
.row {
  display: flex; align-items: flex-start;
  margin-bottom: 18px; gap: 8px;
  min-width: 0;
  animation: msg-in .3s cubic-bezier(.22,1,.36,1) both;
}

@keyframes msg-in {
  from { opacity: 0; transform: translateY(8px); }
  to   { opacity: 1; transform: translateY(0); }
}

.row-user { flex-direction: row-reverse; }
.row-assistant { flex-direction: row; }

.avatar {
  width: 36px; height: 36px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
  box-shadow: 0 1px 3px rgba(0,0,0,.08);
}
.avatar-user { background: linear-gradient(140deg, #07c160, #05a050); }
.avatar-ai { background: linear-gradient(140deg, #5b8ef7, #4170e0); }

.bubble {
  position: relative;
  max-width: 70%;
  min-width: 0; // flex 子项不撑破
  border-radius: 10px;
  box-shadow: 0 1px 2px rgba(0,0,0,.04);
  overflow-wrap: break-word;
  word-break: break-word;
}

.bubble-user {
  background: linear-gradient(160deg, #8fe868, #7cd855);
  border-radius: 10px 4px 10px 10px;
  box-shadow: 0 1px 2px rgba(0,0,0,.04), 0 3px 8px rgba(7,193,96,.08);
}

.bubble-ai {
  background: #fff;
  border-radius: 4px 10px 10px 10px;
  box-shadow: 0 1px 2px rgba(0,0,0,.03), 0 2px 6px rgba(0,0,0,.03);
}

// 用户气泡尖角
.bubble-tail {
  position: absolute;
  top: 10px; right: -5px;
  width: 0; height: 0;
  border-style: solid;
  border-width: 5px 0 5px 6px;
  border-color: transparent transparent transparent #8fe868;
}

.bubble-text {
  padding: 11px 14px;
  font-size: 15px; line-height: 1.5;
  white-space: pre-wrap;
  overflow-wrap: break-word;
  word-break: break-word;
  color: #1a1a1a;
  max-width: 100%;
  overflow: hidden;
}
.bubble-content {
  padding: 11px 14px;
  white-space: pre-wrap;
  overflow-wrap: break-word;
  word-break: break-word;
  max-width: 100%;
  overflow: hidden;
}

// ============ 思考块 ============
.thinking { margin-bottom: 8px; }

.thinking-tag {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 5px 12px;
  background: rgba(0,0,0,.035);
  border-radius: 20px; margin-bottom: 8px;
  &::before {
    content: ''; width: 6px; height: 6px;
    border-radius: 50%; background: #07c160;
    opacity: .5;
  }
}
.thinking-tag-text { font-size: 12px; color: #999; }

.thinking-content {
  padding: 10px 14px;
  background: rgba(0,0,0,.02);
  border-radius: 8px;
  border-left: 2px solid rgba(0,0,0,.06);
}

.thinking-content :deep(.md-body) {
  font-size: 13px; line-height: 1.65; color: #999;
}
.thinking-content :deep(.md-body p) { margin: 0 0 6px; }
.thinking-content :deep(.md-body p:last-child) { margin-bottom: 0; }
.thinking-content :deep(.md-body strong) { color: #666; }
.thinking-content :deep(.md-body code) {
  font-size: .85em; padding: 1px 5px;
  background: rgba(0,0,0,.03); border-radius: 3px;
}

// ============ 打字机 ============
.typing { display: flex; gap: 4px; padding: 8px 0 0; }

.typing-dot {
  width: 6px; height: 6px; border-radius: 50%;
  background: #c0c0c0;
  animation: typing-bounce 1.2s ease-in-out infinite;
}

@keyframes typing-bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: .3; }
  30% { transform: translateY(-5px); opacity: .8; }
}

// ============ Tree ============
.tree-panel {
  margin: 8px 0; background: #fff;
  border-radius: 10px; overflow: hidden;
  box-shadow: 0 1px 3px rgba(0,0,0,.04);
}
.tree-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 13px 16px; border-bottom: .5px solid #e8e8e8;
}
.tree-title { font-size: 15px; font-weight: 600; color: #191919; }
.tree-close { padding: 4px; }
.tree-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 16px; border-bottom: .5px solid #e8e8e8;
  font-size: 14px; color: #191919;
}
.tree-row:last-child { border-bottom: none; }

// ============ Composer — 玻璃质感 ============
.composer {
  flex-shrink: 0;
  background: rgba(255,255,255,.95);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border-top: .5px solid rgba(0,0,0,.05);
  box-shadow: 0 -1px 3px rgba(0,0,0,.03);
  padding: 8px 12px calc(8px + env(safe-area-inset-bottom, 0));
}

.composer-images { display: flex; gap: 6px; padding: 0 0 8px; overflow-x: auto; }

.thumb {
  position: relative; width: 56px; height: 56px;
  border-radius: 8px; overflow: hidden;
  border: 1px solid #e5e5e5; flex-shrink: 0;
  box-shadow: 0 1px 2px rgba(0,0,0,.04);
}
.thumb-img { width: 100%; height: 100%; }
.thumb-close {
  position: absolute; top: 2px; right: 2px;
  width: 18px; height: 18px; border-radius: 50%;
  background: rgba(0,0,0,.5); color: #fff;
  font-size: 10px;
  display: flex; align-items: center; justify-content: center;
}

// 工具栏
.toolbar-row { display: flex; gap: 6px; padding: 0 0 8px; }

.toolbar-btn {
  display: flex; align-items: center; gap: 4px;
  padding: 5px 14px;
  background: rgba(0,0,0,.03);
  border: 1px solid rgba(0,0,0,.04);
  border-radius: 20px;
  font-size: 13px; color: #666;
  transition: background .15s ease;
  &:active { background: rgba(0,0,0,.06); }
}

.toolbar-btn-danger {
  background: rgba(250,81,81,.06);
  border-color: rgba(250,81,81,.1);
  color: #fa5151;
}
.toolbar-text { font-size: 13px; }

// 输入行
.composer-main { display: flex; align-items: flex-end; gap: 8px; }

.composer-input {
  flex: 1;
  min-height: 38px; max-height: 100px;
  padding: 9px 14px;
  background: #f4f3f0;
  border: 1px solid rgba(0,0,0,.06);
  border-radius: 8px;
  font-size: 15px; color: #191919; line-height: 1.45;
  transition: border-color .2s ease, background .2s ease, box-shadow .2s ease;
  &:focus {
    border-color: #07c160;
    background: #fff;
    box-shadow: 0 0 0 3px rgba(7,193,96,.06);
    outline: none;
  }
}
.composer-ph { color: #b5b5b5; }

// 发送按钮
.send-btn {
  width: 52px; height: 38px;
  background: #07c160;
  color: #fff; font-size: 14px;
  border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
  box-shadow: 0 1px 4px rgba(7,193,96,.2);
  transition: background .15s ease, transform .15s ease;
  &:active {
    background: #06ad55;
    transform: scale(.96);
  }
}
.send-btn.loading { opacity: .5; }

.send-btn-plus {
  width: 38px;
  background: #f4f3f0;
  border: 1px solid rgba(0,0,0,.06);
  color: #999;
  box-shadow: none;
  &:active { background: #e8e7e3; }
}
</style>