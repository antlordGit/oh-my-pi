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
// 先把已转义的 HTML 还原，再传给 markdown
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
    : unescapedText.value
)
const html = computed(() => md.render(preview.value))
</script>

<template>
  <article
    :class="['msg', `msg--${role}`]"
    @click="long && (collapsed = !collapsed)"
    :style="long ? { cursor: 'pointer', borderLeftColor: 'var(--bronze)' } : {}"
  >
    <div v-if="long && collapsed" class="fold-hint mono">▸ 展开全部内容</div>
    <div class="msg-body" v-html="html" />
  </article>
</template>

<style scoped>
.msg { position: relative; line-height: 1.7; }

.msg--user {
  background: var(--bg-sunken);
  border-left: 3px solid var(--accent);
  border-radius: 0 8px 8px 0;
  padding: 14px 20px;
  font-size: 16px;
  font-weight: 380;
  color: var(--text);
}

.msg--assistant {
  color: var(--text);
  font-size: 15px;
  padding: 0;
}

.fold-hint {
  font-size: 9px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--bronze);
  margin-bottom: 6px;
}

/* ---- rendered markdown ---- */
.msg-body :deep(p) { margin: 0 0 10px; }
.msg-body :deep(p:last-child) { margin-bottom: 0; }
.msg-body :deep(a) { color: var(--accent); text-decoration: underline; text-underline-offset: 2px; }
.msg-body :deep(code) {
  font-family: var(--font-mono);
  font-size: 0.9em;
  background: var(--bg-sunken);
  padding: 2px 7px;
  color: var(--accent);
  border-radius: 4px;
  border: 1px solid var(--border);
}
.msg-body :deep(pre) {
  background: var(--bg-sunken);
  border: 1px solid var(--border);
  border-left: 3px solid var(--accent);
  border-radius: 8px;
  padding: 16px 18px;
  overflow-x: auto;
  font-size: 13px;
  line-height: 1.6;
  margin: 12px 0;
}
.msg-body :deep(pre code) {
  background: transparent;
  border: 0;
  padding: 0;
  color: var(--text);
}
.msg-body :deep(strong) { font-weight: 600; color: var(--text); }
.msg-body :deep(em) { font-style: italic; color: var(--text-secondary); }
.msg-body :deep(ol), .msg-body :deep(ul) { padding-left: 22px; margin: 8px 0 12px; }
.msg-body :deep(li) { margin-bottom: 6px; }
.msg-body :deep(ul) { list-style: none; }
.msg-body :deep(ul li) { position: relative; }
.msg-body :deep(ul li::before) { content: "—"; position: absolute; left: -22px; color: var(--accent); }
.msg-body :deep(blockquote) {
  border-left: 3px solid var(--accent);
  background: var(--accent-soft);
  border-radius: 0 8px 8px 0;
  padding: 10px 18px;
  margin: 12px 0;
  color: var(--text-secondary);
}
.msg-body :deep(h1), .msg-body :deep(h2), .msg-body :deep(h3) {
  font-family: var(--font-display);
  font-weight: 400;
  margin: 16px 0 8px;
  color: var(--text);
}
.msg-body :deep(h1) { font-size: 24px; }
.msg-body :deep(h2) { font-size: 20px; }
.msg-body :deep(h3) { font-size: 17px; }
.msg-body :deep(hr) { border: 0; border-top: 1px solid var(--border); margin: 16px 0; }
.msg-body :deep(table) {
  border-collapse: collapse;
  width: 100%;
  font-family: var(--font-mono);
  font-size: 12px;
  margin: 12px 0;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--border);
}
.msg-body :deep(th), .msg-body :deep(td) {
  border: 1px solid var(--border);
  padding: 8px 14px;
  text-align: left;
}
.msg-body :deep(th) {
  background: var(--bg-sunken);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 500;
}
</style>