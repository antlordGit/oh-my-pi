<script setup lang="ts">
import { computed, ref, watch, nextTick } from 'vue'
import { useMessage } from 'naive-ui'
import { readFile, writeFile } from '@/api/repo'
import hljs from 'highlight.js/lib/core'
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import json from 'highlight.js/lib/languages/json'
import xml from 'highlight.js/lib/languages/xml'
import css from 'highlight.js/lib/languages/css'
import bash from 'highlight.js/lib/languages/bash'
import python from 'highlight.js/lib/languages/python'
import yaml from 'highlight.js/lib/languages/yaml'
import markdown from 'highlight.js/lib/languages/markdown'
import sql from 'highlight.js/lib/languages/sql'
import java from 'highlight.js/lib/languages/java'
import go from 'highlight.js/lib/languages/go'
import rust from 'highlight.js/lib/languages/rust'
import plaintext from 'highlight.js/lib/languages/plaintext'

// Register languages
hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('json', json)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('html', xml)
hljs.registerLanguage('css', css)
hljs.registerLanguage('bash', bash)
hljs.registerLanguage('shell', bash)
hljs.registerLanguage('python', python)
hljs.registerLanguage('yaml', yaml)
hljs.registerLanguage('markdown', markdown)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('java', java)
hljs.registerLanguage('go', go)
hljs.registerLanguage('rust', rust)
hljs.registerLanguage('plaintext', plaintext)

interface Props {
  repoId: string | null | undefined
  filePath: string | null | undefined
  fileName: string
}

const props = defineProps<Props>()
const emit = defineEmits<{ close: [] }>()

const msg = useMessage()

const content = ref('')
const editing = ref('')
const dirty = ref(false)
const loading = ref(false)
const saving = ref(false)
const showConfirm = ref(false)
const expanded = ref(false)

const textareaRef = ref<HTMLTextAreaElement | null>(null)
const highlightRef = ref<HTMLPreElement | null>(null)

// ============ Search & Replace ============
const showSearch = ref(false)
const searchQuery = ref('')
const replaceQuery = ref('')
const searchCaseSensitive = ref(false)
const searchRegex = ref(false)
const searchResults = ref<number[]>([]) // positions of matches
const searchIndex = ref(0)
const replaceMode = ref(false)

// Language detection from file extension
const lang = computed(() => {
  const ext = props.fileName.split('.').pop()?.toLowerCase() || ''
  const map: Record<string, string> = {
    js: 'javascript', mjs: 'javascript', cjs: 'javascript',
    ts: 'typescript', tsx: 'typescript', jsx: 'typescript',
    json: 'json',
    html: 'html', htm: 'html',
    xml: 'xml',
    css: 'css', scss: 'css', sass: 'css', less: 'css',
    sh: 'bash', bash: 'bash', zsh: 'bash',
    py: 'python',
    yml: 'yaml', yaml: 'yaml',
    md: 'markdown', markdown: 'markdown',
    sql: 'sql',
    java: 'java',
    go: 'go',
    rs: 'rust',
    txt: 'plaintext', log: 'plaintext',
    env: 'plaintext', gitignore: 'plaintext',
  }
  return map[ext] || 'plaintext'
})

// Highlighted HTML with search matches
const highlightedHtml = computed(() => {
  let code = editing.value
  try {
    code = hljs.highlight(code, { language: lang.value, ignoreIllegals: true }).value
  } catch {
    code = hljs.highlightAuto(code).value
  }
  // Apply search highlights
  if (searchQuery.value && searchResults.value.length > 0) {
    code = applySearchHighlights(code)
  }
  return code
})

