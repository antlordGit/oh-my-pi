<script setup lang="ts">
import MarkdownIt from 'markdown-it'
import { computed, ref } from 'vue'

const props = defineProps<{
  role: 'user' | 'assistant' | 'tool'
  text: string
}>()

const md = new MarkdownIt({ html: false, linkify: true, breaks: true, typographer: true })
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
    ? unescapedText.value.slice(0, 2000) + '\n\n··· 点击展开全部 ' + unescapedText.value.length + ' 字符'
    : unescapedText.value,
)
const html = computed(() => md.render(preview.value))

const isUser = computed(() => props.role === 'user')
</script>

<template>
  <article
    :class="['msg', isUser ? 'is-user' : 'is-assistant']"
    @click="long && (collapsed = !collapsed)"
    :style="long ? { cursor: 'pointer' } : {}"
  >
    <div v-if="long && collapsed" class="fold-hint">
      <span class="caret">▸</span> 展开全部内容
    </div>
    <div class="msg-body" v-html="html" />
  </article>
</template>

<style scoped>
.msg {
  position: relative;
  line-height: 1.7;
  animation: fade-up var(--dur-slow) var(--ease-out) both;
}

.is-user {
  background: var(--brand-soft);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius);
  padding: 12px 18px;
  font-size: 15px;
  color: var(--ink);
}

.is-assistant {
  color: var(--ink);
  font-size: 15px;
  padding: 0;
}

.fold-hint {
  font-size: 11px;
  color: var(--brand);
  margin-bottom: 8px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border-radius: var(--radius-pill);
  background: var(--brand-soft);
}
.fold-hint .caret { font-size: 10px; }

/* ---- rendered markdown ---- */
.msg-body :deep(p) { margin: 0 0 10px; }
.msg-body :deep(p:last-child) { margin-bottom: 0; }
.msg-body :deep(a) { color: var(--brand); text-decoration: none; border-bottom: 1px solid var(--border-soft); }
.msg-body :deep(a:hover) { border-bottom-color: var(--brand); }
.msg-body :deep(code) {
  font-family: var(--font-mono);
  font-size: 0.88em;
  background: var(--surface-soft);
  padding: 1px 6px;
  color: var(--brand);
  border-radius: 4px;
  border: 1px solid var(--border);
}
.msg-body :deep(pre) {
  background: var(--surface-soft);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 14px 16px;
  overflow-x: auto;
  font-size: 12.5px;
  line-height: 1.6;
  margin: 12px 0;
}
.msg-body :deep(pre code) {
  background: transparent;
  border: 0;
  padding: 0;
  color: var(--ink);
}
.msg-body :deep(strong) { font-weight: 700; color: var(--ink); }
.msg-body :deep(em) { font-style: italic; color: var(--ink-2); }
.msg-body :deep(ol), .msg-body :deep(ul) { padding-left: 22px; margin: 8px 0 12px; }
.msg-body :deep(li) { margin-bottom: 6px; }
.msg-body :deep(ul) { list-style: none; }
.msg-body :deep(ul li) { position: relative; }
.msg-body :deep(ul li::before) {
  content: "—";
  position: absolute;
  left: -18px;
  color: var(--brand);
}
.msg-body :deep(blockquote) {
  border-left: 2px solid var(--brand);
  background: var(--brand-soft);
  border-radius: 0 var(--radius) var(--radius) 0;
  padding: 10px 16px;
  margin: 12px 0;
  color: var(--ink-2);
}
.msg-body :deep(h1), .msg-body :deep(h2), .msg-body :deep(h3) {
  font-family: var(--font-display);
  font-weight: 700;
  margin: 16px 0 8px;
  color: var(--ink);
}
.msg-body :deep(h1) { font-size: 24px; }
.msg-body :deep(h2) { font-size: 20px; }
.msg-body :deep(h3) { font-size: 17px; }
.msg-body :deep(hr) { border: 0; border-top: 1px dashed var(--border); margin: 16px 0; }
.msg-body :deep(table) {
  border-collapse: collapse;
  width: 100%;
  font-family: var(--font-mono);
  font-size: 12px;
  margin: 12px 0;
  border-radius: var(--radius-sm);
  overflow: hidden;
  border: 1px solid var(--border);
}
.msg-body :deep(th), .msg-body :deep(td) {
  border: 1px solid var(--border);
  padding: 8px 14px;
  text-align: left;
}
.msg-body :deep(th) {
  background: var(--surface-soft);
  color: var(--ink-mute);
  font-size: 11px;
  font-weight: 500;
}
</style>