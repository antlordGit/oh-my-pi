<script setup lang="ts">
import MarkdownIt from 'markdown-it'
import { computed, ref } from 'vue'

const props = defineProps<{
  role: 'user' | 'assistant' | 'tool'
  text: string
}>()

// 平台判断：H5 端用 v-html，小程序保留 mp-html
const isH5 = (() => {
  // #ifdef H5
  return true
  // #endif
  // #ifndef H5
  return false
  // #endif
})()

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  typographer: true,
})

const fullText = computed(() => props.text || '')
const long = computed(() => fullText.value.length > 3000)
const collapsed = ref(true)

const unescapedText = computed(() => {
  return (fullText.value || '')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
})

const preview = computed(() =>
  long.value && collapsed.value
    ? unescapedText.value.slice(0, 2000) +
      '\n\n··· 点击展开全部 ' +
      unescapedText.value.length +
      ' 字符'
    : unescapedText.value,
)

const rendered = computed(() => md.render(preview.value))

// ---- 复制 ----
const copyState = ref<'idle' | 'copied' | 'failed'>('idle')
let copyTimer: ReturnType<typeof setTimeout> | undefined

async function copyText() {
  const text = unescapedText.value
  if (!text) return
  try {
    await uni.setClipboardData({ data: text })
    copyState.value = 'copied'
  } catch {
    copyState.value = 'failed'
  } finally {
    if (copyTimer) clearTimeout(copyTimer)
    copyTimer = setTimeout(() => {
      copyState.value = 'idle'
    }, 1600)
  }
}

function toggleCollapse() {
  if (long.value) collapsed.value = !collapsed.value
}

function onLongPress() {
  uni.showActionSheet({
    itemList: ['复制全文'],
    success: (res) => {
      if (res.tapIndex === 0) copyText()
    },
  })
}

function onImageTap(e: Event) {
  const el = e.currentTarget as any
  const src = el?.attrs?.src
  if (src) {
    uni.previewImage({ urls: [src], current: src })
  }
}
</script>

<template>
  <view
    :class="['msg', { 'is-long': long, collapsed: collapsed }]"
    @click="toggleCollapse"
    @longpress="onLongPress"
  >
    <!-- 折叠提示 -->
    <view v-if="long && collapsed" class="fold-bar">
      <text class="fold-text">展开全部 · {{ fullText.length }} 字符</text>
    </view>

    <!-- H5: v-html -->
    <view v-if="isH5" class="md-body" v-html="rendered" />

    <!-- 小程序: mp-html -->
    <mp-html
      v-else
      :content="rendered"
      :selectable="true"
      :copy-link="false"
      @linktap="(e: any) => {
        if (e.href) {
          uni.setClipboardData({ data: e.href })
          uni.showToast({ title: '链接已复制', icon: 'none' })
        }
      }"
      :container-style="{
        color: 'inherit',
        fontSize: '15px',
        lineHeight: 1.7,
        fontFamily: 'var(--font-ui)',
      }"
      :tag-style="{
        p: { margin: '0 0 8px' },
        'p:last-child': { marginBottom: '0' },
        a: { color: 'var(--brand)', textDecoration: 'none' },
        code: {
          fontFamily: 'var(--font-mono)',
          fontSize: '0.88em',
          background: 'var(--surface-soft)',
          padding: '1px 5px',
          borderRadius: '3px',
        },
        pre: {
          background: 'var(--surface-soft)',
          borderRadius: '6px',
          padding: '10px 14px',
          overflowX: 'auto',
          fontSize: '13px',
          lineHeight: 1.5,
          margin: '8px 0',
        },
        'pre code': {
          background: 'transparent',
          border: '0',
          padding: '0',
          color: 'inherit',
        },
        strong: { fontWeight: '600' },
        blockquote: {
          borderLeft: '3px solid var(--brand)',
          paddingLeft: '12px',
          margin: '8px 0',
          color: 'var(--ink-mute)',
          fontSize: '14px',
        },
      }"
    />
  </view>
</template>

<style scoped>
.msg {
  position: relative;
  color: inherit;
}

.is-long.collapsed {
  cursor: pointer;
}

.fold-bar {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  background: var(--surface-soft);
  border-radius: 4px;
  margin-bottom: 8px;
}

.fold-text {
  font-size: 11px;
  color: var(--brand);
  line-height: 1;
}

/* AI 消息 markdown 渲染 */
.md-body {
  color: var(--bubble-other-text);
  font-size: 15px;
  line-height: 1.7;
}
.md-body :deep(p) { margin: 0 0 8px; }
.md-body :deep(p:last-child) { margin-bottom: 0; }
.md-body :deep(a) {
  color: var(--brand);
  text-decoration: none;
}
.md-body :deep(code) {
  font-family: var(--font-mono);
  font-size: 0.88em;
  background: var(--surface-soft);
  padding: 1px 5px;
  border-radius: 3px;
}
.md-body :deep(pre) {
  background: var(--surface-soft);
  border-radius: 6px;
  padding: 10px 14px;
  overflow-x: auto;
  font-size: 13px;
  line-height: 1.5;
  margin: 8px 0;
}
.md-body :deep(pre code) {
  background: transparent;
  border: 0;
  padding: 0;
  color: inherit;
}
.md-body :deep(strong) { font-weight: 600; }
.md-body :deep(em) { font-style: italic; }
.md-body :deep(blockquote) {
  border-left: 3px solid var(--brand);
  padding-left: 12px;
  margin: 8px 0;
  color: var(--ink-mute);
  font-size: 14px;
}
.md-body :deep(h1),
.md-body :deep(h2),
.md-body :deep(h3) {
  font-weight: 600;
  margin: 12px 0 6px;
}
.md-body :deep(ul),
.md-body :deep(ol) { padding-left: 20px; margin: 6px 0; }
.md-body :deep(li) { margin-bottom: 4px; }
.md-body :deep(table) {
  border-collapse: collapse;
  width: 100%;
  font-size: 13px;
  margin: 8px 0;
}
.md-body :deep(th),
.md-body :deep(td) {
  border: 1px solid var(--border);
  padding: 6px 10px;
  text-align: left;
}
.md-body :deep(th) {
  background: var(--surface-soft);
  font-size: 12px;
}
.md-body :deep(hr) {
  border: 0;
  border-top: 1px dashed var(--border);
  margin: 12px 0;
}
</style>