// Escape HTML entities for display
function escapeHtml(str: string): string {
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

// Apply search highlights to highlighted code
function applySearchHighlights(code: string): string {
  if (!searchQuery.value) return code
  const q = searchQuery.value
  try {
    if (searchRegex.value) {
      const re = new RegExp(q, searchCaseSensitive.value ? 'g' : 'gi')
      return code.replace(re, (match) => {
        // Don't highlight inside HTML tags
        if (match.includes('<') || match.includes('>')) return match
        return `<span class="fe-search-match">${match}</span>`
      })
    } else {
      const escaped = q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      const re = new RegExp(escaped, searchCaseSensitive.value ? 'g' : 'gi')
      return code.replace(re, (match) => {
        if (match.includes('<') || match.includes('>')) return match
        return `<span class="fe-search-match">${match}</span>`
      })
    }
  } catch {
    return code
  }
}

// Find all match positions in plain text
function findMatches(): number[] {
  if (!searchQuery.value) return []
  const text = editing.value
  const q = searchQuery.value
  const positions: number[] = []
  try {
    if (searchRegex.value) {
      const re = new RegExp(q, searchCaseSensitive.value ? 'g' : 'gi')
      let m
      while ((m = re.exec(text)) !== null) {
        positions.push(m.index)
      }
    } else {
      const escaped = q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      const re = new RegExp(escaped, searchCaseSensitive.value ? 'g' : 'gi')
      let m
      while ((m = re.exec(text)) !== null) {
        positions.push(m.index)
      }
    }
  } catch {}
  return positions
}

// Update search results when query changes
watch(searchQuery, () => {
  searchResults.value = findMatches()
  searchIndex.value = 0
})

watch([searchCaseSensitive, searchRegex], () => {
  searchResults.value = findMatches()
  searchIndex.value = 0
})

// Open search panel
function openSearch() {
  showSearch.value = true
  replaceMode.value = false
  nextTick(() => {
    const input = document.querySelector('.fe-search-input') as HTMLInputElement
    input?.focus()
    input?.select()
  })
}

// Open replace panel
function openReplace() {
  showSearch.value = true
  replaceMode.value = true
  nextTick(() => {
    const input = document.querySelector('.fe-search-input') as HTMLInputElement
    input?.focus()
    input?.select()
  })
}

// Open search+replace from header button
function openSearchReplace() {
  openReplace()
}

// Close search panel
function closeSearch() {
  showSearch.value = false
  searchQuery.value = ''
  replaceQuery.value = ''
  searchResults.value = []
  searchIndex.value = 0
}

// Navigate to next match
function nextMatch() {
  if (searchResults.value.length === 0) return
  searchIndex.value = (searchIndex.value + 1) % searchResults.value.length
  scrollToMatch()
}

// Navigate to previous match
function prevMatch() {
  if (searchResults.value.length === 0) return
  searchIndex.value = (searchIndex.value - 1 + searchResults.value.length) % searchResults.value.length
  scrollToMatch()
}

// Scroll to current match
function scrollToMatch() {
  if (!textareaRef.value || searchResults.value.length === 0) return
  const pos = searchResults.value[searchIndex.value]
  const ta = textareaRef.value
  ta.focus()
  ta.setSelectionRange(pos, pos + searchQuery.value.length)
  // Approximate scroll
  const lines = editing.value.substring(0, pos).split('\n')
  const lineHeight = 19.2 // approx
  ta.scrollTop = Math.max(0, (lines.length - 5) * lineHeight)
  syncScroll()
}

// Replace current match
function replaceCurrent() {
  if (searchResults.value.length === 0 || !textareaRef.value) return
  const pos = searchResults.value[searchIndex.value]
  const q = searchQuery.value
  const r = replaceQuery.value
  try {
    let matchLen = q.length
    if (searchRegex.value) {
      const re = new RegExp(q, searchCaseSensitive.value ? '' : 'i')
      const m = editing.value.substring(pos).match(re)
      if (m) matchLen = m[0].length
    }
    editing.value = editing.value.substring(0, pos) + r + editing.value.substring(pos + matchLen)
    dirty.value = editing.value !== content.value
    searchResults.value = findMatches()
    if (searchIndex.value >= searchResults.value.length) {
      searchIndex.value = Math.max(0, searchResults.value.length - 1)
    }
    if (searchResults.value.length > 0) scrollToMatch()
  } catch {}
}

// Replace all matches
function replaceAll() {
  if (searchResults.value.length === 0) return
  const q = searchQuery.value
  const r = replaceQuery.value
  try {
    if (searchRegex.value) {
      const re = new RegExp(q, searchCaseSensitive.value ? 'g' : 'gi')
      editing.value = editing.value.replace(re, r)
    } else {
      const escaped = q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      const re = new RegExp(escaped, searchCaseSensitive.value ? 'g' : 'gi')
      editing.value = editing.value.replace(re, r)
    }
    dirty.value = editing.value !== content.value
    searchResults.value = findMatches()
    searchIndex.value = 0
    msg.success(`已替换 ${searchResults.value.length} 处`)
  } catch {}
}

// ============ File operations ============
watch(
  () => props.filePath,
  async (p) => {
    if (!p || !props.repoId) { content.value = ''; editing.value = ''; dirty.value = false; return }
    loading.value = true
    closeSearch()
    try {
      const r = await readFile(props.repoId, p)
      content.value = r.content
      editing.value = r.content
      dirty.value = false
    } catch (e: any) {
      content.value = ''
      editing.value = ''
      msg.error(e?.response?.data?.error || '读取失败')
    } finally {
      loading.value = false
    }
  },
  { immediate: true },
)

function onInput() {
  dirty.value = editing.value !== content.value
  // Update search results on input
  if (searchQuery.value) {
    searchResults.value = findMatches()
  }
}

function syncScroll() {
  if (!textareaRef.value || !highlightRef.value) return
  highlightRef.value.scrollTop = textareaRef.value.scrollTop
  highlightRef.value.scrollLeft = textareaRef.value.scrollLeft
}

function handleKeydown(e: KeyboardEvent) {
  // Ctrl/Cmd + F: open search
  if ((e.ctrlKey || e.metaKey) && e.key === 'f') {
    e.preventDefault()
    openSearch()
    return
  }
  // Ctrl/Cmd + H: open replace
  if ((e.ctrlKey || e.metaKey) && e.key === 'h') {
    e.preventDefault()
    openReplace()
    return
  }
  // Escape
  if (e.key === 'Escape') {
    if (showSearch.value) { closeSearch(); e.preventDefault(); return }
    if (expanded.value) { expanded.value = false; e.preventDefault(); return }
    emit('close')
    return
  }
  // Ctrl/Cmd + S: save
  if ((e.ctrlKey || e.metaKey) && e.key === 's') {
    e.preventDefault()
    if (dirty.value) showConfirm.value = true
    return
  }
  // Tab support
  if (e.key === 'Tab') {
    e.preventDefault()
    const ta = textareaRef.value
    if (!ta) return
    const start = ta.selectionStart
    const end = ta.selectionEnd
    editing.value = editing.value.substring(0, start) + '  ' + editing.value.substring(end)
    nextTick(() => {
      ta.selectionStart = ta.selectionEnd = start + 2
    })
  }
}

// Search panel keydown
function handleSearchKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter') {
    e.preventDefault()
    if (e.shiftKey) prevMatch()
    else nextMatch()
  }
  if (e.key === 'Escape') {
    closeSearch()
  }
}

function cancelSave() { showConfirm.value = false }
async function confirmSave() {
  if (!props.repoId || !props.filePath) return
  showConfirm.value = false
  saving.value = true
  try {
    await writeFile(props.repoId, props.filePath, editing.value)
    content.value = editing.value
    dirty.value = false
    msg.success('文件已保存')
  } catch (e: any) {
    msg.error(e?.response?.data?.error || '保存失败')
  } finally {
    saving.value = false
  }
}

function revert() {
  editing.value = content.value
  dirty.value = false
  closeSearch()
}

function toggleExpand() {
  expanded.value = !expanded.value
}

const lineCount = computed(() => editing.value.split('\n').length)
const charCount = computed(() => editing.value.length)

const lines = computed(() => {
  const arr = editing.value.split('\n')
  return Array.from({ length: arr.length }, (_, i) => i + 1)
})
</script>

<template>
  <!-- Expanded: fullscreen modal -->
  <Teleport v-if="expanded" to="body">
    <div class="fe-modal" @keydown="handleKeydown">
      <header class="fe-head">
        <div class="fe-head-left">
          <span class="fe-icon">▫</span>
          <span class="fe-name mono">{{ props.fileName }}</span>
          <span class="fe-lang mono">{{ lang }}</span>
          <span class="fe-meta mono dim">{{ lineCount }} 行 · {{ charCount }} 字符</span>
        </div>
        <div class="fe-head-right">
          <button class="fe-btn fe-btn-save" :disabled="!dirty || saving" @click="showConfirm = true">
            {{ saving ? '保存中…' : '保存' }}
          </button>
          <button class="fe-btn" :disabled="!dirty" @click="revert">撤销</button>
          <button class="fe-btn" @click="toggleExpand" title="缩小">⇲</button>
          <button class="fe-btn" @click="openSearchReplace" title="替换 (⌘H)">⇆</button>
          <button class="fe-btn fe-btn-close" @click="emit('close')">✕</button>
        </div>
      </header>

      <!-- Search panel -->
      <div v-if="showSearch" class="fe-search">
        <input
          v-model="searchQuery"
          class="fe-search-input mono"
          placeholder="搜索..."
          @keydown="handleSearchKeydown"
        />
        <button class="fe-btn-sm" @click="prevMatch" :disabled="searchResults.length === 0" title="上一个">↑</button>
        <button class="fe-btn-sm" @click="nextMatch" :disabled="searchResults.length === 0" title="下一个">↓</button>
        <span class="fe-search-count mono" v-if="searchQuery">
          {{ searchResults.length ? `${searchIndex + 1}/${searchResults.length}` : '无匹配' }}
        </span>
        <label class="fe-search-opt"><input type="checkbox" v-model="searchCaseSensitive" /> Aa</label>
        <label class="fe-search-opt"><input type="checkbox" v-model="searchRegex" /> .*</label>
        <template v-if="replaceMode">
          <input
            v-model="replaceQuery"
            class="fe-replace-input mono"
            placeholder="替换为..."
            @keydown.enter.prevent="replaceCurrent"
          />
          <button class="fe-btn-sm" @click="replaceCurrent" :disabled="searchResults.length === 0">替换</button>
          <button class="fe-btn-sm" @click="replaceAll" :disabled="searchResults.length === 0">全部</button>
        </template>
        <button class="fe-btn-sm fe-btn-close-sm" @click="closeSearch">✕</button>
      </div>

      <div class="fe-editor">
        <div class="fe-lines mono"><div v-for="n in lines" :key="n">{{ n }}</div></div>
        <div class="fe-code-wrap">
          <pre class="fe-highlight mono" ref="highlightRef"><code v-html="highlightedHtml"></code></pre>
          <textarea
            ref="textareaRef"
            v-model="editing"
            class="fe-textarea mono"
            spellcheck="false"
            @input="onInput"
            @scroll="syncScroll"
          ></textarea>
        </div>
      </div>
      <Transition name="fade">
        <div v-if="showConfirm" class="fe-overlay" @click.self="cancelSave">
          <div class="fe-confirm card">
            <h3>确认保存</h3>
            <p>即将将当前编辑内容写入<br/><code class="mono">{{ props.filePath }}</code></p>
            <div class="fe-confirm-act">
              <button class="btn-mini-danger" @click="cancelSave">取消</button>
              <button class="btn-primary" @click="confirmSave" :disabled="saving">{{ saving ? '保存中…' : '确认保存' }}</button>
            </div>
          </div>
        </div>
      </Transition>
    </div>
  </Teleport>

  <!-- Normal: slide-in panel -->
  <Transition v-if="!expanded" name="slide-right">
    <aside v-if="props.filePath" class="file-editor">
      <header class="fe-head">
        <div class="fe-head-left">
          <span class="fe-icon">▫</span>
          <span class="fe-name mono">{{ props.fileName }}</span>
          <span class="fe-lang mono">{{ lang }}</span>
        </div>
        <div class="fe-head-right">
          <button class="fe-btn fe-btn-save" :disabled="!dirty || saving" @click="showConfirm = true">{{ saving ? '保存中…' : '保存' }}</button>
          <button class="fe-btn" :disabled="!dirty" @click="revert">撤销</button>
          <button class="fe-btn" @click="toggleExpand" title="放大">⤢</button>
          <button class="fe-btn fe-btn-close" @click="emit('close')">✕</button>
        </div>
      </header>

      <!-- Search panel -->
      <div v-if="showSearch" class="fe-search">
        <input
          v-model="searchQuery"
          class="fe-search-input mono"
          placeholder="搜索..."
          @keydown="handleSearchKeydown"
        />
        <button class="fe-btn-sm" @click="prevMatch" :disabled="searchResults.length === 0">↑</button>
        <button class="fe-btn-sm" @click="nextMatch" :disabled="searchResults.length === 0">↓</button>
        <span class="fe-search-count mono" v-if="searchQuery">
          {{ searchResults.length ? `${searchIndex + 1}/${searchResults.length}` : '无匹配' }}
        </span>
        <label class="fe-search-opt"><input type="checkbox" v-model="searchCaseSensitive" /> Aa</label>
        <label class="fe-search-opt"><input type="checkbox" v-model="searchRegex" /> .*</label>
        <template v-if="replaceMode">
          <input
            v-model="replaceQuery"
            class="fe-replace-input mono"
            placeholder="替换为..."
            @keydown.enter.prevent="replaceCurrent"
          />
          <button class="fe-btn-sm" @click="replaceCurrent" :disabled="searchResults.length === 0">替换</button>
          <button class="fe-btn-sm" @click="replaceAll" :disabled="searchResults.length === 0">全部</button>
        </template>
        <button class="fe-btn-sm fe-btn-close-sm" @click="closeSearch">✕</button>
      </div>

      <div class="fe-editor">
        <div class="fe-lines mono"><div v-for="n in lines" :key="n">{{ n }}</div></div>
        <div class="fe-code-wrap">
          <pre class="fe-highlight mono" ref="highlightRef"><code v-html="highlightedHtml"></code></pre>
          <textarea
            ref="textareaRef"
            v-model="editing"
            class="fe-textarea mono"
            spellcheck="false"
            @input="onInput"
            @scroll="syncScroll"
            @keydown="handleKeydown"
          ></textarea>
        </div>
      </div>
      <Transition name="fade">
        <div v-if="showConfirm" class="fe-overlay" @click.self="cancelSave">
          <div class="fe-confirm card">
            <h3>确认保存</h3>
            <p>即将将当前编辑内容写入<br/><code class="mono">{{ props.filePath }}</code></p>
            <div class="fe-confirm-act">
              <button class="btn-mini-danger" @click="cancelSave">取消</button>
              <button class="btn-primary" @click="confirmSave" :disabled="saving">{{ saving ? '保存中…' : '确认保存' }}</button>
            </div>
          </div>
        </div>
      </Transition>
    </aside>
  </Transition>
</template>

<style scoped>
/* Highlight.js theme inline */
.fe-highlight :deep(.hljs-keyword),
.fe-highlight :deep(.hljs-selector-tag),
.fe-highlight :deep(.hljs-built_in) { color: #d73a49; }
.fe-highlight :deep(.hljs-string),
.fe-highlight :deep(.hljs-title),
.fe-highlight :deep(.hljs-attr) { color: #032f62; }
.fe-highlight :deep(.hljs-number),
.fe-highlight :deep(.hljs-literal) { color: #005cc5; }
.fe-highlight :deep(.hljs-comment),
.fe-highlight :deep(.hljs-quote) { color: #6a737d; font-style: italic; }
.fe-highlight :deep(.hljs-function) { color: #6f42c1; }
.fe-highlight :deep(.hljs-variable),
.fe-highlight :deep(.hljs-template-variable) { color: #e36209; }
.fe-highlight :deep(.hljs-type) { color: #005cc5; }
.fe-highlight :deep(.hljs-tag) { color: #22863a; }
.fe-highlight :deep(.hljs-name) { color: #63a35c; }
.fe-highlight :deep(.hljs-attribute) { color: #6f42c1; }

/* Search highlight */
.fe-highlight :deep(.fe-search-match) {
  background: #ffeb3b;
  color: #000;
  border-radius: 2px;
  padding: 0 1px;
}

/* ===================================================================
   Panel & modal
   =================================================================== */
.file-editor {
  position: absolute;
  inset: 0;
  z-index: 20;
  background: var(--surface, #FFFFFF);
  display: flex;
  flex-direction: column;
  border-radius: var(--radius, 12px);
  overflow: hidden;
}

.fe-modal {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: var(--surface, #FFFFFF);
  display: flex;
  flex-direction: column;
}

/* ===================================================================
   Toolbar
   =================================================================== */
.fe-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--border, #E5E6EB);
  background: var(--surface-soft, #F7F8FA);
  flex-shrink: 0;
}
.fe-head-left { display: flex; align-items: center; gap: 6px; min-width: 0; }
.fe-icon { color: var(--brand, #165DFF); font-size: 12px; }
.fe-name {
  font-size: 11px;
  font-weight: 600;
  color: var(--ink, #1D2129);
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fe-lang {
  font-size: 9px;
  color: #fff;
  background: var(--brand, #165DFF);
  padding: 1px 6px;
  border-radius: 3px;
  text-transform: uppercase;
}
.fe-meta { font-size: 9px; color: var(--ink-faint, #C9CDD4); }

.fe-head-right { display: flex; align-items: center; gap: 4px; }
.fe-btn {
  border: 1px solid var(--border, #E5E6EB);
  border-radius: 4px;
  background: var(--surface, #FFFFFF);
  color: var(--ink-2, #4E5969);
  font-family: var(--font-mono);
  font-size: 10px;
  padding: 3px 8px;
  cursor: pointer;
  transition: all 100ms ease;
}
.fe-btn:hover:not(:disabled) { border-color: var(--brand, #165DFF); color: var(--brand, #165DFF); }
.fe-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.fe-btn-save { background: var(--brand, #165DFF); color: #fff; border-color: var(--brand, #165DFF); }
.fe-btn-save:hover:not(:disabled) { background: #0E49D6; }
.fe-btn-close { font-size: 11px; padding: 3px 6px; }

/* ===================================================================
   Search panel
   =================================================================== */
.fe-search {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  background: #fafbfc;
  border-bottom: 1px solid var(--border, #E5E6EB);
  flex-shrink: 0;
}
.fe-search-input,
.fe-replace-input {
  border: 1px solid var(--border, #E5E6EB);
  border-radius: 4px;
  padding: 4px 8px;
  font-size: 11px;
  font-family: var(--font-mono);
  width: 140px;
  outline: none;
}
.fe-search-input:focus,
.fe-replace-input:focus { border-color: var(--brand, #165DFF); }
.fe-replace-input { width: 100px; }

.fe-btn-sm {
  border: 1px solid var(--border, #E5E6EB);
  border-radius: 3px;
  background: #fff;
  font-size: 10px;
  padding: 2px 6px;
  cursor: pointer;
  font-family: var(--font-mono);
}
.fe-btn-sm:hover:not(:disabled) { background: var(--brand-soft); }
.fe-btn-sm:disabled { opacity: 0.4; cursor: not-allowed; }
.fe-btn-close-sm { margin-left: 4px; }

.fe-search-count { font-size: 10px; color: var(--ink-faint); min-width: 50px; }
.fe-search-opt {
  font-size: 10px;
  color: var(--ink-2);
  display: flex;
  align-items: center;
  gap: 3px;
  cursor: pointer;
}
.fe-search-opt input { margin: 0; }

/* ===================================================================
   Editor
   =================================================================== */
.fe-editor {
  flex: 1;
  display: flex;
  overflow: hidden;
}

.fe-lines {
  flex-shrink: 0;
  width: 36px;
  padding: 12px 0;
  background: var(--surface-soft, #F7F8FA);
  border-right: 1px solid var(--border, #E5E6EB);
  text-align: right;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-faint, #C9CDD4);
  user-select: none;
  overflow: hidden;
}
.fe-lines > div { padding-right: 8px; }

.fe-code-wrap {
  flex: 1;
  position: relative;
  overflow: hidden;
}

.fe-highlight {
  position: absolute;
  inset: 0;
  margin: 0;
  padding: 12px;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre;
  overflow: auto;
  pointer-events: none;
  color: var(--ink, #1D2129);
}
.fe-highlight code { font-family: inherit; background: transparent; }

.fe-textarea {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  padding: 12px;
  border: 0;
  outline: 0;
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  font-family: var(--font-mono);
  color: transparent;
  caret-color: var(--ink, #1D2129);
  background: transparent;
  resize: none;
  tab-size: 2;
  white-space: pre;
  overflow-wrap: normal;
  overflow: auto;
}
.fe-textarea::selection { background: rgba(22, 93, 255, 0.25); }

/* Modal larger */
.fe-modal .fe-lines { font-size: 12px; padding: 14px 0; }
.fe-modal .fe-highlight,
.fe-modal .fe-textarea { font-size: 13px; padding: 14px 16px; }

/* ===================================================================
   Overlay
   =================================================================== */
.fe-overlay {
  position: absolute;
  inset: 0;
  background: rgba(0,0,0,0.28);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 30;
}
.fe-modal .fe-overlay { position: fixed; }
.fe-confirm {
  padding: 24px 32px;
  max-width: 320px;
  text-align: center;
}
.fe-confirm h3 { font-size: 18px; font-weight: 700; margin: 0 0 12px; }
.fe-confirm p { font-size: 13px; color: var(--ink-2); margin: 0 0 16px; line-height: 1.6; }
.fe-confirm code { font-size: 10px; background: var(--brand-soft); color: var(--brand); padding: 1px 5px; border-radius: 3px; word-break: break-all; }
.fe-confirm-act { display: flex; justify-content: center; gap: 10px; }
.fe-confirm-act .btn-mini-danger,
.fe-confirm-act .btn-primary { padding: 6px 16px; font-size: 12px; }

/* ===================================================================
   Transitions
   =================================================================== */
.slide-right-enter-active,
.slide-right-leave-active {
  transition: transform 280ms cubic-bezier(0.22, 0.61, 0.36, 1), opacity 180ms ease;
}
.slide-right-enter-from,
.slide-right-leave-to { transform: translateX(16px); opacity: 0; }

.fade-enter-active,
.fade-leave-active { transition: opacity 140ms ease; }
.fade-enter-from,
.fade-leave-to { opacity: 0; }
</style>